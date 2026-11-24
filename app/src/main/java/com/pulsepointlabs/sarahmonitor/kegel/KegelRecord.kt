package com.pulsepointlabs.sarahmonitor.kegel

import com.pulsepointlabs.sarahmonitor.session.MarkerEntity
import org.json.JSONObject
import kotlin.math.roundToInt

/** A finished Kegel set as saved on its event (see [KegelSession.toJson]). */
data class KegelRecord(
    val markerId: String,
    val routineLabel: String,
    val reps: Int,
    val holdS: Double,
    val restS: Double,
    val startMs: Long,
    val endMs: Long,
    val score: Int,
    val strengthPct: Int?,
    val relaxPct: Int?,
    val stoppedEarly: Boolean,
    /** (rep, on-target %, strength %) */
    val repScores: List<Triple<Int, Int, Int?>>,
    /** Squeeze/hold windows: (start, end, target %) */
    val squeezes: List<Triple<Long, Long, Int>>,
    /** Reference hold and hold threshold at the time (kPa); null on sets saved before 0.25.1. */
    val referenceKpa: Double? = null,
    val thresholdKpa: Double? = null
) {
    /** A plain read of the set. */
    fun story(): String {
        val parts = mutableListOf<String>()
        val onTarget = repScores.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.roundToInt()
        parts += "Score $score" + (if (stoppedEarly) " (stopped early)" else "") + "." +
            (onTarget?.let { " You were on target for $it% of the squeeze time" } ?: "") +
            (strengthPct?.let { ", averaging $it% of your reference hold." } ?: if (onTarget != null) "." else "")
        val s = repScores.mapNotNull { it.third }
        if (s.size >= 4) {
            val first = s.take(s.size / 2).average(); val second = s.drop(s.size / 2).average()
            when {
                first - second >= 15 -> parts += "Strength faded from ${first.roundToInt()}% in the first half to ${second.roundToInt()}% in the second, which is fatigue. Longer rests or fewer reps would keep every rep clean."
                second - first >= 10 -> parts += "Later reps were stronger (${first.roundToInt()}% → ${second.roundToInt()}%), so you warmed into it."
                else -> parts += "Strength held steady across the set."
            }
        }
        val weak = repScores.filter { it.second < 50 }.map { it.first }
        if (weak.isNotEmpty() && weak.size < repScores.size) parts += "Reps ${weak.joinToString(", ")} were under target most of the time."
        relaxPct?.let { r -> parts += if (r >= 75) "You relaxed fully between squeezes ($r%)." else "Between squeezes you only fully relaxed $r% of the time; letting it drop completely matters as much as the squeeze." }
        return parts.joinToString(" ")
    }

    companion object {
        fun from(m: MarkerEntity): KegelRecord? {
            val o = m.snapshot?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONObject("kegel") ?: return null
            return fromJson(m.id, o, m.timestampMs)
        }

        /** From a set's JSON (as saved on its event, or straight from a set that just finished). */
        fun fromJson(id: String, o: JSONObject, endMs: Long): KegelRecord? {
            val m = object { val id = id; val timestampMs = endMs }
            return runCatching {
                val reps = o.getJSONArray("rep_scores")
                val steps = o.getJSONArray("steps")
                val sq = (0 until steps.length()).map { steps.getJSONObject(it) }.filter { !it.isNull("target_pct") }
                    .map { Triple(it.getLong("start_ms"), it.getLong("end_ms"), it.getInt("target_pct")) }
                KegelRecord(
                    m.id, o.getString("routine_label"), o.getInt("reps"), o.getDouble("hold_s"), o.getDouble("rest_s"),
                    o.getLong("start_ms"), m.timestampMs, o.getInt("score"),
                    o.optInt("strength_pct", -1).takeIf { !o.isNull("strength_pct") && it >= 0 },
                    o.optInt("relax_pct", -1).takeIf { !o.isNull("relax_pct") && it >= 0 },
                    o.optBoolean("stopped_early"),
                    (0 until reps.length()).map { reps.getJSONObject(it) }.map { Triple(it.getInt("rep"), it.getInt("on_target_pct"), if (it.isNull("strength_pct")) null else it.getInt("strength_pct")) },
                    sq,
                    o.optDouble("reference_kpa").takeIf { !o.isNull("reference_kpa") && it.isFinite() },
                    o.optDouble("threshold_kpa").takeIf { !o.isNull("threshold_kpa") && it.isFinite() }
                )
            }.getOrNull()
        }
    }
}
