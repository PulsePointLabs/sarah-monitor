package com.pulsepointlabs.sarahmonitor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pulsepointlabs.sarahmonitor.session.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Sarah's voice: OpenAI TTS with Sarah's "HD Crisp" preset (model tts-1-hd, voice nova, speed 0.96). Spoken
 * clips are cached by text, so repeated phrases play instantly and cost nothing. Falls back to Android's
 * offline voice when there's no key or no network.
 */
class SarahVoice(context: Context) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(AudioManager::class.java)
    // Navigation-guidance speech (what map apps use): plays at media volume and is meant to be heard over a
    // movie or music. (Sonification followed the much quieter system volume, so Sarah got lost under playback.)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private fun focusRequest(gain: Int) = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
    } else null
    private val duckFocus = focusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    private val pauseFocus = focusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    private var focus: AudioFocusRequest? = null
    private val cacheDir = File(app.cacheDir, "sarah-voice").apply { mkdirs() }
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Speaks [text] and returns when it has finished (or failed). Never throws. */
    suspend fun speak(text: String, volume: Float, engine: VoiceEngine): String? {
        val key = VoiceSettings.apiKey(app)
        var problem: String? = null
        if (engine == VoiceEngine.Sarah && key.isNotBlank()) {
            val clip = runCatching { clip(text, key) }.onFailure { problem = it.message }.getOrNull()
            if (clip != null) {
                playFile(clip, volume)
                return null
            }
        } else if (engine == VoiceEngine.Sarah) {
            problem = "No OpenAI key saved — using the Android voice"
        }
        speakAndroid(text, volume)
        return problem
    }

    private suspend fun clip(text: String, apiKey: String): File = withContext(Dispatchers.IO) {
        val name = MessageDigest.getInstance("SHA-1").digest("$MODEL|$VOICE|$SPEED|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(cacheDir, "$name.mp3")
        if (file.length() > 0) return@withContext file.also { it.setLastModified(System.currentTimeMillis()) }
        val body = JSONObject()
            .put("model", MODEL)
            .put("voice", VOICE)
            .put("input", text)
            .put("speed", SPEED)
            .put("response_format", "mp3")
            .toString()
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(
                    when (code) {
                        401 -> "Sarah's voice: OpenAI key rejected"
                        429 -> "Sarah's voice: rate limit or no OpenAI credit"
                        else -> "Sarah's voice: OpenAI error $code"
                    }
                )
            }
            val tmp = File(cacheDir, "$name.part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
            trimCache()
            file
        } finally {
            c.disconnect()
        }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED).forEach { it.delete() }
    }

    private suspend fun playFile(file: File, volume: Float) {
        requestFocus()
        try {
            withTimeoutOrNull(60_000L) {
                suspendCancellableCoroutine { cont ->
                    val player = MediaPlayer()
                    fun done() {
                        runCatching { player.release() }
                        if (cont.isActive) cont.resume(Unit)
                    }
                    runCatching {
                        player.setAudioAttributes(attributes)
                        player.setDataSource(file.absolutePath)
                        player.setVolume(volume.coerceAtMost(1f), volume.coerceAtMost(1f))
                        // Above 100 %: a loudness boost on the player's own audio (up to about +9 dB).
                        val boost = if (volume > 1f) runCatching {
                            android.media.audiofx.LoudnessEnhancer(player.audioSessionId).apply {
                                setTargetGain((2000 * kotlin.math.log10(volume.toDouble())).toInt().coerceIn(0, 900)); enabled = true
                            }
                        }.getOrNull() else null
                        player.setOnCompletionListener { runCatching { boost?.release() }; done() }
                        player.setOnErrorListener { _, _, _ -> done(); true }
                        player.prepare()
                        player.start()
                    }.onFailure { done() }
                    cont.invokeOnCancellation { runCatching { player.release() } }
                }
            }
        } finally {
            abandonFocus()
        }
    }

    private suspend fun speakAndroid(text: String, volume: Float) {
        val engine = tts ?: withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var created: TextToSpeech? = null
                created = TextToSpeech(app) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (cont.isActive) cont.resume(created)
                }
            }
        }?.also { tts = it } ?: return
        if (!ttsReady) return
        requestFocus()
        try {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { cont ->
                    val id = "sarah-${System.nanoTime()}"
                    cont.invokeOnCancellation { runCatching { engine.stop() } }
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    })
                    engine.setAudioAttributes(attributes)
                    engine.setSpeechRate(SPEED.toFloat())
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }, id)
                }
            }
        } finally {
            abandonFocus()
        }
    }

    private fun requestFocus() {
        val pause = AudioSettings.voiceOverOthers.value == VoiceOverOthers.Pause
        if (Build.VERSION.SDK_INT >= 26) {
            focus = if (pause) pauseFocus else duckFocus
            focus?.let(audioManager::requestAudioFocus)
        } else @Suppress("DEPRECATION") audioManager.requestAudioFocus(
            null, AudioManager.STREAM_MUSIC, if (pause) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focus?.let(audioManager::abandonAudioFocusRequest)
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
    }

    fun release() {
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val ENDPOINT = "https://api.openai.com/v1/audio/speech"
        /** Sarah's "HD Crisp" preset. */
        const val MODEL = "tts-1-hd"
        const val VOICE = "nova"
        const val SPEED = 0.96
        private const val MAX_CACHED = 300
    }
}
