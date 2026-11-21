package com.pulsepointlabs.sarahmonitor.response

import com.pulsepointlabs.sarahmonitor.core.PelvicPulse
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the body appears to be in the response cycle. A read of the signals, not a diagnosis. */
enum class ResponsePhase(val label: String, val blurb: String) {
    Baseline("Baseline", "Near resting levels"),
    Warming("Warming up", "Early lift above baseline"),
    Building("Building", "Heart rate climbing with arousal"),
    Plateau("Plateau", "Elevated and holding steady"),
    Approaching("Approaching climax", "Signals converging toward threshold"),
    Climax("Climax", "Climax signature or marked climax"),
    BackingOff("Backing off", "Came close, then eased off (edge)"),
    Recovery("Recovery", "Settling after the peak")
}

/** One remembered climax, used to calibrate the approach score to this person. */
data class ClimaxSample(val hrDelta: Double, val pelvicPeakKpa: Double?, val rmssdRatio: Double?)

/** What we've learned about this person's climaxes (from events they marked). */
data class PersonalProfile(val samples: List<ClimaxSample> = emptyList()) {
    /** Typical HR rise above baseline at climax; 30 bpm until there's data. */
    val climaxHrDelta: Double
        get() = samples.map { it.hrDelta }.filter { it > 5 }.sorted().let { if (it.isEmpty()) 30.0 else it[it.size / 2] }.coerceIn(15.0, 60.0)
    val learned: Boolean get() = samples.size >= 2
}

data class TimelinePoint(val timestampMs: Long, val phase: ResponsePhase, val approach: Int, val arousal: Int)

/** A detected climax waiting for the user to confirm (confirming marks it and teaches the profile). */
data class ClimaxCandidate(val timestampMs: Long, val confidence: String, val reason: String)

data class ResponseState(
    val phase: ResponsePhase = ResponsePhase.Baseline,
    val phaseSinceMs: Long? = null,
    /** 0–100: overall arousal (HR above baseline, pelvic activity, HRV tightening). */
    val arousal: Int = 0,
    /** 0–100: how close the signals look to this person's climax. */
    val approach: Int = 0,
    val hr: Int? = null,
    val baselineHr: Int? = null,
    val hrDelta: Int? = null,
    /** bpm change per 30 s. */
    val hrSlope: Double = 0.0,
    val rmssd: Double? = null,
    /** RMSSD vs this session's median (1.0 = typical). */
    val rmssdRatio: Double? = null,
    val hrvSignal: String = "waiting",
    val pelvicKpa: Double? = null,
    val pelvicAvg30: Double? = null,
    val pulsesPerMin: Int = 0,
    val rhythmSpacingS: Double? = null,
    val load: Int? = null,
    val buildSeconds: Int = 0,
    val plateauSeconds: Int = 0,
    val edges: Int = 0,
    val climaxes: Int = 0,
    val signals: List<String> = emptyList(),
    val candidate: ClimaxCandidate? = null,
    val timeline: List<TimelinePoint> = emptyList(),
    val sessionStartMs: Long? = null,
    val learned: Boolean = false
)

/**
 * Live sexual-response read from the H10 and CIVET: baseline, arousal, an approach-to-climax score, the phase
 * (warming → building → plateau → approaching → climax → recovery, or backing off for an edge), and a climax
 * signature (a train of rhythmic pelvic contractions ~0.5–1.5 s apart while highly aroused).
 *
 * Generic rules first; [PersonalProfile] scales the approach score to the HR rise this person reaches at the
 * climaxes they mark, so it gets more accurate with use. Loosely modelled on Sarah's live climax prediction
 * (build duration, HRV compression, caps early in a session), rewritten for this app's signals.
 */
class ArousalEngine(private var profile: PersonalProfile = PersonalProfile()) {
    private val hrHistogram = IntArray(256)
    private var hrCount = 0
    private val rmssdHistogram = IntArray(401)
    private var rmssdCount = 0
    private val hr10 = ArrayDeque<Pair<Long, Int>>()
    private val smooth = ArrayDeque<Pair<Long, Double>>()
    private val rmssdRecent = ArrayDeque<Pair<Long, Double>>()
    private val pelvic = ArrayDeque<Pair<Long, Double>>()
    private val pulses = ArrayDeque<PelvicPulse>()
    private var lastPulseCount = 0
    private var holding = false
    private var lastLoad: Int? = null
    private var lastRmssd: Double? = null
    private var start: Long? = null
    private var buildStart: Long? = null
    private var lastBuildEvidence: Long? = null
    private var plateauStart: Long? = null
    private var phase = ResponsePhase.Baseline
    private var phaseSince: Long? = null
    private var peakApproachAt: Long? = null
    private var peakApproach = 0
    private var peakHr = 0.0
    private var climaxAt: Long? = null
    private var edges = 0
    private var climaxes = 0
    private var candidate: ClimaxCandidate? = null
    private var lastCandidateAt: Long? = null
    private val timeline = ArrayList<TimelinePoint>()
    private var state = ResponseState()

    @Synchronized
    fun setProfile(p: PersonalProfile) { profile = p }

    @Synchronized
    fun reset() {
        hrHistogram.fill(0); hrCount = 0; rmssdHistogram.fill(0); rmssdCount = 0
        hr10.clear(); smooth.clear(); rmssdRecent.clear(); pelvic.clear(); pulses.clear()
        lastPulseCount = 0; holding = false; lastLoad = null; lastRmssd = null; start = null
        buildStart = null; lastBuildEvidence = null; plateauStart = null
        phase = ResponsePhase.Baseline; phaseSince = null; peakApproachAt = null; peakApproach = 0; peakHr = 0.0
        climaxAt = null; edges = 0; climaxes = 0; candidate = null; lastCandidateAt = null
        timeline.clear(); state = ResponseState()
    }

    @Synchronized
    fun current(): ResponseState = state.copy(phase = phase, phaseSinceMs = phaseSince, edges = edges, climaxes = climaxes, candidate = candidate)

    private var emgPct: Double? = null
    private var emgAt = 0L

    /** Strongest calibrated EMG channel, % of its comfortable hold. */
    @Synchronized
    fun onEmg(t: Long, pct: Double?) { emgPct = pct; emgAt = t }

    @Synchronized
    fun onPelvic(t: Long, aboveRestKpa: Double?, holding: Boolean, pulseCount: Int, lastPulse: PelvicPulse?) {
        if (aboveRestKpa != null) {
            pelvic.addLast(t to aboveRestKpa)
            while (pelvic.isNotEmpty() && t - pelvic.first().first > 30_000L) pelvic.removeFirst()
        }
        this.holding = holding
        if (pulseCount < lastPulseCount) lastPulseCount = 0
        if (pulseCount > lastPulseCount && lastPulse != null) {
            lastPulseCount = pulseCount
            pulses.addLast(lastPulse)
            while (pulses.isNotEmpty() && t - pulses.first().onsetMs > 120_000L) pulses.removeFirst()
        }
    }

    /** User-marked events. Orgasm/ejaculation counts as a climax (and teaches the profile via [climaxSample]). */
    @Synchronized
    fun onMarker(t: Long, types: Set<String>) {
        if ("orgasm" in types || "ejaculation" in types) {
            if (climaxAt == null || t - climaxAt!! > 60_000L) climaxes++
            climaxAt = t
            candidate = null
            setPhase(ResponsePhase.Climax, t)
        } else if ("edge" in types && phase != ResponsePhase.BackingOff) {
            edges++
            setPhase(ResponsePhase.BackingOff, t)
        }
    }

    /** Features at this moment, to remember for a confirmed climax. */
    @Synchronized
    fun climaxSample(): ClimaxSample? {
        val base = baseline() ?: return null
        val peak = smooth.filter { (smooth.lastOrNull()?.first ?: 0L) - it.first <= 45_000L }.maxOfOrNull { it.second } ?: return null
        return ClimaxSample(peak - base, pelvic.maxOfOrNull { it.second }, state.rmssdRatio)
    }

    @Synchronized
    fun dismissCandidate() { candidate = null }

    private fun baseline(): Double? {
        if (hrCount < 60) return null
        // 15th percentile of the session so far: robust to a session that starts already aroused.
        var seen = 0
        hrHistogram.forEachIndexed { v, n -> seen += n; if (seen >= hrCount * 0.15) return v.toDouble() }
        return null
    }

    private fun median(h: IntArray, n: Int): Double? {
        if (n == 0) return null
        var seen = 0
        h.forEachIndexed { v, c -> seen += c; if (seen * 2 >= n) return v.toDouble() }
        return null
    }

    private fun setPhase(p: ResponsePhase, t: Long) {
        if (p != phase) { phase = p; phaseSince = t }
    }

    @Synchronized
    fun onHeart(t: Long, bpm: Int?, rmssd: Double?, load: Int?): ResponseState {
        if (start == null) start = t
        lastLoad = load ?: lastLoad
        if (bpm != null && bpm in 25..250) {
            hrHistogram[bpm]++; hrCount++
            hr10.addLast(t to bpm)
            while (hr10.isNotEmpty() && t - hr10.first().first > 10_000L) hr10.removeFirst()
            smooth.addLast(t to hr10.sumOf { it.second }.toDouble() / hr10.size)
            while (smooth.isNotEmpty() && t - smooth.first().first > 180_000L) smooth.removeFirst()
        }
        if (rmssd != null && rmssd.isFinite()) {
            lastRmssd = rmssd
            rmssdHistogram[rmssd.roundToInt().coerceIn(0, 400)]++; rmssdCount++
            rmssdRecent.addLast(t to rmssd)
            while (rmssdRecent.isNotEmpty() && t - rmssdRecent.first().first > 60_000L) rmssdRecent.removeFirst()
        }
        state = evaluate(t)
        return state
    }

    private fun evaluate(t: Long): ResponseState {
        val signals = mutableListOf<String>()
        val hrNow = smooth.lastOrNull()?.second
        val base = baseline()
        val delta = if (hrNow != null && base != null) hrNow - base else null
        val ago = smooth.firstOrNull { t - it.first <= 30_000L }
        val slope = if (hrNow != null && ago != null && t - ago.first >= 15_000L) (hrNow - ago.second) * 30_000.0 / (t - ago.first) else 0.0
        val climaxDelta = profile.climaxHrDelta
        if (hrNow != null) peakHr = max(peakHr, hrNow)
        val recentPeak = smooth.filter { t - it.first <= 120_000L }.maxOfOrNull { it.second }
        val dropFromPeak = if (hrNow != null && recentPeak != null) recentPeak - hrNow else 0.0

        // Build duration: time with HR 8+ above baseline, tolerating 15 s breaks.
        if (delta != null && delta >= 8) {
            if (buildStart == null || (lastBuildEvidence != null && t - lastBuildEvidence!! > 15_000L)) buildStart = t
            lastBuildEvidence = t
        } else if (lastBuildEvidence != null && t - lastBuildEvidence!! > 15_000L) {
            buildStart = null
        }
        val buildS = buildStart?.let { ((t - it) / 1000).toInt() } ?: 0

        // HRV: compressed / tightening / opening against this session's median.
        val rMed = median(rmssdHistogram, rmssdCount)
        val rNow = lastRmssd
        val ratio = if (rMed != null && rMed >= 3 && rNow != null && rmssdCount >= 30) rNow / rMed else null
        val rFirst = rmssdRecent.firstOrNull()?.second
        val hrvSignal = when {
            ratio == null -> "waiting"
            ratio <= 0.68 && (delta ?: 0.0) >= 8 && slope >= -0.5 -> "compressed"
            rNow != null && rFirst != null && rNow - rFirst < -3 && slope > 0.8 -> "tightening"
            ratio >= 1.5 && (delta ?: 0.0) >= 8 -> "opening"
            else -> "steady"
        }

        // Pelvic: 30 s average above rest, contraction rate and rhythm.
        val pAvg = if (pelvic.isEmpty()) null else pelvic.sumOf { it.second } / pelvic.size
        val pNow = pelvic.lastOrNull()?.second
        val minuteAgo = t - 60_000L
        val ppm = pulses.count { it.onsetMs >= minuteAgo }
        val train = rhythmicTrain(t)

        // Arousal 0–100.
        var arousal = 0.0
        if (delta != null) arousal += (delta / climaxDelta * 70).coerceIn(0.0, 70.0)
        if (pAvg != null) arousal += (pAvg / 2.0 * 12).coerceIn(0.0, 12.0)
        arousal += (ppm * 1.0).coerceIn(0.0, 8.0)
        if (hrvSignal == "compressed" || hrvSignal == "tightening") arousal += 10
        val arousalI = arousal.roundToInt().coerceIn(0, 100)

        // Approach-to-climax 0–100.
        var approach = 0.0
        if (delta != null) approach += (delta / climaxDelta * 62).coerceIn(0.0, 70.0)
        if (slope > 1.0) approach += (slope * 2.2).coerceIn(0.0, 14.0)
        approach += when (hrvSignal) { "compressed" -> 12.0; "tightening" -> 7.0; "opening" -> -6.0; else -> 0.0 }
        approach += (ppm * 1.2).coerceIn(0.0, 12.0)
        if (holding) approach += 4
        if (train != null) approach += 10
        val emg = emgPct?.takeIf { t - emgAt < 3_000L }
        // EMG adds like Sarah's live prediction: a rise of 18%+ counts, up to +12.
        if (emg != null && emg >= 18) approach += (emg * 0.16).coerceAtMost(12.0)
        if (dropFromPeak > 8) approach -= 12
        // Caps: a fresh build or early session can't claim "near climax" on HR alone.
        val confirmations = listOf(
            (delta ?: 0.0) >= 12 && buildS >= 60, hrvSignal == "compressed" || hrvSignal == "tightening",
            slope >= 1.5 && buildS >= 45, ppm >= 4, train != null, holding, (emg ?: 0.0) >= 18
        ).count { it }
        val minutes = (t - (start ?: t)) / 60_000.0
        if (buildS < 45 && confirmations < 2) approach = min(approach, 45.0)
        if (minutes < 5 && confirmations < 3) approach = min(approach, 50.0)
        val approachI = approach.roundToInt().coerceIn(0, 100)
        if (approachI > peakApproach || (peakApproachAt != null && t - peakApproachAt!! > 120_000L)) {
            peakApproach = approachI; peakApproachAt = t
        }

        // Climax signature → candidate for the user to confirm.
        if (train != null && (approachI >= 55 || (delta ?: 0.0) >= climaxDelta * 0.6) && (lastCandidateAt == null || t - lastCandidateAt!! > 90_000L) &&
            (climaxAt == null || t - climaxAt!! > 90_000L)
        ) {
            lastCandidateAt = t
            candidate = ClimaxCandidate(t, "probable", "${train.first} rhythmic contractions about ${"%.1f".format(train.second)} s apart at high arousal")
        } else if (train == null && pelvic.isEmpty() && peakApproach >= 85 && dropFromPeak >= 10 && (lastCandidateAt == null || t - lastCandidateAt!! > 90_000L) &&
            (climaxAt == null || t - climaxAt!! > 90_000L)
        ) {
            lastCandidateAt = t
            candidate = ClimaxCandidate(t, "possible", "HR peaked near your climax range, then dropped ${dropFromPeak.roundToInt()} bpm")
        }
        if (candidate != null && t - candidate!!.timestampMs > 180_000L) candidate = null

        // Plateau: elevated and steady for a minute.
        val steadyElevated = delta != null && delta >= 12 && abs(slope) <= 1.5
        if (steadyElevated) { if (plateauStart == null) plateauStart = t } else if (abs(slope) > 2.5 || (delta ?: 0.0) < 10) plateauStart = null
        val plateauS = plateauStart?.let { ((t - it) / 1000).toInt() } ?: 0

        // Phase.
        val inClimax = climaxAt != null && t - climaxAt!! < 45_000L
        val afterClimax = climaxAt != null && !inClimax
        when {
            inClimax -> setPhase(ResponsePhase.Climax, t)
            candidate?.confidence == "probable" && t - candidate!!.timestampMs < 45_000L -> setPhase(ResponsePhase.Climax, t)
            afterClimax -> setPhase(ResponsePhase.Recovery, t)
            approachI >= 75 -> setPhase(ResponsePhase.Approaching, t)
            phase == ResponsePhase.Approaching && approachI < 60 && dropFromPeak >= 8 -> { edges++; setPhase(ResponsePhase.BackingOff, t) }
            phase == ResponsePhase.BackingOff && phaseSince != null && t - phaseSince!! < 60_000L -> Unit
            plateauS >= 60 -> setPhase(ResponsePhase.Plateau, t)
            delta != null && delta >= 10 && slope > 1.0 -> setPhase(ResponsePhase.Building, t)
            phase == ResponsePhase.Plateau && steadyElevated -> Unit
            delta != null && delta >= 6 -> if (phase == ResponsePhase.Baseline || phase == ResponsePhase.Recovery || phase == ResponsePhase.BackingOff) setPhase(ResponsePhase.Warming, t)
            else -> setPhase(ResponsePhase.Baseline, t)
        }
        // Recovery ends once HR has been back near baseline for a minute; a new build then starts fresh.
        if (afterClimax && (delta ?: 99.0) < 8 && t - climaxAt!! > 105_000L) {
            climaxAt = null
            setPhase(ResponsePhase.Baseline, t)
        }

        // Why — plain-English signals.
        if (delta != null) signals += "HR ${"%+d".format(delta.roundToInt())} over your baseline ${base!!.roundToInt()}" + (if (abs(slope) >= 1) ", ${if (slope > 0) "rising" else "falling"} ${"%.1f".format(abs(slope))} bpm per 30 s" else ", steady")
        else signals += "Learning your baseline (first minute)"
        when (hrvSignal) {
            "compressed" -> signals += "HRV compressed (RMSSD ${(ratio!! * 100).roundToInt()}% of session median)"
            "tightening" -> signals += "HRV tightening as HR rises"
            "opening" -> signals += "HRV opening — reads more like settling"
        }
        if (pAvg != null) signals += "Pelvic floor ${"%.1f".format(pAvg)} kPa above rest (30 s avg)" + if (ppm > 0) ", $ppm contractions/min" else ""
        if (train != null) signals += "Rhythmic contraction train: ${train.first} at ~${"%.1f".format(train.second)} s"
        if (holding) signals += "Holding a contraction"
        emg?.let { signals += "EMG ${it.roundToInt()}% of your calibrated hold" }
        if (buildS >= 30) signals += "Building for ${buildS / 60}:${"%02d".format(buildS % 60)}"
        if (plateauS >= 30) signals += "Plateau for ${plateauS / 60}:${"%02d".format(plateauS % 60)}"
        if (!profile.learned) signals += "Approach uses a generic 30 bpm climax rise until you've marked a couple of climaxes"

        if (timeline.isEmpty() || t - timeline.last().timestampMs >= 5_000L) {
            timeline += TimelinePoint(t, phase, approachI, arousalI)
            if (timeline.size > 10_000) timeline.subList(0, 1_000).clear()
        }
        return ResponseState(
            phase = phase, phaseSinceMs = phaseSince, arousal = arousalI, approach = approachI,
            hr = hrNow?.roundToInt(), baselineHr = base?.roundToInt(), hrDelta = delta?.roundToInt(), hrSlope = slope,
            rmssd = rNow, rmssdRatio = ratio, hrvSignal = hrvSignal,
            pelvicKpa = pNow, pelvicAvg30 = pAvg, pulsesPerMin = ppm, rhythmSpacingS = train?.second,
            load = lastLoad, buildSeconds = buildS, plateauSeconds = plateauS, edges = edges, climaxes = climaxes,
            signals = signals, candidate = candidate, timeline = timeline.toList(), sessionStartMs = start, learned = profile.learned
        )
    }

    /** The latest run of ≥4 short contractions whose onsets are 0.5–1.5 s apart, ending within the last 10 s. */
    private fun rhythmicTrain(t: Long): Pair<Int, Double>? {
        val list = pulses.toList()
        if (list.size < 4 || t - list.last().onsetMs > 10_000L) return null
        var n = 1
        for (i in list.size - 1 downTo 1) {
            val gap = (list[i].onsetMs - list[i - 1].onsetMs) / 1000.0
            if (gap in 0.5..1.5 && list[i].durationS < 2.0) n++ else break
        }
        if (n < 4) return null
        val group = list.takeLast(n)
        return n to (group.last().onsetMs - group.first().onsetMs) / 1000.0 / (n - 1)
    }
}
