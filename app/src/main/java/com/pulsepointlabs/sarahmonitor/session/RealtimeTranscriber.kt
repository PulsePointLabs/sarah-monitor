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

/**
 * Joins the streamed transcription pieces, in the order the turns started, using the pause before each piece:
 * a short pause before "and / but / so…" stays one sentence (comma), a long thinking pause reads as "…", and a
 * new thought gets a full stop. Pure; unit-tested.
 */
class TranscriptAssembler(private val prefix: String = "") {
    private val items = LinkedHashMap<String, String>()
    private val done = mutableSetOf<String>()
    private val gaps = mutableMapOf<String, Long>()

    /** The silence (ms) before this piece started. */
    fun gapBefore(itemId: String, ms: Long) { gaps[itemId] = ms }

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

    /** Forget a finished piece (hands-free listening hands each phrase on and keeps nothing). */
    fun remove(itemId: String) { items.remove(itemId); done.remove(itemId); gaps.remove(itemId) }

    /** True once anything beyond the existing note has been transcribed. */
    fun hasNewText(): Boolean = items.values.any { it.isNotBlank() }

    fun text(): String = join(listOf(prefix to null) + items.map { (id, t) -> t to gaps[id] })

    companion object {
        private val CONTINUATIONS = setOf(
            "and", "but", "so", "or", "because", "cause", "then", "which", "that", "with", "to", "of", "like", "maybe", "um", "uh",
            "also", "if", "when", "while", "just", "kind", "sort", "as", "about", "for", "into", "in", "on", "at", "where", "who", "plus", "until", "since"
        )
        private const val THINKING_PAUSE_MS = 1_500L
        private const val SHORT_PAUSE_MS = 900L

        fun join(pieces: List<Pair<String, Long?>>): String {
            val out = StringBuilder()
            pieces.forEach { (raw, gap) ->
                val next = raw.trim()
                if (next.isEmpty()) return@forEach
                if (out.isEmpty()) { out.append(next); return@forEach }
                val first = next.takeWhile { it.isLetter() || it == '\'' }
                // Without timing (an older note), the model's own punctuation stands.
                val continues = gap != null && (first.lowercase() in CONTINUATIONS || next.first().isLowerCase())
                val end = out.last()
                val pause = gap ?: 0L
                if (continues) {
                    // Same thought: drop the model's full stop and keep going.
                    if (end == '.' && !out.endsWith("…")) out.setLength(out.length - 1)
                    val lastNow = out.last()
                    out.append(
                        when {
                            pause >= THINKING_PAUSE_MS -> if (lastNow == '…') " " else "… "
                            lastNow in ",;:" -> " "
                            pause >= SHORT_PAUSE_MS -> ", "
                            else -> " "
                        }
                    )
                    out.append(if (first.lowercase() in CONTINUATIONS && first != "I") next.replaceFirstChar { it.lowercase() } else next)
                } else {
                    if (end !in ".!?…") out.append('.')
                    out.append(' ').append(next.replaceFirstChar { it.uppercase() })
                }
            }
            return out.toString()
        }
    }
}

/**
 * Live, punctuated speech-to-text with OpenAI's Realtime transcription over a WebSocket: words appear while you
 * speak. Microphone audio (24 kHz, mono, 16-bit PCM) streams in 100 ms chunks; a turn is committed after 0.7 s of
 * quiet so each sentence finalises quickly. Uses gpt-live-transcribe; if the account can't use it, falls back to
 * gpt-4o-transcribe with server-side turn detection.
 */
class RealtimeTranscriber(private val context: android.content.Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var recordJob: Job? = null
    private var record: AudioRecord? = null
    private var assembler = TranscriptAssembler()
    private var finished: CompletableDeferred<Unit>? = null
    @Volatile private var model = LIVE_MODEL
    @Volatile private var gotText = false
    /** Audio waits until the server has applied our session settings (otherwise early audio uses defaults). */
    @Volatile private var ready = false
    private val pending = ArrayDeque<ByteArray>()
    /** Everything recorded, so a silent live stream can still be transcribed afterwards. */
    private val captured = java.io.ByteArrayOutputStream()
    /** Last thing that went wrong on the live stream, shown if no text comes back. */
    @Volatile var lastProblem: String? = null
        private set
    @Volatile var active = false
        private set
    /** Loudest moment heard (dBFS); below about −55 means the mic gave silence. */
    @Volatile private var peakDb = -120.0
    private var route: MicRoute.Route? = null
    private var onAutoStop: () -> Unit = {}
    private var autoStopMs = 0
    /** Hands-free: each finished phrase with the wall-clock time its speech began. */
    private var onUtterance: ((text: String, startedAtMs: Long) -> Unit)? = null
    /** Start of each spoken phrase (wall clock) and the silence before it, from our own voice detection. */
    private val speechStarts = ArrayDeque<Pair<Long, Long>>()
    private val itemStart = mutableMapOf<String, Long>()
    @Volatile private var micStartedAtMs = 0L
    private var continuous = false
    private var lastSpeechEndAudioMs: Long? = null

    private lateinit var onText: (String) -> Unit
    private lateinit var onStatus: (String) -> Unit
    private lateinit var onLevel: (Float) -> Unit
    private lateinit var onError: (String) -> Unit
    private var apiKey = ""

    fun start(
        apiKey: String, existingText: String, onText: (String) -> Unit, onStatus: (String) -> Unit, onLevel: (Float) -> Unit, onError: (String) -> Unit,
        onAutoStop: () -> Unit = {},
        onUtterance: ((String, Long) -> Unit)? = null,
        /** Hands-free: never stop on quiet, and don't build up a note. */
        continuous: Boolean = false
    ) {
        stop()
        this.apiKey = apiKey
        this.onAutoStop = onAutoStop
        this.onUtterance = onUtterance
        synchronized(speechStarts) { speechStarts.clear() }
        itemStart.clear(); lastSpeechEndAudioMs = null
        autoStopMs = if (continuous) 0 else (VoiceSettings.autoStopSeconds(context) * 1000).toInt()
        this.continuous = continuous
        peakDb = -120.0
        this.onText = onText; this.onStatus = onStatus; this.onLevel = onLevel; this.onError = onError
        assembler = TranscriptAssembler(existingText)
        model = LIVE_MODEL
        gotText = false
        ready = false
        synchronized(pending) { pending.clear() }
        synchronized(captured) { captured.reset() }
        lastProblem = null
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
                // If the server never confirms, start streaming anyway after 3 s.
                scope.launch { kotlinx.coroutines.delay(3_000L); if (socket === webSocket) markReady(webSocket) }
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
            "session.updated", "transcription_session.updated" -> markReady(webSocket)
            "input_audio_buffer.committed" -> {
                val id = event.optString("item_id")
                assembler.touch(id)
                // Our own commits: pair the item with the speech start we saw for it.
                if (id !in itemStart) synchronized(speechStarts) { speechStarts.removeFirstOrNull() }?.let { (wall, gap) -> itemStart[id] = wall; assembler.gapBefore(id, gap) }
            }
            "input_audio_buffer.speech_started" -> {
                val id = event.optString("item_id")
                assembler.touch(id)
                // Server turn detection gives exact times: use them (and drop our own estimate).
                if (event.has("audio_start_ms")) {
                    val start = event.optLong("audio_start_ms")
                    itemStart[id] = micStartedAtMs + start
                    assembler.gapBefore(id, lastSpeechEndAudioMs?.let { (start - it).coerceAtLeast(0) } ?: 0L)
                    synchronized(speechStarts) { speechStarts.removeFirstOrNull() }
                }
                onStatus("Hearing you…")
            }
            "input_audio_buffer.speech_stopped" -> if (event.has("audio_end_ms")) lastSpeechEndAudioMs = event.optLong("audio_end_ms")
            "conversation.item.input_audio_transcription.delta" -> {
                gotText = true
                assembler.delta(event.optString("item_id"), event.optString("delta"))
                onText(VoiceTextNormalizer.normalize(assembler.text(), fixStrayCaps = false))
            }
            "conversation.item.input_audio_transcription.completed" -> {
                gotText = true
                val id = event.optString("item_id")
                assembler.completed(id, event.optString("transcript"))
                onText(VoiceTextNormalizer.normalize(assembler.text(), fixStrayCaps = false))
                onUtterance?.let { cb -> event.optString("transcript").trim().takeIf { it.isNotEmpty() }?.let { cb(it, itemStart[id] ?: System.currentTimeMillis()) } }
                if (continuous) { assembler.remove(id); itemStart.remove(id) }
                onStatus("Listening…")
                if (!active && !assembler.pending()) finished?.complete(Unit)
            }
            "conversation.item.input_audio_transcription.failed" -> {
                lastProblem = "Live: " + event.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "a phrase couldn't be transcribed" }
                onStatus(lastProblem!!)
                if (!active && !assembler.pending()) finished?.complete(Unit)
            }
            "error" -> {
                val err = event.optJSONObject("error")
                val message = err?.optString("message").orEmpty()
                val param = err?.optString("param").orEmpty()
                // An empty commit (quiet turn) is harmless.
                if (message.contains("buffer too small", true) || message.contains("buffer is empty", true)) {
                    if (!active && !assembler.pending()) finished?.complete(Unit)
                    return
                }
                lastProblem = "Live: " + message.ifBlank { "error" } + if (param.isNotBlank()) " ($param)" else ""
                if (model == LIVE_MODEL && !gotText && (param.contains("transcription") || param.contains("turn_detection") || message.contains("model", true))) {
                    model = FALLBACK_MODEL
                    ready = false
                    webSocket.send(sessionUpdate(model).toString())
                    return
                }
                onStatus(lastProblem!!)
            }
        }
    }

    @SuppressLint("MissingPermission") // checked by the caller before start()
    private fun startMic() {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        route = MicRoute.open(context)
        val source = if (route?.headset == true) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val r = runCatching {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK_BYTES * 4))
                .also { rec -> route?.input?.let { rec.preferredDevice = it } }
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            r?.release()
            fail("Couldn't open the microphone")
            return
        }
        record = r
        r.startRecording()
        micStartedAtMs = System.currentTimeMillis()
        recordJob = scope.launch {
            val buf = ByteArray(CHUNK_BYTES)
            var spoke = false
            var everSpoke = false
            var quietMs = 0
            var silentMs = 0
            var noiseFloor = -50.0
            while (isActive && active) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                val db = levelDb(buf, n)
                if (db > peakDb) peakDb = db
                // Track the room's noise floor, so "speech" means clearly above it.
                noiseFloor = if (db < noiseFloor) db else noiseFloor * 0.995 + db * 0.005
                onLevel(((db + 60) / 60).toFloat().coerceIn(0f, 1f))
                val chunk = buf.copyOf(n)
                if (!continuous) synchronized(captured) { if (captured.size() < MAX_CAPTURE_BYTES) captured.write(chunk) }
                if (ready) sendAudio(chunk) else synchronized(pending) { pending.addLast(chunk); while (pending.size > 300) pending.removeFirst() }
                val speaking = db > maxOf(noiseFloor + 12, -45.0)
                // First speech of a new phrase: remember when it began and how long the pause before it was.
                if (speaking && !spoke) synchronized(speechStarts) {
                    speechStarts.addLast(System.currentTimeMillis() - CHUNK_MS to if (everSpoke) quietMs.toLong() else 0L)
                    while (speechStarts.size > 20) speechStarts.removeFirst()
                }
                if (speaking) { spoke = true; everSpoke = true; quietMs = 0; silentMs = 0 } else { quietMs += CHUNK_MS; silentMs += CHUNK_MS }
                // Optional: finish by itself once you've stopped talking for the set time.
                if (autoStopMs > 0 && everSpoke && silentMs >= autoStopMs) { silentMs = Int.MIN_VALUE / 2; onAutoStop() }
                // Our own turn detection for the live model (it has none): commit after 0.7 s of quiet.
                if (ready && model == LIVE_MODEL && spoke && quietMs >= 700) {
                    socket?.send("""{"type":"input_audio_buffer.commit"}""")
                    spoke = false
                }
            }
        }
    }

    private fun sendAudio(chunk: ByteArray) {
        socket?.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.encodeToString(chunk, Base64.NO_WRAP)).toString())
    }

    private fun markReady(webSocket: WebSocket) {
        if (ready || socket !== webSocket) return
        ready = true
        val backlog = synchronized(pending) { pending.toList().also { pending.clear() } }
        backlog.forEach { sendAudio(it) }
    }

    /** Stops listening and waits (up to 8 s) for the last words to come back. Returns the full text. */
    suspend fun finish(): String {
        if (!active) return assembler.text()
        active = false
        finished = CompletableDeferred()
        recordJob?.cancel()
        releaseMic()
        if (!ready) socket?.let { markReady(it) }
        socket?.send("""{"type":"input_audio_buffer.commit"}""")
        onStatus("Finishing…")
        withTimeoutOrNull(8_000L) { finished?.await() }
        closeSocket()
        onLevel(0f)
        val live = VoiceTextNormalizer.normalize(assembler.text(), fixStrayCaps = false)
        if (assembler.hasNewText()) return live
        // The live stream gave nothing back: transcribe the recording in one go so the words aren't lost.
        val pcm = synchronized(captured) { captured.toByteArray() }
        if (pcm.size < SAMPLE_RATE * 2 / 2) return live // under 0.5 s
        if (peakDb < -55) {
            lastProblem = "No sound reached the microphone (${route?.label ?: "mic"})"
            return live
        }
        onStatus("Transcribing the recording…")
        val wav = com.pulsepointlabs.sarahmonitor.audio.BeatSynth.wav(ShortArray(pcm.size / 2) { i -> ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort() }, SAMPLE_RATE)
        val text = runCatching { WhisperTranscriber.transcribe(wav, apiKey, "note.wav", "audio/wav", FALLBACK_MODEL) }
            .recoverCatching { WhisperTranscriber.transcribe(wav, apiKey, "note.wav", "audio/wav", "whisper-1") }
            .onFailure { lastProblem = (lastProblem?.let { "$it · " } ?: "") + (it.message ?: "transcription failed") }
            .getOrNull()?.trim().orEmpty()
        if (text.isBlank()) return live
        return VoiceTextNormalizer.normalize(listOf(live, text).filter { it.isNotBlank() }.joinToString(" "), fixStrayCaps = false)
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

    /** Which microphone is in use (for the listening label). */
    val micLabel: String get() = route?.label ?: "Phone mic"

    private fun releaseMic() {
        MicRoute.close(context, route)
        record?.let { runCatching { it.stop() }; runCatching { it.release() } }
        record = null
    }

    private fun closeSocket() {
        socket?.close(1000, "done")
        socket = null
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        /** Keep up to 8 minutes of audio for the after-the-fact fallback. */
        private const val MAX_CAPTURE_BYTES = SAMPLE_RATE * 2 * 480 // 8 min ≈ 23 MB, under the 25 MB upload limit
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
