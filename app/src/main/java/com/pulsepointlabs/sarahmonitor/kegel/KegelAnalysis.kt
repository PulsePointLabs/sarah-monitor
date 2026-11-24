package com.pulsepointlabs.sarahmonitor.kegel

import kotlin.math.sqrt

/** One squeeze (or one elevator floor) measured from the pressure trace. Values are % of the reference hold, or kPa without one. */
data class Contraction(
    val index: Int,
    val rep: Int,
    val startMs: Long,
    val endMs: Long,
    val targetPct: Int,
    val peak: Double?,
    val mean: Double?,
    /** Share of the squeeze spent at or above 70 % of target. */
    val onTargetPct: Int?,
    /** Seconds from the cue to first reaching 70 % of target (null = never got there). */
    val riseS: Double?,
    /** Wobble while holding (standard deviation over the last 70 % of the squeeze), same units as [mean]. */
    val steadiness: Double?,
    /** Seconds after the cue to let go back below the relax line (null = never fully let go before the next step). */
    val releaseS: Double?
)

/** A pressure point for the Kegel views: time and value (% of reference, or kPa above rest without a reference). */
data class KegelPoint(val t: Long, val v: Double)

/**
 * Measures every contraction in a set from the raw pressure, so the review can show each one in detail.
 * With a reference hold, values are % of it (target lines apply); without, kPa above rest (targets not drawn).
 */
object KegelAnalysis {
    /** Below this (% of reference) counts as let go. Without a reference, half the hold threshold in kPa. */
    const val RELAX_PCT = 20.0

    fun toValue(aboveKpa: Double, referenceKpa: Double?): Double =
        referenceKpa?.takeIf { it > 0.3 }?.let { aboveKpa / it * 100.0 } ?: aboveKpa

    fun relaxLine(referenceKpa: Double?, thresholdKpa: Double): Double = if (referenceKpa != null && referenceKpa > 0.3) RELAX_PCT else thresholdKpa * 0.5

    fun contractions(record: KegelRecord, points: List<KegelPoint>): List<Contraction> {
        val hasRef = record.referenceKpa != null && record.referenceKpa > 0.3
        val relax = relaxLine(record.referenceKpa, record.thresholdKpa ?: 0.3)
        // Rep of each squeeze window: in order, windows map onto reps (elevator floors share a rep).
        val perRep = if (record.reps > 0) (record.squeezes.size / record.reps).coerceAtLeast(1) else 1
        return record.squeezes.mapIndexed { i, (a, b, target) ->
            val inside = points.filter { it.t in a..b }.map { it.v }
            val tgt = if (hasRef) target * 0.7 else null
            val reach = tgt?.let { th -> points.firstOrNull { it.t in a..b && it.v >= th }?.let { (it.t - a) / 1000.0 } }
            val settled = points.filter { it.t >= a + (b - a) * 3 / 10 && it.t <= b }.map { it.v }
            val nextStart = record.squeezes.getOrNull(i + 1)?.first ?: (b + 15_000L)
            val release = points.firstOrNull { it.t > b && it.t < nextStart && it.v < relax }?.let { (it.t - b) / 1000.0 }
            Contraction(
                index = i + 1, rep = i / perRep + 1, startMs = a, endMs = b, targetPct = target,
                peak = inside.maxOrNull(), mean = inside.takeIf { it.isNotEmpty() }?.average(),
                onTargetPct = if (tgt == null || inside.isEmpty()) null else inside.count { it >= tgt } * 100 / inside.size,
                riseS = reach, steadiness = settled.takeIf { it.size >= 3 }?.let { sd(it) }, releaseS = release
            )
        }
    }

    /** -1…1: how much the peaks fell (-) or rose (+) from the first half of the set to the second. */
    fun fatigueTrend(cs: List<Contraction>): Double? {
        val peaks = cs.mapNotNull { it.peak }
        if (peaks.size < 4) return null
        val first = peaks.take(peaks.size / 2).average(); val second = peaks.drop(peaks.size / 2).average()
        if (first <= 0.0) return null
        return ((second - first) / first * 2).coerceIn(-1.0, 1.0)
    }

    private fun sd(v: List<Double>): Double { val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / v.size) }
}
