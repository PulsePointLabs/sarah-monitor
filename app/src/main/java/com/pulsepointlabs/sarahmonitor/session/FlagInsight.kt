package com.pulsepointlabs.sarahmonitor.session

import kotlin.math.abs
import kotlin.math.roundToInt

/** One labeled number in a breakdown ("Peak", "128 bpm"). */
data class InsightFact(val label: String, val value: String)

/** A time series for a mini chart (timestamps + values, NaN = gap). */
data class InsightTrace(val label: String, val unit: String, val timestamps: LongArray, val values: DoubleArray)

data class FlagBreakdown(
    val facts: List<InsightFact>,
    val story: String,
    /** Window shown in the mini charts. */
    val fromMs: Long,
    val toMs: Long,
    val traces: List<InsightTrace>
)

/**
 * Expands an automatic flag into something you can read: what the main signal did before, during and after it,
 * what the rest of the body was doing at the peak, how long it took to settle, and what you'd marked nearby.
 */
object FlagInsight {
    private const val SIDE_MS = 120_000L

    private fun avg(xs: List<Double>) = xs.takeIf { it.isNotEmpty() }?.average()
    private fun n(v: Double?, d: Int = 0) = v?.let { if (d == 0) it.roundToInt().toString() else "%.${d}f".format(it) } ?: "--"

    fun explain(flag: SessionFlag, data: SessionData): FlagBreakdown {
        val from = flag.startMs - SIDE_MS
        val to = flag.endMs + SIDE_MS
        val hr = data.hr
        fun hrIn(a: Long, b: Long) = hr.filter { it.timestampMs in a until b }
        val before = hrIn(flag.startMs - SIDE_MS, flag.startMs)
        val during = hrIn(flag.startMs, flag.endMs + 1)
        val after = hrIn(flag.endMs + 1, flag.endMs + SIDE_MS)

        // The main signal for this kind of flag.
        data class Signal(val label: String, val unit: String, val get: (HrSampleEntity) -> Double?, val decimals: Int, val higherIsWorse: Boolean)
        val main = when (flag.kind.chart) {
            FlagChart.Hrv -> Signal("RMSSD", "ms", { it.rmssdMs }, 1, false)
            FlagChart.Load -> Signal("Load", "", { it.load?.toDouble() }, 0, true)
            else -> Signal("Heart rate", "bpm", { it.bpm.toDouble() }, 0, flag.kind != FlagKind.LowHr)
        }
        val facts = mutableListOf<InsightFact>()
        val sentences = mutableListOf<String>()
        val pelvicFlag = flag.kind.chart == FlagChart.Pelvic

        if (flag.kind.chart in setOf(FlagChart.Hr, FlagChart.Hrv, FlagChart.Load)) {
            val b = avg(before.mapNotNull(main.get))
            val d = avg(during.mapNotNull(main.get))
            val peakRow = (if (main.higherIsWorse) during.maxByOrNull { main.get(it) ?: Double.NEGATIVE_INFINITY } else during.minByOrNull { main.get(it) ?: Double.POSITIVE_INFINITY })
            val peak = peakRow?.let(main.get)
            val a = avg(after.mapNotNull(main.get))
            val unit = if (main.unit.isBlank()) "" else " ${main.unit}"
            facts += InsightFact("2 min before", n(b, main.decimals) + unit)
            facts += InsightFact("During (avg)", n(d, main.decimals) + unit)
            facts += InsightFact(if (main.higherIsWorse) "Peak" else "Lowest", n(peak, main.decimals) + unit)
            facts += InsightFact("2 min after", n(a, main.decimals) + unit)
            facts += InsightFact("Lasted", SessionAnalysis.durationWords(flag.durationMs.coerceAtLeast(1_000L)))
            // Settled: first point after the end within 25% of the way from the before-level to the peak.
            val settled = if (b != null && peak != null && abs(peak - b) > 0.01) {
                val limit = b + (peak - b) * 0.25
                hr.firstOrNull { it.timestampMs > flag.endMs && main.get(it)?.let { v -> if (main.higherIsWorse) v <= limit else v >= limit } == true }
                    ?.let { it.timestampMs - flag.endMs }
            } else null
            facts += InsightFact("Settled after", settled?.let { SessionAnalysis.durationWords(it.coerceAtLeast(1_000L)) } ?: "not yet")

            val change = if (b != null && peak != null) peak - b else null
            sentences += when (flag.kind) {
                FlagKind.HighLoad -> "Load rose from ${n(b)} to a peak of ${n(peak)} and stayed in the elevated range for ${SessionAnalysis.durationWords(flag.durationMs)}."
                FlagKind.HighHr -> "Heart rate held high for ${SessionAnalysis.durationWords(flag.durationMs)}, peaking at ${n(peak)} bpm (${n(b)} in the two minutes before)."
                FlagKind.HrSurge -> "Heart rate jumped ${n(change)} bpm in ${SessionAnalysis.durationWords(flag.durationMs.coerceAtLeast(1_000L))}, from ${n(b)} to ${n(peak)}."
                FlagKind.LowHr -> "Heart rate sat low, down to ${n(peak)} bpm, for ${SessionAnalysis.durationWords(flag.durationMs)}."
                FlagKind.HrvDrop -> "HRV dropped from ${n(b, 1)} to ${n(peak, 1)} ms and stayed suppressed for ${SessionAnalysis.durationWords(flag.durationMs)}."
                FlagKind.HrSignalGap -> "The strap sent nothing for ${SessionAnalysis.durationWords(flag.durationMs)}; numbers around it are less certain."
                else -> flag.detail
            }
            // The rest of the body at the peak.
            peakRow?.let { p ->
                val bHr = avg(before.map { it.bpm.toDouble() })
                val bR = avg(before.mapNotNull { it.rmssdMs })
                val ctx = mutableListOf<String>()
                if (main.label != "Heart rate") ctx += "heart rate ${p.bpm}" + (bHr?.let { " (${SessionAnalysis.signed(p.bpm - it)} on before)" } ?: "")
                if (main.label != "RMSSD") p.rmssdMs?.let { r -> ctx += "HRV ${n(r, 1)} ms" + (bR?.takeIf { it > 0 }?.let { " (${((r / it - 1) * 100).roundToInt()}%)" } ?: "") }
                if (main.label != "Load") p.load?.let { ctx += "load $it" }
                p.breathsPerMin?.let { ctx += "breathing ${it.roundToInt()}/min" }
                p.stress?.let { ctx += "estimated stress $it" }
                if (ctx.isNotEmpty()) sentences += "At that point: " + ctx.joinToString(", ") + "."
                p.rmssdMs?.let { facts += InsightFact("HRV at peak", n(it, 1) + " ms") }
                if (main.label != "Heart rate") facts += InsightFact("HR at peak", "${p.bpm} bpm")
                p.breathsPerMin?.let { facts += InsightFact("Breathing", "${it.roundToInt()}/min") }
                p.stress?.let { facts += InsightFact("Stress est.", "$it") }
            }
            // Plain read of what it means.
            val bR = avg(before.mapNotNull { it.rmssdMs })
            val pR = peakRow?.rmssdMs
            if (flag.kind in setOf(FlagKind.HighLoad, FlagKind.HighHr, FlagKind.HrSurge)) {
                sentences += if (bR != null && pR != null && pR < bR * 0.7) "HRV fell as heart rate rose, which is the pattern of a sympathetic (fight-or-flight / arousal) push rather than just moving around."
                else "HRV didn't fall much with it, so some of this may be movement or exertion rather than a stress or arousal response."
            }
            sentences += when {
                settled == null -> "It hadn't come back down by the end of what was recorded."
                settled <= 60_000L -> "It settled quickly afterwards (${SessionAnalysis.durationWords(settled.coerceAtLeast(1_000L))})."
                else -> "It took ${SessionAnalysis.durationWords(settled)} to settle afterwards."
            }
        } else if (pelvicFlag) {
            val ps = data.pelvic.filter { it.timestampMs in flag.startMs..flag.endMs }
            val peak = ps.mapNotNull { it.aboveRestKpa }.maxOrNull()
            facts += InsightFact("Peak", com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(peak))
            facts += InsightFact("Lasted", SessionAnalysis.durationWords(flag.durationMs.coerceAtLeast(1_000L)))
            during.maxByOrNull { it.bpm }?.let { facts += InsightFact("HR during", "${it.bpm} bpm max") }
            sentences += flag.detail + "."
            val hrB = avg(before.map { it.bpm.toDouble() }); val hrD = during.maxOfOrNull { it.bpm }
            if (hrB != null && hrD != null) sentences += "Heart rate went from about ${n(hrB)} to ${hrD} through it."
        } else {
            sentences += flag.detail + "."
        }
        if (flag.alongside.isNotEmpty()) sentences += "It overlapped with: " + flag.alongside.joinToString(", ") { it.lowerTitle } + "."
        val near = data.markers.filter { it.timestampMs in (flag.startMs - 60_000L)..(flag.endMs + 60_000L) }
        if (near.isNotEmpty()) sentences += "You'd marked " + near.joinToString(", ") { it.label.lowercase() + (if (it.note.isNotBlank()) " (\"${it.note.take(60)}\")" else "") } + " around it."

        val win = hr.filter { it.timestampMs in from..to }
        val ts = win.map { it.timestampMs }.toLongArray()
        val traces = buildList {
            if (pelvicFlag) {
                val p = data.pelvic.filter { it.timestampMs in from..to }
                add(InsightTrace("Pelvic", com.pulsepointlabs.sarahmonitor.core.Pressure.label, p.map { it.timestampMs }.toLongArray(), p.map { it.aboveRestKpa?.let(com.pulsepointlabs.sarahmonitor.core.Pressure::value) ?: Double.NaN }.toDoubleArray()))
            }
            add(InsightTrace("Heart rate", "bpm", ts, win.map { it.bpm.toDouble() }.toDoubleArray()))
            if (win.any { it.rmssdMs != null }) add(InsightTrace("RMSSD", "ms", ts, win.map { it.rmssdMs ?: Double.NaN }.toDoubleArray()))
            if (flag.kind.chart == FlagChart.Load && win.any { it.load != null }) add(0, InsightTrace("Load", "", ts, win.map { it.load?.toDouble() ?: Double.NaN }.toDoubleArray()))
        }
        return FlagBreakdown(facts, sentences.joinToString(" "), from, to, traces)
    }
}
