package com.pulsepointlabs.sarahmonitor.session

import com.pulsepointlabs.sarahmonitor.core.PelvicProcessor
import com.pulsepointlabs.sarahmonitor.core.PressureIntegrity
import com.pulsepointlabs.sarahmonitor.core.PressureQuality

/** A stretch of pressure that may not be analyzed, and why. */
data class QualitySpan(val quality: PressureQuality, val startMs: Long, val endMs: Long, val replayed: Boolean)

/**
 * The quality of every recorded pressure sample, as the review uses it.
 *
 * - Recorded with 0.25.0 or later: the quality stored live with each sample.
 * - Older recordings (no stored quality): [PressureIntegrity] is replayed over the raw samples, using the rest that
 *   was in force (`pressure - above rest`). Without the live latch, a problem is marked while its condition lasts
 *   plus [REPLAY_TAIL_MS]; everything else is [PressureQuality.Unknown] (usable, as before). No retroactive
 *   "stabilizing" after gaps: that is a live rule from 0.25 on.
 * - Either way, "Sensor problem" events exclude their range (or [POINT_EXCLUSION_MS] after a single moment).
 *
 * Original samples are never changed: this is computed on the fly, so a better algorithm can re-read old sessions.
 */
object PressureReview {
    const val REPLAY_TAIL_MS = 10_000L
    const val POINT_EXCLUSION_MS = 30_000L
    /** Same as the live processor: after a gap this long the signal re-stabilizes before it counts. */
    const val GAP_MS = PelvicProcessor.RECONNECT_GAP_MS

    fun qualities(samples: List<PelvicSampleEntity>, markers: List<MarkerEntity> = emptyList()): Array<PressureQuality> {
        val out = Array(samples.size) { i -> PressureQuality.of(samples[i].quality) ?: PressureQuality.Unknown }
        if (samples.any { it.quality == null }) replayLegacy(samples, out)
        val excluded = markers.filter { "sensor_issue" in MarkerTypes.split(it.types) }
            .map { it.timestampMs..(it.endTimeMs?.takeIf { e -> e > it.timestampMs } ?: (it.timestampMs + POINT_EXCLUSION_MS)) }
        if (excluded.isNotEmpty()) samples.forEachIndexed { i, s ->
            if (excluded.any { s.timestampMs in it }) out[i] = PressureQuality.ReportedProblem
        }
        return out
    }

    private fun replayLegacy(samples: List<PelvicSampleEntity>, out: Array<PressureQuality>) {
        val integrity = PressureIntegrity()
        var lastRest: Double? = null
        var restSteadySince: Long? = null
        var flagged: PressureQuality? = null
        var flaggedUntil = Long.MIN_VALUE
        var previous: Long? = null
        samples.forEachIndexed { i, s ->
            val gap = previous?.let { s.timestampMs - it } ?: 0L
            previous = s.timestampMs
            // Older recordings keep their own semantics after a gap (no retroactive "stabilizing"); only clear
            // displacements and shifted rests are flagged. The checks restart after the gap.
            if (gap >= GAP_MS) integrity.reset()
            if (s.quality != null) return@forEachIndexed
            val above = s.aboveRestKpa
            if (above == null) { out[i] = PressureQuality.Preview; return@forEachIndexed }
            val rest = s.pressureKpa - above
            // A rest that hasn't moved for a while was captured; an auto rest moves with the signal.
            val wasCaptured = restSteadySince?.let { s.timestampMs - it >= 20_000L } == true
            val changed = lastRest?.let { kotlin.math.abs(it - rest) >= 0.005 } ?: true
            if (changed) {
                restSteadySince = s.timestampMs
                // A captured rest that changes is a recapture: the user recalibrated, so start the checks afresh.
                if (wasCaptured) { integrity.reset(); flagged = null }
            }
            lastRest = rest
            val captured = restSteadySince?.let { s.timestampMs - it >= 20_000L } == true
            val issue = integrity.update(s.timestampMs, s.pressureKpa, above, rest, null, captured)
            if (issue != null) {
                flagged = if (issue == PressureIntegrity.Issue.Displacement) PressureQuality.SuspectedDisplacement else PressureQuality.BaselineShift
                flaggedUntil = s.timestampMs + REPLAY_TAIL_MS
            }
            out[i] = if (flagged != null && s.timestampMs <= flaggedUntil) flagged!! else { flagged = null; PressureQuality.Unknown }
        }
    }

    /** Contiguous stretches of faulted or unusable-while-recording pressure (preview/calibration excluded). */
    fun faultSpans(samples: List<PelvicSampleEntity>, q: Array<PressureQuality>): List<QualitySpan> {
        val spans = mutableListOf<QualitySpan>()
        var start = -1
        fun close(end: Int) {
            if (start < 0) return
            spans += QualitySpan(q[start], samples[start].timestampMs, samples[end].timestampMs, samples[start].quality == null)
            start = -1
        }
        for (i in samples.indices) {
            if (q[i].fault) {
                if (start >= 0 && q[start] != q[i]) close(i - 1)
                if (start < 0) start = i
            } else close(i - 1)
        }
        close(samples.lastIndex)
        return spans
    }
}
