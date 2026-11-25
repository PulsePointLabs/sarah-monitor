package com.pulsepointlabs.sarahmonitor.core

import kotlin.math.max
import kotlin.math.sqrt

/** One trace point: time, raw pressure, pressure above rest (null until a rest level exists) and its quality. */
data class PelvicPoint(
    val timestampMs: Long,
    val pressureKpa: Double,
    val aboveRestKpa: Double?,
    val gapBefore: Boolean,
    val quality: PressureQuality = PressureQuality.Valid
)

/** A completed contraction: rise above the hold threshold and back down. */
data class PelvicPulse(val onsetMs: Long, val endMs: Long, val peakKpa: Double) {
    val durationS: Double get() = (endMs - onsetMs) / 1000.0
}

enum class RestSource { None, Auto, Captured }

enum class CaptureKind { Rest, Reference }

data class CaptureStatus(val kind: CaptureKind, val remainingS: Double, val message: String)

/** Something that changed the meaning of the pressure signal, timestamped for the recording. */
data class PelvicEvent(val timestampMs: Long, val kind: String, val detail: String)

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
    val trace: List<PelvicPoint> = emptyList(),
    /** Quality of the latest sample (what the recording stores with it). */
    val quality: PressureQuality = PressureQuality.Preview,
    /** The user switched pressure logging on (contractions and statistics count only then). */
    val logging: Boolean = false,
    /** The current hold has lasted longer than a contraction can: sustained elevation, origin unknown. */
    val sustained: Boolean = false
) {
    /** Above-rest pressure only when it may be analyzed (null while previewing, calibrating or faulted). */
    val usableAboveRestKpa: Double? get() = aboveRestKpa?.takeIf { quality.usable }
}

/**
 * Turns raw CIVET pressure into "pressure above rest" plus hold/pulse detection, with a measurement-quality state
 * on every sample.
 *
 * Workflow: connect and watch the live pressure while positioning (Preview). Capture a relaxed rest (or let auto
 * rest settle), then switch logging on. Only then do contractions, holds and pressure statistics count. Calibration
 * and preview samples are still recorded raw, labeled with their quality, and never feed the analysis.
 *
 * Rest comes from a 5 s relaxed capture when the user takes one, otherwise from a rolling low percentile of the
 * last 30 s ("auto rest"), which follows slow drift but is not as trustworthy as a captured rest. A reference
 * capture (a comfortable 5 s hold) lets the card show "% of reference". Readings are mechanical pressure relative
 * to the sensor's zero and seating, not calibrated muscle force; the rest level is not "zero anatomical pressure".
 *
 * While logging, [PressureIntegrity] watches for a suspected displacement (pressure far below rest) or a shifted
 * rest. Either one stops analysis until the user recaptures rest or resumes: a fault is never recalibrated away
 * silently. A signal gap (reconnect) waits [STABILIZE_MS] before trusting pressure again, without touching the rest.
 */
class PelvicProcessor {
    private val trace = ArrayDeque<PelvicPoint>()
    private val pulses = ArrayDeque<PelvicPulse>()
    private val integrity = PressureIntegrity()
    private val events = mutableListOf<PelvicEvent>()
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
    private var logging = false
    private var fault: PressureQuality? = null
    private var stabilizeUntilMs: Long? = null
    /** Auto rest only looks at samples from here on (moved forward when the user resumes after a fault). */
    private var autoRestFromMs = Long.MIN_VALUE

    @Synchronized
    fun reset() {
        trace.clear(); pulses.clear(); capture = null; captureValues.clear(); integrity.reset()
        holdOnsetMs = null; belowSinceMs = null; holdPeak = 0.0
        pulseCount = 0; sessionMax = null; longestHold = null; lastTimestamp = null; message = null
        logging = false; fault = null; stabilizeUntilMs = null; autoRestFromMs = Long.MIN_VALUE
    }

    /** Clears session statistics (counts, maxima) but keeps rest, reference and the logging state. */
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
        abortHold()
        message = null
        event(nowMs, "capture_start", if (kind == CaptureKind.Rest) "rest capture started" else "reference capture started")
        return null
    }

    @Synchronized
    fun clearCalibration() {
        capturedRest = null; restNoise = null; reference = null; message = null
        integrity.reset()
        lastTimestamp?.let { event(it, "rest_auto", "switched to auto rest") }
    }

    /** Switches pressure logging on. Needs a rest level (captured, or auto rest once it has settled). */
    @Synchronized
    fun startLogging(nowMs: Long): String? {
        if (capture != null) return "Wait for the capture to finish."
        val rest = currentRest(nowMs, trace.lastOrNull()?.pressureKpa)
            ?: return "No rest level yet. Relax and capture rest (or wait a few seconds for auto rest)."
        val wasFault = fault
        logging = true
        fault = null
        stabilizeUntilMs = null
        integrity.reset()
        resetStats()
        abortHold()
        message = "Logging pressure. Rest ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(rest)} (${if (capturedRest != null) "captured" else "auto"})."
        event(nowMs, "logging_on", "rest ${com.pulsepointlabs.sarahmonitor.core.Pressure.record(rest)} ${if (capturedRest != null) "captured" else "auto"}" + (wasFault?.let { "; cleared ${it.label}" } ?: ""))
        return null
    }

    @Synchronized
    fun stopLogging(nowMs: Long) {
        if (!logging) return
        logging = false
        fault = null
        abortHold()
        message = "Pressure logging paused (preview only)."
        event(nowMs, "logging_off", "logging switched off")
    }

    /**
     * The user checked the sensor and wants analysis back with the current rest (a false alarm, or re-seated at
     * the same level). Auto rest starts fresh so the faulty stretch can't drag it down.
     */
    @Synchronized
    fun resume(nowMs: Long): String? {
        val was = fault ?: return null
        fault = null
        integrity.reset()
        if (capturedRest == null) autoRestFromMs = nowMs
        stabilizeUntilMs = nowMs + STABILIZE_MS
        message = "Resumed. Checking the signal for ${STABILIZE_MS / 1000} s."
        event(nowMs, "resumed", "user resumed after ${was.label}")
        return null
    }

    /** The user reported a sensor problem (slipped, re-seated, re-inflated): analysis stops until they recalibrate or resume. */
    @Synchronized
    fun reportProblem(nowMs: Long, note: String) {
        event(nowMs, "reported_problem", note.ifBlank { "sensor problem reported" })
        if (!logging) return
        fault = PressureQuality.ReportedProblem
        abortHold()
        message = "Sensor problem noted. Re-seat, then capture rest or tap Resume."
    }

    /** Events since the last call (for the recording). */
    @Synchronized
    fun drainEvents(): List<PelvicEvent> = events.toList().also { events.clear() }

    /** Everything needed to describe the calibration at a moment (session start). */
    @Synchronized
    fun describeState(): String =
        "quality ${quality(lastTimestamp ?: 0L, currentRestOrNull()).name}; logging ${if (logging) "on" else "off"}; " +
            "rest ${currentRestOrNull()?.let { "%.2f".format(it) } ?: "none"} (${if (capturedRest != null) "captured" else "auto"})" +
            (restNoise?.let { "; rest noise ±${"%.2f".format(it)}" } ?: "") + (reference?.let { "; reference ${"%.2f".format(it)}" } ?: "")

    @Synchronized
    fun ingest(timestampMs: Long, pressureKpa: Double): PelvicSnapshot {
        val previous = lastTimestamp
        val backwards = previous?.let { timestampMs < it } ?: false
        val gapMs = previous?.let { timestampMs - it } ?: 0L
        val gap = backwards || gapMs > GAP_MS
        lastTimestamp = timestampMs
        // The phone clock was corrected backwards: start the trace afresh rather than draw time out of order.
        if (backwards) trace.clear()
        if (gap) abortHold()
        if (logging && fault == null && (backwards || gapMs >= RECONNECT_GAP_MS)) {
            stabilizeUntilMs = timestampMs + STABILIZE_MS
            event(timestampMs, "signal_gap", if (backwards) "clock went backwards" else "no pressure for ${"%.1f".format(gapMs / 1000.0)} s; stabilizing")
        }
        handleCapture(timestampMs, pressureKpa, gap)

        val rest = currentRest(timestampMs, pressureKpa)
        val above = rest?.let { pressureKpa - it }
        val issue = integrity.update(timestampMs, pressureKpa, above, rest, restNoise, capturedRest != null)
        val stabilizing = stabilizeUntilMs?.let { timestampMs < it } == true
        if (!stabilizing && stabilizeUntilMs != null) {
            stabilizeUntilMs = null
            event(timestampMs, "stable", "signal trusted again")
        }
        if (issue != null && logging && fault == null && capture == null && !stabilizing) raise(timestampMs, issue, pressureKpa, above, rest)

        val quality = quality(timestampMs, rest)
        trace.addLast(PelvicPoint(timestampMs, pressureKpa, above, gap, quality))
        while (trace.isNotEmpty() && timestampMs - trace.first().timestampMs > TRACE_MS) trace.removeFirst()

        val threshold = threshold()
        if (above != null && quality.usable) {
            detect(timestampMs, above, threshold)
            sessionMax = max(sessionMax ?: above, above)
        } else abortHold()
        while (pulses.isNotEmpty() && timestampMs - pulses.first().endMs > 60_000L) pulses.removeFirst()
        return snapshot(timestampMs, pressureKpa, above, rest, threshold, quality)
    }

    private fun quality(t: Long, rest: Double?): PressureQuality = when {
        capture != null -> PressureQuality.Calibrating
        fault != null -> fault!!
        !logging -> PressureQuality.Preview
        rest == null || stabilizeUntilMs?.let { t < it } == true -> PressureQuality.Stabilizing
        else -> PressureQuality.Valid
    }

    private fun raise(t: Long, issue: PressureIntegrity.Issue, pressure: Double, above: Double?, rest: Double?) {
        fault = when (issue) {
            PressureIntegrity.Issue.Displacement -> PressureQuality.SuspectedDisplacement
            PressureIntegrity.Issue.BaselineShift -> PressureQuality.BaselineShift
        }
        abortHold()
        message = when (issue) {
            PressureIntegrity.Issue.Displacement -> "Possible CIVET sensor displacement. Check the probe, then capture rest or tap Resume."
            PressureIntegrity.Issue.BaselineShift -> "Rest level shifted. Check the sensor, then capture a new rest or tap Resume."
        }
        event(t, if (issue == PressureIntegrity.Issue.Displacement) "suspected_displacement" else "baseline_shift",
            "pressure ${com.pulsepointlabs.sarahmonitor.core.Pressure.record(pressure)}, ${above?.let { com.pulsepointlabs.sarahmonitor.core.Pressure.record(it) } ?: "?"} vs rest ${rest?.let { com.pulsepointlabs.sarahmonitor.core.Pressure.record(it) } ?: "?"}")
    }

    private fun event(t: Long, kind: String, detail: String) {
        events += PelvicEvent(t, kind, detail)
        if (events.size > 200) events.removeAt(0)
    }

    private fun abortHold() {
        holdOnsetMs = null
        belowSinceMs = null
    }

    private fun handleCapture(timestampMs: Long, pressureKpa: Double, gap: Boolean) {
        val kind = capture ?: return
        if (gap) {
            capture = null
            message = "Signal interrupted. Repeat the ${if (kind == CaptureKind.Rest) "rest" else "hold"}."
            event(timestampMs, "capture_failed", "signal interrupted")
            return
        }
        captureValues += pressureKpa
        if (timestampMs - captureStartMs < CAPTURE_MS) return
        capture = null
        val values = captureValues.toList()
        if (values.size < MIN_CAPTURE_SAMPLES) {
            message = "Too few samples (${values.size}). Repeat."
            event(timestampMs, "capture_failed", "too few samples (${values.size})")
            return
        }
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        when (kind) {
            CaptureKind.Rest -> {
                if (sd > MAX_REST_NOISE_KPA) {
                    message = "Rest wasn't steady (±${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(sd)}). Relax and repeat."
                    event(timestampMs, "capture_failed", "rest not steady ±${com.pulsepointlabs.sarahmonitor.core.Pressure.record(sd)}")
                } else {
                    capturedRest = mean
                    restNoise = sd
                    reference = null
                    integrity.reset()
                    val cleared = fault
                    // Recapturing rest is the user recalibrating: a fault ends here, under their control.
                    fault = null
                    stabilizeUntilMs = null
                    message = "Rest set at ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(mean)}." +
                        if (logging) " Logging." else " Tap Start logging when ready."
                    event(timestampMs, "rest_captured", "rest ${com.pulsepointlabs.sarahmonitor.core.Pressure.record(mean)} ±${com.pulsepointlabs.sarahmonitor.core.Pressure.record(sd)}" + (cleared?.let { "; cleared ${it.label}" } ?: ""))
                    resetStats()
                }
            }
            CaptureKind.Reference -> {
                val rest = capturedRest ?: return
                val ref = percentile(values, 0.95) - rest
                val minimum = max(0.1, (restNoise ?: 0.05) * 6)
                message = if (ref < minimum) {
                    event(timestampMs, "capture_failed", "reference too small")
                    "Hold was too small or noisy. Repeat a steady 5 s hold."
                } else {
                    reference = ref
                    event(timestampMs, "reference_captured", "reference ${com.pulsepointlabs.sarahmonitor.core.Pressure.record(ref)} above rest")
                    "Reference set: ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(ref)} above rest = 100%."
                }
            }
        }
    }

    private fun currentRestOrNull(): Double? = lastTimestamp?.let { currentRest(it, trace.lastOrNull()?.pressureKpa) }

    private fun currentRest(timestampMs: Long, pressureKpa: Double?): Double? {
        capturedRest?.let { return it }
        val recent = trace.filter { it.timestampMs >= autoRestFromMs && timestampMs - it.timestampMs <= AUTO_REST_WINDOW_MS }.map { it.pressureKpa } +
            listOfNotNull(pressureKpa)
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
                // Longer than a contraction can last: sustained elevation (posture, tone or drift), not a pulse.
                if (pulse.durationS >= MIN_PULSE_S && pulse.durationS <= MAX_CONTRACTION_S) {
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

    private fun snapshot(nowMs: Long, pressure: Double, above: Double?, rest: Double?, threshold: Double, quality: PressureQuality): PelvicSnapshot {
        // Statistics only from samples that may be analyzed.
        val recent = trace.filter { nowMs - it.timestampMs <= 30_000L && it.quality.usable }.mapNotNull { it.aboveRestKpa }
        val previous = trace.filter { nowMs - it.timestampMs in 30_001L..60_000L && it.quality.usable }.mapNotNull { it.aboveRestKpa }
        val avg = recent.takeIf { it.isNotEmpty() }?.average()
        val prevAvg = previous.takeIf { it.size >= 50 }?.average()
        val trend = when {
            avg == null || prevAvg == null || recent.size < 50 -> 0
            avg - prevAvg > 0.1 -> 1
            avg - prevAvg < -0.1 -> -1
            else -> 0
        }
        val onset = holdOnsetMs
        val holdS = onset?.let { (nowMs - it) / 1000.0 }
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
            holdSeconds = holdS,
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
            trace = trace.toList(),
            quality = quality,
            logging = logging,
            sustained = holdS != null && holdS > MAX_CONTRACTION_S
        )
    }

    companion object {
        const val MIN_THRESHOLD_KPA = 0.3
        const val GAP_MS = 1_000L
        /** A gap this long (link stall or reconnect) makes the signal re-stabilize before it counts again. */
        const val RECONNECT_GAP_MS = 3_000L
        const val STABILIZE_MS = 5_000L
        const val TRACE_MS = 120_000L
        const val CAPTURE_MS = 5_000L
        const val MIN_CAPTURE_SAMPLES = 25
        const val MAX_REST_NOISE_KPA = 0.25
        const val AUTO_REST_WINDOW_MS = 30_000L
        const val AUTO_REST_MIN_SAMPLES = 20
        const val RELEASE_MS = 200L
        const val MIN_PULSE_S = 0.2
        /**
         * Longest rise still called a contraction. Voluntary pelvic-floor holds are seconds to tens of seconds;
         * anything longer is reported as sustained elevation (origin unknown). Exploratory boundary.
         */
        const val MAX_CONTRACTION_S = 30.0

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
