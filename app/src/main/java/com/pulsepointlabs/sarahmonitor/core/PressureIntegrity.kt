package com.pulsepointlabs.sarahmonitor.core

import kotlin.math.max

/**
 * Measurement quality of one CIVET sample. Stored with every sample (by name) from 0.25.0; older recordings have
 * none and are treated as [Unknown] unless the review's replay finds a problem.
 *
 * [usable]: the sample may feed contraction detection, pressure statistics and the arousal engine.
 */
enum class PressureQuality(val label: String, val usable: Boolean, val fault: Boolean = false) {
    /** Connected and showing pressure, but logging hasn't been switched on (positioning, settling, calibrating). */
    Preview("Preview, not logging", false),
    /** A rest or reference capture is running. */
    Calibrating("Calibrating", false),
    /** Back after a signal gap (reconnect); waiting a few seconds before trusting it again. */
    Stabilizing("Stabilizing", false),
    Valid("Logging", true),
    /** Pressure fell well below the relaxed rest: the sensor may have slipped or deflated. */
    SuspectedDisplacement("Possible sensor displacement", false, true),
    /** The resting floor moved well below the captured rest and stayed there. */
    BaselineShift("Rest level shifted", false, true),
    /** The user said the sensor had a problem (slipped, re-seated, re-inflated). */
    ReportedProblem("Sensor problem reported", false, true),
    /** Recorded before quality states existed (0.24 and earlier). */
    Unknown("Quality unknown (older recording)", true);

    companion object {
        fun of(name: String?): PressureQuality? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Signal-integrity checks on CIVET pressure, shared by the live processor and the review's replay of older
 * recordings, so both apply the same rule.
 *
 * Displacement: a pelvic contraction raises pressure; nothing physiological pushes it far *below* a relaxed rest.
 * A sustained fall well below rest, or a loss of most of the resting preload, is the signature of a measuring
 * sensor that slipped, came out or deflated (the ICS urodynamic standards describe the same pattern for an expelled
 * pressure catheter). Pressure alone cannot always tell displacement from other mechanics, so this is reported as
 * *suspected*.
 *
 * Baseline shift: with a captured rest, the resting floor (20th percentile of the last 30 s) staying well below that
 * rest means the rest no longer describes the sensor's seating. Upward floor shifts are *not* treated as faults:
 * they may be real sustained tension and are reported as "sustained elevation" instead.
 *
 * Thresholds are first, conservative values ([VERSION] 1). They are exploratory and must be checked against
 * recordings with known displacements before being trusted for anything finer.
 */
class PressureIntegrity {
    enum class Issue { Displacement, BaselineShift }

    private val window = ArrayDeque<Pair<Long, Double>>()
    private var dropSince: Long? = null
    private var shiftSince: Long? = null
    private var floorCache: Double? = null
    private var floorAt = Long.MIN_VALUE

    fun reset() {
        window.clear(); dropSince = null; shiftSince = null; floorCache = null; floorAt = Long.MIN_VALUE
    }

    /** Feeds one sample; returns the issue whose condition has lasted long enough, or null. */
    fun update(t: Long, pressureKpa: Double, aboveKpa: Double?, restKpa: Double?, restNoiseKpa: Double?, restCaptured: Boolean): Issue? {
        if (aboveKpa == null || restKpa == null) { dropSince = null; shiftSince = null; return null }
        val drop = isDrop(pressureKpa, aboveKpa, restKpa, restNoiseKpa)
        // Displacement samples stay out of the floor: otherwise a slip would be reported again as a shifted rest.
        if (!drop) window.addLast(t to aboveKpa)
        while (window.isNotEmpty() && t - window.first().first > FLOOR_WINDOW_MS) window.removeFirst()

        dropSince = if (drop) dropSince ?: t else null
        if (dropSince != null && t - dropSince!! >= DROP_PERSIST_MS) return Issue.Displacement

        val spanOk = window.size >= 2 && window.last().first - window.first().first >= FLOOR_MIN_SPAN_MS
        // The floor moves slowly: recompute it twice a second rather than on every 10 Hz sample.
        if (!spanOk) floorCache = null
        else if (floorCache == null || t - floorAt >= 500L) { floorCache = PelvicProcessor.percentile(window.map { it.second }, 0.2); floorAt = t }
        val floor = floorCache
        val shifted = restCaptured && floor != null && floor <= -shiftThreshold(restNoiseKpa)
        shiftSince = if (shifted) shiftSince ?: t else null
        if (shiftSince != null && t - shiftSince!! >= SHIFT_PERSIST_MS) return Issue.BaselineShift
        return null
    }

    companion object {
        const val VERSION = 1
        /** A fall this far below rest (or 10× the rest noise, whichever is larger) is not pelvic muscle activity. */
        const val DROP_MIN_KPA = 3.0
        const val DROP_NOISE_MULT = 10.0
        /** …or losing most of the resting preload: raw pressure under 30 % of a rest of at least 1 kPa. */
        const val PRELOAD_FRACTION = 0.3
        const val PRELOAD_MIN_REST_KPA = 1.0
        const val PRELOAD_MIN_DROP_KPA = 0.75
        /** Brief dips (a cough, a shift) shorter than this are ignored. */
        const val DROP_PERSIST_MS = 1_000L
        const val SHIFT_MIN_KPA = 1.0
        const val SHIFT_NOISE_MULT = 6.0
        const val FLOOR_WINDOW_MS = 30_000L
        const val FLOOR_MIN_SPAN_MS = 20_000L
        const val SHIFT_PERSIST_MS = 15_000L

        fun dropThreshold(noise: Double?) = max(DROP_MIN_KPA, DROP_NOISE_MULT * (noise ?: 0.0))
        fun shiftThreshold(noise: Double?) = max(SHIFT_MIN_KPA, SHIFT_NOISE_MULT * (noise ?: 0.0))

        fun isDrop(pressureKpa: Double, aboveKpa: Double, restKpa: Double, noise: Double?): Boolean =
            aboveKpa <= -dropThreshold(noise) ||
                (restKpa >= PRELOAD_MIN_REST_KPA && pressureKpa <= restKpa * PRELOAD_FRACTION && aboveKpa <= -PRELOAD_MIN_DROP_KPA)
    }
}
