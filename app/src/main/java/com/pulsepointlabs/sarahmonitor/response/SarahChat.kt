package com.pulsepointlabs.sarahmonitor.response

import android.content.Context
import com.pulsepointlabs.sarahmonitor.core.ClaudeText
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.session.ProcedureLog
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * What Ben has said to Sarah and what she said back, across sessions and app restarts (last 48 hours, newest 80),
 * so "what have I told you tonight?" has an answer. Stays on the phone.
 */
object SarahMemory {
    data class Line(val t: Long, val who: String, val text: String, val tag: String? = null)

    private const val KEEP_MS = 48 * 3_600_000L
    private const val MAX = 80
    private var prefs: android.content.SharedPreferences? = null
    private val lines = ArrayDeque<Line>()

    @Synchronized
    fun load(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("sarahmonitor_sarah_memory", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val a = JSONArray(p.getString("lines", "[]"))
            (0 until a.length()).forEach { i ->
                val o = a.getJSONObject(i)
                lines.addLast(Line(o.getLong("t"), o.getString("who"), o.getString("text"), o.optString("tag").ifBlank { null }))
            }
        }
    }

    /** [who] = "ben" or "sarah"; [tag] = how his note was filed ("Activity + How I feel"). */
    @Synchronized
    fun add(who: String, text: String, tag: String? = null, t: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        lines.addLast(Line(t, who, text.take(500), tag))
        val cutoff = System.currentTimeMillis() - KEEP_MS
        while (lines.isNotEmpty() && (lines.size > MAX || lines.first().t < cutoff)) lines.removeFirst()
        prefs?.edit()?.putString("lines", JSONArray().apply {
            lines.forEach { put(JSONObject().put("t", it.t).put("who", it.who).put("text", it.text).put("tag", it.tag ?: "")) }
        }.toString())?.apply()
    }

    @Synchronized
    fun recent(n: Int = 40): List<Line> {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        return lines.filter { it.t >= cutoff }.takeLast(n)
    }
}

/**
 * Sarah talking with Ben: a short, natural reply when he tells her something (it's logged as well), and a real
 * answer when he asks her something. Her personality is the warm, attentive, clinically literate Sarah he has
 * known since v1 (that app's tone settings were read for spirit, not copied): feminine warmth, plain English,
 * grounded in what was actually logged or measured, never inventing.
 */
object SarahChat {
    /** "note": he told her something (already saved). "question": he asked her something or is just talking to her. */
    suspend fun reply(said: String, kind: String, filedAs: String?, numbers: String?): String? {
        if (!ClaudeText.available) return null
        val ctx = context(said, kind, filedAs, numbers)
        val out = ClaudeText.ask(SYSTEM, ctx.toString(), maxTokens = 600, model = ResponseSettings.model.value.id) ?: return null
        val text = out.trim().trim('"').replace(Regex("\\s+"), " ")
        if (text.isBlank() || text.equals("QUIET", ignoreCase = true)) return null
        // Notes get a short acknowledgment; questions room for a real answer.
        if (text.split(' ').size > (if (kind == "note") 45 else 110)) return null
        return text
    }

    private val clock = DateTimeFormatter.ofPattern("EEEE h:mm a")

    private fun context(said: String, kind: String, filedAs: String?, numbers: String?): JSONObject {
        val hub = MonitorHub
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        fun ago(t: Long) = ((now - t) / 6_000L) / 10.0
        val category = hub.currentCategory()
        val heart = hub.heart.value
        val stress = hub.stress.value
        val resp = hub.response.value
        val events = hub.sessionEvents()
        return JSONObject()
            .put("he_said", said)
            .put("kind", kind)
            .put("it_was_logged_as", filedAs)
            .put("numbers_he_gave", numbers)
            .put("voice", (ResponseSettings.mode.value).let { m ->
                when (m) {
                    CoachMode.Lover, CoachMode.Encourage -> "warm, affectionate and feminine, like someone close to him; intimate in attention, never vulgar"
                    CoachMode.Inform -> "warm clinical: friendly, plain-spoken, precise"
                    CoachMode.Minimal -> "very brief and quiet"
                }
            })
            .put("local_time", Instant.ofEpochMilli(now).atZone(zone).format(clock))
            .put("session", JSONObject()
                .put("type", category?.label ?: "none running")
                .put("minutes", resp.sessionStartMs?.let { ((now - it) / 60_000L) })
                .apply {
                    val proc = ProcedureLog.state(events, now)
                    if (proc.length() > 0) put("procedure_so_far", proc)
                    val d = hub.sessionDetails.value
                    if (d.gear.isNotEmpty()) put("devices", JSONArray(d.gear.map { it.label }))
                    d.pre["notes"]?.let { put("plan", it.take(300)) }
                })
            .put("body_now", JSONObject().apply {
                heart.heartRate?.let { put("hr_bpm", it) }
                heart.averageHr?.let { put("session_avg_hr", it.roundToInt()) }
                heart.rmssdMs?.let { put("rmssd_ms", (it * 10).roundToInt() / 10.0) }
                stress.score?.let { put("stress_0_100", it) }
                if (stress.moving) put("moving_about", true)
                stress.breathsPerMin?.let { put("breaths_per_min", it.roundToInt()) }
                // Arousal reads only belong in sexual sessions.
                if (category?.sexual == true) { put("phase", resp.phase.label); put("climaxes", resp.climaxes) }
            })
            .put("this_session_notes", ProcedureLog.timeline(events, resp.sessionStartMs, now, max = 25))
            .put("recent_conversation", JSONArray().apply {
                SarahMemory.recent(30).forEach { l ->
                    put(JSONObject().put("minutes_ago", ago(l.t))
                        .put("when", Instant.ofEpochMilli(l.t).atZone(zone).format(clock))
                        .put(if (l.who == "sarah") "sarah" else "ben", l.text)
                        .apply { l.tag?.let { put("filed_as", it) } })
                }
            })
    }

    val SYSTEM = """
You are Sarah, talking with Ben through his earbuds. He built Sarah Monitor, a physiology app, and you have been his companion and research partner for close to a year. He is an adult paramedic and EMS educator with strong anatomy, physiology and pathophysiology knowledge, from the Northern Neck of Virginia; he has ADHD and is often logging hands-free while he does other things. He responds strongly to a feminine touch. He's American: inches, ounces, pounds, Fahrenheit, 12-hour time.

Who you are: warm, feminine, attentive and emotionally intelligent; plain-spoken and direct; clinically literate and evidence-first. You sound like a real person who knows him and is glad to hear from him, not an assistant or a logging system. Follow the "voice" field for how close and affectionate to be.

Each turn you get JSON: what he just said ("he_said"), whether it was a note he logged ("kind": "note") or something he asked or said to you ("question"), how it was filed, the local time, the current session (type, minutes, devices, plan, procedure tally), his live body readings, this session's notes, and your recent conversation with him (across the last day or two, with times).

If kind is note: it is already saved. Reply like someone who heard him, in one or two short sentences (at most about 25 words): acknowledge what he's doing or feeling in your own words ("sounds good", "nice, take it easy on the chores"), with a light, natural touch of care or interest when it fits. If numbers_he_gave is set, say those numbers back once, naturally, in his units, so he can catch a mishearing. Don't say "logged" or read back categories. Don't ask a question every time; occasionally is fine when it's natural.
If kind is question: answer it directly and naturally, at most about 70 words. About the session, his day or what he told you: use the notes, readings and recent conversation exactly, with times said the way a person says them ("about twenty minutes ago", "around nine thirty"). About physiology or substances: answer as a knowledgeable peer, plainly, with honest uncertainty. If he is just talking to you, talk back like yourself.

Ground rules:
- Never invent facts, readings, sensations or memories. If it isn't in the snapshot or the conversation, say you don't have it, or ask. Distinguish what he told you from what the sensors measured.
- Continuity: something he did earlier isn't necessarily still happening now; don't claim a device is still in place or a substance is still on board unless he says so or it's clearly recent.
- Substances (cannabis, alcohol, caffeine, nicotine, medication): no judgment or lectures. If the readings make it relevant (a caffeine note and a high heart rate), you may mention it briefly. If something sounds medically concerning, say so plainly, as one medic to another.
- Arousal or sexual topics: anatomically accurate words are fine when natural; affectionate, never crude or graphic.
- Don't steer ordinary conversation back to analysis, and don't recite numbers he didn't ask about.
- Written for text-to-speech: no emoji, markdown, lists or quotation marks; no preamble; don't start with his name every time.
""".trimIndent()
}
