package com.pulsepointlabs.sarahmonitor.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Everything the app knew at the moment an event was marked, frozen as JSON on the event (schema version "v").
 * Values are raw numbers with units in the key names, so analysis and chat can use them later without guessing.
 * Missing sensors are simply absent (never zero).
 */
object EventSnapshot {
    const val VERSION = 1

    private fun r1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun JSONObject.opt(key: String, v: Any?): JSONObject = if (v == null) this else put(key, v)

    fun capture(atMs: Long): JSONObject {
        val o = JSONObject().put("v", VERSION).put("t_ms", atMs)
        val session = MonitorHub.session.value
        session?.let {
            o.put("session_id", it.id).put("session_elapsed_s", ((atMs - it.startTimeMs) / 1000).coerceAtLeast(0))
                .opt("session_category", it.category)
        }
        val h = MonitorHub.heart.value
        if (h.heartRate != null && h.updatedAtMs != null && atMs - h.updatedAtMs < 10_000L) {
            o.put("heart", JSONObject()
                .put("hr_bpm", h.heartRate).opt("avg_bpm", h.averageHr?.let(::r1)).opt("min_bpm", h.minHr).opt("max_bpm", h.maxHr)
                .opt("rmssd_ms", h.rmssdMs?.let(::r1)).opt("sdnn_ms", h.sdnnMs?.let(::r1)).opt("pnn50_pct", h.pnn50Percent?.let(::r1))
                .put("hrv_signal_quality", h.hrvQuality).put("rr_coverage_pct", r1(h.rrCoveragePercent)).opt("contact", h.contact).opt("movement", h.movement))
            o.put("load", JSONObject().opt("score", h.load.score).opt("hr_load_pct", h.load.hrLoadPercent)
                .opt("hrv_suppression_pct", h.load.hrvSuppressionPercent).opt("confidence_pct", h.load.confidencePercent))
        }
        val st = MonitorHub.stress.value
        if (st.score != null) {
            o.put("stress", JSONObject().put("score", st.score).opt("level", st.level?.name).put("moving", st.moving).put("confidence_pct", st.confidence)
                .put("components", JSONObject().apply { st.components.forEach { c -> c.score?.let { put(c.key, it) } } }))
        }
        val resp = MonitorHub.response.value
        resp.breathsPerMin?.let { o.put("breaths_per_min", r1(it)) }
        resp.motionMg?.let { o.put("motion_mg", r1(it)) }
        if (resp.timeline.isNotEmpty()) {
            o.put("response", JSONObject().put("phase", resp.phase.name).put("approach", resp.approach).put("arousal", resp.arousal)
                .put("plateau", resp.plateau).put("recovery", resp.recovery).put("edges", resp.edges).put("climaxes", resp.climaxes)
                .opt("hr_delta_bpm", resp.hrDelta).opt("baseline_hr_bpm", resp.baselineHr).put("hrv_signal", resp.hrvSignal))
        }
        val p = MonitorHub.pelvic.value
        if (p.pressureKpa != null && (p.trace.lastOrNull()?.timestampMs?.let { atMs - it < 5_000L } == true)) {
            o.put("pelvic", JSONObject().opt("above_rest_kpa", p.aboveRestKpa?.let(::r1)).opt("avg_30s_kpa", p.average30s?.let(::r1))
                .opt("max_30s_kpa", p.max30s?.let(::r1)).put("holding", p.holding).opt("hold_s", p.holdSeconds?.let(::r1))
                .put("pulses_per_min", p.pulsesLastMinute).opt("pct_of_reference", p.percentOfReference?.roundToInt()).put("rest_source", p.restSource.name))
        }
        val emg = MonitorHub.emg.value
        if (emg.fresh && emg.channels.isNotEmpty()) {
            o.put("emg", JSONArray().apply {
                emg.channels.forEach { c -> put(JSONObject().put("name", c.name).opt("pct_of_hold", c.pct?.roundToInt()).opt("raw", c.raw)) }
            })
        }
        fun ageMin(t: Long) = ((atMs - t) / 60_000L).coerceAtLeast(0)
        MonitorHub.bp.items.value.duringSession(MonitorHub.session.value?.startTimeMs).lastOrNull { it.timestampMs <= atMs }?.let {
            o.put("bp", JSONObject().put("systolic", it.systolic).put("diastolic", it.diastolic).opt("pulse", it.pulse)
                .opt("site", it.site).opt("posture", it.posture).put("age_min", ageMin(it.timestampMs)))
        }
        MonitorHub.glucose.items.value.lastOrNull { atMs - it.timestampMs in 0..24 * 3_600_000L }?.let {
            o.put("glucose", JSONObject().put("mg_dl", r1(it.mgDl)).put("age_min", ageMin(it.timestampMs)))
        }
        MonitorHub.spo2.items.value.lastOrNull { atMs - it.timestampMs in 0..12 * 3_600_000L }?.let {
            o.put("spo2", JSONObject().put("pct", it.spo2).opt("pulse_bpm", it.pulse).put("age_min", ageMin(it.timestampMs)))
        }
        MonitorHub.body.items.value.lastOrNull()?.let {
            o.put("body", JSONObject().put("weight_kg", it.weightKg).opt("body_fat_pct", it.bodyFatPct).put("age_days", ageMin(it.timestampMs) / 1440))
        }
        // What Howl was delivering at this moment (power 0–200 per channel).
        com.pulsepointlabs.sarahmonitor.howl.Howl.state.value.takeIf { it.connected }?.let { h ->
            o.put("howl", JSONObject().put("power_a", h.powerA).put("power_b", h.powerB).put("muted", h.muted).put("playing", h.playing)
                .opt("activity", h.activity?.label ?: h.title))
        }
        o.put("sensors", JSONObject()
            .put("h10", MonitorHub.h10.state.value.name).put("civet", MonitorHub.civet.state.value.name)
            .put("omron", MonitorHub.omron.state.value.name).put("emg", MonitorHub.emgClient.state.value.name))
        return o
    }

    /** One readable line for review / summaries: "stress 34 · breathing 14/min · BP 128/82 (arm, sitting; 12 min before)". */
    fun describe(json: String?): String? {
        val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val parts = mutableListOf<String>()
        o.optJSONObject("gear")?.let { parts += "device: " + it.optString("label") }
        o.optJSONObject("howl")?.let { parts += "Howl A ${it.optInt("power_a")} · B ${it.optInt("power_b")}" + (it.optString("activity").takeIf { a -> a.isNotBlank() && a != "null" }?.let { a -> " ($a)" } ?: "") + if (it.optBoolean("playing")) "" else " paused" }
        o.optJSONObject("stress")?.let { parts += "stress ${it.optInt("score")}" + (if (it.optBoolean("moving")) " (moving)" else "") }
        if (o.has("breaths_per_min")) parts += "breathing ${o.optDouble("breaths_per_min").roundToInt()}/min"
        o.optJSONObject("response")?.let { r -> if (r.optInt("approach") > 0 || r.optString("phase") != "Baseline") parts += "${r.optString("phase").lowercase()} · approach ${r.optInt("approach")}" }
        o.optJSONObject("pelvic")?.let { if (it.has("above_rest_kpa")) parts += "pelvic ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(it.optDouble("above_rest_kpa"))}" }
        o.optJSONObject("bp")?.let { b ->
            parts += "BP ${b.optInt("systolic")}/${b.optInt("diastolic")}" +
                listOfNotNull(b.optString("site").takeIf { it.isNotBlank() && it != "null" }, b.optString("posture").takeIf { it.isNotBlank() && it != "null" })
                    .joinToString(", ").let { if (it.isBlank()) "" else " ($it)" } + " ${b.optLong("age_min")} min before"
        }
        o.optJSONObject("glucose")?.let { parts += "glucose ${it.optDouble("mg_dl").roundToInt()} (${it.optLong("age_min")} min before)" }
        o.optJSONObject("spo2")?.let { parts += "SpO2 ${it.optInt("pct")}%" }
        return parts.joinToString(" · ").ifBlank { null }
    }
}
