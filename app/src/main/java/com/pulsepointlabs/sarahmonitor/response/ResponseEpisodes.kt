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
    val markedEvents: List<String>,
    /** Highest Load (0–100) in the episode, and its area in load-minutes (Load × minutes). */
    val loadPeak: Int? = null,
    val loadMinutes: Double? = null,
    /** Area of usable pressure above rest over the episode (+30 s), kPa·s. */
    val pressureIntegralKpaS: Double? = null,
    /** Share of the episode's pressure samples that were usable (null = no pressure recorded). */
    val pressureUsablePct: Int? = null,
    /** HR rise per minute from trough to peak. */
    val hrRiseBpmPerMin: Double? = null,
    /** Lowest smoothed HR in the 3 minutes after the peak (before the next episode starts). */
    val recoveryLowHr: Double? = null,
    /** Seconds from this episode's end to the next one's start (null for the last). */
    val nextGapS: Int? = null,
    /** "good", "limited" or "low": how much of the episode's data was there and usable. Not a probability. */
    val confidence: String = "good"
) {
    /** How far HR came back down after the peak, as a share of the rise (1 = fully back to the trough). */
    val recoveryFraction: Double? get() {
        val rise = riseBpm ?: return null
        if (rise < 3 || peakHr == null || recoveryLowHr == null) return null
        return ((peakHr - recoveryLowHr) / rise).coerceIn(0.0, 1.5)
    }

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
        val pulses = data.pelvicSummary.pulses
        val quality = data.pelvicQuality
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
                // Pressure that may not be analysed (displaced sensor, preview, calibration) doesn't feed the read.
                val usable = quality[pIdx].usable
                engine.onPelvic(p.timestampMs, p.aboveRestKpa.takeIf { usable }, p.holding && usable, pulseIdx, last)
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

        val kept = merged.filter { it.last - it.first >= 20_000L }
        return kept.mapIndexed { index, span ->
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
            val pelvicIdx = data.pelvic.indices.filter { data.pelvic[it].timestampMs in span.first..span.last + 30_000L }
            val pelvicIn = pelvicIdx.filter { quality[it].usable }.map { data.pelvic[it] }
            // Rectangle rule: each usable sample's positive pressure times the interval to the next sample (capped at 1 s).
            val integral = if (pelvicIn.isEmpty()) null else pelvicIn.zipWithNext().sumOf { (a, b) ->
                (a.aboveRestKpa ?: 0.0).coerceAtLeast(0.0) * ((b.timestampMs - a.timestampMs).coerceAtMost(1_000L) / 1000.0)
            }
            val inEpisode = hrWindow(span.first, span.last)
            val loads = inEpisode.mapNotNull { h -> h.load?.let { h.timestampMs to it } }
            val loadMinutes = if (loads.size < 2) null else loads.zipWithNext().sumOf { (a, b) -> a.second * ((b.first - a.first).coerceAtMost(5_000L) / 60_000.0) }
            val nextStart = kept.getOrNull(index + 1)?.first
            val recoveryEnd = minOf(peakMs + 180_000L, nextStart ?: Long.MAX_VALUE)
            val recoveryLow = hrWindow(peakMs, recoveryEnd).mapNotNull { smoothHr(it.timestampMs) }.minOrNull()
            val hrCoverage = inEpisode.size / ((span.last - span.first) / 1000.0).coerceAtLeast(1.0)
            val usablePct = if (pelvicIdx.isEmpty()) null else (pelvicIn.size * 100 / pelvicIdx.size)
            val confidence = when {
                hrCoverage < 0.5 -> "low"
                hrCoverage < 0.8 || (usablePct != null && usablePct < 70) -> "limited"
                else -> "good"
            }
            val build = trough?.let { ((peakMs - it.timestampMs) / 1000.0) }
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
                markedEvents = marks.map { it.label },
                loadPeak = loads.maxOfOrNull { it.second },
                loadMinutes = loadMinutes,
                pressureIntegralKpaS = integral,
                pressureUsablePct = usablePct,
                hrRiseBpmPerMin = if (peakHr != null && troughHr != null && build != null && build >= 10) (peakHr - troughHr) / build * 60.0 else null,
                recoveryLowHr = recoveryLow,
                nextGapS = nextStart?.let { ((it - span.last) / 1000).toInt() },
                confidence = confidence
            )
        }
    }

    /**
     * Episode-to-episode comparison in plain numbers: peak HR, rise, build speed, starting level and recovery.
     * Says what changed; it does not assume intensity climbs each time, and says so when it doesn't.
     */
    fun progression(episodes: List<ResponseEpisode>): List<String> {
        if (episodes.size < 2) return emptyList()
        fun seq(values: List<Double?>, fmt: (Double) -> String) = values.joinToString(" → ") { it?.let(fmt) ?: "?" }
        fun direction(values: List<Double?>, tolerance: Double): String {
            val v = values.filterNotNull()
            if (v.size < 2) return "not enough data"
            val steps = v.zipWithNext { a, b -> b - a }
            return when {
                steps.all { it > tolerance } -> "higher every time"
                steps.all { it < -tolerance } -> "lower every time"
                steps.all { kotlin.math.abs(it) <= tolerance } -> "about the same each time"
                v.last() - v.first() > tolerance -> "higher overall, not every time"
                v.first() - v.last() > tolerance -> "lower overall, not every time"
                else -> "no consistent direction"
            }
        }
        val out = mutableListOf<String>()
        val peaks = episodes.map { it.peakHr }
        out += "Peak HR: ${seq(peaks) { "${it.roundToInt()}" }} bpm (${direction(peaks, 2.0)})."
        val rises = episodes.map { it.riseBpm }
        out += "Rise from the trough: ${seq(rises) { "+${it.roundToInt()}" }} bpm (${direction(rises, 2.0)})."
        val builds = episodes.map { it.buildS?.toDouble() }
        if (builds.count { it != null } >= 2) out += "Time to peak: ${seq(builds) { SessionAnalysis.durationWords((it * 1000).toLong()) }} (${direction(builds, 10.0).replace("higher", "longer").replace("lower", "shorter")})."
        val troughs = episodes.map { it.troughHr }
        out += "Starting level (trough HR): ${seq(troughs) { "${it.roundToInt()}" }} bpm (${direction(troughs, 2.0)})."
        val recov = episodes.dropLast(1).map { it.recoveryFraction }
        if (recov.count { it != null } >= 1) out += "Recovery before the next build: ${recov.joinToString(" → ") { r -> r?.let { "${(it * 100).roundToInt()}%" } ?: "?" }} of each rise came back down."
        val press = episodes.map { it.pelvicPeakKpa }
        if (press.count { it != null } >= 2) out += "Peak pressure: ${seq(press) { SessionAnalysis.num(it) }} kPa (${direction(press, 0.3)})."
        val low = episodes.count { it.confidence != "good" }
        if (low > 0) out += "$low of ${episodes.size} episodes have limited or low data quality; read their numbers with care."
        return out
    }
}
