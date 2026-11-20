package com.pulsepointlabs.sarahmonitor.ui

import com.pulsepointlabs.sarahmonitor.ble.EcgSample
import kotlin.math.roundToInt

/**
 * Display-only clean-up of the live ECG trace. Nothing here feeds a detector or a stored value.
 *
 * The raw H10 stream carries baseline wander (breathing, strap movement) and occasional spikes; drawing it
 * raw and scaling to the window's min/max let one spike or a drifting baseline flatten every beat.
 */
class EcgDisplayTrace(
    val timestampsMs: LongArray,
    /** Baseline-corrected microvolts. */
    val values: FloatArray,
    /** True where the path must restart (first sample, or a time step larger than the gap threshold). */
    val breakBefore: BooleanArray,
    val low: Float,
    val high: Float
) {
    val size: Int get() = timestampsMs.size

    /** 0 (bottom) to 1 (top), clipped to the robust bounds. */
    fun normalized(index: Int): Float = normalize(values[index])

    fun normalize(value: Float): Float = ((value - low) / (high - low)).coerceIn(0f, 1f)

    /** Index of the sample with exactly this timestamp, or -1. */
    fun indexOf(timestampMs: Long): Int = timestampsMs.binarySearch(timestampMs).let { if (it >= 0) it else -1 }
}

object EcgDisplayFilter {
    /** Centred moving-average window; subtracting it is roughly a 0.5 Hz high-pass. */
    const val BASELINE_WINDOW_MS = 800L
    /** A step longer than this between samples is a gap: the path breaks instead of bridging it. */
    const val GAP_MS = 100L
    private const val LOW_PERCENTILE = 0.01
    private const val HIGH_PERCENTILE = 0.99
    private const val MARGIN_FRACTION = 0.12f
    private const val MIN_RANGE_UV = 200f

    /**
     * @param smooth also applies a light [1, 2, 1] / 4 low-pass inside each gap-free segment. At the H10's
     * 130 Hz that is about a 30 Hz cutoff: it knocks out mains hum (60 Hz is nearly nulled) and muscle fuzz
     * while keeping the QRS shape, which is what a bedside monitor's "monitor" filter does.
     */
    fun prepare(samples: List<EcgSample>, smooth: Boolean = false): EcgDisplayTrace? {
        if (samples.size < 2) return null
        // Sort by time and drop exact duplicate timestamps.
        val sorted = samples.sortedBy { it.timestampMs }
        val times = ArrayList<Long>(sorted.size)
        val raw = ArrayList<Int>(sorted.size)
        sorted.forEach { sample ->
            if (times.isEmpty() || sample.timestampMs != times.last()) {
                times += sample.timestampMs
                raw += sample.microvolts
            }
        }
        val n = times.size
        if (n < 2) return null
        val t = times.toLongArray()
        val breakBefore = BooleanArray(n) { i -> i == 0 || t[i] - t[i - 1] > GAP_MS }

        // Centred moving average within each gap-free segment (prefix sums, two pointers).
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + raw[i]
        val segmentStart = IntArray(n)
        val segmentEnd = IntArray(n)
        var s = 0
        while (s < n) {
            var e = s
            while (e + 1 < n && !breakBefore[e + 1]) e++
            for (i in s..e) {
                segmentStart[i] = s
                segmentEnd[i] = e
            }
            s = e + 1
        }
        val half = BASELINE_WINDOW_MS / 2
        val filtered = FloatArray(n)
        var lo = 0
        var hi = 0
        for (i in 0 until n) {
            if (lo < segmentStart[i]) lo = segmentStart[i]
            while (lo < i && t[i] - t[lo] > half) lo++
            if (hi < i) hi = i
            while (hi + 1 <= segmentEnd[i] && t[hi + 1] - t[i] <= half) hi++
            // Keep the window centred near segment edges (shrink both sides) so drift does not bias the ends.
            val radius = minOf(i - lo, hi - i)
            val mean = (prefix[i + radius + 1] - prefix[i - radius]) / (2 * radius + 1)
            filtered[i] = (raw[i] - mean).toFloat()
        }

        if (smooth && n >= 3) {
            val source = filtered.copyOf()
            for (i in 0 until n) {
                val prev = if (i > segmentStart[i]) source[i - 1] else source[i]
                val next = if (i < segmentEnd[i]) source[i + 1] else source[i]
                filtered[i] = (prev + 2f * source[i] + next) / 4f
            }
        }

        // Robust bounds: 1st-99th percentile plus a margin, always containing the median baseline.
        val ordered = filtered.sortedArray()
        fun percentile(p: Double): Float = ordered[(ordered.lastIndex * p).roundToInt().coerceIn(0, ordered.lastIndex)]
        val median = percentile(0.5)
        var low = minOf(percentile(LOW_PERCENTILE), median)
        var high = maxOf(percentile(HIGH_PERCENTILE), median)
        val range = high - low
        if (range < MIN_RANGE_UV) {
            val pad = (MIN_RANGE_UV - range) / 2f
            low -= pad
            high += pad
        }
        val margin = (high - low) * MARGIN_FRACTION
        low -= margin
        high += margin
        return EcgDisplayTrace(t, filtered, breakBefore, low, high)
    }
}
