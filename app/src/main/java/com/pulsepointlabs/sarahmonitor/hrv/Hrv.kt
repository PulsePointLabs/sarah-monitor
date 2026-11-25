package com.pulsepointlabs.sarahmonitor.hrv

import kotlin.math.pow
import kotlin.math.sqrt

data class RrFilterResult(
    val rawMs: Int,
    val cleanMs: Int?,
    val valid: Boolean,
    val rejectionReason: String?,
    val artifact: Boolean = false,
    val accepted: Boolean = valid,
    /**
     * A real heartbeat interval that touches an ectopic (premature) beat. It is excluded from HRV because it
     * is not normal-to-normal, but it is not a signal artifact. [cleanMs] keeps the measured interval.
     */
    val ectopic: Boolean = false
)

data class HrvMetrics(
    val rmssdMs: Double?,
    val sdnnMs: Double?,
    val pnn50Percent: Double?,
    val rrCoveragePercent: Double,
    val validCount: Int,
    val rejectedCount: Int,
    val totalCount: Int,
    val acceptedCount: Int,
    /** Accepted share of the intervals that could have been normal-to-normal, i.e. ectopic intervals excluded. */
    val rrAcceptancePercent: Double,
    val contextualArtifactCount: Int,
    val quality: String,
    /** Intervals excluded as ectopic (premature beat and the interval after it). Not counted as artifacts. */
    val ectopicCount: Int = 0
)

object HrvCalculator {
    private const val ABSOLUTE_MIN_MS = 300
    private const val ABSOLUTE_MAX_MS = 2000
    private const val CONTEXT_MIN_PRIOR_ACCEPTED = 3
    private const val CONTEXT_WINDOW_SIZE = 7
    private const val ABRUPT_CHANGE_RATIO = 0.25
    private const val MISSED_BEAT_MIN_RATIO = 1.8
    private const val MISSED_BEAT_MAX_RATIO = 2.2
    private const val FALSE_BEAT_MIN_RATIO = 0.45
    private const val FALSE_BEAT_MAX_RATIO = 0.60
    private const val RESEED_RUN_SIZE = 6
    private const val RESEED_RUN_TOLERANCE = 0.12
    // Matches the ECG ectopy detector's PREMATURE_RATIO: an interval this much shorter than the local normal is early.
    private const val PREMATURE_RATIO = 0.86
    // A premature beat also arrives abruptly: at least 12% earlier than the interval before it. Respiratory sinus
    // arrhythmia shortens intervals gradually, so this keeps normal breathing variation out of the ectopic bucket.
    private const val PREMATURE_PREVIOUS_RATIO = 0.88
    // The interval after an ectopic beat may be a full compensatory pause, a reset, or anything in between.
    private const val POST_ECTOPIC_MAX_RATIO = 1.6
    private const val COMPENSATORY_SUM_TOLERANCE = 0.15
    // Two intervals summing to one normal interval: an interpolated beat or a spurious split detection.
    private const val SPLIT_SUM_TOLERANCE = 0.15
    private const val PREMATURE_TREND_RATIO = 0.92
    private const val RAW_MEAN_WINDOW = 8
    private const val RAW_MEAN_MIN_COUNT = 4
    private const val RAW_MEAN_MAX_DEVIATION = 0.45
    private const val ECTOPY_REPLAY_DEPTH = 6
    // Heart-rate packets arrive about once a second, so neighbouring beats' offsets can differ from the interval by up to that.
    private const val MAX_NEIGHBOUR_SLACK_MS = 2_000L
    private const val HR_PACKET_INTERVAL_MS = 1_000L

    const val REASON_ECTOPIC_PREMATURE = "ectopic_premature"
    const val REASON_ECTOPIC_COMPENSATORY = "ectopic_compensatory_pause"
    const val REASON_ECTOPIC_POST = "ectopic_post_beat"
    const val REASON_ECTOPIC_INTERPOLATED = "ectopic_interpolated"
    const val REASON_ECTOPIC_RUN = "ectopic_run"

    /** True for an interval excluded because it touches an ectopic beat (as opposed to a signal artifact). */
    fun isEctopicReason(reason: String?): Boolean = reason?.startsWith("ectopic_") == true

    fun isEctopic(sample: RrSampleEntity): Boolean =
        !sample.accepted && !sample.artifact && isEctopicReason(sample.rejectionReason)

    fun filter(rawMs: Int): RrFilterResult {
        return when {
            rawMs < ABSOLUTE_MIN_MS -> RrFilterResult(rawMs, null, false, "below_absolute_minimum", accepted = false)
            rawMs > ABSOLUTE_MAX_MS -> RrFilterResult(rawMs, null, false, "above_absolute_maximum", accepted = false)
            else -> RrFilterResult(rawMs, rawMs, true, null)
        }
    }

    private enum class BeatState { Normal, Premature, Run, PostEctopic, Artifact }

    private data class Classification(val state: BeatState, val reason: String?)

    /** One earlier raw interval and how the replay classified it. */
    private data class History(val ms: Int, val state: BeatState)

    /**
     * Classifies one interval against the local normal interval [median], given the two intervals before it and how
     * they were classified. Ectopy is recognized from the RR pattern alone: an abrupt early interval, then the
     * interval that starts at the ectopic beat (compensatory pause, reset or interpolated), or a run of early beats.
     */
    private fun classify(
        rawMs: Int,
        median: Double,
        prematureReference: Double,
        previous: History?,
        beforePrevious: History?
    ): Classification {
        val ratio = rawMs.toDouble() / median
        val prematureRatio = rawMs.toDouble() / prematureReference
        val deviation = kotlin.math.abs(rawMs - median) / median
        val previousState = previous?.state ?: BeatState.Normal
        val sumWithPrevious = previous?.let { (it.ms + rawMs).toDouble() }
        val completesOneInterval = sumWithPrevious != null &&
            kotlin.math.abs(sumWithPrevious - median) / median <= SPLIT_SUM_TOLERANCE

        if (previous != null && (previousState == BeatState.Premature || previousState == BeatState.Run)) {
            val previousRatio = previous.ms / median
            when {
                previousState == BeatState.Premature && completesOneInterval ->
                    // Two very short halves of one normal interval are a spurious extra detection, not a beat.
                    return if (ratio < FALSE_BEAT_MAX_RATIO && previousRatio < FALSE_BEAT_MAX_RATIO) {
                        Classification(BeatState.Artifact, "probable_false_beat")
                    } else {
                        Classification(BeatState.PostEctopic, REASON_ECTOPIC_INTERPOLATED)
                    }
                ratio >= FALSE_BEAT_MAX_RATIO && ratio < PREMATURE_RATIO ->
                    return Classification(BeatState.Run, REASON_ECTOPIC_RUN)
                ratio >= PREMATURE_RATIO && ratio <= POST_ECTOPIC_MAX_RATIO -> {
                    val compensatory = kotlin.math.abs(sumWithPrevious!! - 2.0 * median) / (2.0 * median) <= COMPENSATORY_SUM_TOLERANCE
                    return Classification(
                        BeatState.PostEctopic,
                        if (compensatory) REASON_ECTOPIC_COMPENSATORY else REASON_ECTOPIC_POST
                    )
                }
            }
        }
        if (previousState == BeatState.Artifact && completesOneInterval && ratio < PREMATURE_RATIO) {
            // The remainder of an interval that a spurious detection split in two.
            return Classification(BeatState.Artifact, "probable_false_beat")
        }
        if (ratio in MISSED_BEAT_MIN_RATIO..MISSED_BEAT_MAX_RATIO) {
            return Classification(BeatState.Artifact, "probable_missed_beat")
        }
        if (ratio >= FALSE_BEAT_MIN_RATIO && prematureRatio < PREMATURE_RATIO && isAbruptlyEarly(rawMs, median, previous, beforePrevious)) {
            return Classification(BeatState.Premature, REASON_ECTOPIC_PREMATURE)
        }
        return when {
            ratio in FALSE_BEAT_MIN_RATIO..FALSE_BEAT_MAX_RATIO -> Classification(BeatState.Artifact, "probable_false_beat")
            deviation > ABRUPT_CHANGE_RATIO -> Classification(BeatState.Artifact, "abrupt_contextual_change")
            else -> Classification(BeatState.Normal, null)
        }
    }

    /**
     * The interval a beat must undercut to count as premature. The median of accepted intervals alone drifts upward
     * once short intervals start being excluded (strong breathing variation would then feed on itself), so it is
     * capped by the plain mean of recent raw intervals. That mean is unbiased by ectopy: an early beat and its
     * compensatory pause, or bigeminy's short-long pairs, average out to the normal interval. Intervals far from the
     * median (missed or split detections) are left out of the mean.
     */
    private fun prematureReference(median: Double, recentRawRrMs: List<Int>): Double {
        val recent = recentRawRrMs.takeLast(RAW_MEAN_WINDOW)
            .filter { kotlin.math.abs(it - median) / median <= RAW_MEAN_MAX_DEVIATION }
        if (recent.size < RAW_MEAN_MIN_COUNT) return median
        return minOf(median, recent.average())
    }

    /**
     * A premature beat cuts the rhythm short suddenly: clearly shorter than the interval before it and than where a
     * gradual trend (two normal intervals extrapolated) would land. Respiratory sinus arrhythmia can drop well below
     * the local median, but it does so smoothly over several beats, so it does not meet this test.
     */
    private fun isAbruptlyEarly(rawMs: Int, median: Double, previous: History?, beforePrevious: History?): Boolean {
        if (previous == null || previous.state == BeatState.Artifact) return rawMs <= median * PREMATURE_PREVIOUS_RATIO
        if (rawMs > previous.ms * PREMATURE_PREVIOUS_RATIO) return false
        if (previous.state == BeatState.Normal && beforePrevious?.state == BeatState.Normal && previous.ms < beforePrevious.ms) {
            val trend = 2.0 * previous.ms - beforePrevious.ms
            if (rawMs > trend * PREMATURE_TREND_RATIO) return false
        }
        return true
    }

    /**
     * [recentRawRrMs] holds the raw intervals that immediately preceded [rawMs], accepted or not.
     * When they form a steady run with [rawMs], the rhythm has genuinely shifted (posture change,
     * exercise onset) and the run is accepted instead of being judged forever against a stale baseline.
     * They are also replayed to recognize ectopic patterns (premature beat, compensatory pause, bigeminy).
     */
    fun filterContextual(rawMs: Int, priorAcceptedRrMs: List<Int>, recentRawRrMs: List<Int> = emptyList()): RrFilterResult {
        val absolute = filter(rawMs)
        if (!absolute.valid) return absolute
        val baselineValues = priorAcceptedRrMs.takeLast(CONTEXT_WINDOW_SIZE)
        if (baselineValues.size < CONTEXT_MIN_PRIOR_ACCEPTED) return absolute

        val median = median(baselineValues)
        if (median <= 0.0) return absolute
        val prematureReference = prematureReference(median, recentRawRrMs)

        // Replay the last few raw intervals against the same local normal to know how the previous ones were classified.
        var previous: History? = null
        var beforePrevious: History? = null
        recentRawRrMs.takeLast(ECTOPY_REPLAY_DEPTH).forEach { earlier ->
            val state = classify(earlier, median, prematureReference, previous, beforePrevious).state
            beforePrevious = previous
            previous = History(earlier, state)
        }
        val reason = classify(rawMs, median, prematureReference, previous, beforePrevious).reason
        return if (reason == null || isSteadyRun(rawMs, recentRawRrMs)) {
            absolute
        } else if (isEctopicReason(reason)) {
            RrFilterResult(
                rawMs = rawMs,
                cleanMs = rawMs,
                valid = true,
                rejectionReason = reason,
                artifact = false,
                accepted = false,
                ectopic = true
            )
        } else {
            RrFilterResult(
                rawMs = rawMs,
                cleanMs = null,
                valid = true,
                rejectionReason = reason,
                artifact = true,
                accepted = false
            )
        }
    }

    fun cleanSeries(rawIntervalsMs: List<Int>): List<RrFilterResult> {
        val accepted = mutableListOf<Int>()
        val recentRaw = mutableListOf<Int>()
        return rawIntervalsMs.map { raw ->
            val result = filterContextual(raw, accepted, recentRaw)
            if (result.accepted && result.cleanMs != null) accepted += result.cleanMs
            if (result.valid) recentRaw += raw
            result
        }
    }

    private fun isSteadyRun(rawMs: Int, recentRawRrMs: List<Int>): Boolean {
        if (recentRawRrMs.size < RESEED_RUN_SIZE - 1) return false
        val run = recentRawRrMs.takeLast(RESEED_RUN_SIZE - 1) + rawMs
        val runMedian = median(run)
        return run.all { kotlin.math.abs(it - runMedian) / runMedian <= RESEED_RUN_TOLERANCE }
    }

    /**
     * [hrSamples] is the number of heart-rate packets, which the strap sends about once a second, so it stands in for
     * the elapsed time unless [elapsedMs] is given. When [rr] is only a recent window of a longer series (live use),
     * the totals describe the whole series and [ectopicCount] is estimated from the window unless passed in.
     */
    fun calculate(
        rr: List<RrSampleEntity>,
        hrSamples: Int,
        totalRrCount: Int = rr.size,
        acceptedCount: Int = rr.count { it.accepted },
        artifactCount: Int = rr.count { it.artifact },
        ectopicCount: Int = estimateEctopicCount(rr, totalRrCount, acceptedCount, artifactCount),
        elapsedMs: Long? = null
    ): HrvMetrics {
        val valid = rr.filter { it.accepted }.mapNotNull { it.cleanIntervalMs }
        val rejected = (totalRrCount - acceptedCount).coerceAtLeast(0)
        val ectopic = ectopicCount.coerceIn(0, rejected)
        // Ectopic intervals are real beats that are simply not normal-to-normal; they say nothing about signal quality.
        val eligible = totalRrCount - ectopic
        val acceptance = if (eligible <= 0) 0.0 else acceptedCount.toDouble() / eligible.toDouble() * 100.0
        val coverage = coveragePercent(rr, hrSamples, totalRrCount, elapsedMs)
        val successive = successiveDifferences(rr)
        val hasNnPairs = valid.size >= 3 && successive.isNotEmpty()
        val rmssd = if (hasNnPairs) {
            sqrt(successive.map { it.toDouble().pow(2.0) }.average())
        } else null
        val sdnn = if (hasNnPairs) {
            val avg = valid.average()
            sqrt(valid.map { (it - avg).pow(2.0) }.average())
        } else null
        val pnn50 = if (hasNnPairs) {
            successive.count { kotlin.math.abs(it) > 50 }.toDouble() / successive.size.toDouble() * 100.0
        } else null
        val quality = when {
            !hasNnPairs -> "Unavailable"
            coverage >= 70.0 && acceptance >= 98.0 -> "Good"
            coverage >= 30.0 && acceptance >= 90.0 -> "Limited"
            else -> "Poor"
        }
        return HrvMetrics(
            rmssdMs = rmssd,
            sdnnMs = sdnn,
            pnn50Percent = pnn50,
            rrCoveragePercent = coverage,
            validCount = acceptedCount,
            rejectedCount = rejected,
            totalCount = totalRrCount,
            acceptedCount = acceptedCount,
            rrAcceptancePercent = acceptance,
            contextualArtifactCount = artifactCount,
            quality = quality,
            ectopicCount = ectopic
        )
    }

    /**
     * Share of the elapsed time that is spanned by received RR intervals. Counting intervals per heart-rate packet
     * instead made coverage depend on heart rate (about 67% at 40 bpm with perfect data, 100% at 120 bpm with half
     * the beats missing).
     */
    private fun coveragePercent(rr: List<RrSampleEntity>, hrSamples: Int, totalRrCount: Int, elapsedMs: Long?): Double {
        val elapsed = elapsedMs ?: (hrSamples.toLong() * HR_PACKET_INTERVAL_MS)
        if (elapsed <= 0L || totalRrCount <= 0 || rr.isEmpty()) return 0.0
        val windowSumMs = rr.sumOf { it.rawIntervalMs.coerceIn(0, ABSOLUTE_MAX_MS).toLong() }.toDouble()
        val rrTimeMs = if (rr.size >= totalRrCount) windowSumMs else windowSumMs / rr.size * totalRrCount
        return (rrTimeMs / elapsed.toDouble() * 100.0).coerceIn(0.0, 100.0)
    }

    /**
     * Ectopic intervals in [rr]. A "premature" interval that the next interval showed to be the first half of a
     * spurious split detection is noise, so it is left out and counts against quality like the artifact after it.
     */
    fun countEctopic(rr: List<RrSampleEntity>): Int =
        rr.indices.count { index ->
            isEctopic(rr[index]) && !(
                rr[index].rejectionReason == REASON_ECTOPIC_PREMATURE &&
                    rr.getOrNull(index + 1)?.rejectionReason == "probable_false_beat"
                )
        }

    /**
     * Exact when [rr] is the whole series. For a bounded live window with whole-session totals, the window's share
     * of ectopic intervals among its non-artifact rejections is applied to the session's non-artifact rejections.
     */
    private fun estimateEctopicCount(rr: List<RrSampleEntity>, totalRrCount: Int, acceptedCount: Int, artifactCount: Int): Int {
        val windowEctopic = countEctopic(rr)
        if (rr.size >= totalRrCount) return windowEctopic
        val windowOtherRejections = rr.count { !it.accepted && !it.artifact }
        if (windowOtherRejections <= 0) return 0
        val sessionOtherRejections = (totalRrCount - acceptedCount - artifactCount).coerceAtLeast(0)
        return Math.round(sessionOtherRejections.toDouble() * windowEctopic / windowOtherRejections).toInt()
    }

    /**
     * Beat-to-beat differences for RMSSD and pNN50, taken only between intervals that were truly back to
     * back: both accepted, nothing rejected between them, and no recording gap. Pairing across a removed
     * beat or a dropout compares beats that were never neighbours and inflates HRV. Ectopic intervals are never
     * accepted, so pairs touching a premature beat or its post-ectopic pause are skipped as well.
     */
    fun successiveDifferences(rr: List<RrSampleEntity>): List<Int> =
        rr.zipWithNext().mapNotNull { (a, b) ->
            val first = a.cleanIntervalMs?.takeIf { a.accepted } ?: return@mapNotNull null
            val second = b.cleanIntervalMs?.takeIf { b.accepted } ?: return@mapNotNull null
            if (b.offsetMs - a.offsetMs > second + MAX_NEIGHBOUR_SLACK_MS) return@mapNotNull null
            second - first
        }

    private fun median(values: List<Int>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]).toDouble() / 2.0
        } else {
            sorted[mid].toDouble()
        }
    }
}

/** One RR interval with its filter result (SarahVS stored these in Room; here they live in memory). */
data class RrSampleEntity(
    val id: String,
    val sessionId: String,
    val timestampMs: Long,
    val offsetMs: Long,
    val rawIntervalMs: Int,
    val cleanIntervalMs: Int?,
    val valid: Boolean,
    val rejectionReason: String?,
    val artifact: Boolean = false,
    val accepted: Boolean = valid
)
