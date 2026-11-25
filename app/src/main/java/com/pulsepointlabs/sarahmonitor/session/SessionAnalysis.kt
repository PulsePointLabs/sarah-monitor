package com.pulsepointlabs.sarahmonitor.session

import com.pulsepointlabs.sarahmonitor.core.BpEntry
import com.pulsepointlabs.sarahmonitor.core.GlucoseEntry
import com.pulsepointlabs.sarahmonitor.hrv.HrvCalculator
import com.pulsepointlabs.sarahmonitor.hrv.HrvMetrics
import com.pulsepointlabs.sarahmonitor.hrv.RrSampleEntity
import kotlin.math.abs
import kotlin.math.max

/** A contraction found in recorded pressure: start, end and peak above rest. */
data class RecordedPulse(val startMs: Long, val endMs: Long, val peakKpa: Double) {
    val durationS: Double get() = (endMs - startMs) / 1000.0
}

data class PelvicSummary(
    val maxKpa: Double?,
    val averageKpa: Double?,
    /** Contractions: rises above the hold threshold lasting up to [com.pulsepointlabs.sarahmonitor.core.PelvicProcessor.MAX_CONTRACTION_S]. */
    val pulses: List<RecordedPulse>,
    val longestHoldS: Double?,
    val sampleCount: Int,
    /** Rises that lasted longer than a contraction can: sustained elevation, origin unknown (tension, posture, drift). */
    val sustained: List<RecordedPulse> = emptyList(),
    /** Samples that may be analyzed (logging on, no fault). */
    val usableCount: Int = sampleCount,
    /** Samples recorded while logging was on or during a fault (excludes preview/calibration). */
    val loggedCount: Int = sampleCount,
    /** Stretches excluded as suspected displacement, shifted rest or a reported sensor problem. */
    val faults: List<QualitySpan> = emptyList()
) {
    /** Share of the logged pressure that was usable, 0–1 (null with nothing logged). */
    val usableFraction: Double? get() = if (loggedCount > 0) usableCount.toDouble() / loggedCount else null
}

/** What an event looked like in the data: values at the moment and how HR responded afterwards. */
data class MarkerContext(
    val hrAt: Int?,
    val hrBefore: Double?,
    val hrAfter1Min: Double?,
    val hrAfter5Min: Double?,
    val rmssdAt: Double?,
    val pressureAt: Double?,
    val peakPressureNext30s: Double?
) {
    val delta1Min: Double? get() = if (hrBefore != null && hrAfter1Min != null) hrAfter1Min - hrBefore else null
    val delta5Min: Double? get() = if (hrBefore != null && hrAfter5Min != null) hrAfter5Min - hrBefore else null
}

data class SessionData(
    val session: SessionEntity,
    val hr: List<HrSampleEntity>,
    val rr: List<RrEntity>,
    val pelvic: List<PelvicSampleEntity>,
    val markers: List<MarkerEntity>,
    val bp: List<BpEntry>,
    val glucose: List<GlucoseEntry>,
    val hasEcg: Boolean,
    val emg: List<EmgSampleEntity> = emptyList(),
    /** Connection, calibration and quality changes recorded automatically (0.25.0+). */
    val sensorEvents: List<SensorEventEntity> = emptyList()
) {
    /** Quality of every pressure sample as the review uses it (see [PressureReview]). Computed once. */
    val pelvicQuality: Array<com.pulsepointlabs.sarahmonitor.core.PressureQuality> by lazy { PressureReview.qualities(pelvic, markers) }
    /** The pressure analysis for this session (quality-aware, annotation exclusions applied). Computed once. */
    val pelvicSummary: PelvicSummary by lazy { SessionAnalysis.pelvic(pelvic, markers, pelvicQuality) }

    val endMs: Long get() = session.endTimeMs ?: listOfNotNull(hr.lastOrNull()?.timestampMs, pelvic.lastOrNull()?.timestampMs, System.currentTimeMillis()).max()
}

object SessionAnalysis {
    fun hrv(session: SessionEntity, rr: List<RrEntity>, hrCount: Int): HrvMetrics =
        HrvCalculator.calculate(
            rr.mapIndexed { i, r ->
                RrSampleEntity(
                    id = "rr$i", sessionId = session.id, timestampMs = r.timestampMs, offsetMs = r.timestampMs - session.startTimeMs,
                    rawIntervalMs = r.rawMs, cleanIntervalMs = r.cleanMs, valid = r.cleanMs != null, rejectionReason = r.rejectionReason,
                    artifact = r.artifact, accepted = r.accepted
                )
            },
            hrCount,
            elapsedMs = (rr.lastOrNull()?.timestampMs ?: session.startTimeMs) - session.startTimeMs
        )

    /**
     * Holds are runs where the live detector reported "holding" on usable samples (see [PressureReview]); at least
     * 0.2 s long. A run that hits unusable pressure (fault, preview, calibration) is dropped, not cut short. Runs
     * longer than a contraction can last are reported as sustained elevation instead.
     */
    fun pelvic(
        samples: List<PelvicSampleEntity>,
        markers: List<MarkerEntity> = emptyList(),
        quality: Array<com.pulsepointlabs.sarahmonitor.core.PressureQuality> = PressureReview.qualities(samples, markers)
    ): PelvicSummary {
        val pulses = mutableListOf<RecordedPulse>()
        val sustained = mutableListOf<RecordedPulse>()
        var start: Long? = null
        var peak = 0.0
        var previous: PelvicSampleEntity? = null
        samples.forEachIndexed { i, s ->
            val gap = previous != null && s.timestampMs - previous!!.timestampMs > 1_000L
            if (gap || !quality[i].usable) start = null
            if (s.holding && quality[i].usable) {
                if (start == null) { start = s.timestampMs; peak = 0.0 }
                peak = max(peak, s.aboveRestKpa ?: 0.0)
            } else if (start != null) {
                val pulse = RecordedPulse(start!!, s.timestampMs, peak)
                when {
                    pulse.durationS > com.pulsepointlabs.sarahmonitor.core.PelvicProcessor.MAX_CONTRACTION_S -> sustained += pulse
                    pulse.durationS >= 0.2 -> pulses += pulse
                }
                start = null
            }
            previous = s
        }
        // Still "holding" when the recording ends: report it (a long one as sustained elevation) rather than drop it.
        if (start != null && previous != null) {
            val open = RecordedPulse(start!!, previous!!.timestampMs, peak)
            when {
                open.durationS > com.pulsepointlabs.sarahmonitor.core.PelvicProcessor.MAX_CONTRACTION_S -> sustained += open
                open.durationS >= 0.2 -> pulses += open
            }
        }
        val values = samples.indices.filter { quality[it].usable }.mapNotNull { samples[it].aboveRestKpa }
        return PelvicSummary(
            maxKpa = values.maxOrNull(),
            averageKpa = values.takeIf { it.isNotEmpty() }?.average(),
            pulses = pulses,
            longestHoldS = pulses.maxOfOrNull { it.durationS },
            sampleCount = samples.size,
            sustained = sustained,
            usableCount = quality.count { it.usable },
            loggedCount = quality.count { it.usable || it.fault || it == com.pulsepointlabs.sarahmonitor.core.PressureQuality.Stabilizing },
            faults = PressureReview.faultSpans(samples, quality)
        )
    }

    fun sparkline(hr: List<HrSampleEntity>, points: Int = 48): String {
        if (hr.isEmpty()) return ""
        val size = max(1, hr.size / points)
        return hr.chunked(size).map { c -> c.map { it.bpm }.average().toInt() }.take(points).joinToString(",")
    }

    fun summarize(session: SessionEntity, hr: List<HrSampleEntity>, rr: List<RrEntity>, pelvic: List<PelvicSampleEntity>, markerCount: Int, markers: List<MarkerEntity> = emptyList()): SessionEntity {
        val hrv = if (rr.size >= 3) hrv(session, rr, hr.size) else null
        val p = pelvic(pelvic, markers)
        return session.copy(
            averageHr = hr.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average(),
            minHr = hr.minOfOrNull { it.bpm },
            maxHr = hr.maxOfOrNull { it.bpm },
            rmssdMs = hrv?.rmssdMs,
            sdnnMs = hrv?.sdnnMs,
            pnn50Percent = hrv?.pnn50Percent,
            hrvQuality = hrv?.quality,
            pelvicMaxKpa = p.maxKpa,
            pelvicAverageKpa = p.averageKpa,
            pelvicPulseCount = if (pelvic.isEmpty()) null else p.pulses.size,
            pelvicLongestHoldS = p.longestHoldS,
            markerCount = markerCount,
            hrSparkline = sparkline(hr)
        )
    }

    private fun averageHr(hr: List<HrSampleEntity>, from: Long, to: Long): Double? =
        hr.filter { it.timestampMs in from..to }.map { it.bpm }.takeIf { it.size >= 2 }?.average()

    fun markerContext(marker: MarkerEntity, data: SessionData): MarkerContext {
        val t = marker.timestampMs
        val nearestHr = data.hr.minByOrNull { abs(it.timestampMs - t) }?.takeIf { abs(it.timestampMs - t) < 10_000L }
        val nearestPelvic = data.pelvic.minByOrNull { abs(it.timestampMs - t) }?.takeIf { abs(it.timestampMs - t) < 2_000L }
        return MarkerContext(
            hrAt = nearestHr?.bpm,
            hrBefore = averageHr(data.hr, t - 60_000L, t),
            hrAfter1Min = averageHr(data.hr, t + 30_000L, t + 90_000L),
            hrAfter5Min = averageHr(data.hr, t + 4 * 60_000L, t + 6 * 60_000L),
            rmssdAt = nearestHr?.rmssdMs,
            pressureAt = nearestPelvic?.aboveRestKpa,
            peakPressureNext30s = data.pelvic.filter { it.timestampMs in t..(t + 30_000L) }.mapNotNull { it.aboveRestKpa }.maxOrNull()
        )
    }

    /** "82% of logged pressure usable; 3 suspected displacements excluded" — null when everything was usable. */
    fun pressureQualityLine(p: PelvicSummary): String? {
        val frac = p.usableFraction ?: return null
        if (p.faults.isEmpty() && frac >= 0.98) return null
        val counts = p.faults.groupingBy { it.quality }.eachCount().entries.joinToString(", ") { (q, n) -> "$n × ${q.label.lowercase()}" }
        val replayed = if (p.faults.any { it.replayed }) " (found by re-checking an older recording, which has no live quality data)" else ""
        return "Pressure quality: ${"%.0f".format(frac * 100)}% of logged pressure usable" + (if (counts.isNotEmpty()) "; excluded $counts$replayed" else "") + "."
    }

    /** "18 min before start" / "4 min after end" for a reading outside the recording; null when it was during it. */
    fun bpTiming(t: Long, data: SessionData): String? {
        val start = data.session.startTimeMs
        return when {
            t < start -> "${(start - t + 30_000L) / 60_000L} min before start"
            t > data.endMs -> "${(t - data.endMs + 30_000L) / 60_000L} min after end"
            else -> null
        }
    }

    /** Elapsed time that can be negative ("-12:30" before the start), unlike [elapsed]. */
    fun elapsedSigned(ms: Long): String = if (ms < 0) "-" + elapsed(-ms) else elapsed(ms)

    /** Fixed decimals without "-0.0". */
    fun num(v: Double, decimals: Int = 1): String {
        val text = "%.${decimals}f".format(java.util.Locale.US, v)
        return if (text.trimStart('-').all { it == '0' || it == '.' }) text.trimStart('-') else text
    }

    /** "+8", "-3" or "±0" for a change. */
    fun signed(v: Double, decimals: Int = 0): String {
        val text = num(v, decimals)
        return when {
            text.all { it == '0' || it == '.' } -> "±$text"
            v > 0 -> "+$text"
            else -> text
        }
    }

    fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun durationWords(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "${ms / 1000} s"
            m < 60 -> "$m min"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    /** One plain-English paragraph that says what happened, for the top of the review. */
    fun plainSummary(data: SessionData): List<String> {
        val s = data.session
        val lines = mutableListOf<String>()
        val events = if (data.markers.isNotEmpty()) " with ${data.markers.size} marked event${if (data.markers.size == 1) "" else "s"}" else ""
        lines += if (s.active) "Recording for ${durationWords(data.endMs - s.startTimeMs)} so far$events (updates live)."
        else "${durationWords(data.endMs - s.startTimeMs)} session$events."
        if (s.averageHr != null) {
            lines += "Heart rate averaged ${"%.0f".format(s.averageHr)} bpm (range ${s.minHr}–${s.maxHr})." +
                (s.rmssdMs?.let { " HRV: RMSSD ${"%.1f".format(it)} ms, SDNN ${s.sdnnMs?.let { v -> "%.1f".format(v) } ?: "--"} ms (${s.hrvQuality ?: "quality unknown"})." } ?: "")
        } else {
            lines += if (s.active) "Waiting for heart-rate data from the H10." else "No heart-rate data was recorded."
        }
        if (data.pelvic.isNotEmpty()) {
            val p = data.pelvicSummary
            lines += when {
                p.loggedCount == 0 -> "Pelvic pressure was only previewed (logging was never switched on), so no contractions were analyzed."
                p.maxKpa != null -> "Pelvic pressure peaked at ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(p.maxKpa)} above rest with ${p.pulses.size} contraction${if (p.pulses.size == 1) "" else "s"}" +
                    (p.longestHoldS?.let { "; longest hold ${"%.1f".format(it)} s" } ?: "") +
                    (if (p.sustained.isNotEmpty()) "; ${p.sustained.size} sustained elevation${if (p.sustained.size == 1) "" else "s"} over ${com.pulsepointlabs.sarahmonitor.core.PelvicProcessor.MAX_CONTRACTION_S.toInt()} s (not counted as contractions)" else "") + "."
                else -> "Pelvic pressure was recorded but no rest level was set."
            }
            pressureQualityLine(p)?.let { lines += it }
        }
        if (data.bp.isNotEmpty()) {
            lines += "Blood pressure: " + data.bp.joinToString(" → ") { "${it.systolic}/${it.diastolic}" + (it.positionLabel?.let { p -> " ($p)" } ?: "") + bpTiming(it.timestampMs, data)?.let { t -> " ($t)" }.orEmpty() } + "."
        }
        if (data.glucose.isNotEmpty()) {
            lines += "Glucose: " + data.glucose.joinToString(" → ") { "${"%.0f".format(it.mgDl)}" } + " mg/dL."
        }
        return lines
    }
}
