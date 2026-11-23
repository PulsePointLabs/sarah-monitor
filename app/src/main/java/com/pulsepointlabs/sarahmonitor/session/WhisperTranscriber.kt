package com.pulsepointlabs.sarahmonitor.session

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Which speech-to-text engine event notes use, and the OpenAI key for Whisper. */
object VoiceSettings {
    enum class Engine(val label: String) {
        Realtime("OpenAI live (words as you speak)"),
        Android("Android live (on device)"),
        Whisper("OpenAI Whisper (after you stop)")
    }

    private const val PREFS = "sarahmonitor_voice"

    fun engine(context: Context): Engine =
        runCatching { Engine.valueOf(prefs(context).getString("engine", Engine.Realtime.name)!!) }.getOrDefault(Engine.Android)

    fun setEngine(context: Context, engine: Engine) = prefs(context).edit().putString("engine", engine.name).apply()

    fun apiKey(context: Context): String = prefs(context).getString("openai_key", "").orEmpty()

    fun setApiKey(context: Context, key: String) = prefs(context).edit().putString("openai_key", key.trim()).apply()

    /** Stop listening after this many seconds of quiet once you've spoken (0 = only when you tap stop). */
    fun autoStopSeconds(context: Context): Float = prefs(context).getFloat("auto_stop", 3f)
    fun setAutoStopSeconds(context: Context, s: Float) = prefs(context).edit().putFloat("auto_stop", s).apply()

    /** After dictation, let Claude tidy punctuation and capitals (same words). Needs the Anthropic key. */
    fun tidyWithClaude(context: Context): Boolean = prefs(context).getBoolean("tidy_claude", true)
    fun setTidyWithClaude(context: Context, on: Boolean) = prefs(context).edit().putBoolean("tidy_claude", on).apply()

    /** Use a connected Bluetooth headset's microphone when there is one. */
    fun preferHeadsetMic(context: Context): Boolean = prefs(context).getBoolean("headset_mic", true)
    fun setPreferHeadsetMic(context: Context, on: Boolean) = prefs(context).edit().putBoolean("headset_mic", on).apply()

    /** Whisper is used only when chosen and a key is saved; otherwise Android's recognizer. */
    fun useWhisper(context: Context): Boolean = engine(context) == Engine.Whisper && apiKey(context).isNotBlank()

    /** The engine that will actually run: the OpenAI ones need a saved key, otherwise Android's recognizer. */
    fun effective(context: Context): Engine = engine(context).let { if (it != Engine.Android && apiKey(context).isBlank()) Engine.Android else it }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Records a voice note and transcribes it with OpenAI Whisper. Audio is kept in the app cache only until
 * it's sent, then deleted.
 */
class WhisperTranscriber(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var route: MicRoute.Route? = null

    /** 0–1 loudness since the last call (for the level meter / auto-stop). */
    fun level(): Float = recorder?.let { r -> runCatching { (r.maxAmplitude / 32767f).let { a -> if (a <= 0f) 0f else ((20 * kotlin.math.log10(a) + 60) / 60).coerceIn(0f, 1f) } }.getOrDefault(0f) } ?: 0f

    val micLabel: String get() = route?.label ?: "Phone mic"

    val recording: Boolean get() = recorder != null

    fun start() {
        stopRecorder()
        val out = File(context.cacheDir, "voice-note-${System.currentTimeMillis()}.m4a")
        route = MicRoute.open(context)
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(if (route?.headset == true) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION)
        route?.input?.let { r.setPreferredDevice(it) }
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16_000)
        r.setAudioChannels(1)
        r.setAudioEncodingBitRate(48_000)
        r.setMaxDuration(120_000)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = out
    }

    /** Stops recording and returns the transcript (throws with a readable message on failure). */
    suspend fun stopAndTranscribe(apiKey: String): String {
        val audio = file
        stopRecorder()
        if (audio == null || !audio.exists() || audio.length() < 1_000) {
            audio?.delete()
            throw IllegalStateException("No audio captured")
        }
        return try {
            withContext(Dispatchers.IO) { transcribe(audio.readBytes(), apiKey) }
        } finally {
            audio.delete()
        }
    }

    fun cancel() {
        stopRecorder()
        file?.delete()
        file = null
    }

    private fun stopRecorder() {
        recorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        recorder = null
        MicRoute.close(context, route)
    }

    companion object {
        private const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"

        /** Vocabulary hint so Whisper spells the app's terms right. */
        const val PROMPT = "Event note for a physiology monitoring session. Terms: HR, HRV, RMSSD, SDNN, pNN50, " +
            "CIVET, H10, Omron, BP, glucose, arousal, edging, plateau, orgasm, ejaculation, pelvic floor, kegel."

        fun transcribe(audio: ByteArray, apiKey: String, fileName: String = "note.m4a", fileType: String = "audio/mp4", model: String = "whisper-1"): String {
            val boundary = "----SarahMonitor" + UUID.randomUUID().toString().replace("-", "")
            val body = multipartBody(
                boundary,
                fields = listOf("model" to model, "response_format" to "text", "prompt" to PROMPT),
                fileField = "file", fileName = fileName, fileType = fileType, fileBytes = audio
            )
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw IllegalStateException(
                        when (code) {
                            401 -> "Whisper: the OpenAI API key was rejected"
                            429 -> "Whisper: rate limit or no credit on the OpenAI account"
                            else -> "Whisper error $code"
                        }
                    )
                }
                return text.trim()
            } finally {
                connection.disconnect()
            }
        }

        fun multipartBody(
            boundary: String,
            fields: List<Pair<String, String>>,
            fileField: String,
            fileName: String,
            fileType: String,
            fileBytes: ByteArray
        ): ByteArray {
            val out = ByteArrayOutputStream()
            fun line(s: String) = out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
            fields.forEach { (name, value) ->
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"$name\"")
                line("")
                line(value)
            }
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"")
            line("Content-Type: $fileType")
            line("")
            out.write(fileBytes)
            line("")
            line("--$boundary--")
            return out.toByteArray()
        }
    }
}
