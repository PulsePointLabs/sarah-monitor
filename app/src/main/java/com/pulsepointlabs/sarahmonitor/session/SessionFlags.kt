package com.pulsepointlabs.sarahmonitor.session

import kotlin.math.max
import kotlin.math.min

enum class FlagSeverity { Info, Notable, High }

enum class FlagKind(val title: String, val chart: FlagChart) {
    HighHr("High heart rate", FlagChart.Hr),
    HrSurge("Heart-rate surge", FlagChart.Hr),
    LowHr("Low heart rate", FlagChart.Hr),
    HrSignalGap("HR signal gap", FlagChart.Hr),
    HrvDrop("HRV drop", FlagChart.Hrv),
    HighLoad("High physiological load", FlagChart.Load),
    LongHold("Long pelvic hold", FlagChart.Pelvic),
    StrongContraction("Strong contraction", FlagChart.Pelvic),
    ContractionBurst("Rhythmic contraction burst", FlagChart.Pelvic),
    SustainedPressure("Sustained pressure elevation", FlagChart.Pelvic),
    PressureFault("Pressure sensor problem", FlagChart.Pelvic),
    HighBp("High blood pressure", FlagChart.Bp),
    LowGlucose("Low glucose", FlagChart.Glucose),
    HighGlucose("High glucose", FlagChart.Glucose)
}

/** Title for use mid-sentence ("hrv drop" → "HRV drop"). */
val FlagKind.lowerTitle: String get() = title.lowercase().replace("hrv", "HRV")

/** Which review graph a flag belongs to (it's also listed on its own). */
enum class FlagChart { Hr, Hrv, Load, Pelvic, Bp, Glucose }

/** Something in the recording that looks worth a second look. Found automatically, never entered by the user. */
data class SessionFlag(
    val kind: FlagKind,
    val startMs: Long,
    val endMs: Long,
    val severity: FlagSeverity,
    val detail: String,
    /** Other flags that overlap this one, e.g. "with a contraction burst". */
    val alongside: List<FlagKind> = emptyList()
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Automatic flags across every sensor in a session. Thresholds are partly relative to the session itself (its
 * median HR/RMSSD, its typical contraction), so what's flagged is what stands out for that session. These are
 * prompts for review, not diagnoses.
 */
object SessionFlags {
    private const val HR_SMOOTH_MS = 15_000L
    private const val MIN_HIGH_HR_MS = 30_000L
    private const val MERGE_GAP_MS = 20_000L

    fun detect(data: SessionData): List<SessionFlag> {
        val flags = mutableListOf<SessionFlag>()
        flags += heart(data)
        flags += hrv(data)
        flags += load(data)
        flags += pelvic(data)
        data.bp.forEach { bp ->
            if (bp.systolic >= 140 || bp.diastolic >= 90) {
                val severe = bp.systolic >= 180 || bp.diastolic >= 120
                flags += SessionFlag(
                    FlagKind.HighBp, bp.timestampMs, bp.timestampMs,
                    if (severe) FlagSeverity.High else FlagSeverity.Notable,
                    "${bp.systolic}/${bp.diastolic} mmHg" + (if (severe) " (very high)" else "") + (bp.pulse?.let { ", pulse $it" } ?: "") + (bp.positionLabel?.let { ", $it" } ?: "") +
                        (SessionAnalysis.bpTiming(bp.timestampMs, data)?.let { "; taken $it, not during the recording" } ?: "")
                )
            }
        }
        data.glucose.filter { it.timestampMs in data.session.startTimeMs..data.endMs }.forEach { g ->
            when {
                g.mgDl < 70 -> flags += SessionFlag(FlagKind.LowGlucose, g.timestampMs, g.timestampMs, if (g.mgDl < 54) FlagSeverity.High else FlagSeverity.Notable, "${"%.0f".format(g.mgDl)} mg/dL")
                g.mgDl > 180 -> flags += SessionFlag(FlagKind.HighGlucose, g.timestampMs, g.timestampMs, if (g.mgDl > 250) FlagSeverity.High else FlagSeverity.Notable, "${"%.0f".format(g.mgDl)} mg/dL")
            }
        }
        return withOverlaps(flags.sortedBy { it.startMs })
    }

    /** Notes which flags happened together (within 30 s), so "HR surge during a contraction burst" reads as one story. */
    private fun withOverlaps(flags: List<SessionFlag>): List<SessionFlag> = flags.map { f ->
        val others = flags.filter { o ->
            o !== f && o.kind != f.kind && o.kind != FlagKind.HrSignalGap && f.kind != FlagKind.HrSignalGap &&
                o.startMs <= f.endMs + 30_000L && f.startMs <= o.endMs + 30_000L
        }.map { it.kind }.distinct()
        f.copy(alongside = others)
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Runs where [test] holds on consecutive points, as (start, end, indices), bridging short breaks. */
    private fun <T> runs(points: List<T>, time: (T) -> Long, test: (T) -> Boolean, mergeGapMs: Long): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var start: Long? = null
        var last = 0L
        points.forEach { p ->
            val t = time(p)
            if (test(p)) {
                if (start != null && t - last > mergeGapMs) { out += start!! to last; start = null }
                if (start == null) start = t
                last = t
            } else if (start != null && t - last > mergeGapMs) {
                out += start!! to last; start = null
            }
        }
        if (start != null) out += start!! to last
        return out
    }

    private fun heart(data: SessionData): List<SessionFlag> {
        val hr = data.hr
        if (hr.size < 30) return emptyList()
        val flags = mutableListOf<SessionFlag>()
        // 15 s rolling mean so a single noisy beat can't trigger anything.
        val smooth = ArrayList<Pair<Long, Double>>(hr.size)
        var from = 0
        var sum = 0.0
        hr.forEachIndexed { i, s ->
            sum += s.bpm
            while (s.timestampMs - hr[from].timestampMs > HR_SMOOTH_MS) { sum -= hr[from].bpm; from++ }
            smooth += s.timestampMs to sum / (i - from + 1)
        }
        val med = median(hr.map { it.bpm.toDouble() })!!
        val highThreshold = max(100.0, med + 25.0)
        runs(smooth, { it.first }, { it.second >= highThreshold }, MERGE_GAP_MS).forEach { (a, b) ->
            if (b - a < MIN_HIGH_HR_MS) return@forEach
            val peak = hr.filter { it.timestampMs in a..b }.maxOf { it.bpm }
            flags += SessionFlag(
                FlagKind.HighHr, a, b,
                if (peak >= 150) FlagSeverity.High else FlagSeverity.Notable,
                "Peak $peak bpm for ${SessionAnalysis.durationWords(b - a)} (session median ${"%.0f".format(med)})"
            )
        }
        runs(smooth, { it.first }, { it.second < 45.0 }, MERGE_GAP_MS).forEach { (a, b) ->
            if (b - a < MIN_HIGH_HR_MS) return@forEach
            val low = hr.filter { it.timestampMs in a..b }.minOf { it.bpm }
            flags += SessionFlag(FlagKind.LowHr, a, b, if (low < 40) FlagSeverity.High else FlagSeverity.Notable, "Down to $low bpm for ${SessionAnalysis.durationWords(b - a)}")
        }
        // Surges: a rise of 25+ bpm within a minute (smoothed), one flag per surge.
        var i = 0
        var lastSurgeEnd = Long.MIN_VALUE
        while (i < smooth.size) {
            val (t, v) = smooth[i]
            var j = i
            var lowest = v
            var lowT = t
            // lowest point in the preceding minute
            while (j > 0 && t - smooth[j - 1].first <= 60_000L) {
                j--
                if (smooth[j].second < lowest) { lowest = smooth[j].second; lowT = smooth[j].first }
            }
            if (v - lowest >= 25.0 && lowT > lastSurgeEnd) {
                // ride the surge to its peak
                var k = i
                while (k + 1 < smooth.size && smooth[k + 1].second >= smooth[k].second && smooth[k + 1].first - t < 120_000L) k++
                val peak = smooth[k]
                flags += SessionFlag(
                    FlagKind.HrSurge, lowT, peak.first,
                    if (peak.second - lowest >= 40) FlagSeverity.High else FlagSeverity.Notable,
                    "${"%.0f".format(lowest)} → ${"%.0f".format(peak.second)} bpm in ${SessionAnalysis.durationWords(peak.first - lowT)}"
                )
                lastSurgeEnd = peak.first
                i = k + 1
                continue
            }
            i++
        }
        // Signal gaps while recording (strap lost contact or the link dropped).
        hr.zipWithNext().forEach { (a, b) ->
            if (b.timestampMs - a.timestampMs > 10_000L) {
                flags += SessionFlag(FlagKind.HrSignalGap, a.timestampMs, b.timestampMs, FlagSeverity.Info, "No HR data for ${SessionAnalysis.durationWords(b.timestampMs - a.timestampMs)}")
            }
        }
        return flags
    }

    private fun hrv(data: SessionData): List<SessionFlag> {
        val points = data.hr.filter { it.rmssdMs != null }
        if (points.size < 120) return emptyList()
        val med = median(points.map { it.rmssdMs!! }) ?: return emptyList()
        if (med < 5) return emptyList()
        val limit = med * 0.6
        return runs(points, { it.timestampMs }, { it.rmssdMs!! <= limit }, MERGE_GAP_MS)
            .filter { (a, b) -> b - a >= 60_000L }
            .map { (a, b) ->
                val low = points.filter { it.timestampMs in a..b }.minOf { it.rmssdMs!! }
                SessionFlag(
                    FlagKind.HrvDrop, a, b,
                    if (low <= med * 0.4) FlagSeverity.High else FlagSeverity.Notable,
                    "RMSSD down to ${SessionAnalysis.num(low)} ms (session median ${SessionAnalysis.num(med)}) for ${SessionAnalysis.durationWords(b - a)}"
                )
            }
    }

    private fun load(data: SessionData): List<SessionFlag> {
        val points = data.hr.filter { it.load != null }
        if (points.isEmpty()) return emptyList()
        return runs(points, { it.timestampMs }, { it.load!! >= 58 }, MERGE_GAP_MS)
            .filter { (a, b) -> b - a >= 60_000L }
            .map { (a, b) ->
                val peak = points.filter { it.timestampMs in a..b }.maxOf { it.load!! }
                SessionFlag(
                    FlagKind.HighLoad, a, b,
                    if (peak >= 82) FlagSeverity.High else FlagSeverity.Notable,
                    "Load up to $peak (${if (peak >= 82) "high" else "elevated"}) for ${SessionAnalysis.durationWords(b - a)}"
                )
            }
    }

    private fun pelvic(data: SessionData): List<SessionFlag> {
        val summary = data.pelvicSummary
        val pulses = summary.pulses
        val flags = mutableListOf<SessionFlag>()
        // Excluded pressure: shown so nobody reads a displaced sensor as physiology.
        summary.faults.forEach { span ->
            flags += SessionFlag(
                FlagKind.PressureFault, span.startMs, span.endMs, FlagSeverity.Info,
                "${span.quality.label}; pressure here is excluded from contractions and statistics" + if (span.replayed) " (found by re-checking an older recording)" else ""
            )
        }
        summary.sustained.forEach {
            flags += SessionFlag(
                FlagKind.SustainedPressure, it.startMs, it.endMs, FlagSeverity.Info,
                "${SessionAnalysis.durationWords(it.endMs - it.startMs)} above the hold threshold, peak ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(it.peakKpa)}; too long for a contraction (sustained tension, posture or a drifting rest)"
            )
        }
        if (pulses.isEmpty()) return flags
        pulses.filter { it.durationS >= 5.0 }.forEach {
            flags += SessionFlag(FlagKind.LongHold, it.startMs, it.endMs, if (it.durationS >= 10) FlagSeverity.High else FlagSeverity.Notable, "${SessionAnalysis.num(it.durationS)} s hold, peak ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(it.peakKpa)}")
        }
        val typical = median(pulses.map { it.peakKpa }) ?: 0.0
        val strong = max(3.0, typical * 2.0)
        pulses.filter { it.peakKpa >= strong }.forEach {
            flags += SessionFlag(FlagKind.StrongContraction, it.startMs, it.endMs, FlagSeverity.Notable, "Peak ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(it.peakKpa)} (typical ${com.pulsepointlabs.sarahmonitor.core.Pressure.fmt(typical)})")
        }
        // Bursts: 4+ short contractions with each start within 3 s of the previous one.
        var i = 0
        while (i < pulses.size) {
            var j = i
            while (j + 1 < pulses.size && pulses[j + 1].startMs - pulses[j].startMs <= 3_000L && pulses[j + 1].durationS < 4.0) j++
            val count = j - i + 1
            if (count >= 4) {
                val group = pulses.subList(i, j + 1)
                val spacing = (group.last().startMs - group.first().startMs) / 1000.0 / (count - 1)
                flags += SessionFlag(
                    FlagKind.ContractionBurst, group.first().startMs, group.last().endMs,
                    if (count >= 8) FlagSeverity.High else FlagSeverity.Notable,
                    "$count contractions about ${SessionAnalysis.num(spacing)} s apart, peak ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(group.maxOf { it.peakKpa })}"
                )
                i = j + 1
            } else i++
        }
        return flags
    }

    /** "2 high-load periods, 1 heart-rate surge" — for the summary. */
    fun describeCounts(flags: List<SessionFlag>): String? {
        val counts = flags.filter { it.kind != FlagKind.HrSignalGap }.groupingBy { it.kind }.eachCount()
        if (counts.isEmpty()) return null
        return counts.entries.sortedByDescending { it.value }.joinToString(", ") { (k, n) -> "$n × ${k.lowerTitle}" }
    }

    fun clampToSession(flag: SessionFlag, startMs: Long, endMs: Long): SessionFlag =
        flag.copy(startMs = max(startMs, min(flag.startMs, endMs)), endMs = min(endMs, max(flag.endMs, startMs)))
}
