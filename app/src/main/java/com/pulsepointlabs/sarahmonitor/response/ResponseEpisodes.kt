package com.pulsepointlabs.sarahmonitor.response

import com.pulsepointlabs.sarahmonitor.core.PelvicPulse
import com.pulsepointlabs.sarahmonitor.session.SessionAnalysis
import com.pulsepointlabs.sarahmonitor.session.SessionData
import kotlin.math.abs
import kotlin.math.roundToInt

enum class EpisodeKind(val label: String) {
    Climax("Climax"),
    NearClimax("Near climax"),
    Edge("Edge"),
    Close("Close approach"),
    Build("Arousal build")
}

/** One build → peak → recovery cycle in a sexual session, measured from the recording. */
data class ResponseEpisode(
    val kind: EpisodeKind,
    val startMs: Long,
    val peakMs: Long,
    val endMs: Long,
    /** Highest approach-to-climax read (0–100): how close it got. */
    val peakApproach: Int,
    /** Highest arousal read (0–100): intensity. */
    val peakArousal: Int,
    val peakHr: Double?,
    /** Session baseline HR the rise is measured from. */
    val baselineHr: Double?,
    /** Lowest HR in the 3 minutes before the build (the trough it climbed from). */
    val troughHr: Double?,
    /** Seconds from the trough to the peak. */
    val buildS: Int?,
    val rmssdAtPeak: Double?,
    val rmssdBefore: Double?,
    val pelvicPeakKpa: Double?,
    val contractions: Int,
    /** HR fall from the peak 30 s / 60 s later (positive = dropped). */
    val drop30: Double?,
    val drop60: Double?,
    /** Seconds for HR to fall halfway back from the peak to the trough. */
    val halfRecoveryS: Int?,
    val breathsAtPeak: Double?,
    val stressAtPeak: Int?,
    val markedEvents: List<String>
) {
    val durationMs: Long get() = endMs - startMs
    val riseBpm: Double? get() = peakHr?.let { p -> (troughHr ?: baselineHr)?.let { p - it } }

    /** A plain, two-or-three sentence read of the episode. */
    fun story(): String {
        val parts = mutableListOf<String>()
        val rise = riseBpm
        val from = troughHr ?: baselineHr
        parts += buildString {
            append(when (kind) {
                EpisodeKind.Climax -> "This build ended in climax."
                EpisodeKind.NearClimax -> "You got close to climax here, then backed off."
                EpisodeKind.Edge -> "A marked edge."
                EpisodeKind.Close -> "A strong build that came fairly close."
                EpisodeKind.Build -> "A moderate arousal build."
            })
            if (peakHr != null && from != null && rise != null) {
                append(" Heart rate climbed from ${from.roundToInt()} to ${peakHr.roundToInt()} (+${rise.roundToInt()})")
                buildS?.let { append(" over ${SessionAnalysis.durationWords(it * 1000L)}") }
                append(".")
            }
        }
        val hrv = if (rmssdAtPeak != null && rmssdBefore != null && rmssdBefore > 0) {
            val pct = ((1 - rmssdAtPeak / rmssdBefore) * 100).roundToInt()
            if (pct >= 15) "HRV tightened by $pct% at the peak (${SessionAnalysis.num(rmssdBefore)} → ${SessionAnalysis.num(rmssdAtPeak)} ms), the usual sign of high sympathetic drive." else null
        } else null
        hrv?.let { parts += it }
        if (pelvicPeakKpa != null && pelvicPeakKpa >= 0.5) parts += "Pelvic floor peaked at ${SessionAnalysis.num(pelvicPeakKpa)} kPa above rest" + (if (contractions > 0) " with $contractions contraction${if (contractions == 1) "" else "s"}." else ".")
        val rec = when {
            halfRecoveryS != null -> "It came halfway back down in ${SessionAnalysis.durationWords(halfRecoveryS * 1000L)}" + (drop60?.let { " (−${it.roundToInt()} bpm after a minute)." } ?: ".")
            drop60 != null -> "HR was down ${drop60.roundToInt()} bpm a minute after the peak."
            else -> null
        }
        rec?.let { parts += it }
        return parts.joinToString(" ")
    }
}

/**
 * Finds arousal episodes by replaying the recording through the same [ArousalEngine] the live board uses, then
 * cutting its approach-to-climax trace into episodes: start at 50, end below 40, at least 20 s long, merged when
 * closer than 30 s. Each one is measured against the trough it climbed from.
 */
object ResponseEpisodes {
    private const val START = 50
    private const val END = 40

    fun detect(data: SessionData, profile: PersonalProfile = PersonalProfile()): List<ResponseEpisode> {
        if (data.hr.size < 60) return emptyList()
        val engine = ArousalEngine(profile)
        val pelvic = SessionAnalysis.pelvic(data.pelvic)
        val pulses = pelvic.pulses
        var pulseIdx = 0
        var pIdx = 0
        var mIdx = 0
        val markers = data.markers.sortedBy { it.timestampMs }
        data.hr.forEach { h ->
            val t = h.timestampMs
            while (pIdx < data.pelvic.size && data.pelvic[pIdx].timestampMs <= t) {
                val p = data.pelvic[pIdx]
                while (pulseIdx < pulses.size && pulses[pulseIdx].endMs <= p.timestampMs) pulseIdx++
                val last = pulses.getOrNull(pulseIdx - 1)?.let { PelvicPulse(it.startMs, it.endMs, it.peakKpa) }
                engine.onPelvic(p.timestampMs, p.aboveRestKpa, p.holding, pulseIdx, last)
                pIdx++
            }
            while (mIdx < markers.size && markers[mIdx].timestampMs <= t) {
                engine.onMarker(markers[mIdx].timestampMs, com.pulsepointlabs.sarahmonitor.session.MarkerTypes.split(markers[mIdx].types)); mIdx++
            }
            engine.onHeart(t, h.bpm, h.rmssdMs, h.load)
        }
        val timeline = engine.current().timeline
        if (timeline.isEmpty()) return emptyList()

        // Spans above threshold (with hysteresis), then merge close ones.
        val spans = mutableListOf<LongRange>()
        var open: Long? = null
        timeline.forEach { p ->
            if (open == null && p.approach >= START) open = p.timestampMs
            else if (open != null && p.approach < END) { spans += open!!..p.timestampMs; open = null }
        }
        open?.let { spans += it..timeline.last().timestampMs }
        val merged = mutableListOf<LongRange>()
        spans.forEach { s -> val last = merged.lastOrNull(); if (last != null && s.first - last.last < 30_000L) merged[merged.size - 1] = last.first..s.last else merged += s }

        val hrTs = data.hr.map { it.timestampMs }.toLongArray()
        fun lower(t: Long): Int { var i = hrTs.binarySearch(t); if (i < 0) i = -i - 1; return i }
        fun hrWindow(from: Long, to: Long) = data.hr.subList(lower(from), lower(to + 1).coerceAtLeast(lower(from)))
        fun smoothHr(t: Long): Double? = hrWindow(t - 5_000L, t + 5_000L).map { it.bpm }.takeIf { it.isNotEmpty() }?.average()

        return merged.filter { it.last - it.first >= 20_000L }.map { span ->
            val pts = timeline.filter { it.timestampMs in span }
            val peakPt = pts.maxBy { it.approach }
            // Peak HR within the episode (+30 s, the HR peak often lags the approach peak).
            val hrPeak = hrWindow(span.first, span.last + 30_000L).maxByOrNull { it.bpm }
            val peakMs = hrPeak?.timestampMs ?: peakPt.timestampMs
            val peakHr = smoothHr(peakMs)
            val before = hrWindow(span.first - 180_000L, span.first)
            val trough = before.minByOrNull { it.bpm }
            val troughHr = trough?.let { smoothHr(it.timestampMs) }
            val rmssdBefore = before.mapNotNull { it.rmssdMs }.takeIf { it.isNotEmpty() }?.average()
            val atPeak = data.hr.minByOrNull { abs(it.timestampMs - peakMs) }
            val drop30 = peakHr?.let { p -> smoothHr(peakMs + 30_000L)?.let { p - it } }
            val drop60 = peakHr?.let { p -> smoothHr(peakMs + 60_000L)?.let { p - it } }
            val half = if (peakHr != null && troughHr != null && peakHr - troughHr >= 6) {
                val target = peakHr - (peakHr - troughHr) / 2
                hrWindow(peakMs, peakMs + 300_000L).firstOrNull { (smoothHr(it.timestampMs) ?: 999.0) <= target }?.let { ((it.timestampMs - peakMs) / 1000).toInt() }
            } else null
            val win = span.first - 30_000L..span.last + 90_000L
            val marks = markers.filter { it.timestampMs in win }
            val types = marks.flatMap { com.pulsepointlabs.sarahmonitor.session.MarkerTypes.split(it.types) }.toSet()
            val climaxed = "orgasm" in types || "ejaculation" in types || pts.any { it.phase == ResponsePhase.Climax }
            val peakApproach = pts.maxOf { it.approach }
            val kind = when {
                climaxed -> EpisodeKind.Climax
                "edge" in types -> EpisodeKind.Edge
                peakApproach >= 75 || "near" in types -> EpisodeKind.NearClimax
                peakApproach >= 60 -> EpisodeKind.Close
                else -> EpisodeKind.Build
            }
            val pelvicIn = data.pelvic.filter { it.timestampMs in span.first..span.last + 30_000L }
            ResponseEpisode(
                kind = kind, startMs = span.first, peakMs = peakMs, endMs = span.last,
                peakApproach = peakApproach, peakArousal = pts.maxOf { it.arousal },
                peakHr = peakHr, baselineHr = peakPt.baseline, troughHr = troughHr,
                buildS = trough?.let { ((peakMs - it.timestampMs) / 1000).toInt() },
                rmssdAtPeak = atPeak?.rmssdMs, rmssdBefore = rmssdBefore,
                pelvicPeakKpa = pelvicIn.mapNotNull { it.aboveRestKpa }.maxOrNull(),
                contractions = pulses.count { it.startMs in span.first..span.last + 30_000L },
                drop30 = drop30, drop60 = drop60, halfRecoveryS = half,
                breathsAtPeak = atPeak?.breathsPerMin, stressAtPeak = atPeak?.stress,
                markedEvents = marks.map { it.label }
            )
        }
    }
}
