package com.pulsepointlabs.sarahmonitor.hrv

import com.pulsepointlabs.sarahmonitor.ble.AccelerometerSample
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class PhysiologicalStressLevel {
    EstablishingBaseline,
    Settled,
    MildLoad,
    ElevatedLoad,
    HighLoad,
    Moving,
    PoorSignal,
    Unavailable
}

data class PhysiologicalStressReading(
    val score: Int? = null,
    val level: PhysiologicalStressLevel = PhysiologicalStressLevel.Unavailable,
    val currentHr: Double? = null,
    val currentRmssdMs: Double? = null,
    val baselineHr: Double? = null,
    val baselineRmssdMs: Double? = null,
    val lowMotionPercent: Double? = null,
    val rrAcceptancePercent: Double? = null,
    val confidencePercent: Int? = null,
    val baselineCoveragePercent: Double? = null,
    val hrLoadPercent: Int? = null,
    val hrvSuppressionPercent: Int? = null,
    val motionSamplesObserved: Int = 0,
    val motionSamplesRequired: Int = MIN_MOTION_SAMPLES,
    val rrSamplesObserved: Int = 0,
    val rrSamplesRequired: Int = MIN_RR_SAMPLES,
    val hrSamplesObserved: Int = 0,
    val hrSamplesRequired: Int = MIN_HR_SAMPLES,
    val windowSpanMs: Long = 0L,
    val windowSpanRequiredMs: Long = MIN_WINDOW_SPAN_MS,
    val baselineWindowsObserved: Int = 0,
    val baselineWindowsRequired: Int = 0
) {
    companion object {
        const val MIN_HR_SAMPLES = 20
        const val MIN_RR_SAMPLES = 20
        const val MIN_MOTION_SAMPLES = 60
        const val MIN_WINDOW_SPAN_MS = 45_000L
    }
}

data class PhysiologicalStressBaseline(val hr: Double, val rmssdMs: Double)

/**
 * A conservative autonomic-load estimate. It scores only clean, low-motion windows and combines
 * HR elevation with RMSSD suppression relative to a personal baseline. It does not infer emotion.
 *
 * The first baseline needs [baselineWindowCount] non-overlapping one-minute windows. After that the baseline
 * keeps adapting slowly (an exponentially weighted update from calm, low-motion windows with a bounded
 * per-window step), so a baseline captured in one posture does not keep the reading elevated forever.
 */
class PhysiologicalStressEstimator(
    initialBaseline: PhysiologicalStressBaseline? = null,
    private val onBaselineUpdated: (PhysiologicalStressBaseline) -> Unit = {},
    private val evaluationIntervalMs: Long = 10_000L,
    private val analysisWindowMs: Long = 60_000L,
    private val baselineWindowCount: Int = 5
) {
    private data class TimedValue(val timestampMs: Long, val value: Double)
    /** [followsPrevious] is false when a rejected beat or a gap separates this interval from the one before it. */
    private data class TimedRr(val timestampMs: Long, val value: Double, val followsPrevious: Boolean)
    /** [ectopic] intervals are real beats that are not normal-to-normal; they do not count against signal quality. */
    private data class TimedAcceptance(val timestampMs: Long, val accepted: Boolean, val ectopic: Boolean = false)
    private data class TimedMotion(val timestampMs: Long, val quietMotion: Boolean)
    private data class WindowMetrics(
        val hr: Double,
        val rmssdMs: Double,
        val rrAcceptancePercent: Double,
        val lowMotionPercent: Double,
        val hrSamplesObserved: Int,
        val rrSamplesObserved: Int,
        val motionSamplesObserved: Int,
        val windowSpanMs: Long
    )

    private val hr = ArrayDeque<TimedValue>()
    private val rr = ArrayDeque<TimedRr>()
    private var rrChainBroken = true
    private val rrAcceptance = ArrayDeque<TimedAcceptance>()
    private val motion = ArrayDeque<TimedMotion>()
    private val baselineCandidates = ArrayDeque<WindowMetrics>()
    private var lastBaselineCandidateMs = Long.MIN_VALUE
    private var lastBaselineAdaptMs = Long.MIN_VALUE
    private var baseline = initialBaseline
    private var baselineWindowScore = if (initialBaseline == null) 0 else baselineWindowCount
    private var lastEvaluationMs = Long.MIN_VALUE
    private var latest = PhysiologicalStressReading(
        level = if (initialBaseline == null) PhysiologicalStressLevel.EstablishingBaseline else PhysiologicalStressLevel.Unavailable,
        baselineHr = initialBaseline?.hr,
        baselineRmssdMs = initialBaseline?.rmssdMs,
        baselineCoveragePercent = if (initialBaseline == null) 0.0 else 100.0,
        baselineWindowsObserved = if (initialBaseline == null) 0 else baselineWindowCount,
        baselineWindowsRequired = baselineWindowCount
    )

    @Synchronized
    fun addHeartRate(timestampMs: Long, bpm: Int) {
        if (bpm in 25..250) hr.addLast(TimedValue(timestampMs, bpm.toDouble()))
        trim(timestampMs)
    }

    /**
     * [ectopic] marks an interval excluded because it touches an ectopic beat rather than because of noise. It
     * defaults to the RR filter's convention: an ectopic interval keeps its measured value but is not accepted,
     * while artifacts and out-of-range intervals carry no clean value.
     */
    @Synchronized
    fun addRr(timestampMs: Long, intervalMs: Int?, accepted: Boolean, ectopic: Boolean = !accepted && intervalMs != null) {
        rrAcceptance.addLast(TimedAcceptance(timestampMs, accepted, ectopic && !accepted))
        if (accepted && intervalMs != null && intervalMs in 300..2000) {
            val previous = rr.lastOrNull()
            val follows = !rrChainBroken && previous != null &&
                timestampMs - previous.timestampMs <= intervalMs + MAX_NEIGHBOUR_SLACK_MS
            rr.addLast(TimedRr(timestampMs, intervalMs.toDouble(), follows))
            rrChainBroken = false
        } else {
            rrChainBroken = true
        }
        trim(timestampMs)
    }

    @Synchronized
    fun addAccelerometer(samples: List<AccelerometerSample>) {
        samples.forEach { sample ->
            val movement = MovementAnalyzer.classify(sample.xMilliG, sample.yMilliG, sample.zMilliG)
            motion.addLast(
                TimedMotion(
                    timestampMs = sample.timestampMs,
                    quietMotion = movement.dynamicMagnitudeMilliG <= QUIET_DYNAMIC_MG
                )
            )
        }
        samples.lastOrNull()?.timestampMs?.let(::trim)
    }

    @Synchronized
    fun evaluate(nowMs: Long): PhysiologicalStressReading {
        if (lastEvaluationMs != Long.MIN_VALUE && nowMs - lastEvaluationMs < evaluationIntervalMs) return latest
        lastEvaluationMs = nowMs
        val metrics = windowMetrics(nowMs) ?: run {
            latest = unavailableReading(nowMs)
            return latest
        }

        if (metrics.lowMotionPercent < MIN_LOW_MOTION_PERCENT) {
            latest = metrics.toReading(PhysiologicalStressLevel.Moving)
            return latest
        }
        if (metrics.rrAcceptancePercent < MIN_RR_ACCEPTANCE_PERCENT) {
            latest = metrics.toReading(PhysiologicalStressLevel.PoorSignal)
            return latest
        }

        if (baseline == null) {
            // Only non-overlapping windows count, so the first baseline rests on several distinct minutes of data.
            if (lastBaselineCandidateMs == Long.MIN_VALUE || nowMs - lastBaselineCandidateMs >= analysisWindowMs) {
                baselineCandidates.addLast(metrics)
                lastBaselineCandidateMs = nowMs
                lastBaselineAdaptMs = nowMs
            }
            while (baselineCandidates.size > baselineWindowCount) baselineCandidates.removeFirst()
            if (baselineCandidates.size >= baselineWindowCount) {
                baseline = PhysiologicalStressBaseline(
                    hr = median(baselineCandidates.map { it.hr }),
                    rmssdMs = median(baselineCandidates.map { it.rmssdMs })
                ).also {
                    baselineWindowScore = baselineWindowCount
                    onBaselineUpdated(it)
                }
            }
            latest = metrics.toReading(PhysiologicalStressLevel.EstablishingBaseline)
            return latest
        }

        val activeBaseline = baseline!!
        val hrDelta = (metrics.hr - activeBaseline.hr).coerceAtLeast(0.0)
        val rmssdLogDrop = (ln(activeBaseline.rmssdMs) - ln(metrics.rmssdMs)).coerceAtLeast(0.0)
        val hrLoad = softPercent(hrDelta, 12.0)
        val hrvSuppression = softPercent(rmssdLogDrop, 0.42)
        val corroboration = min(hrLoad, hrvSuppression)
        val confidence = confidencePercent(metrics, corroboration)
        val confidenceFactor = 0.55 + (confidence / 100.0) * 0.45
        val rawScore = (hrLoad * 0.35 + hrvSuppression * 0.45 + corroboration * 0.20) * confidenceFactor
        val score = rawScore.toInt().coerceIn(0, 100)
        val level = when {
            score >= 82 && confidence >= 78 -> PhysiologicalStressLevel.HighLoad
            score >= 58 -> PhysiologicalStressLevel.ElevatedLoad
            score >= 28 -> PhysiologicalStressLevel.MildLoad
            else -> PhysiologicalStressLevel.Settled
        }
        adaptBaseline(nowMs, activeBaseline, metrics, score)
        latest = metrics.toReading(
            level = level,
            score = score,
            confidencePercent = confidence,
            hrLoadPercent = hrLoad.toInt().coerceIn(0, 100),
            hrvSuppressionPercent = hrvSuppression.toInt().coerceIn(0, 100)
        )
        return latest
    }

    /**
     * Slowly pulls the baseline toward the current calm, low-motion window, whatever the score: once per
     * non-overlapping window, by a fraction of the gap, never more than a bounded step. A baseline learned lying
     * down therefore follows a posture change over tens of minutes, while a brief stress episode barely moves it.
     */
    private fun adaptBaseline(nowMs: Long, activeBaseline: PhysiologicalStressBaseline, metrics: WindowMetrics, score: Int) {
        if (metrics.lowMotionPercent < CALM_LOW_MOTION_PERCENT) return
        if (lastBaselineAdaptMs != Long.MIN_VALUE && nowMs - lastBaselineAdaptMs < analysisWindowMs) return
        lastBaselineAdaptMs = nowMs
        // An elevated reading may be a real stress episode, so the baseline gives way to it at half the pace.
        val pace = if (score >= 58) 0.5 else 1.0
        val weight = BASELINE_ADAPT_WEIGHT * pace
        val maxHrStep = MAX_BASELINE_HR_STEP * pace
        val maxLogStep = MAX_BASELINE_LOG_RMSSD_STEP * pace
        val hrStep = ((metrics.hr - activeBaseline.hr) * weight).coerceIn(-maxHrStep, maxHrStep)
        val logStep = ((ln(metrics.rmssdMs) - ln(activeBaseline.rmssdMs)) * weight).coerceIn(-maxLogStep, maxLogStep)
        baseline = PhysiologicalStressBaseline(
            hr = activeBaseline.hr + hrStep,
            rmssdMs = activeBaseline.rmssdMs * exp(logStep)
        ).also {
            baselineWindowScore = (baselineWindowScore + 1).coerceAtMost(baselineWindowCount * 2)
            onBaselineUpdated(it)
        }
    }

    @Synchronized
    fun resetSession() {
        hr.clear()
        rr.clear()
        rrChainBroken = true
        rrAcceptance.clear()
        motion.clear()
        baselineCandidates.clear()
        lastBaselineCandidateMs = Long.MIN_VALUE
        lastBaselineAdaptMs = Long.MIN_VALUE
        lastEvaluationMs = Long.MIN_VALUE
        latest = PhysiologicalStressReading(
            level = if (baseline == null) PhysiologicalStressLevel.EstablishingBaseline else PhysiologicalStressLevel.Unavailable,
            baselineHr = baseline?.hr,
            baselineRmssdMs = baseline?.rmssdMs,
            baselineCoveragePercent = baselineCoveragePercent(),
            baselineWindowsObserved = baselineProgressCount(),
            baselineWindowsRequired = baselineWindowCount
        )
    }

    private fun windowMetrics(nowMs: Long): WindowMetrics? {
        val start = nowMs - analysisWindowMs
        val hrWindow = hr.filter { it.timestampMs >= start }
        val rrWindow = rr.filter { it.timestampMs >= start }
        val acceptanceWindow = rrAcceptance.filter { it.timestampMs >= start }
        val motionWindow = motion.filter { it.timestampMs >= start }
        val spanMs = (hrWindow.lastOrNull()?.timestampMs ?: nowMs) - (hrWindow.firstOrNull()?.timestampMs ?: nowMs)
        if (
            hrWindow.size < PhysiologicalStressReading.MIN_HR_SAMPLES ||
            rrWindow.size < PhysiologicalStressReading.MIN_RR_SAMPLES ||
            acceptanceWindow.size < PhysiologicalStressReading.MIN_RR_SAMPLES ||
            motionWindow.size < PhysiologicalStressReading.MIN_MOTION_SAMPLES
        ) return null
        if (spanMs < PhysiologicalStressReading.MIN_WINDOW_SPAN_MS) return null
        val differences = rrWindow.zipWithNext().filter { (_, b) -> b.followsPrevious }.map { (a, b) -> b.value - a.value }
        if (differences.size < MIN_RMSSD_DIFFERENCES) return null
        val rmssd = sqrt(differences.map { it.pow(2.0) }.average())
        if (!rmssd.isFinite() || rmssd <= 0.0) return null
        return WindowMetrics(
            hr = hrWindow.map { it.value }.average(),
            rmssdMs = rmssd,
            rrAcceptancePercent = acceptancePercent(acceptanceWindow) ?: return null,
            lowMotionPercent = motionWindow.count { it.quietMotion }.toDouble() / motionWindow.size * 100.0,
            hrSamplesObserved = hrWindow.size,
            rrSamplesObserved = rrWindow.size,
            motionSamplesObserved = motionWindow.size,
            windowSpanMs = spanMs
        )
    }

    private fun unavailableReading(nowMs: Long): PhysiologicalStressReading {
        val start = nowMs - analysisWindowMs
        val motionWindow = motion.filter { it.timestampMs >= start }
        val acceptanceWindow = rrAcceptance.filter { it.timestampMs >= start }
        val hrWindow = hr.filter { it.timestampMs >= start }
        val lowMotion = motionWindow.takeIf { it.isNotEmpty() }
            ?.let { values -> values.count { it.quietMotion }.toDouble() / values.size * 100.0 }
        val acceptance = acceptancePercent(acceptanceWindow)
        val spanMs = (hrWindow.lastOrNull()?.timestampMs ?: nowMs) - (hrWindow.firstOrNull()?.timestampMs ?: nowMs)
        val level = when {
            motionWindow.size < PhysiologicalStressReading.MIN_MOTION_SAMPLES ||
                hrWindow.size < PhysiologicalStressReading.MIN_HR_SAMPLES ||
                acceptanceWindow.size < PhysiologicalStressReading.MIN_RR_SAMPLES ||
                spanMs < PhysiologicalStressReading.MIN_WINDOW_SPAN_MS ->
                PhysiologicalStressLevel.Unavailable
            lowMotion != null && lowMotion < MIN_LOW_MOTION_PERCENT -> PhysiologicalStressLevel.Moving
            acceptance != null && acceptance < MIN_RR_ACCEPTANCE_PERCENT -> PhysiologicalStressLevel.PoorSignal
            else -> PhysiologicalStressLevel.EstablishingBaseline
        }
        return PhysiologicalStressReading(
            level = level,
            lowMotionPercent = lowMotion,
            rrAcceptancePercent = acceptance,
            baselineHr = baseline?.hr,
            baselineRmssdMs = baseline?.rmssdMs,
            baselineCoveragePercent = baselineCoveragePercent(),
            motionSamplesObserved = motionWindow.size,
            rrSamplesObserved = acceptanceWindow.size,
            hrSamplesObserved = hrWindow.size,
            windowSpanMs = spanMs,
            baselineWindowsObserved = baselineProgressCount(),
            baselineWindowsRequired = baselineWindowCount
        )
    }

    private fun WindowMetrics.toReading(
        level: PhysiologicalStressLevel,
        score: Int? = null,
        confidencePercent: Int? = null,
        hrLoadPercent: Int? = null,
        hrvSuppressionPercent: Int? = null
    ) =
        PhysiologicalStressReading(
            score = score,
            level = level,
            currentHr = hr,
            currentRmssdMs = rmssdMs,
            baselineHr = baseline?.hr,
            baselineRmssdMs = baseline?.rmssdMs,
            lowMotionPercent = lowMotionPercent,
            rrAcceptancePercent = rrAcceptancePercent,
            confidencePercent = confidencePercent,
            baselineCoveragePercent = baselineCoveragePercent(),
            hrLoadPercent = hrLoadPercent,
            hrvSuppressionPercent = hrvSuppressionPercent,
            motionSamplesObserved = motionSamplesObserved,
            rrSamplesObserved = rrSamplesObserved,
            hrSamplesObserved = hrSamplesObserved,
            windowSpanMs = windowSpanMs,
            baselineWindowsObserved = baselineProgressCount(),
            baselineWindowsRequired = baselineWindowCount
        )

    /** Accepted share of the intervals that could have been normal-to-normal: ectopic intervals are left out. */
    private fun acceptancePercent(window: List<TimedAcceptance>): Double? {
        val eligible = window.filterNot { it.ectopic }
        if (eligible.isEmpty()) return null
        return eligible.count { it.accepted }.toDouble() / eligible.size * 100.0
    }

    private fun baselineProgressCount(): Int =
        when {
            baseline != null -> baselineWindowCount
            baselineCandidates.isNotEmpty() -> baselineCandidates.size.coerceAtMost(baselineWindowCount)
            else -> 0
        }

    private fun baselineCoveragePercent(): Double =
        when {
            baseline != null -> (baselineWindowScore.toDouble() / (baselineWindowCount * 2).toDouble() * 100.0).coerceIn(50.0, 100.0)
            baselineCandidates.isNotEmpty() -> (baselineCandidates.size.toDouble() / baselineWindowCount.toDouble() * 100.0).coerceIn(0.0, 99.0)
            else -> 0.0
        }

    private fun confidencePercent(metrics: WindowMetrics, corroboration: Double): Int {
        val rrFactor = ((metrics.rrAcceptancePercent - MIN_RR_ACCEPTANCE_PERCENT) / (100.0 - MIN_RR_ACCEPTANCE_PERCENT)).coerceIn(0.0, 1.0)
        val motionFactor = ((metrics.lowMotionPercent - MIN_LOW_MOTION_PERCENT) / (100.0 - MIN_LOW_MOTION_PERCENT)).coerceIn(0.0, 1.0)
        val baselineFactor = (baselineCoveragePercent() / 100.0).coerceIn(0.0, 1.0)
        val corroborationFactor = (corroboration / 100.0).coerceIn(0.0, 1.0)
        return ((rrFactor * 0.25 + motionFactor * 0.20 + baselineFactor * 0.25 + corroborationFactor * 0.30) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun softPercent(value: Double, divisor: Double): Double {
        if (value <= 0.0) return 0.0
        return ((1.0 - exp(-value / divisor)) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun trim(nowMs: Long) {
        val cutoff = nowMs - MAX_BUFFER_MS
        while (hr.firstOrNull()?.timestampMs?.let { it < cutoff } == true) hr.removeFirst()
        while (rr.firstOrNull()?.timestampMs?.let { it < cutoff } == true) rr.removeFirst()
        while (rrAcceptance.firstOrNull()?.timestampMs?.let { it < cutoff } == true) rrAcceptance.removeFirst()
        while (motion.firstOrNull()?.timestampMs?.let { it < cutoff } == true) motion.removeFirst()
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private companion object {
        const val MAX_BUFFER_MS = 3 * 60_000L
        const val MIN_LOW_MOTION_PERCENT = 72.0
        const val MIN_RR_ACCEPTANCE_PERCENT = 95.0
        const val QUIET_DYNAMIC_MG = 140.0
        const val MAX_NEIGHBOUR_SLACK_MS = 2_000L
        // RMSSD from a handful of differences is noise; a minute of clean rhythm gives dozens.
        const val MIN_RMSSD_DIFFERENCES = 20
        const val CALM_LOW_MOTION_PERCENT = 85.0
        const val BASELINE_ADAPT_WEIGHT = 0.10
        const val MAX_BASELINE_HR_STEP = 1.0
        const val MAX_BASELINE_LOG_RMSSD_STEP = 0.05
    }
}
