package com.pulsepointlabs.sarahmonitor.core

import kotlin.math.max
import kotlin.math.sqrt

/** One trace point: time, raw pressure and pressure above rest (null until a rest level exists). */
data class PelvicPoint(val timestampMs: Long, val pressureKpa: Double, val aboveRestKpa: Double?, val gapBefore: Boolean)

/** A completed contraction: rise above the hold threshold and back down. */
data class PelvicPulse(val onsetMs: Long, val endMs: Long, val peakKpa: Double) {
    val durationS: Double get() = (endMs - onsetMs) / 1000.0
}

enum class RestSource { None, Auto, Captured }

enum class CaptureKind { Rest, Reference }

data class CaptureStatus(val kind: CaptureKind, val remainingS: Double, val message: String)

data class PelvicSnapshot(
    val pressureKpa: Double? = null,
    val aboveRestKpa: Double? = null,
    val restKpa: Double? = null,
    val restSource: RestSource = RestSource.None,
    val referenceKpa: Double? = null,
    val percentOfReference: Double? = null,
    val thresholdKpa: Double = PelvicProcessor.MIN_THRESHOLD_KPA,
    val holding: Boolean = false,
    val holdSeconds: Double? = null,
    val lastPulse: PelvicPulse? = null,
    val pulseCount: Int = 0,
    val pulsesLastMinute: Int = 0,
    val average30s: Double? = null,
    val max30s: Double? = null,
    val sessionMax: Double? = null,
    val longestHoldS: Double? = null,
    /** +1 rising, -1 falling, 0 steady: last 30 s average vs the 30 s before. */
    val trend: Int = 0,
    val capture: CaptureStatus? = null,
    val message: String? = null,
    val trace: List<PelvicPoint> = emptyList()
)

/**
 * Turns raw CIVET pressure into "pressure above rest" plus hold/pulse detection.
 *
 * Rest comes from a 5 s relaxed capture when the user takes one, otherwise from a rolling low percentile of the
 * last 30 s ("auto rest"), which follows slow drift but is not as trustworthy as a captured rest. A reference
 * capture (a comfortable 5 s hold) lets the card show "% of reference". Readings are mechanical pressure, not
 * calibrated muscle force.
 */
class PelvicProcessor {
    private val trace = ArrayDeque<PelvicPoint>()
    private val pulses = ArrayDeque<PelvicPulse>()
    private var capturedRest: Double? = null
    private var restNoise: Double? = null
    private var reference: Double? = null
    private var capture: CaptureKind? = null
    private var captureStartMs = 0L
    private val captureValues = mutableListOf<Double>()
    private var message: String? = null
    private var holdOnsetMs: Long? = null
    private var holdPeak = 0.0
    private var belowSinceMs: Long? = null
    private var pulseCount = 0
    private var sessionMax: Double? = null
    private var longestHold: Double? = null
    private var lastTimestamp: Long? = null

    @Synchronized
    fun reset() {
        trace.clear(); pulses.clear(); capture = null; captureValues.clear()
        holdOnsetMs = null; belowSinceMs = null; holdPeak = 0.0
        pulseCount = 0; sessionMax = null; longestHold = null; lastTimestamp = null; message = null
    }

    /** Clears session statistics (counts, maxima) but keeps rest and reference. */
    @Synchronized
    fun resetStats() {
        pulses.clear(); pulseCount = 0; sessionMax = null; longestHold = null
    }

    @Synchronized
    fun startCapture(kind: CaptureKind, nowMs: Long): String? {
        if (kind == CaptureKind.Reference && capturedRest == null) return "Capture rest first, fully relaxed."
        capture = kind
        captureStartMs = nowMs
        captureValues.clear()
        message = null
        return null
    }

    @Synchronized
    fun clearCalibration() {
        capturedRest = null; restNoise = null; reference = null; message = null
    }

    @Synchronized
    fun ingest(timestampMs: Long, pressureKpa: Double): PelvicSnapshot {
        val backwards = lastTimestamp?.let { timestampMs < it } ?: false
        val gap = backwards || lastTimestamp?.let { timestampMs - it > GAP_MS } ?: false
        lastTimestamp = timestampMs
        // The phone clock was corrected backwards: start the trace afresh rather than draw time out of order.
        if (backwards) trace.clear()
        if (gap) {
            holdOnsetMs = null
            belowSinceMs = null
        }
        handleCapture(timestampMs, pressureKpa, gap)

        val rest = currentRest(timestampMs, pressureKpa)
        val above = rest?.let { pressureKpa - it }
        trace.addLast(PelvicPoint(timestampMs, pressureKpa, above, gap))
        while (trace.isNotEmpty() && timestampMs - trace.first().timestampMs > TRACE_MS) trace.removeFirst()

        val threshold = threshold()
        if (above != null && capture == null) detect(timestampMs, above, threshold)
        if (above != null && capture == null) sessionMax = max(sessionMax ?: above, above)
        while (pulses.isNotEmpty() && timestampMs - pulses.first().endMs > 60_000L) pulses.removeFirst()
        return snapshot(timestampMs, pressureKpa, above, rest, threshold)
    }

    private fun handleCapture(timestampMs: Long, pressureKpa: Double, gap: Boolean) {
        val kind = capture ?: return
        if (gap) {
            capture = null
            message = "Signal interrupted. Repeat the ${if (kind == CaptureKind.Rest) "rest" else "hold"}."
            return
        }
        captureValues += pressureKpa
        if (timestampMs - captureStartMs < CAPTURE_MS) return
        capture = null
        val values = captureValues.toList()
        if (values.size < MIN_CAPTURE_SAMPLES) {
            message = "Too few samples (${values.size}). Repeat."
            return
        }
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        when (kind) {
            CaptureKind.Rest -> {
                if (sd > MAX_REST_NOISE_KPA) {
                    message = "Rest wasn't steady (±${"%.2f".format(sd)} kPa). Relax and repeat."
                } else {
                    capturedRest = mean
                    restNoise = sd
                    reference = null
                    message = "Rest set at ${"%.2f".format(mean)} kPa. Optional: capture a reference hold."
                    resetStats()
                }
            }
            CaptureKind.Reference -> {
                val rest = capturedRest ?: return
                val ref = percentile(values, 0.95) - rest
                val minimum = max(0.1, (restNoise ?: 0.05) * 6)
                message = if (ref < minimum) {
                    "Hold was too small or noisy. Repeat a steady 5 s hold."
                } else {
                    reference = ref
                    "Reference set: ${"%.2f".format(ref)} kPa above rest = 100%."
                }
            }
        }
    }

    private fun currentRest(timestampMs: Long, pressureKpa: Double): Double? {
        capturedRest?.let { return it }
        val recent = trace.filter { timestampMs - it.timestampMs <= AUTO_REST_WINDOW_MS }.map { it.pressureKpa } + pressureKpa
        if (recent.size < AUTO_REST_MIN_SAMPLES) return null
        return percentile(recent, 0.2)
    }

    private fun threshold(): Double = max(MIN_THRESHOLD_KPA, (restNoise ?: 0.0) * 5)

    private fun detect(timestampMs: Long, above: Double, threshold: Double) {
        val onset = holdOnsetMs
        if (onset == null) {
            if (above >= threshold) {
                holdOnsetMs = timestampMs
                holdPeak = above
                belowSinceMs = null
            }
            return
        }
        holdPeak = max(holdPeak, above)
        if (above < threshold * 0.5) {
            val since = belowSinceMs ?: timestampMs.also { belowSinceMs = it }
            if (timestampMs - since >= RELEASE_MS) {
                val pulse = PelvicPulse(onset, since, holdPeak)
                if (pulse.durationS >= MIN_PULSE_S) {
                    pulses.addLast(pulse)
                    pulseCount++
                    longestHold = max(longestHold ?: pulse.durationS, pulse.durationS)
                }
                holdOnsetMs = null
                belowSinceMs = null
            }
        } else {
            belowSinceMs = null
        }
    }

    private fun snapshot(nowMs: Long, pressure: Double, above: Double?, rest: Double?, threshold: Double): PelvicSnapshot {
        val recent = trace.filter { nowMs - it.timestampMs <= 30_000L }.mapNotNull { it.aboveRestKpa }
        val previous = trace.filter { nowMs - it.timestampMs in 30_001L..60_000L }.mapNotNull { it.aboveRestKpa }
        val avg = recent.takeIf { it.isNotEmpty() }?.average()
        val prevAvg = previous.takeIf { it.size >= 50 }?.average()
        val trend = when {
            avg == null || prevAvg == null || recent.size < 50 -> 0
            avg - prevAvg > 0.1 -> 1
            avg - prevAvg < -0.1 -> -1
            else -> 0
        }
        val onset = holdOnsetMs
        val captureStatus = capture?.let { kind ->
            val remaining = ((CAPTURE_MS - (nowMs - captureStartMs)) / 1000.0).coerceAtLeast(0.0)
            CaptureStatus(kind, remaining, if (kind == CaptureKind.Rest) "Relax completely…" else "Hold a comfortable squeeze…")
        }
        return PelvicSnapshot(
            pressureKpa = pressure,
            aboveRestKpa = above,
            restKpa = rest,
            restSource = when {
                capturedRest != null -> RestSource.Captured
                rest != null -> RestSource.Auto
                else -> RestSource.None
            },
            referenceKpa = reference,
            percentOfReference = if (above != null && reference != null) above / reference!! * 100.0 else null,
            thresholdKpa = threshold,
            holding = onset != null,
            holdSeconds = onset?.let { (nowMs - it) / 1000.0 },
            lastPulse = pulses.lastOrNull(),
            pulseCount = pulseCount,
            pulsesLastMinute = pulses.size,
            average30s = avg,
            max30s = recent.maxOrNull(),
            sessionMax = sessionMax,
            longestHoldS = longestHold,
            trend = trend,
            capture = captureStatus,
            message = message,
            trace = trace.toList()
        )
    }

    companion object {
        const val MIN_THRESHOLD_KPA = 0.3
        const val GAP_MS = 1_000L
        const val TRACE_MS = 120_000L
        const val CAPTURE_MS = 5_000L
        const val MIN_CAPTURE_SAMPLES = 25
        const val MAX_REST_NOISE_KPA = 0.25
        const val AUTO_REST_WINDOW_MS = 30_000L
        const val AUTO_REST_MIN_SAMPLES = 20
        const val RELEASE_MS = 200L
        const val MIN_PULSE_S = 0.2

        fun percentile(values: List<Double>, p: Double): Double {
            val sorted = values.sorted()
            if (sorted.isEmpty()) return Double.NaN
            val index = (p * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
            val lo = index.toInt()
            val hi = kotlin.math.min(lo + 1, sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (index - lo)
        }
    }
}
