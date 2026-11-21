package com.pulsepointlabs.sarahmonitor.audio

import com.pulsepointlabs.sarahmonitor.core.PelvicPulse
import com.pulsepointlabs.sarahmonitor.session.FlagSeverity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One live alert: [spoken] is what Sarah says, [short] is for the screen. */
data class LiveAlert(val kind: AlertKind, val severity: FlagSeverity, val spoken: String, val short: String, val atMs: Long)

data class AlertLimits(val highHr: Int = 0, val lowHr: Int = 45, val longHoldS: Int = 10)

/**
 * Watches the live streams and raises [LiveAlert]s. The rules are the streaming versions of [com.pulsepointlabs.sarahmonitor.session.SessionFlags]
 * (same thresholds and persistence), with hysteresis so nothing flip-flops at a threshold and a per-alert cooldown
 * so it doesn't nag. Pure Kotlin; time comes from the samples.
 */
class LiveAlertEngine(private val limits: () -> AlertLimits = { AlertLimits() }) {
    private val hr = ArrayDeque<Pair<Long, Int>>()
    private val smooth = ArrayDeque<Pair<Long, Double>>()
    private val hrHistogram = IntArray(256)
    private var hrCount = 0
    private val rmssdHistogram = IntArray(401)
    private var rmssdCount = 0
    private val lastFired = mutableMapOf<AlertKind, Long>()
    private val latched = mutableSetOf<AlertKind>()
    private var highSince: Long? = null
    private var lowSince: Long? = null
    private var hrvLowSince: Long? = null
    private var loadSince: Long? = null
    private var noContactSince: Long? = null
    private var lastHrAt: Long? = null
    private val shift = SustainedHrChangeDetector()
    private val pulses = ArrayDeque<PelvicPulse>()
    private val pulsePeaks = mutableListOf<Double>()
    private var lastPulseCount = 0
    private var holdLatched = false
    private var burstChainStart: Long? = null

    @Synchronized
    fun reset() {
        hr.clear(); smooth.clear(); hrHistogram.fill(0); hrCount = 0; rmssdHistogram.fill(0); rmssdCount = 0
        lastFired.clear(); latched.clear()
        highSince = null; lowSince = null; hrvLowSince = null; loadSince = null; noContactSince = null; lastHrAt = null
        shift.reset(); pulses.clear(); pulsePeaks.clear(); lastPulseCount = 0; holdLatched = false; burstChainStart = null
    }

    private fun fire(out: MutableList<LiveAlert>, kind: AlertKind, t: Long, severity: FlagSeverity, spoken: String, short: String) {
        val previous = lastFired[kind]
        if (previous != null && t - previous < kind.cooldownMs) return
        lastFired[kind] = t
        out += LiveAlert(kind, severity, spoken, short, t)
    }

    private fun median(histogram: IntArray, count: Int): Int? {
        if (count == 0) return null
        var seen = 0
        histogram.forEachIndexed { value, n -> seen += n; if (seen * 2 >= count) return value }
        return null
    }

    /** Heart data from one H10 packet. [ectopic] is true if any RR in it was classed premature/ectopic. */
    @Synchronized
    fun onHeart(t: Long, bpm: Int?, rmssd: Double?, load: Int?, contact: Boolean?, ectopic: Boolean): List<LiveAlert> {
        val out = mutableListOf<LiveAlert>()
        val lim = limits()
        // Contact: off for 10 s straight.
        if (contact == false) {
            val since = noContactSince ?: t.also { noContactSince = it }
            if (t - since >= 10_000L && AlertKind.SignalLost !in latched) {
                latched += AlertKind.SignalLost
                fire(out, AlertKind.SignalLost, t, FlagSeverity.Info, "Heart strap has lost skin contact.", "H10 contact lost")
            }
        } else {
            noContactSince = null
            if (bpm != null) latched -= AlertKind.SignalLost
        }
        if (bpm == null || bpm !in 25..250) return out
        lastHrAt = t
        hr.addLast(t to bpm)
        while (hr.isNotEmpty() && t - hr.first().first > 15_000L) hr.removeFirst()
        hrHistogram[bpm]++; hrCount++
        val s = hr.sumOf { it.second }.toDouble() / hr.size
        smooth.addLast(t to s)
        while (smooth.isNotEmpty() && t - smooth.first().first > 120_000L) smooth.removeFirst()
        val now = bpm
        val minuteAgo = smooth.firstOrNull { t - it.first <= 60_000L }?.second
        val change = minuteAgo?.let { (s - it).roundToInt() }
        fun changeWords() = when {
            change == null || abs(change) < 5 -> ""
            change > 0 -> ", up $change in the last minute"
            else -> ", down ${-change} in the last minute"
        }

        // High HR: limit is fixed, or "auto" = session median + 25 (at least 100), like the auto-flags.
        val med = median(hrHistogram, hrCount)
        val highLimit = if (lim.highHr > 0) lim.highHr else if (hrCount >= 30 && med != null) max(100, med + 25) else null
        if (highLimit != null) {
            if (s >= highLimit) {
                val since = highSince ?: t.also { highSince = it }
                if (t - since >= 30_000L && AlertKind.HighHr !in latched) {
                    latched += AlertKind.HighHr
                    fire(out, AlertKind.HighHr, t, if (now >= 150) FlagSeverity.High else FlagSeverity.Notable,
                        "Heart rate $now${changeWords()}. Above $highLimit for 30 seconds.", "HR $now (> $highLimit)")
                }
            } else {
                highSince = null
                if (s < highLimit - 5) latched -= AlertKind.HighHr
            }
        }
        if (s < lim.lowHr) {
            val since = lowSince ?: t.also { lowSince = it }
            if (t - since >= 30_000L && AlertKind.LowHr !in latched) {
                latched += AlertKind.LowHr
                fire(out, AlertKind.LowHr, t, if (now < 40) FlagSeverity.High else FlagSeverity.Notable,
                    "Heart rate is low, $now, under ${lim.lowHr} for 30 seconds.", "HR $now (< ${lim.lowHr})")
            }
        } else {
            lowSince = null
            if (s > lim.lowHr + 5) latched -= AlertKind.LowHr
        }
        // Surge: 15 s average up 25+ from its lowest point in the previous minute.
        val lowest = smooth.filter { t - it.first <= 60_000L }.minByOrNull { it.second }
        if (lowest != null) {
            val rise = s - lowest.second
            if (rise >= 25 && AlertKind.HrSurge !in latched) {
                latched += AlertKind.HrSurge
                val secs = ((t - lowest.first) / 1000).coerceAtLeast(1)
                fire(out, AlertKind.HrSurge, t, if (rise >= 40) FlagSeverity.High else FlagSeverity.Notable,
                    "Heart rate surge: up ${rise.roundToInt()} to $now in $secs seconds.", "HR surge +${rise.roundToInt()}")
            } else if (rise < 10) latched -= AlertKind.HrSurge
        }
        shift.add(t, bpm)?.let { c ->
            val up = c.direction == SustainedHrDirection.Increase
            fire(out, AlertKind.HrShift, t, FlagSeverity.Info,
                "Heart rate has ${if (up) "climbed" else "settled"} and held: about ${c.recentBpm.roundToInt()}, ${if (up) "up" else "down"} ${abs(c.deltaBpm).roundToInt()} from the last couple of minutes.",
                "HR ${if (up) "up" else "down"} ${abs(c.deltaBpm).roundToInt()} (held)")
        }
        // HRV drop: RMSSD at or below 60% of the session median for a minute.
        if (rmssd != null && rmssd.isFinite()) {
            rmssdHistogram[rmssd.roundToInt().coerceIn(0, 400)]++; rmssdCount++
            val rMed = median(rmssdHistogram, rmssdCount)
            if (rmssdCount >= 120 && rMed != null && rMed >= 5) {
                if (rmssd <= rMed * 0.6) {
                    val since = hrvLowSince ?: t.also { hrvLowSince = it }
                    if (t - since >= 60_000L && AlertKind.HrvDrop !in latched) {
                        latched += AlertKind.HrvDrop
                        fire(out, AlertKind.HrvDrop, t, if (rmssd <= rMed * 0.4) FlagSeverity.High else FlagSeverity.Notable,
                            "HRV has dropped: RMSSD ${rmssd.roundToInt()}, against a session median of $rMed.", "RMSSD ${rmssd.roundToInt()} (median $rMed)")
                    }
                } else {
                    hrvLowSince = null
                    if (rmssd > rMed * 0.7) latched -= AlertKind.HrvDrop
                }
            }
        }
        // Load: 58+ for a minute; re-arms once it falls below 45 (as SarahVS's stress gate).
        if (load != null) {
            if (load >= 58) {
                val since = loadSince ?: t.also { loadSince = it }
                if (t - since >= 60_000L && AlertKind.HighLoad !in latched) {
                    latched += AlertKind.HighLoad
                    val high = load >= 82
                    fire(out, AlertKind.HighLoad, t, if (high) FlagSeverity.High else FlagSeverity.Notable,
                        if (high) "Load is high, $load." else "Load is elevated, $load, for a minute now.", "Load $load")
                }
            } else {
                loadSince = null
                if (load < 45) latched -= AlertKind.HighLoad
            }
        }
        if (ectopic) fire(out, AlertKind.IrregularBeat, t, FlagSeverity.Info, "Irregular beat on the heart strap.", "Irregular beat")
        return out
    }

    /** Call every few seconds: catches HR data stopping altogether. */
    @Synchronized
    fun tick(t: Long, h10Connected: Boolean): List<LiveAlert> {
        val out = mutableListOf<LiveAlert>()
        val last = lastHrAt ?: return out
        if (h10Connected && t - last >= 10_000L && AlertKind.SignalLost !in latched) {
            latched += AlertKind.SignalLost
            fire(out, AlertKind.SignalLost, t, FlagSeverity.Info, "No heart data for 10 seconds.", "No HR data")
        }
        return out
    }

    /** A sensor dropped its connection (after having been connected). */
    @Synchronized
    fun onDisconnected(t: Long, sensor: String): List<LiveAlert> {
        val out = mutableListOf<LiveAlert>()
        fire(out, AlertKind.SignalLost, t, FlagSeverity.Notable, "$sensor disconnected.", "$sensor disconnected")
        return out
    }

    @Synchronized
    fun onPelvic(t: Long, holding: Boolean, holdSeconds: Double?, pulseCount: Int, lastPulse: PelvicPulse?): List<LiveAlert> {
        val out = mutableListOf<LiveAlert>()
        val lim = limits()
        if (holding && (holdSeconds ?: 0.0) >= lim.longHoldS && !holdLatched) {
            holdLatched = true
            fire(out, AlertKind.LongHold, t, FlagSeverity.Notable, "Holding ${lim.longHoldS} seconds.", "Hold ${lim.longHoldS} s")
        }
        if (!holding) holdLatched = false
        if (pulseCount < lastPulseCount) { lastPulseCount = 0; pulses.clear() }
        if (pulseCount > lastPulseCount && lastPulse != null) {
            lastPulseCount = pulseCount
            val typical = pulsePeaks.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            if (typical != null && pulsePeaks.size >= 5 && lastPulse.peakKpa >= max(3.0, typical * 2)) {
                fire(out, AlertKind.StrongContraction, t, FlagSeverity.Notable,
                    "Strong contraction, ${"%.1f".format(lastPulse.peakKpa)} kilopascals.", "Strong ${"%.1f".format(lastPulse.peakKpa)} kPa")
            }
            pulsePeaks += lastPulse.peakKpa
            pulses.addLast(lastPulse)
            while (pulses.size > 20) pulses.removeFirst()
            // Burst: trailing chain of short contractions, each starting within 3 s of the previous.
            var chain = 1
            val list = pulses.toList()
            for (i in list.size - 1 downTo 1) {
                if (list[i].onsetMs - list[i - 1].onsetMs <= 3_000L && list[i].durationS < 4.0 && list[i - 1].durationS < 4.0) chain++ else break
            }
            val chainStart = list[list.size - chain].onsetMs
            if (chain >= 4 && burstChainStart != chainStart) {
                burstChainStart = chainStart
                val group = list.takeLast(chain)
                val spacing = (group.last().onsetMs - group.first().onsetMs) / 1000.0 / (chain - 1)
                fire(out, AlertKind.ContractionBurst, t, FlagSeverity.Notable,
                    "Contraction burst: $chain in a row, about ${"%.1f".format(spacing)} seconds apart.", "Burst ×$chain")
            }
        }
        return out
    }

    @Synchronized
    fun onBp(t: Long, systolic: Int, diastolic: Int, where: String? = null): List<LiveAlert> {
        val out = mutableListOf<LiveAlert>()
        if (systolic >= 140 || diastolic >= 90) {
            val severe = systolic >= 180 || diastolic >= 120
            fire(out, AlertKind.HighBp, t, if (severe) FlagSeverity.High else FlagSeverity.Notable,
                "Blood pressure $systolic over $diastolic${if (severe) ", very high" else ""}${where?.let { ", $it" } ?: ""}.", "BP $systolic/$diastolic")
        }
        return out
    }

    @Synchronized
    fun onGlucose(t: Long, mgDl: Double): List<LiveAlert> {
        val out = mutableListOf<LiveAlert>()
        val v = mgDl.roundToInt()
        when {
            mgDl < 70 -> fire(out, AlertKind.Glucose, t, if (mgDl < 54) FlagSeverity.High else FlagSeverity.Notable, "Glucose is low, $v.", "Glucose $v")
            mgDl > 180 -> fire(out, AlertKind.Glucose, t, if (mgDl > 250) FlagSeverity.High else FlagSeverity.Notable, "Glucose is high, $v.", "Glucose $v")
        }
        return out
    }
}

enum class SustainedHrDirection { Increase, Decrease }

data class SustainedHrChange(val direction: SustainedHrDirection, val baselineBpm: Double, val recentBpm: Double, val deltaBpm: Double)

/** SarahVS's detector: a change held across the last 45 s versus the 2 minutes before, not a single packet. */
class SustainedHrChangeDetector(
    private val deltaThresholdBpm: Double = 12.0,
    private val rearmThresholdBpm: Double = 5.0,
    private val baselineWindowMs: Long = 120_000L,
    private val recentWindowMs: Long = 45_000L,
    private val minimumBaselineSpanMs: Long = 60_000L,
    private val minimumRecentSpanMs: Long = 30_000L
) {
    private data class Sample(val timestampMs: Long, val bpm: Int)

    private val samples = ArrayDeque<Sample>()
    private var lastTimestampMs = Long.MIN_VALUE
    private var latchedDirection: SustainedHrDirection? = null

    fun add(timestampMs: Long, bpm: Int): SustainedHrChange? {
        if (timestampMs <= lastTimestampMs || bpm !in 25..250) return null
        lastTimestampMs = timestampMs
        samples.addLast(Sample(timestampMs, bpm))
        val cutoff = timestampMs - 5 * 60_000L
        while (samples.firstOrNull()?.timestampMs?.let { it < cutoff } == true) samples.removeFirst()
        val recentStart = timestampMs - recentWindowMs
        val baselineStart = recentStart - baselineWindowMs
        val baseline = samples.filter { it.timestampMs in baselineStart until recentStart }
        val recent = samples.filter { it.timestampMs >= recentStart }
        if (!hasSpan(baseline, minimumBaselineSpanMs) || !hasSpan(recent, minimumRecentSpanMs)) return null
        val baselineAverage = baseline.map { it.bpm }.average()
        val recentAverage = recent.map { it.bpm }.average()
        val delta = recentAverage - baselineAverage
        if (abs(delta) <= rearmThresholdBpm) latchedDirection = null
        val direction = when {
            delta >= deltaThresholdBpm -> SustainedHrDirection.Increase
            delta <= -deltaThresholdBpm -> SustainedHrDirection.Decrease
            else -> return null
        }
        if (latchedDirection == direction) return null
        val supporting = recent.count {
            if (direction == SustainedHrDirection.Increase) it.bpm >= baselineAverage + deltaThresholdBpm / 2
            else it.bpm <= baselineAverage - deltaThresholdBpm / 2
        }.toDouble() / recent.size
        if (supporting < 0.8) return null
        latchedDirection = direction
        return SustainedHrChange(direction, baselineAverage, recentAverage, delta)
    }

    fun reset() { samples.clear(); lastTimestampMs = Long.MIN_VALUE; latchedDirection = null }

    private fun hasSpan(values: List<Sample>, minimumSpanMs: Long): Boolean =
        values.size >= 2 && values.last().timestampMs - values.first().timestampMs >= minimumSpanMs
}
