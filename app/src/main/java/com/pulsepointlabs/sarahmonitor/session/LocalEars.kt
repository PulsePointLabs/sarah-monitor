package com.pulsepointlabs.sarahmonitor.session

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The offline English speech model (Vosk small, ~40 MB), downloaded once into the app's files and checked
 * against its published SHA-256 before use.
 */
object VoskModel {
    const val NAME = "vosk-model-small-en-us-0.15"
    private const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"
    private const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    @Volatile private var model: org.vosk.Model? = null

    private fun dir(c: Context) = File(c.filesDir, "vosk/$NAME")
    private fun okFile(c: Context) = File(dir(c), ".verified")
    fun ready(c: Context) = okFile(c).exists()

    /** Downloads, verifies and unpacks the model if needed. [progress] gets 0–100. Throws on failure. */
    suspend fun ensure(c: Context, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (ready(c)) return@withContext
        val tmp = File(c.cacheDir, "$NAME.zip.part")
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(URL).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Model download failed (${r.code})")
            val body = r.body ?: throw IllegalStateException("Model download was empty")
            val total = body.contentLength().takeIf { it > 0 } ?: 41_000_000L
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        val pct = (done * 100 / total).toInt().coerceIn(0, 99)
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != SHA256) { tmp.delete(); throw IllegalStateException("Model checksum didn't match; not using it") }
        }
        val target = dir(c)
        target.deleteRecursively(); target.mkdirs()
        ZipInputStream(tmp.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty() || rel.contains("..")) continue
                val f = File(target, rel)
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
            }
        }
        tmp.delete()
        okFile(c).writeText(SHA256)
        progress(100)
    }

    /** Loads (once) and returns the model. Call off the main thread. */
    @Synchronized
    fun load(c: Context): org.vosk.Model = model ?: org.vosk.Model(dir(c).absolutePath).also { model = it }
}

/** The last [seconds] of 16 kHz mono PCM, addressable by absolute sample index. Pure; unit-tested. */
class PcmRing(seconds: Int, private val rate: Int = 16_000) {
    private val buf = ShortArray(seconds * rate)
    /** Samples written so far (absolute index of the next sample). */
    var written = 0L
        private set

    fun write(samples: ShortArray, n: Int) {
        for (i in 0 until n) { buf[((written + i) % buf.size).toInt()] = samples[i] }
        written += n
    }

    /** Samples [from, to), clipped to what's still held. */
    fun slice(from: Long, to: Long): ShortArray {
        val start = maxOf(from, written - buf.size, 0L)
        val end = minOf(to, written)
        if (end <= start) return ShortArray(0)
        return ShortArray((end - start).toInt()) { buf[((start + it) % buf.size).toInt()] }
    }
}

/**
 * Hands-free "ears", all on the phone: a continuous mic stream into a rolling buffer, an offline recogniser
 * that only has to notice "Sarah", and simple voice-activity detection for where a phrase starts and ends.
 *
 * The moment "Sarah" is heard, [onWake] fires once (she stops talking, media pauses, the listening tone plays).
 * Everything you say from there is one note, until you've clearly finished: [END_QUIET_MS] of quiet (a little more
 * for a long note), longer when the last words sound unfinished ("…and the", "…because"), and up to
 * [WAIT_FOR_START_MS] after a bare "Sarah". There's no time limit on a note; new words always count as talking.
 * Its audio goes to [onPhrase]; nothing else ever leaves the phone. A bare "Sarah" followed by silence calls
 * [onNothing].
 */
class LocalEars(
    private val context: Context,
    private val scope: CoroutineScope,
    /** pcm (16 kHz), when the phrase started (wall clock), what the offline recogniser heard. */
    private val onPhrase: (pcm: ShortArray, startedAtMs: Long, offlineText: String) -> Unit,
    private val onWake: () -> Unit,
    private val onNothing: () -> Unit,
    private val onProblem: (String) -> Unit
) {
    companion object {
        const val RATE = 16_000
        private const val CHUNK = RATE / 10           // 100 ms
        private const val LEAD_IN = RATE * 3 / 10     // keep 0.3 s before the phrase
        /** A finished-sounding thought ends after this much quiet. */
        const val END_QUIET_MS = 2_500
        /** A longer note (you're thinking as you go) gets a little more room to pause. */
        const val LONG_NOTE_QUIET_MS = 3_500
        /** Sound with no new words for this long past the usual quiet (a fan, the TV hum) also ends a note. */
        const val WORDLESS_EXTRA_MS = 6_000
        /** …and one that trails off mid-sentence gets this long to carry on. */
        const val UNFINISHED_QUIET_MS = 5_500
        /** After "Sarah" on its own: how long she waits for you to start. */
        const val WAIT_FOR_START_MS = 8_000
        /** Only a safety net for a stuck mic; a note normally ends on silence, never on the clock. */
        const val MAX_PHRASE_MS = 300_000
        private val WAKE = Regex("^(?:(?:ok|okay|hey|hi|so)\\s+)?(sarah|sara|sera|serra|zara)\\b", RegexOption.IGNORE_CASE)
        /** Words a finished thought rarely ends on. */
        private val DANGLING = setOf(
            "and", "but", "or", "so", "because", "cause", "the", "a", "an", "to", "of", "with", "in", "on", "at", "for", "from", "into",
            "my", "his", "her", "your", "its", "it's", "is", "was", "are", "were", "be", "been", "it", "i", "i'm", "im", "that", "which",
            "like", "um", "uh", "er", "hmm", "then", "about", "just", "more", "less", "very", "really", "kind", "sort", "getting", "feeling",
            "feels", "feel", "starting", "still", "also", "when", "while", "if", "as", "than", "some", "this", "there", "where", "what", "how"
        )

        fun startsWithWake(text: String) = WAKE.containsMatchIn(text.trim())

        /** Quiet needed before a phrase counts as finished, given what's been heard so far. Pure; unit-tested. */
        fun quietNeededMs(heard: String): Int {
            val after = WAKE.find(heard.trim())?.let { heard.trim().substring(it.range.last + 1) } ?: heard
            val words = after.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
            return when {
                words.isEmpty() -> WAIT_FOR_START_MS
                words.size <= 2 || words.last() in DANGLING -> UNFINISHED_QUIET_MS
                words.size >= 20 -> LONG_NOTE_QUIET_MS
                else -> END_QUIET_MS
            }
        }

        fun wordCount(text: String) = text.lowercase().split(Regex("[^a-z0-9']+")).count { it.isNotBlank() }

        /**
         * Is the note over? [quietMs] = time since the mic last heard speech OR the recogniser last heard a new word
         * (a soft-spoken stretch still counts as talking). [wordlessMs] = time since the last new word at all, which
         * ends a note when steady noise keeps the mic "busy" but nobody's saying anything. Pure; unit-tested.
         */
        fun phraseOver(quietMs: Int, wordlessMs: Int, heard: String, lengthMs: Long): Boolean {
            val need = quietNeededMs(heard)
            return quietMs >= need || wordlessMs >= need + WORDLESS_EXTRA_MS || lengthMs >= MAX_PHRASE_MS
        }

        /** Brings a quiet clip up to a healthy level (peaks near −3 dBFS, at most ×8) before transcription. */
        fun normalized(pcm: ShortArray): ShortArray {
            val peak = pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: return pcm
            if (peak < 50) return pcm
            val gain = (23_000.0 / peak).coerceIn(1.0, 8.0)
            return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
        }
        /** True when the phrase was only the wake word. */
        fun wakeOnly(text: String) = WAKE.find(text.trim())?.let { text.trim().substring(it.range.last + 1).isBlank() } ?: false
    }

    private var job: Job? = null
    @Volatile private var running = false
    val active: Boolean get() = running

    fun start(model: org.vosk.Model) {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.IO) { runCatching { loop(model) }.onFailure { onProblem(it.message ?: "microphone stopped") }; running = false }
    }

    fun stop() { running = false; job?.cancel(); job = null }

    @SuppressLint("MissingPermission") // checked by HandsFree before start()
    private suspend fun loop(model: org.vosk.Model) {
        // The phone's own mic unless asked otherwise: a headset mic would put the headset in call-quality mode
        // for the whole session (music and Sarah's voice would sound like a phone call).
        // Always-on listening must not take audio focus: that stopped other apps for the whole session (and cut off
        // voice chats). Media is paused only once she hears her name (MediaHold).
        val route = MicRoute.open(context, useHeadset = HandsFreeSettings.headsetMic.value, holdMedia = false)
        val source = if (route.headset) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 2 * 4))
        route.input?.let { rec.preferredDevice = it }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); MicRoute.close(context, route); throw IllegalStateException("Couldn't open the microphone") }
        val recognizer = org.vosk.Recognizer(model, RATE.toFloat())
        val ring = PcmRing(MAX_PHRASE_MS / 1000 + 10)
        val startedAt = System.currentTimeMillis()
        fun wallAt(sample: Long) = startedAt + sample * 1000 / RATE
        val pcm = ShortArray(CHUNK)
        val bytes = ByteArray(CHUNK * 2)
        var noiseFloor = -50.0
        var speechDb = -30.0                  // typical level of your speech, for the automatic gain
        var quietMs = 1_000
        var wordlessMs = 0
        var wordsSeen = 0
        var onsetSample = 0L
        var utteranceStart: Long? = null      // onset of the speech the recogniser is currently hearing
        var capturing: Long? = null           // start sample of a phrase being captured
        var captureText = StringBuilder()
        var partialNow = ""                   // what the recogniser is hearing right now (not yet final)
        fun begin(from: Long, text: String) {
            capturing = maxOf(0L, from - LEAD_IN); captureText = StringBuilder(text); partialNow = ""
            wordsSeen = wordCount(text); wordlessMs = 0
            onWake()
        }
        val ownId = rec.audioSessionId
        com.pulsepointlabs.sarahmonitor.audio.OtherAudio.ownRecording(ownId, true)
        rec.startRecording()
        try {
            while (scope.isActive && running) {
                val n = rec.read(pcm, 0, CHUNK)
                if (n <= 0) continue
                // Voice activity: clearly above the room's noise floor.
                var sum = 0.0
                for (i in 0 until n) { val s = pcm[i] / 32768.0; sum += s * s }
                val db = 10 * kotlin.math.log10(sum / n + 1e-12)
                // The floor follows quiet rooms down at once and drifts up slowly to a noisier room, but never while a
                // note is being taken: during a long note it used to creep up toward your own voice until your speech
                // counted as "quiet" and cut you off mid-sentence.
                noiseFloor = when {
                    db < noiseFloor -> db
                    capturing != null -> noiseFloor
                    else -> noiseFloor * 0.995 + db * 0.005
                }
                val speaking = db > maxOf(noiseFloor + 10, -50.0)
                // Automatic gain: a phone mic a few feet away is quiet, so speech is lifted toward −22 dBFS
                // (at most ×8) before the recogniser and the clip buffer see it.
                if (speaking) speechDb = speechDb * 0.9 + db * 0.1
                val gain = Math.pow(10.0, (-22.0 - speechDb) / 20.0).coerceIn(1.0, 8.0)
                if (gain > 1.01) for (i in 0 until n) pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                ring.write(pcm, n)
                for (i in 0 until n) { bytes[2 * i] = (pcm[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                if (speaking) {
                    if (quietMs >= 300) onsetSample = ring.written - n
                    quietMs = 0
                } else quietMs += 100
                wordlessMs += 100

                val final = recognizer.acceptWaveForm(bytes, n * 2)
                if (final) {
                    val text = JSONObject(recognizer.result).optString("text")
                    if (capturing != null) { captureText.append(' ').append(text); partialNow = "" }
                    else if (startsWithWake(text)) begin(utteranceStart ?: onsetSample, text)
                    utteranceStart = null
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial")
                    if (capturing != null) partialNow = partial
                    else {
                        if (partial.isNotBlank() && utteranceStart == null) utteranceStart = onsetSample
                        // Heard "Sarah": react now, not when the sentence is over. A second "Sarah" inside the same
                        // note is just part of it (never a second listen).
                        if (startsWithWake(partial)) begin(utteranceStart ?: onsetSample, "")
                    }
                }

                val start = capturing ?: continue
                val lengthMs = (ring.written - start) * 1000 / RATE
                val heardSoFar = "$captureText $partialNow"
                // A new word is proof you're still talking, even if the mic level dipped.
                val words = wordCount(heardSoFar)
                if (words > wordsSeen) { wordsSeen = words; wordlessMs = 0; quietMs = 0 }
                if (phraseOver(quietMs, wordlessMs, heardSoFar, lengthMs)) {
                    // Phrase over: everything up to a little after the last speech.
                    val end = ring.written - (quietMs - 300).coerceAtLeast(0) * RATE / 1000
                    val heard = (captureText.toString() + " " + JSONObject(recognizer.finalResult).optString("text")).trim().replace(Regex("\\s+"), " ")
                    recognizer.reset()
                    capturing = null; utteranceStart = null; partialNow = ""
                    if (wakeOnly(heard) || heard.isBlank()) onNothing()
                    else onPhrase(ring.slice(start, end), wallAt(start + LEAD_IN), heard)
                }
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
            com.pulsepointlabs.sarahmonitor.audio.OtherAudio.ownRecording(ownId, false)
            recognizer.close()
            MicRoute.close(context, route)
        }
    }
}
