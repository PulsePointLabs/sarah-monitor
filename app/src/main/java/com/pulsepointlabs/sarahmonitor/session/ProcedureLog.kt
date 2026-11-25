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
    val erection: String? = null,
    /** cannabis, alcohol, caffeine, nicotine, medication or other. */
    val substance: String? = null,
    /** What and how much, in his words: "indica vape, two hits", "second beer", "16 oz cold brew". */
    val substanceDetail: String? = null,
    /** How strongly he feels it, 0-10 (buzzed / high / intoxicated / wired), only when he gave a number or a clear word. */
    val effect: Int? = null
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
        substance?.let { put("substance", it) }
        substanceDetail?.let { put("substance_detail", it) }
        effect?.let { put("effect_0_10", it) }
    }

    /** In Ben's units (US): "+8 oz · depth 4.5 in · urge 6/10". Stored values stay in mL and cm. */
    fun short(): String = buildList {
        totalMl?.let { add("${UsUnits.volume(it)} in") }
        addedMl?.let { add("+${UsUnits.volume(it)}") }
        depthCm?.let { add("depth ${UsUnits.depth(it)}") }
        urge?.let { add("urge $it/10") }
        discomfort?.let { add("discomfort $it/10") }
        arousal?.let { add("arousal $it/10") }
        fullness?.let { add("fullness $it/10") }
        erection?.let { add("erection: $it") }
        substance?.let { add(it + (substanceDetail?.let { d -> " ($d)" } ?: "")) }
        effect?.let { add("feeling it $it/10") }
    }.joinToString(" · ")

    /** Said back out loud: "16 ounces in, depth 4.5 inches, urge 6 out of 10". */
    fun spoken(): String = buildList {
        totalMl?.let { add("${UsUnits.spokenVolume(it)} in") }
        addedMl?.let { add("${UsUnits.spokenVolume(it)} more") }
        depthCm?.let { add("depth ${Measures.fmt(UsUnits.inches(it))} inches") }
        urge?.let { add("urge $it out of 10") }
        discomfort?.let { add("discomfort $it out of 10") }
        arousal?.let { add("arousal $it out of 10") }
        fullness?.let { add("fullness $it out of 10") }
        erection?.let { add("erection $it") }
        substanceDetail?.let { add(it) } ?: substance?.let { add(it) }
        effect?.let { add("feeling it $it out of 10") }
    }.joinToString(", ")

    /** The same numbers in US units, for Sarah (so she talks in ounces and inches). */
    fun toUsJson(): JSONObject = JSONObject().apply {
        totalMl?.let { put("total", UsUnits.volumeBoth(it)) }
        addedMl?.let { put("added", UsUnits.volumeBoth(it)) }
        depthCm?.let { put("depth_in", UsUnits.inches(it)) }
        urge?.let { put("urge_0_10", it) }
        discomfort?.let { put("discomfort_0_10", it) }
        arousal?.let { put("arousal_0_10", it) }
        fullness?.let { put("fullness_0_10", it) }
        erection?.let { put("erection", it) }
        substance?.let { put("substance", it) }
        substanceDetail?.let { put("substance_detail", it) }
        effect?.let { put("effect_0_10", it) }
    }

    /** Fill gaps from [other] (Claude's read first, keyword read for anything it left out). */
    fun orElse(other: Measures): Measures {
        // Volume comes as a pair: Claude's total-vs-added call wins whole, never mixed with the keyword guess.
        val hasVolume = totalMl != null || addedMl != null
        return Measures(
        if (hasVolume) totalMl else other.totalMl, if (hasVolume) addedMl else other.addedMl, depthCm ?: other.depthCm,
        urge ?: other.urge, discomfort ?: other.discomfort, arousal ?: other.arousal, fullness ?: other.fullness, erection ?: other.erection,
        substance ?: other.substance, substanceDetail ?: other.substanceDetail, effect ?: other.effect
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
                o.optString("erection").trim().takeIf { it.isNotBlank() && it != "null" }?.take(40),
                o.optString("substance").trim().lowercase().takeIf { it.isNotBlank() && it != "null" }?.take(20),
                o.optString("substance_detail").trim().takeIf { it.isNotBlank() && it != "null" }?.take(60),
                score("effect_0_10")
            )
        }
    }
}

/**
 * Ben is in the US: everything he sees or hears is in inches, fluid ounces, quarts and cc. Measurements are stored in
 * mL and cm (one unit for the research data) and converted only for display and for Sarah.
 */
object UsUnits {
    const val ML_PER_OZ = 29.5735
    const val CM_PER_IN = 2.54

    fun inches(cm: Double): Double = (cm / CM_PER_IN * 10).roundToInt() / 10.0
    fun oz(ml: Int): Double = (ml / ML_PER_OZ * 2).roundToInt() / 2.0

    /** Small amounts (lube, balloon) in cc; enema-size amounts in ounces, with quarts past a quart. */
    fun volume(ml: Int): String = when {
        ml < 120 -> "$ml cc"
        ml < 946 -> "${Measures.fmt(oz(ml))} oz"
        else -> "${Measures.fmt(oz(ml))} oz (${Measures.fmt((ml / 946.353 * 10).roundToInt() / 10.0)} qt)"
    }
    fun spokenVolume(ml: Int): String = if (ml < 120) "$ml cc" else "${Measures.fmt(oz(ml))} ounces"
    fun volumeBoth(ml: Int): String = "${Measures.fmt(oz(ml))} oz = $ml cc"
    fun depth(cm: Double): String = "${Measures.fmt(inches(cm))} in"
}

/** One logged event as Sarah remembers it for the running picture. */
data class LoggedEvent(val timestampMs: Long, val types: Set<String>, val note: String, val measures: Measures = Measures())

/** Pure procedure bookkeeping (unit-tested): reading numbers from notes, and the running state of the session. */
object ProcedureLog {
    private val NUM_WORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "sixteen" to 16, "twenty" to 20, "twenty four" to 24, "thirty" to 30, "thirty two" to 32, "forty eight" to 48, "sixty four" to 64,
        "a hundred" to 100, "one hundred" to 100, "two hundred" to 200, "two fifty" to 250, "three hundred" to 300,
        "four hundred" to 400, "five hundred" to 500, "six hundred" to 600, "seven fifty" to 750, "seven hundred" to 700,
        "eight hundred" to 800, "nine hundred" to 900, "a thousand" to 1000, "one thousand" to 1000
    )
    private const val N = "(\\d+(?:\\.\\d+)?|a hundred|one hundred|two hundred|two fifty|three hundred|four hundred|five hundred|six hundred|seven fifty|seven hundred|eight hundred|nine hundred|a thousand|one thousand|twenty four|thirty two|forty eight|sixty four|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|sixteen|twenty|thirty)"

    private fun num(s: String): Double? = s.toDoubleOrNull() ?: NUM_WORDS[s]?.toDouble()

    private val ML = Regex("\\b$N\\s*(?:ml|mls|milliliters?|millilitres?|mils?|cc|ccs)\\b")
    private val LITRES = Regex("\\b(?:(half) (?:a )?(?:liter|litre)|$N\\s*(?:liters?|litres?|l)\\b|(a) (?:liter|litre)\\b)")
    private val OZ = Regex("\\b$N\\s*(?:fl\\.?\\s*|fluid\\s+)?(?:oz|ounces?)\\b")
    private val QUARTS = Regex("\\b(?:(half) (?:a )?quart|$N\\s*quarts?\\b|(a) quart\\b)")
    private val PINTS = Regex("\\b(?:(half) (?:a )?pint|$N\\s*pints?\\b|(a) pint\\b)")
    private val CUPS = Regex("\\b(?:(half) (?:a )?cup|$N\\s*cups?\\b|(a) cup\\b)")
    private fun unitAmount(r: Regex, t: String): Double? = r.find(t)?.let { m ->
        when { m.groupValues[1].isNotBlank() -> 0.5; m.groupValues[3].isNotBlank() -> 1.0; else -> num(m.groupValues[2]) }
    }
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

    // Which kind of substance a note is about. Cannabis first: "vape" alone reads as nicotine, "THC vape" as cannabis.
    private val SUBSTANCES = listOf(
        "cannabis" to Regex("\\b(indica|sativa|hybrid strain|weed|cannabis|marijuana|thc|cbd|edible|gumm(?:y|ies)|joint|blunt|bong|dab|cart|stoned|getting high|i'm high|pretty high)\\b"),
        "alcohol" to Regex("\\b(beers?|wine|liquor|whiske?y|vodka|bourbon|rum|tequila|seltzers?|drunk|buzzed|tipsy|alcohol|intoxicated|hungover)\\b"),
        "caffeine" to Regex("\\b(coffee|caffeine|espresso|energy drink|monster|red bull|cold brew|pre-?workout|tea|soda|coke|mountain dew)\\b"),
        "nicotine" to Regex("\\b(nicotine|vape|vaping|zyns?|pouch(?:es)?|cigarettes?|cigs?|dip|snus|smoke break)\\b"),
        "medication" to Regex("\\b(medication|meds|pills?|ibuprofen|tylenol|acetaminophen|advil|benadryl|melatonin|gabapentin|adderall|vyvanse|ritalin|xanax|ativan|klonopin|zoloft|lexapro|wellbutrin|trazodone|cialis|viagra|sildenafil|tadalafil)\\b")
    )

    fun substanceIn(note: String): String? { val t = note.lowercase(); return SUBSTANCES.firstOrNull { it.second.containsMatchIn(t) }?.first }

    /** Numbers from a note, the plain way (no network). */
    fun parseLocal(note: String): Measures {
        val t = note.lowercase().replace(",", "")
        var total: Int? = null; var added: Int? = null
        val ml = ML.find(t)?.let { num(it.groupValues[1]) }
            ?: OZ.find(t)?.let { num(it.groupValues[1]) }?.times(UsUnits.ML_PER_OZ)
            ?: unitAmount(QUARTS, t)?.times(946.353)
            ?: unitAmount(PINTS, t)?.times(473.176)
            ?: unitAmount(CUPS, t)?.times(236.588)
            ?: LITRES.find(t)?.let { m -> when { m.groupValues[1].isNotBlank() -> 0.5; m.groupValues[3].isNotBlank() -> 1.0; else -> num(m.groupValues[2]) }?.times(1000) }
        ml?.roundToInt()?.takeIf { it in 1..5_000 }?.let { v -> if (ADDED.containsMatchIn(t) && !TOTAL.containsMatchIn(t)) added = v else total = v }
        val depth = CM.find(t)?.let { num(it.groupValues[1]) } ?: INCH.find(t)?.let { num(it.groupValues[1])?.times(UsUnits.CM_PER_IN) }
        return Measures(
            total, added, depth?.takeIf { it > 0 && it < 100 }?.let { (it * 10).roundToInt() / 10.0 },
            score(URGE, t), score(DISCOMFORT, t), score(AROUSAL, t), score(FULLNESS, t),
            ERECTION.firstOrNull { it.first.containsMatchIn(t) }?.second,
            substanceIn(t)
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
        if (anyVolume) { o.put("instilled_total_oz", UsUnits.oz(total)); o.put("instilled_total_cc", total) }
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
        lastMeasure { it.depthCm?.let(UsUnits::inches) }?.let { o.put("depth_in_last_stated", it) }
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
                .apply { if (!e.measures.empty) put("measures", e.measures.toUsJson()) })
        }
    }

    // A question for Sarah, not a note to file: "how long have I been holding?", "what's my heart rate", "tell me…".
    private val QUESTION_START = Regex("^(?:(?:so|okay|ok|and|hey|um|uh)\\s+)?(?:what|what's|whats|how|how's|hows|is|are|am i|can|could|should|would|will|do|does|did|why|when|where|which|who|tell me|remind me|give me|read me|any idea|any thoughts)\\b(?!')")

    fun isQuestion(command: String): Boolean {
        val t = command.trim().lowercase()
        return t.endsWith("?") || QUESTION_START.containsMatchIn(t)
    }
}
