package com.pulsepointlabs.sarahmonitor.emg

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Splits the Arduino's serial stream into "A0,A1" (or "A0") lines. Keeps a partial line until its newline arrives
 * and rejects malformed or out-of-range values — never reads half a number as a sample (as Sarah's helper).
 */
class EmgLineParser {
    private val partial = StringBuilder()

    fun feed(bytes: ByteArray): List<IntArray> {
        val out = mutableListOf<IntArray>()
        for (b in bytes) {
            val c = (b.toInt() and 0xff).toChar()
            if (c == '\n') {
                parse(partial.toString().trim())?.let { out += it }
                partial.clear()
            } else if (c != '\r') {
                partial.append(c)
                if (partial.length > 64) partial.clear() // garbage, not a line
            }
        }
        return out
    }

    private fun parse(line: String): IntArray? {
        if (line.isEmpty()) return null
        val parts = line.split(',')
        if (parts.size !in 1..2) return null
        val v = parts.map { it.trim().toIntOrNull() ?: return null }
        if (v.any { it !in 0..1023 }) return null
        return v.toIntArray()
    }
}

enum class EmgStep { Rest, Hold1, Hold2 }

data class EmgCapture(val step: EmgStep, val phase: String, val remainingS: Double, val message: String)

data class EmgChannel(
    val name: String,
    val raw: Int? = null,
    val smooth: Double? = null,
    val pct: Double? = null,
    val rest: Double? = null,
    val hold: Double? = null,
    val rawMin: Int? = null,
    val rawMax: Int? = null,
    val nearLimit: Boolean = false
) {
    val calibrated: Boolean get() = rest != null && hold != null
}

data class EmgSnapshot(
    val channels: List<EmgChannel> = emptyList(),
    val sampleHz: Double = 0.0,
    val lastSampleMs: Long? = null,
    val capture: EmgCapture? = null,
    val message: String? = null,
    /** Last 20 s at ~20 Hz: (time, % or raw per channel). */
    val trace: List<Pair<Long, DoubleArray>> = emptyList()
) {
    val fresh: Boolean get() = lastSampleMs?.let { System.currentTimeMillis() - it < 1_500L } == true
    /** The strongest calibrated channel, in % of its comfortable hold. */
    val peakPct: Double? get() = channels.mapNotNull { it.pct }.maxOrNull()
}

/**
 * MyoWare ENV processing, following Sarah's guided setup: light smoothing (the ENV output is already an envelope),
 * calibration of rest and a comfortable hold per channel (3 s prep, 5 s measure, quality-gated), and
 * "% of hold" = rise above rest relative to the hold's rise. Raw ADC diagnostics stay visible without calibration.
 */
class EmgProcessor(names: List<String> = listOf("Sensor 1", "Sensor 2")) {
    private var channelNames = names
    private var count = 0
    private val smooth = DoubleArray(2)
    private var initialized = false
    private val rest = arrayOfNulls<Double>(2)
    private val hold = arrayOfNulls<Double>(2)
    private val window = ArrayDeque<Pair<Long, IntArray>>()
    private val trace = ArrayDeque<Pair<Long, DoubleArray>>()
    private var lastTrace = 0L
    private val stamps = ArrayDeque<Long>()
    private var lastT: Long? = null
    private var raw: IntArray? = null
    private var capStep: EmgStep? = null
    private var capStart = 0L
    private val capValues = mutableListOf<Double>()
    private var capMax = 0
    private val restOthers = mutableListOf<DoubleArray>()
    private var message: String? = null
    private var limitSince = arrayOfNulls<Long>(2)

    var onCalibrated: (Int, Double, Double) -> Unit = { _, _, _ -> }

    @Synchronized fun setNames(n: List<String>) { channelNames = n }

    @Synchronized fun setCalibration(ch: Int, r: Double?, h: Double?) { rest[ch] = r; hold[ch] = h }

    @Synchronized
    fun reset() { initialized = false; window.clear(); trace.clear(); stamps.clear(); lastT = null; raw = null; capStep = null; count = 0 }

    @Synchronized
    fun add(t: Long, values: IntArray) {
        if (values.size != count) { count = values.size; initialized = false }
        raw = values
        lastT = t
        stamps.addLast(t); while (stamps.isNotEmpty() && t - stamps.first() > 2_000L) stamps.removeFirst()
        for (i in values.indices) {
            smooth[i] = if (!initialized) values[i].toDouble() else smooth[i] + ALPHA * (values[i] - smooth[i])
            // Near the ADC ceiling for half a second: an electrical/gain problem, not a strong contraction.
            if (values[i] >= 1015) { if (limitSince[i] == null) limitSince[i] = t } else limitSince[i] = null
        }
        initialized = true
        window.addLast(t to values); while (window.isNotEmpty() && t - window.first().first > 2_000L) window.removeFirst()
        if (t - lastTrace >= 50L) {
            lastTrace = t
            trace.addLast(t to DoubleArray(count) { i -> pct(i) ?: smooth[i] / 10.23 })
            while (trace.isNotEmpty() && t - trace.first().first > 20_000L) trace.removeFirst()
        }
        stepCapture(t)
    }

    private fun pct(i: Int): Double? {
        val r = rest[i] ?: return null
        val h = hold[i] ?: return null
        if (h - r < 10) return null
        return max(0.0, (smooth[i] - r) / (h - r) * 100)
    }

    /** Starts a guided step: 3 s to get ready, then 5 s measured. */
    @Synchronized
    fun startCapture(step: EmgStep, now: Long) {
        if (step == EmgStep.Hold2 && count < 2) { message = "Only one sensor is sending"; return }
        if (step != EmgStep.Rest && rest.take(count).any { it == null }) { message = "Capture rest first"; return }
        capStep = step; capStart = now; capValues.clear(); restOthers.clear(); capMax = 0; message = null
    }

    @Synchronized
    fun cancelCapture() { capStep = null }

    private fun stepCapture(t: Long) {
        val step = capStep ?: return
        val el = t - capStart
        if (el < PREP_MS) return
        val ch = when (step) { EmgStep.Hold2 -> 1; else -> 0 }
        if (step == EmgStep.Rest) restOthers += smooth.copyOf(count) else capValues += smooth[ch]
        raw?.let { r -> capMax = max(capMax, if (step == EmgStep.Rest) r.max() else r[ch]) }
        if (el < PREP_MS + MEASURE_MS) return
        capStep = null
        finishCapture(step, ch)
    }

    private fun finishCapture(step: EmgStep, ch: Int) {
        if (capMax >= 1015) { message = "Signal hit the ADC limit — check gain, snaps and cable, then redo"; restOthers.clear(); return }
        if (step == EmgStep.Rest) {
            val rows = restOthers.toList(); restOthers.clear()
            if (rows.size < 100) { message = "Too few samples (${rows.size}) — is the Arduino sending at 100 Hz?"; return }
            for (i in 0 until count) {
                val v = rows.map { it[i] }
                val m = v.average()
                val sd = sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
                if (sd > 25) { message = "${channelNames.getOrElse(i) { "Sensor ${i + 1}" }} wasn't steady at rest — relax and redo"; return }
            }
            for (i in 0 until count) {
                rest[i] = rows.map { it[i] }.sorted()[rows.size / 2]
                onCalibrated(i, rest[i]!!, hold[i] ?: -1.0)
            }
            message = "Rest captured — now a comfortable firm hold"
            return
        }
        val v = capValues.sorted()
        if (v.size < 100) { message = "Too few samples — redo"; return }
        val h = v[(v.size * 0.8).toInt().coerceAtMost(v.size - 1)]
        val r = rest[ch] ?: return
        if (h - r < 30) { message = "Not enough rise above rest on ${channelNames.getOrElse(ch) { "the sensor" }} — squeeze a bit firmer, or check placement"; return }
        hold[ch] = h
        onCalibrated(ch, r, h)
        message = "${channelNames.getOrElse(ch) { "Sensor" }} calibrated — 100% = this hold"
    }

    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis()): EmgSnapshot {
        val chans = (0 until count).map { i ->
            val vals = window.map { it.second.getOrElse(i) { 0 } }
            EmgChannel(
                channelNames.getOrElse(i) { "Sensor ${i + 1}" }, raw?.getOrNull(i), if (initialized) smooth[i] else null, pct(i),
                rest[i], hold[i], vals.minOrNull(), vals.maxOrNull(), limitSince[i]?.let { (lastT ?: it) - it >= 500L } == true
            )
        }
        val hz = if (stamps.size >= 2) (stamps.size - 1) * 1000.0 / (stamps.last() - stamps.first()).coerceAtLeast(1) else 0.0
        val cap = capStep?.let { s ->
            val el = now - capStart
            val prep = el < PREP_MS
            EmgCapture(
                s, if (prep) "Get ready" else "Measuring", ((if (prep) PREP_MS - el else PREP_MS + MEASURE_MS - el) / 1000.0).coerceAtLeast(0.0),
                when (s) {
                    EmgStep.Rest -> "Relax completely"
                    EmgStep.Hold1 -> "Comfortable firm hold: ${channelNames.getOrElse(0) { "sensor 1" }}"
                    EmgStep.Hold2 -> "Comfortable firm hold: ${channelNames.getOrElse(1) { "sensor 2" }}"
                }
            )
        }
        return EmgSnapshot(chans, hz, lastT, cap, message, trace.toList())
    }

    companion object {
        /** Like Sarah's ALPHA_ENV at 100 Hz. */
        const val ALPHA = 0.12
        const val PREP_MS = 3_000L
        const val MEASURE_MS = 5_000L
    }
}
