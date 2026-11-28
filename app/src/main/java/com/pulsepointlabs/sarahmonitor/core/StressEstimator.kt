package com.pulsepointlabs.sarahmonitor.core

import kotlin.math.roundToInt

enum class StressLevel(val label: String) { Calm("Calm"), Mild("Mild"), Elevated("Elevated"), High("High") }

/** One part of the stress estimate, 0–100, or null when that sensor isn't available. */
data class StressComponent(val key: String, val label: String, val score: Int?, val detail: String) {
    companion object {
        /** Every driver, in its fixed display order. */
        val ORDER = listOf("hrv" to "HRV", "hr" to "Heart", "breath" to "Breathing", "tension" to "Pelvic floor", "restless" to "Restless")

        /**
         * All drivers in a fixed order, each in its own slot whether or not it has a reading right now, so the
         * stress card and overlay never jump when breathing (or anything else) drops in and out. Pure; unit-tested.
         */
        fun slots(components: List<StressComponent>, withTension: Boolean = true): List<StressComponent> =
            ORDER.filter { withTension || it.first != "tension" }
                .map { (k, l) -> components.firstOrNull { it.key == k } ?: StressComponent(k, l, null, "") }
    }
}

data class StressReading(
    /** 0–100 overall, smoothed over ~20 s. Null until there's enough to say. */
    val score: Int? = null,
    val level: StressLevel? = null,
    /** True while you're moving enough that heart rate is mostly exertion. */
    val moving: Boolean = false,
    /** Share (0–100 %) of the inputs that were available. */
    val confidence: Int = 0,
    val breathsPerMin: Double? = null,
    val components: List<StressComponent> = emptyList(),
    val updatedAtMs: Long? = null
)

/** Everything the estimate can use; missing sensors are null. */
data class StressInputs(
    val hr: Int?,
    val rmssd: Double?,
    val restingHr: Double?,
    val restingRmssd: Double?,
    val breathsPerMin: Double?,
    /** Chest motion, dynamic milli-g (90th percentile of the last 10 s). */
    val motionMg: Double?,
    /** Pelvic floor above rest, kPa (30 s average). */
    val pelvicKpa: Double?,
    /** EMG, % of calibrated hold. */
    val emgPct: Double?,
    /**
     * Whether pelvic-floor tension counts toward the stress score. Off in sexual and Kegel sessions (clenching there
     * is arousal or training, not stress), but the pelvic floor reading is still shown.
     */
    val tensionCounts: Boolean = true
)

/**
 * "How stressed are you?" — a mental/autonomic stress estimate, separate from Load (cardiovascular strain).
 * Combines, against your own resting baseline:
 *  - HRV suppression (RMSSD below resting) — the strongest single marker
 *  - heart rate above resting that movement doesn't explain (so exercise doesn't read as stress)
 *  - faster breathing (from respiratory sinus arrhythmia in the RR intervals)
 *  - muscle tension (pelvic floor pressure, EMG) when not in a sexual session
 *  - restlessness (small, frequent movement)
 * Weights are re-normalized over the inputs that are available.
 */
object StressEstimator {
    private const val W_HRV = 0.35
    private const val W_HR = 0.25
    private const val W_BREATH = 0.2
    private const val W_TENSION = 0.1
    private const val W_RESTLESS = 0.1

    fun level(score: Int): StressLevel = when {
        score < 25 -> StressLevel.Calm
        score < 50 -> StressLevel.Mild
        score < 70 -> StressLevel.Elevated
        else -> StressLevel.High
    }

    private fun clamp(v: Double) = v.coerceIn(0.0, 100.0)

    /** Instantaneous (unsmoothed) estimate. */
    fun estimate(i: StressInputs): StressReading {
        val motion = i.motionMg
        val moving = (motion ?: 0.0) >= 180
        val hrv = if (i.rmssd != null && i.restingRmssd != null && i.restingRmssd > 3) {
            clamp((1 - i.rmssd / i.restingRmssd) / 0.6 * 100)
        } else null
        // Movement raises HR legitimately: allow ~1 bpm per 4 mg of dynamic acceleration.
        val allowance = (motion ?: 0.0) / 4
        val hrExcess = if (i.hr != null && i.restingHr != null) i.hr - i.restingHr - allowance else null
        val hr = hrExcess?.let { clamp(it / 30 * 100) }
        val breath = i.breathsPerMin?.let { clamp((it - 12) / 10 * 100) }
        val tensionParts = listOfNotNull(i.pelvicKpa?.let { clamp(it / 2.0 * 100) }, i.emgPct?.let { clamp(it) })
        val tension = tensionParts.takeIf { it.isNotEmpty() }?.max()
        val restless = motion?.let { m -> if (m in 15.0..180.0) clamp((m - 15) / 75 * 100) else 0.0 }
        val parts = listOf(hrv to W_HRV, hr to W_HR, breath to W_BREATH, (if (i.tensionCounts) tension else null) to W_TENSION, restless to W_RESTLESS)
        val available = parts.filter { it.first != null }
        val weight = available.sumOf { it.second }
        // Need at least an HRV or heart-rate read to say anything.
        val score = if (hrv == null && hr == null) null else (available.sumOf { it.first!! * it.second } / weight)
        val components = listOf(
            StressComponent("hrv", "HRV", hrv?.roundToInt(), i.rmssd?.let { r -> i.restingRmssd?.let { "RMSSD %.0f vs %.0f rest".format(r, it) } } ?: ""),
            StressComponent("hr", "Heart", hr?.roundToInt(), hrExcess?.let { "%+.0f bpm unexplained".format(it) } ?: ""),
            StressComponent("breath", "Breathing", breath?.roundToInt(), i.breathsPerMin?.let { "%.0f /min".format(it) } ?: ""),
            StressComponent("tension", "Pelvic floor", tension?.roundToInt(), if (i.tensionCounts) "" else "shown, not counted in this session"),
            StressComponent("restless", "Restless", restless?.roundToInt(), motion?.let { "%.0f mg".format(it) } ?: "")
        )
        return StressReading(
            score = score?.roundToInt(), level = score?.let { level(it.roundToInt()) }, moving = moving,
            confidence = (weight * 100).roundToInt(), breathsPerMin = i.breathsPerMin, components = components
        )
    }
}

/** Smooths the estimate (~20 s) so it reads as a state, not a beat-to-beat number. */
class StressTracker {
    private var smooth: Double? = null
    private var last = StressReading()

    @Synchronized
    fun update(t: Long, inputs: StressInputs): StressReading {
        val raw = StressEstimator.estimate(inputs)
        val s = raw.score?.toDouble()
        if (s != null) smooth = smooth?.let { it + 0.05 * (s - it) } ?: s
        val v = smooth?.roundToInt()
        last = raw.copy(score = v, level = v?.let { StressEstimator.level(it) }, updatedAtMs = t)
        return last
    }

    @Synchronized
    fun current(): StressReading = last

    @Synchronized
    fun reset() { smooth = null; last = StressReading() }
}
