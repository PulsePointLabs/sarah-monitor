package com.pulsepointlabs.sarahmonitor.session

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Numbers pulled out of one note: how much went in, how deep, the scores he gave and what his erection was doing.
 * Claude fills these when it can; [ProcedureLog.parseLocal] reads the plain cases without a network. All optional.
 */
data class Measures(
    /** He stated a running total ("that's 750 in"). */
    val totalMl: Int? = null,
    /** He stated an amount added ("another 250", "250 more", "started with 500"). */
    val addedMl: Int? = null,
    /** Estimated insertion depth of the tip / nozzle / device. */
    val depthCm: Double? = null,
    val urge: Int? = null,
    val discomfort: Int? = null,
    val arousal: Int? = null,
    val fullness: Int? = null,
    /** none, partial, full, or a short phrase ("getting hard", "softening"). */
    val erection: String? = null
) {
    val empty: Boolean get() = this == Measures()

    fun toJson(): JSONObject = JSONObject().apply {
        totalMl?.let { put("total_ml", it) }
        addedMl?.let { put("added_ml", it) }
        depthCm?.let { put("depth_cm", it) }
        urge?.let { put("urge_0_10", it) }
        discomfort?.let { put("discomfort_0_10", it) }
        arousal?.let { put("arousal_0_10", it) }
        fullness?.let { put("fullness_0_10", it) }
        erection?.let { put("erection", it) }
    }

    /** "+250 mL · depth 12 cm · urge 6/10". */
    fun short(): String = buildList {
        totalMl?.let { add("$it mL in") }
        addedMl?.let { add("+$it mL") }
        depthCm?.let { add("depth ${fmt(it)} cm") }
        urge?.let { add("urge $it/10") }
        discomfort?.let { add("discomfort $it/10") }
        arousal?.let { add("arousal $it/10") }
        fullness?.let { add("fullness $it/10") }
        erection?.let { add("erection: $it") }
    }.joinToString(" · ")

    /** Fill gaps from [other] (Claude's read first, keyword read for anything it left out). */
    fun orElse(other: Measures): Measures {
        // Volume comes as a pair: Claude's total-vs-added call wins whole, never mixed with the keyword guess.
        val hasVolume = totalMl != null || addedMl != null
        return Measures(
        if (hasVolume) totalMl else other.totalMl, if (hasVolume) addedMl else other.addedMl, depthCm ?: other.depthCm,
        urge ?: other.urge, discomfort ?: other.discomfort, arousal ?: other.arousal, fullness ?: other.fullness, erection ?: other.erection
        )
    }

    companion object {
        fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else ((d * 10).roundToInt() / 10.0).toString()

        fun fromJson(o: JSONObject?): Measures {
            if (o == null) return Measures()
            fun int(k: String) = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { it.isFinite() }?.roundToInt() else null
            fun score(k: String) = int(k)?.takeIf { it in 0..10 }
            return Measures(
                int("total_ml")?.takeIf { it in 1..5_000 }, int("added_ml")?.takeIf { it in 1..5_000 },
                if (o.has("depth_cm") && !o.isNull("depth_cm")) o.optDouble("depth_cm").takeIf { it.isFinite() && it > 0 && it < 100 } else null,
                score("urge_0_10"), score("discomfort_0_10"), score("arousal_0_10"), score("fullness_0_10"),
                o.optString("erection").trim().takeIf { it.isNotBlank() && it != "null" }?.take(40)
            )
        }
    }
}

/** One logged event as Sarah remembers it for the running picture. */
data class LoggedEvent(val timestampMs: Long, val types: Set<String>, val note: String, val measures: Measures = Measures())

/** Pure procedure bookkeeping (unit-tested): reading numbers from notes, and the running state of the session. */
object ProcedureLog {
    private val NUM_WORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
        "a hundred" to 100, "one hundred" to 100, "two hundred" to 200, "two fifty" to 250, "three hundred" to 300,
        "four hundred" to 400, "five hundred" to 500, "six hundred" to 600, "seven fifty" to 750, "seven hundred" to 700,
        "eight hundred" to 800, "nine hundred" to 900, "a thousand" to 1000, "one thousand" to 1000
    )
    private const val N = "(\\d+(?:\\.\\d+)?|a hundred|one hundred|two hundred|two fifty|three hundred|four hundred|five hundred|six hundred|seven fifty|seven hundred|eight hundred|nine hundred|a thousand|one thousand|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty)"

    private fun num(s: String): Double? = s.toDoubleOrNull() ?: NUM_WORDS[s]?.toDouble()

    private val ML = Regex("\\b$N\\s*(?:ml|mls|milliliters?|millilitres?|mils?|cc|ccs)\\b")
    private val LITRES = Regex("\\b(?:(half) (?:a )?(?:liter|litre)|$N\\s*(?:liters?|litres?|l)\\b|(a) (?:liter|litre)\\b)")
    private val ADDED = Regex("\\b(another|more|additional|added|adding|extra|plus)\\b")
    private val TOTAL = Regex("\\b(total|so far|altogether|in all|all together)\\b")
    private val CM = Regex("\\b$N\\s*(?:cm|centimeters?|centimetres?)\\b")
    private val INCH = Regex("\\b$N\\s*(?:inches|inch|in\\.)\\b")
    private fun scoreFor(word: String) = Regex("\\b$word\\w*\\b[^.,;0-9]{0,25}?\\b$N(?:\\s*(?:out of|/|of)\\s*(?:10|ten))?\\b|\\b$N\\s*(?:out of|/)\\s*(?:10|ten)\\s+(?:for\\s+)?(?:the\\s+)?$word")

    private val URGE = scoreFor("urge")
    private val DISCOMFORT = scoreFor("(?:discomfort|pain|pinch|cramp)")
    private val AROUSAL = scoreFor("arous")
    private val FULLNESS = scoreFor("full")

    private fun score(r: Regex, t: String): Int? {
        val m = r.find(t) ?: return null
        val v = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return null
        return num(v)?.roundToInt()?.takeIf { it in 0..10 }
    }

    private val ERECTION = listOf(
        Regex("\\b(fully erect|full erection|rock hard|completely hard|fully hard)\\b") to "full",
        Regex("\\b(partial(?:ly)? (?:erect|hard|erection)|half (?:hard|erect|mast)|semi(?:-| )?(?:hard|erect)|chub)") to "partial",
        Regex("\\b(getting hard|getting an erection|erection (?:is )?(?:starting|coming|building)|starting to get hard|getting erect|stiffening)") to "building",
        Regex("\\b(losing (?:my |the )?erection|going soft|softening|getting soft)") to "softening",
        Regex("\\b(no erection|flaccid|not erect|completely soft|soft)\\b") to "none",
        Regex("\\b(erection|erect|hard-on|hard on)\\b") to "yes"
    )

    /** Numbers from a note, the plain way (no network). */
    fun parseLocal(note: String): Measures {
        val t = note.lowercase().replace(",", "")
        var total: Int? = null; var added: Int? = null
        val ml = ML.find(t)?.let { num(it.groupValues[1]) }
            ?: LITRES.find(t)?.let { m -> when { m.groupValues[1].isNotBlank() -> 0.5; m.groupValues[3].isNotBlank() -> 1.0; else -> num(m.groupValues[2]) }?.times(1000) }
        ml?.roundToInt()?.takeIf { it in 1..5_000 }?.let { v -> if (ADDED.containsMatchIn(t) && !TOTAL.containsMatchIn(t)) added = v else total = v }
        val depth = CM.find(t)?.let { num(it.groupValues[1]) } ?: INCH.find(t)?.let { num(it.groupValues[1])?.times(2.54) }
        return Measures(
            total, added, depth?.takeIf { it > 0 && it < 100 }?.let { (it * 10).roundToInt() / 10.0 },
            score(URGE, t), score(DISCOMFORT, t), score(AROUSAL, t), score(FULLNESS, t),
            ERECTION.firstOrNull { it.first.containsMatchIn(t) }?.second
        )
    }

    /** The running picture of a procedure from everything logged so far. */
    fun state(events: List<LoggedEvent>, now: Long): JSONObject {
        val sorted = events.sortedBy { it.timestampMs }
        fun ago(t: Long) = ((now - t) / 6_000L) / 10.0
        var total = 0; var anyVolume = false
        sorted.forEach { e ->
            e.measures.totalMl?.let { total = it; anyVolume = true }
            e.measures.addedMl?.let { total += it; anyVolume = true }
        }
        fun last(type: String) = sorted.lastOrNull { type in it.types }
        fun <T> lastMeasure(get: (Measures) -> T?): JSONObject? = sorted.lastOrNull { get(it.measures) != null }?.let { e ->
            JSONObject().put("value", get(e.measures)).put("minutes_ago", ago(e.timestampMs))
        }
        val o = JSONObject()
        if (anyVolume) o.put("instilled_total_ml", total)
        last("instill")?.let { o.put("instillation_started_minutes_ago", ago(it.timestampMs)) }
        last("instill_end")?.let { o.put("instillation_ended_minutes_ago", ago(it.timestampMs)) }
        val release = last("release")
        last("hold")?.takeIf { h -> release == null || release.timestampMs < h.timestampMs }?.let { o.put("holding_for_minutes", ago(it.timestampMs)) }
        release?.let { o.put("released_minutes_ago", ago(it.timestampMs)) }
        val removed = last("remove")
        listOf("insert", "seated").mapNotNull { last(it) }.maxByOrNull { it.timestampMs }?.let { ins ->
            if (removed == null || removed.timestampMs < ins.timestampMs) o.put("device_in_place_minutes", ago(ins.timestampMs))
        }
        removed?.let { o.put("removed_minutes_ago", ago(it.timestampMs)) }
        lastMeasure { it.depthCm }?.let { o.put("depth_cm_last_stated", it) }
        lastMeasure { it.urge }?.let { o.put("urge_last", it) }
        lastMeasure { it.discomfort }?.let { o.put("discomfort_last", it) }
        sorted.mapNotNull { it.measures.discomfort }.maxOrNull()?.let { o.put("discomfort_worst", it) }
        lastMeasure { it.arousal }?.let { o.put("arousal_last", it) }
        lastMeasure { it.fullness }?.let { o.put("fullness_last", it) }
        lastMeasure { it.erection }?.let { o.put("erection_last", it) }
        val counts = JSONObject()
        listOf("resistance", "discomfort", "urge", "sensation", "finding", "arousal", "adjust").forEach { t ->
            sorted.count { t in it.types }.takeIf { it > 0 }?.let { counts.put(t, it) }
        }
        if (counts.length() > 0) o.put("times_logged", counts)
        return o
    }

    /** The whole session's notes, oldest first, for answering questions ("what did I say the depth was?"). */
    fun timeline(events: List<LoggedEvent>, sessionStartMs: Long?, now: Long, max: Int = 40): JSONArray = JSONArray().apply {
        events.sortedBy { it.timestampMs }.takeLast(max).forEach { e ->
            put(JSONObject()
                .put("at_min", sessionStartMs?.let { ((e.timestampMs - it) / 6_000L) / 10.0 })
                .put("minutes_ago", ((now - e.timestampMs) / 6_000L) / 10.0)
                .put("types", JSONArray(e.types.toList()))
                .put("note", e.note.take(240))
                .apply { if (!e.measures.empty) put("measures", e.measures.toJson()) })
        }
    }

    // A question for Sarah, not a note to file: "how long have I been holding?", "what's my heart rate", "tell me…".
    private val QUESTION_START = Regex("^(?:(?:so|okay|ok|and|hey|um|uh)\\s+)?(?:what|what's|whats|how|how's|hows|is|are|am i|was|were|can|could|should|would|will|do|does|did|why|when|where|which|who|tell me|remind me|give me|read me|any idea|any thoughts)\\b")

    fun isQuestion(command: String): Boolean {
        val t = command.trim().lowercase()
        return t.endsWith("?") || QUESTION_START.containsMatchIn(t)
    }
}
