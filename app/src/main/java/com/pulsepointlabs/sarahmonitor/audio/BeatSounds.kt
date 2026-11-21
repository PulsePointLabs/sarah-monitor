package com.pulsepointlabs.sarahmonitor.audio

import com.pulsepointlabs.sarahmonitor.R
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Heartbeat sounds. The first five are SarahVS's own files (44.1 kHz). The "HD" ones are synthesized here at 48 kHz
 * with smooth attack and release envelopes, so there are no clicks at the start or end of a beat.
 */
enum class BeatSound(val label: String, val rawRes: Int?, val followsHr: Boolean = false) {
    Classic("Classic (SarahVS)", R.raw.heartbeat_beep),
    Soft("Soft (SarahVS)", R.raw.heartbeat_soft),
    Crisp("Crisp (SarahVS)", R.raw.heartbeat_crisp),
    Monitor("Monitor (SarahVS)", R.raw.heartbeat_monitor),
    LubDub("Lub-dub (SarahVS)", R.raw.heartbeat_lub_dub),
    SoftTick("Soft tick · HD", null),
    Heartbeat("Heartbeat · HD", null),
    Bedside("Bedside tone · HD (pitch follows HR)", null, followsHr = true),
    WoodBlock("Wood block · HD", null),
    Sonar("Sonar ping · HD", null);

    /** Longest the sound rings, so audio focus is held long enough. */
    val durationMs: Long
        get() = when (this) {
            LubDub -> 650L
            Heartbeat -> 420L
            Sonar -> 700L
            else -> 160L
        }
}

/** Alert chimes played before (or instead of) a spoken alert. */
enum class AlertTone(val label: String, val rawRes: Int?) {
    Soft("Soft (SarahVS)", R.raw.hr_alert_soft),
    Monitor("Monitor (SarahVS)", R.raw.hr_alert_monitor),
    Chime("Two-note chime · HD", null)
}

/** Pure PCM synthesis, 16-bit mono. Kept free of Android classes so it's unit-testable. */
object BeatSynth {
    const val SAMPLE_RATE = 48_000

    fun render(sound: BeatSound): ShortArray? = when (sound) {
        BeatSound.SoftTick -> softTick()
        BeatSound.Heartbeat -> heartbeat()
        BeatSound.Bedside -> bedside()
        BeatSound.WoodBlock -> woodBlock()
        BeatSound.Sonar -> sonar()
        else -> null
    }

    fun render(tone: AlertTone): ShortArray? = if (tone == AlertTone.Chime) chime() else null

    private fun buffer(ms: Int) = DoubleArray(SAMPLE_RATE * ms / 1000)

    /** Raised-cosine fade in over [attackMs] and out over the last [releaseMs] — removes clicks. */
    private fun edges(buf: DoubleArray, attackMs: Double, releaseMs: Double) {
        val a = (attackMs * SAMPLE_RATE / 1000).toInt().coerceAtLeast(1)
        val r = (releaseMs * SAMPLE_RATE / 1000).toInt().coerceAtLeast(1)
        for (i in 0 until min(a, buf.size)) buf[i] *= 0.5 - 0.5 * cos(PI * i / a)
        for (i in 0 until min(r, buf.size)) buf[buf.size - 1 - i] *= 0.5 - 0.5 * cos(PI * i / r)
    }

    private fun addPartial(buf: DoubleArray, freq: Double, amp: Double, decayMs: Double, startMs: Double = 0.0, glideTo: Double? = null, glideMs: Double = 1.0) {
        val start = (startMs * SAMPLE_RATE / 1000).toInt()
        var phase = 0.0
        for (i in start until buf.size) {
            val t = (i - start).toDouble() / SAMPLE_RATE
            val f = if (glideTo == null) freq else glideTo + (freq - glideTo) * exp(-t * 1000 / glideMs)
            phase += 2 * PI * f / SAMPLE_RATE
            buf[i] += amp * exp(-t * 1000 / decayMs) * sin(phase)
        }
    }

    private fun finish(buf: DoubleArray, peak: Double = 0.89): ShortArray {
        val maxAbs = buf.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
        return ShortArray(buf.size) { (buf[it] / maxAbs * peak * Short.MAX_VALUE).roundToInt().toShort() }
    }

    private fun softTick(): ShortArray {
        val b = buffer(60)
        addPartial(b, 2_400.0, 0.6, 9.0)
        addPartial(b, 1_200.0, 0.4, 14.0)
        edges(b, 1.5, 8.0)
        return finish(b, 0.6)
    }

    /** A realistic two-part heartbeat: a low "lub" then a slightly higher, shorter "dub". */
    private fun heartbeat(): ShortArray {
        val b = buffer(420)
        val rnd = Random(7)
        fun thump(startMs: Double, freq: Double, amp: Double, decay: Double) {
            addPartial(b, freq * 1.6, amp, decay, startMs, glideTo = freq, glideMs = 25.0)
            addPartial(b, freq * 2.1, amp * 0.25, decay * 0.6, startMs)
            // a touch of filtered noise gives it a body instead of a pure sine
            val start = (startMs * SAMPLE_RATE / 1000).toInt()
            var lp = 0.0
            for (i in start until b.size) {
                val t = (i - start).toDouble() / SAMPLE_RATE
                lp += 0.02 * ((rnd.nextDouble() * 2 - 1) - lp)
                b[i] += amp * 1.2 * lp * exp(-t * 1000 / (decay * 0.5))
            }
        }
        thump(0.0, 48.0, 1.0, 70.0)
        thump(150.0, 62.0, 0.7, 50.0)
        edges(b, 4.0, 40.0)
        return finish(b)
    }

    /** Bedside-monitor beep: a clean 880 Hz tone with a soft second harmonic. Pitch is shifted live to follow HR. */
    private fun bedside(): ShortArray {
        val b = buffer(130)
        addPartial(b, 880.0, 1.0, 400.0)
        addPartial(b, 1_760.0, 0.12, 120.0)
        edges(b, 5.0, 35.0)
        return finish(b, 0.75)
    }

    /** Modal wood block: three inharmonic partials with fast decay. */
    private fun woodBlock(): ShortArray {
        val b = buffer(140)
        addPartial(b, 820.0, 1.0, 28.0)
        addPartial(b, 1_910.0, 0.45, 16.0)
        addPartial(b, 3_150.0, 0.2, 9.0)
        edges(b, 0.8, 30.0)
        return finish(b)
    }

    /** Sonar ping: a pure tone with a long tail and one quiet echo. */
    private fun sonar(): ShortArray {
        val b = buffer(700)
        addPartial(b, 1_250.0, 1.0, 180.0)
        addPartial(b, 1_250.0, 0.22, 140.0, startMs = 260.0)
        edges(b, 6.0, 120.0)
        return finish(b, 0.7)
    }

    /** Two-note alert chime (E6 then B5), bell-like partials. */
    private fun chime(): ShortArray {
        val b = buffer(900)
        listOf(0.0 to 1_318.5, 220.0 to 987.8).forEach { (at, f) ->
            addPartial(b, f, 1.0, 380.0, at)
            addPartial(b, f * 2.0, 0.3, 200.0, at)
            addPartial(b, f * 3.01, 0.12, 120.0, at)
        }
        edges(b, 4.0, 180.0)
        return finish(b, 0.8)
    }

    /** 16-bit mono PCM WAV file bytes. */
    fun wav(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val data = samples.size * 2
        val out = ByteArrayOutputStream(44 + data)
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun int(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun short(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        str("RIFF"); int(36 + data); str("WAVE")
        str("fmt "); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
        str("data"); int(data)
        samples.forEach { short(it.toInt()) }
        return out.toByteArray()
    }

    /** SoundPool rate for the bedside tone: 0.85× at 50 bpm up to 1.45× at 170 bpm. */
    fun hrPitch(hr: Int?): Float {
        val bpm = (hr ?: 70).coerceIn(40, 200)
        return (0.85 + (bpm - 50).coerceAtLeast(0) / 120.0 * 0.6).coerceIn(0.8, 1.5).toFloat()
    }

    /** Pitch offset in semitones → playback rate (SoundPool allows 0.5–2.0). */
    fun semitoneRate(semitones: Int): Float = Math.pow(2.0, semitones / 12.0).toFloat().coerceIn(0.5f, 2f)

    fun combinedRate(semitones: Int, hrFollow: Float?): Float = (semitoneRate(semitones) * (hrFollow ?: 1f)).coerceIn(0.5f, 2f)

    @Suppress("unused")
    private fun clamp(v: Double) = max(-1.0, min(1.0, v))
}

/**
 * Turns the H10's RR intervals into beat timing, so the beeps follow the real rhythm (extra beats and pauses
 * included) instead of a steady metronome. RR arrives in ~1 s batches, so a small queue is kept; if it grows
 * past [maxQueue] the oldest are dropped so the sound never lags more than a few beats behind.
 */
class BeatScheduler(private val maxQueue: Int = 4) {
    private val queue = ArrayDeque<Int>()
    private var hr: Int? = null

    @Synchronized
    fun onHeart(hr: Int?, rrMs: List<Int>, followRhythm: Boolean) {
        this.hr = hr
        if (!followRhythm) { queue.clear(); return }
        rrMs.filter { it in 250..2_000 }.forEach { queue.addLast(it) }
        while (queue.size > maxQueue) queue.removeFirst()
    }

    /** Milliseconds until the next beat. */
    @Synchronized
    fun nextIntervalMs(): Long {
        queue.removeFirstOrNull()?.let { return it.toLong() }
        val bpm = (hr ?: 60).coerceIn(30, 220)
        return (60_000L / bpm).coerceIn(250L, 2_000L)
    }

    @Synchronized
    fun reset() { queue.clear(); hr = null }

    @Synchronized
    fun queued(): Int = queue.size
}
