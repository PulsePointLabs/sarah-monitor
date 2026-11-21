package com.pulsepointlabs.sarahmonitor.core

/** The last couple of minutes of each live metric, for the small trend lines and trend colours. */
enum class Metric(
    /** A change of this size over a minute counts as a strong rise or fall. */
    val fullScale: Double
) {
    Hr(10.0), Rmssd(10.0), Sdnn(10.0), Pnn50(10.0), Load(12.0), Pelvic(1.0)
}

data class TimedValue(val timestampMs: Long, val value: Double)

data class RecentHistory(val series: Map<Metric, List<TimedValue>> = emptyMap()) {
    fun values(metric: Metric): List<Double> = series[metric].orEmpty().map { it.value }

    /**
     * -1 (falling strongly) … 0 (steady) … +1 (rising strongly): the average of the last 15 s against the
     * average of 45–60 s earlier, scaled by the metric's [Metric.fullScale].
     */
    fun trend(metric: Metric, nowMs: Long = series[metric]?.lastOrNull()?.timestampMs ?: 0L): Double =
        RecentHistory.trend(series[metric].orEmpty(), metric.fullScale, nowMs)

    companion object {
        fun trend(points: List<TimedValue>, fullScale: Double, nowMs: Long): Double {
            val recent = points.filter { nowMs - it.timestampMs <= 15_000L }.map { it.value }
            val earlier = points.filter { nowMs - it.timestampMs in 45_000L..60_000L }.map { it.value }
            if (recent.isEmpty() || earlier.isEmpty()) return 0.0
            return ((recent.average() - earlier.average()) / fullScale).coerceIn(-1.0, 1.0)
        }
    }
}

/** Keeps [windowMs] of each metric, sampled no faster than once per [minStepMs]. */
class RecentHistoryRecorder(private val windowMs: Long = 120_000L, private val minStepMs: Long = 500L) {
    private val data = mutableMapOf<Metric, ArrayDeque<TimedValue>>()

    @Synchronized
    fun add(metric: Metric, timestampMs: Long, value: Double?): Boolean {
        if (value == null || !value.isFinite()) return false
        val q = data.getOrPut(metric) { ArrayDeque() }
        if (q.isNotEmpty() && timestampMs >= q.last().timestampMs && timestampMs - q.last().timestampMs < minStepMs) return false
        if (q.isNotEmpty() && timestampMs < q.last().timestampMs) q.clear()
        q.addLast(TimedValue(timestampMs, value))
        while (q.isNotEmpty() && timestampMs - q.first().timestampMs > windowMs) q.removeFirst()
        return true
    }

    @Synchronized
    fun snapshot(): RecentHistory = RecentHistory(data.mapValues { it.value.toList() })

    @Synchronized
    fun clear() = data.clear()
}
