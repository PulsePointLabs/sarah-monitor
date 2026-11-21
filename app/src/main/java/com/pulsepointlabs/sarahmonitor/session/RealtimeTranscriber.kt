package com.pulsepointlabs.sarahmonitor.session

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/** Joins the streamed transcription pieces, in the order the turns started. Pure; unit-tested. */
class TranscriptAssembler(private val prefix: String = "") {
    private val items = LinkedHashMap<String, String>()
    private val done = mutableSetOf<String>()

    fun touch(itemId: String) { if (itemId !in items) items[itemId] = "" }

    fun delta(itemId: String, text: String) {
        if (itemId in done) return
        items[itemId] = (items[itemId] ?: "") + text
    }

    fun completed(itemId: String, transcript: String) {
        items[itemId] = transcript
        done += itemId
    }

    fun pending(): Boolean = items.keys.any { it !in done && items[it]!!.isNotBlank() }

    fun text(): String {
        val body = items.values.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return listOf(prefix.trim(), body).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/**
 * Live, punctuated speech-to-text with OpenAI's Realtime transcription over a WebSocket: words appear while you
 * speak. Microphone audio (24 kHz, mono, 16-bit PCM) streams in 100 ms chunks; a turn is committed after 0.7 s of
 * quiet so each sentence finalises quickly. Uses gpt-live-transcribe; if the account can't use it, falls back to
 * gpt-4o-transcribe with server-side turn detection.
 */
class RealtimeTranscriber {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var recordJob: Job? = null
    private var record: AudioRecord? = null
    private var assembler = TranscriptAssembler()
    private var finished: CompletableDeferred<Unit>? = null
    @Volatile private var model = LIVE_MODEL
    @Volatile private var gotText = false
    @Volatile var active = false
        private set

    private lateinit var onText: (String) -> Unit
    private lateinit var onStatus: (String) -> Unit
    private lateinit var onLevel: (Float) -> Unit
    private lateinit var onError: (String) -> Unit
    private var apiKey = ""

    fun start(apiKey: String, existingText: String, onText: (String) -> Unit, onStatus: (String) -> Unit, onLevel: (Float) -> Unit, onError: (String) -> Unit) {
        stop()
        this.apiKey = apiKey
        this.onText = onText; this.onStatus = onStatus; this.onLevel = onLevel; this.onError = onError
        assembler = TranscriptAssembler(existingText)
        model = LIVE_MODEL
        gotText = false
        active = true
        connect()
        startMic()
    }

    private fun connect() {
        onStatus("Connecting…")
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer $apiKey")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(sessionUpdate(model).toString())
                onStatus("Listening…")
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                val code = response?.code
                fail(
                    when (code) {
                        401 -> "Live transcription: OpenAI key rejected"
                        403 -> "Live transcription isn't enabled for this OpenAI key"
                        429 -> "Live transcription: rate limit or no OpenAI credit"
                        else -> "Live transcription disconnected (${t.message ?: code ?: "network"})"
                    }
                )
            }
        })
    }

    private fun handle(webSocket: WebSocket, raw: String) {
        if (socket !== webSocket) return
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (event.optString("type")) {
            "input_audio_buffer.committed" -> assembler.touch(event.optString("item_id"))
            "input_audio_buffer.speech_started" -> { assembler.touch(event.optString("item_id")); onStatus("Hearing you…") }
            "conversation.item.input_audio_transcription.delta" -> {
                gotText = true
                assembler.delta(event.optString("item_id"), event.optString("delta"))
                onText(assembler.text())
            }
            "conversation.item.input_audio_transcription.completed" -> {
                gotText = true
                assembler.completed(event.optString("item_id"), event.optString("transcript"))
                onText(VoiceTextNormalizer.normalize(assembler.text()))
                onStatus("Listening…")
                if (!active && !assembler.pending()) finished?.complete(Unit)
            }
            "conversation.item.input_audio_transcription.failed" ->
                onStatus("A phrase couldn't be transcribed (${event.optJSONObject("error")?.optString("message").orEmpty()})")
            "error" -> {
                val err = event.optJSONObject("error")
                val message = err?.optString("message").orEmpty()
                val param = err?.optString("param").orEmpty()
                // An empty commit (quiet turn) is harmless.
                if (message.contains("buffer too small", true) || message.contains("buffer is empty", true)) {
                    if (!active && !assembler.pending()) finished?.complete(Unit)
                    return
                }
                if (model == LIVE_MODEL && !gotText && (param.contains("transcription") || param.contains("turn_detection") || message.contains("model", true))) {
                    model = FALLBACK_MODEL
                    webSocket.send(sessionUpdate(model).toString())
                    return
                }
                onStatus(message.ifBlank { "Live transcription error" })
            }
        }
    }

    @SuppressLint("MissingPermission") // checked by the caller before start()
    private fun startMic() {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK_BYTES * 4))
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            r?.release()
            fail("Couldn't open the microphone")
            return
        }
        record = r
        r.startRecording()
        recordJob = scope.launch {
            val buf = ByteArray(CHUNK_BYTES)
            var spoke = false
            var quietMs = 0
            var noiseFloor = -50.0
            while (isActive && active) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                val db = levelDb(buf, n)
                // Track the room's noise floor, so "speech" means clearly above it.
                noiseFloor = if (db < noiseFloor) db else noiseFloor * 0.995 + db * 0.005
                onLevel(((db + 60) / 60).toFloat().coerceIn(0f, 1f))
                socket?.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)).toString())
                val speaking = db > maxOf(noiseFloor + 12, -45.0)
                if (speaking) { spoke = true; quietMs = 0 } else quietMs += CHUNK_MS
                // Our own turn detection for the live model (it has none): commit after 0.7 s of quiet.
                if (model == LIVE_MODEL && spoke && quietMs >= 700) {
                    socket?.send("""{"type":"input_audio_buffer.commit"}""")
                    spoke = false
                }
            }
        }
    }

    /** Stops listening and waits (up to 4 s) for the last words to come back. Returns the full text. */
    suspend fun finish(): String {
        if (!active) return assembler.text()
        active = false
        finished = CompletableDeferred()
        recordJob?.cancel()
        releaseMic()
        socket?.send("""{"type":"input_audio_buffer.commit"}""")
        onStatus("Finishing…")
        withTimeoutOrNull(4_000L) { finished?.await() }
        closeSocket()
        onLevel(0f)
        return VoiceTextNormalizer.normalize(assembler.text())
    }

    fun stop() {
        active = false
        recordJob?.cancel()
        releaseMic()
        closeSocket()
    }

    fun release() { stop(); scope.cancel() }

    private fun fail(message: String) {
        stop()
        onLevel(0f)
        onError(message)
    }

    private fun releaseMic() {
        record?.let { runCatching { it.stop() }; runCatching { it.release() } }
        record = null
    }

    private fun closeSocket() {
        socket?.close(1000, "done")
        socket = null
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val CHUNK_MS = 100
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS / 1000
        const val LIVE_MODEL = "gpt-live-transcribe"
        const val FALLBACK_MODEL = "gpt-4o-transcribe"
        val KEYWORDS = listOf(
            "HR", "HRV", "RMSSD", "SDNN", "pNN50", "CIVET", "H10", "Omron", "BP", "glucose", "kegel", "pelvic floor",
            "arousal", "edging", "plateau", "orgasm", "ejaculation", "catheter", "enema"
        )

        /** session.update for a transcription session (GA Realtime API). */
        fun sessionUpdate(model: String): JSONObject {
            val transcription = JSONObject().put("model", model)
            val input = JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
                .put("noise_reduction", JSONObject().put("type", "near_field"))
            if (model == LIVE_MODEL) {
                transcription.put("languages", JSONArray().put("en")).put("keywords", JSONArray(KEYWORDS))
                input.put("turn_detection", JSONObject.NULL)
            } else {
                transcription.put("language", "en").put("prompt", WhisperTranscriber.PROMPT)
                input.put("turn_detection", JSONObject().put("type", "server_vad").put("silence_duration_ms", 600))
            }
            input.put("transcription", transcription)
            return JSONObject()
                .put("type", "session.update")
                .put("session", JSONObject().put("type", "transcription").put("audio", JSONObject().put("input", input)))
        }

        /** RMS level of 16-bit little-endian PCM in dBFS. */
        fun levelDb(buf: ByteArray, n: Int): Double {
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < n) {
                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble() / 32768.0
                sum += s * s; count++; i += 2
            }
            if (count == 0) return -90.0
            return 20 * log10(sqrt(sum / count).coerceAtLeast(1e-5))
        }
    }
}
