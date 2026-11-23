package com.pulsepointlabs.sarahmonitor.session

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Structured details for a session: how you were going in, every device / product used (with its specifics),
 * how it went, and the video it lines up with. Stored as JSON on the session (sessions.details) so new fields
 * never need a database change. Field definitions below drive the forms, review and export alike.
 */

enum class FieldKind { Choice, Multi, Number, Text, Toggle, Scale }

/**
 * One form field. The essentials show by default; [more] fields sit behind "More details". [showIf] hides a
 * field until it applies (ejaculate questions only after a climax with ejaculation, "where?" only with discomfort).
 */
data class FieldSpec(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val options: List<String> = emptyList(),
    val unit: String? = null,
    val more: Boolean = false,
    val hint: String? = null,
    val showIf: ((Map<String, String>) -> Boolean)? = null
) {
    fun applies(values: Map<String, String>) = showIf?.invoke(values) ?: true
}

private fun on(key: String): (Map<String, String>) -> Boolean = { it[key] == "true" }
private fun eq(key: String, v: String): (Map<String, String>) -> Boolean = { it[key] == v }
private fun above0(key: String): (Map<String, String>) -> Boolean = { (it[key]?.toIntOrNull() ?: 0) > 0 }
private fun has(key: String, vararg any: String): (Map<String, String>) -> Boolean = { m -> m[key].orEmpty().split('|').any { it in any } }

private fun choice(key: String, label: String, vararg o: String, more: Boolean = false, showIf: ((Map<String, String>) -> Boolean)? = null) = FieldSpec(key, label, FieldKind.Choice, o.toList(), more = more, showIf = showIf)
private fun multi(key: String, label: String, vararg o: String, more: Boolean = false, showIf: ((Map<String, String>) -> Boolean)? = null) = FieldSpec(key, label, FieldKind.Multi, o.toList(), more = more, showIf = showIf)
private fun number(key: String, label: String, unit: String, more: Boolean = false, showIf: ((Map<String, String>) -> Boolean)? = null) = FieldSpec(key, label, FieldKind.Number, unit = unit, more = more, showIf = showIf)
private fun text(key: String, label: String, hint: String? = null, more: Boolean = false, showIf: ((Map<String, String>) -> Boolean)? = null) = FieldSpec(key, label, FieldKind.Text, more = more, hint = hint, showIf = showIf)
private fun toggle(key: String, label: String, more: Boolean = false) = FieldSpec(key, label, FieldKind.Toggle, more = more)
private fun scale(key: String, label: String, hint: String? = null, more: Boolean = false, showIf: ((Map<String, String>) -> Boolean)? = null) = FieldSpec(key, label, FieldKind.Scale, more = more, hint = hint, showIf = showIf)

/** Same five words as the event check-in, so "how you feel" means one thing everywhere. */
val FEELING = arrayOf("Awful", "Low", "OK", "Good", "Great")
private val LUBES = arrayOf("Water-based", "Silicone", "Hybrid", "Oil", "Lidocaine gel", "Sterile surgical", "Saliva", "None")
private val PADS = arrayOf("Glans", "Frenulum", "Shaft", "Base", "Scrotum", "Perineum", "Inner thigh", "Lower abdomen", "Buttock", "Anal electrode", "Urethral electrode")
private val MATERIALS = arrayOf("Silicone", "TPE", "Glass", "Steel", "ABS", "Other")

/** A device or product used in a session: a few key facts up front, the rest under "More details". */
enum class GearType(val label: String, val lane: Set<Lane>, val fields: List<FieldSpec>) {
    Foley("Foley catheter", setOf(Lane.Exploration), listOf(
        number("size_fr", "Size", "Fr"),
        number("balloon_ml", "Balloon", "mL"),
        choice("material", "Material", "Silicone", "Latex", "Coated latex", "Hydrophilic", "PVC"),
        toggle("lube_instilled", "Lube instilled into urethra"),
        choice("lube_type", "Instilled lube", *LUBES, showIf = on("lube_instilled")),
        number("lube_ml", "Amount", "mL", showIf = on("lube_instilled")),
        choice("tip", "Tip", "Straight", "Coudé", "Council", more = true),
        choice("balloon_fluid", "Balloon fluid", "Sterile water", "Saline", "Air", more = true),
        choice("drainage", "Drainage", "Leg bag", "Night bag", "Valve", "Plug", "Open", more = true),
        number("depth_cm", "Inserted length", "cm", more = true),
        text("notes", "Notes", more = true)
    )),
    Sound("Urethral sound", setOf(Lane.Exploration), listOf(
        choice("style", "Style", "Hegar", "Dittel", "Van Buren", "Rosebud", "Plug", "Other"),
        number("size_mm", "Size", "mm"),
        choice("lube_type", "Lube", *LUBES),
        choice("material", "Material", "Steel", "Titanium", "Silicone", more = true),
        number("lube_ml", "Lube instilled", "mL", more = true),
        number("depth_cm", "Depth reached", "cm", more = true),
        toggle("estim", "Used as an e-stim electrode", more = true),
        text("notes", "Notes", more = true)
    )),
    Enema("Enema", setOf(Lane.Exploration), listOf(
        number("volume_ml", "Volume", "mL"),
        multi("solution", "Solution", "Water", "Saline", "Castile soap", "Other"),
        text("additive", "How much soap / additive", "e.g. 1 tbsp per litre", showIf = has("solution", "Castile soap", "Other")),
        choice("temperature", "Temperature", "Warm", "Body temp", "Room temp", "Cool"),
        choice("nozzle", "Nozzle", "Rigid short", "Rigid long", "Soft flexible", "Deep tube", "Inflatable", "Double balloon", "Bulb tip"),
        choice("delivery", "Delivery", "Bag", "Bulb", "Shower", "Syringe", more = true),
        choice("flow", "Flow", "Slow", "Moderate", "Fast", more = true),
        number("nozzle_depth_cm", "Nozzle depth", "cm", more = true),
        number("retention_min", "Held for", "min", more = true),
        number("rounds", "Rounds", "×", more = true),
        text("notes", "Notes", more = true)
    )),
    Lubricant("Lube", setOf(Lane.Exploration, Lane.Sexual), listOf(
        choice("type", "Type", *LUBES),
        text("brand", "Product", more = true)
    )),
    Sleeve("Sleeve / stroker", setOf(Lane.Sexual), listOf(
        text("brand", "Which one"),
        choice("tightness", "Fit", "Loose", "Snug", "Tight", more = true),
        toggle("warmed", "Warmed", more = true)
    )),
    AnalToy("Anal toy", setOf(Lane.Sexual, Lane.Exploration), listOf(
        choice("kind", "Kind", "Plug", "Dilator", "Dildo", "Prostate", "Vibrator", "Beads"),
        text("size", "Size", "e.g. 3.5 cm / #4"),
        choice("material", "Material", *MATERIALS, more = true),
        toggle("vibration", "Vibration on", more = true)
    )),
    Vibrator("Vibrator / wand", setOf(Lane.Sexual), listOf(
        text("placement", "Where", "e.g. frenulum, perineum"),
        text("setting", "Setting", more = true)
    )),
    Estim("E-stim (Howl)", setOf(Lane.Sexual, Lane.Exploration), listOf(
        multi("pads_a", "Channel A", *PADS),
        multi("pads_b", "Channel B", *PADS),
        text("mode", "Mode / pattern"),
        number("max_a", "A max", "level"),
        number("max_b", "B max", "level"),
        choice("electrode_kind", "Electrodes", "Pads", "Rubber", "Loops", "Insertable", more = true),
        number("start_a", "A start", "level", more = true),
        number("start_b", "B start", "level", more = true),
        number("frequency_hz", "Frequency", "Hz", more = true)
    )),
    Other("Something else", setOf(Lane.Sexual, Lane.Exploration), listOf(
        text("name", "What it is"),
        text("details", "Details", more = true)
    ));

    /** Short summary for lists: "16 Fr · 10 mL · Silicone". */
    fun summary(v: Map<String, String>): String = fields.filter { it.applies(v) && it.key != "notes" && it.kind != FieldKind.Toggle && !v[it.key].isNullOrBlank() }
        .take(4).joinToString(" · ") { f -> v[f.key]!!.replace("|", ", ") + (f.unit?.let { u -> if (u == "×") "×" else " $u" } ?: "") }
}

/** Which form fields a category gets. */
enum class Lane { General, Sexual, Exploration }

fun SessionCategory.lanes(): Set<Lane> = when (this) {
    SessionCategory.Masturbation, SessionCategory.Sexual -> setOf(Lane.Sexual)
    SessionCategory.Exploration -> setOf(Lane.Exploration)
    SessionCategory.Hybrid -> setOf(Lane.General, Lane.Sexual, Lane.Exploration)
    else -> setOf(Lane.General)
}

object DetailForms {
    val pre: List<FieldSpec> = listOf(
        choice("feeling", "How you feel", *FEELING),
        scale("stress", "Stress", "0 none · 10 extreme"),
        scale("arousal", "Arousal"),
        text("notes", "Notes / plan"),
        scale("energy", "Energy", more = true),
        choice("hydration", "Hydration", "Low", "OK", "Good", more = true),
        choice("last_ejaculation", "Last ejaculation", "Today", "Yesterday", "2–3 days", "4–7 days", "Over a week", more = true),
        multi("substances", "Last few hours", "Caffeine", "Nicotine", "Alcohol", "THC", "Medication", more = true)
    )

    private val climaxed: (Map<String, String>) -> Boolean = { it["outcome"].orEmpty().startsWith("Climax") }

    val postSexual: List<FieldSpec> = listOf(
        choice("outcome", "Outcome", "Climax with ejaculation", "Climax, dry", "Edged only", "Stopped early"),
        scale("climax_intensity", "Climax intensity", showIf = climaxed),
        choice("ejac_volume", "Volume", "Small", "Moderate", "Large", showIf = eq("outcome", "Climax with ejaculation")),
        choice("ejac_force", "Force", "Dribble", "Steady", "Spurts", "Strong spurts", showIf = eq("outcome", "Climax with ejaculation")),
        choice("ejac_consistency", "Consistency", "Watery", "Normal", "Thick", "Gel / clumpy", showIf = eq("outcome", "Climax with ejaculation")),
        choice("ejac_color", "Colour", "Clear", "White / grey", "Yellowish", "Pink / red", showIf = eq("outcome", "Climax with ejaculation")),
        scale("satisfaction", "Satisfaction"),
        scale("erection_stability", "Erection stability", "0 couldn't keep it · 10 firm throughout"),
        scale("discomfort", "Discomfort"),
        text("discomfort_where", "Where / what", showIf = above0("discomfort")),
        choice("feeling_after", "How you feel now", *FEELING),
        text("notes", "What you noticed"),
        number("contractions", "Contractions felt", "×", more = true)
    )

    val postExploration: List<FieldSpec> = listOf(
        choice("result", "How it went", "As planned", "Partly", "Stopped early"),
        scale("comfort", "Comfort", "0 very uncomfortable · 10 completely fine"),
        scale("max_discomfort", "Worst discomfort"),
        text("discomfort_where", "Where / what", showIf = above0("max_discomfort")),
        multi("aftereffects", "After-effects", "None", "Irritation", "Burning", "Spotting", "Cramping"),
        choice("feeling_after", "How you feel now", *FEELING),
        text("notes", "What you noticed")
    )

    val postGeneral: List<FieldSpec> = listOf(
        choice("feeling_after", "How you feel now", *FEELING),
        scale("stress_after", "Stress now"),
        text("notes", "What you noticed")
    )

    fun post(category: SessionCategory?): List<FieldSpec> {
        val lanes = category?.lanes() ?: setOf(Lane.General)
        return buildList {
            if (Lane.Sexual in lanes) addAll(postSexual)
            if (Lane.Exploration in lanes) postExploration.forEach { f -> if (none { it.key == f.key }) add(f) }
            if (isEmpty()) addAll(postGeneral)
        }
    }

    fun gearFor(category: SessionCategory?): List<GearType> {
        val lanes = category?.lanes() ?: setOf(Lane.General)
        return GearType.entries.filter { g -> g.lane.any { it in lanes } }
    }
}

data class GearItem(val id: String = UUID.randomUUID().toString(), val type: GearType, val values: Map<String, String> = emptyMap()) {
    val label: String get() = type.label + (type.summary(values).takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
}

/** The OBS recording a session lines up with (wall-clock times, so video time = t − startedAtMs − pauses). */
data class VideoLink(
    val source: String = "obs",
    val startedAtMs: Long,
    val stoppedAtMs: Long? = null,
    val path: String? = null,
    val pauses: List<Pair<Long, Long?>> = emptyList()
) {
    /** Position in the video file for a wall-clock moment, or null outside the recording. */
    fun videoOffsetMs(t: Long): Long? {
        if (t < startedAtMs || (stoppedAtMs != null && t > stoppedAtMs)) return null
        var off = t - startedAtMs
        pauses.forEach { (a, b) -> val end = b ?: t; if (a < t) off -= (minOf(end, t) - a).coerceAtLeast(0) }
        return off.coerceAtLeast(0)
    }
}

data class SessionDetails(
    val pre: Map<String, String> = emptyMap(),
    val gear: List<GearItem> = emptyList(),
    val post: Map<String, String> = emptyMap(),
    val video: VideoLink? = null
) {
    val empty: Boolean get() = pre.isEmpty() && gear.isEmpty() && post.isEmpty() && video == null

    fun toJson(): String = JSONObject().put("v", 1)
        .put("pre", JSONObject(pre))
        .put("gear", JSONArray().apply { gear.forEach { put(JSONObject().put("id", it.id).put("type", it.type.name).put("values", JSONObject(it.values))) } })
        .put("post", JSONObject(post))
        .apply {
            video?.let { v ->
                put("video", JSONObject().put("source", v.source).put("started_at_ms", v.startedAtMs).put("stopped_at_ms", v.stoppedAtMs ?: JSONObject.NULL)
                    .put("path", v.path ?: JSONObject.NULL)
                    .put("pauses", JSONArray().apply { v.pauses.forEach { (a, b) -> put(JSONArray().put(a).put(b ?: JSONObject.NULL)) } }))
            }
        }.toString()

    /** Readable lines for exports and Sarah's context. */
    fun describe(category: SessionCategory?): List<String> = buildList {
        fun fmt(specs: List<FieldSpec>, values: Map<String, String>) = specs.filter { it.applies(values) }.mapNotNull { f ->
            val v = values[f.key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when (f.kind) {
                FieldKind.Toggle -> if (v == "true") f.label else null
                FieldKind.Scale -> "${f.label} $v/10"
                FieldKind.Multi -> "${f.label}: ${v.replace("|", ", ")}"
                else -> "${f.label}: $v${f.unit?.let { u -> if (u == "×") "" else " $u" } ?: ""}"
            }
        }
        fmt(DetailForms.pre, pre).takeIf { it.isNotEmpty() }?.let { add("Before: " + it.joinToString("; ")) }
        gear.forEach { g -> add("Used ${g.type.label}: " + fmt(g.type.fields, g.values).joinToString("; ").ifBlank { "no details" }) }
        fmt(DetailForms.post(category), post).takeIf { it.isNotEmpty() }?.let { add("After: " + it.joinToString("; ")) }
        video?.let { v -> add("Video: OBS recording" + (v.path?.let { " $it" } ?: "") + (if (v.pauses.isNotEmpty()) " (${v.pauses.size} pause${if (v.pauses.size == 1) "" else "s"})" else "")) }
    }

    companion object {
        private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { optString(it) }.filterValues { it.isNotBlank() && it != "null" }

        fun parse(json: String?): SessionDetails {
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return SessionDetails()
            val gear = o.optJSONArray("gear")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val g = a.optJSONObject(i) ?: return@mapNotNull null
                    val type = runCatching { GearType.valueOf(g.getString("type")) }.getOrNull() ?: return@mapNotNull null
                    GearItem(g.optString("id").ifBlank { UUID.randomUUID().toString() }, type, g.optJSONObject("values")?.toStringMap() ?: emptyMap())
                }
            } ?: emptyList()
            val video = o.optJSONObject("video")?.let { v ->
                VideoLink(
                    v.optString("source", "obs"), v.getLong("started_at_ms"), if (v.isNull("stopped_at_ms")) null else v.optLong("stopped_at_ms"),
                    if (v.isNull("path")) null else v.optString("path"),
                    v.optJSONArray("pauses")?.let { p -> (0 until p.length()).mapNotNull { i -> p.optJSONArray(i)?.let { it.getLong(0) to (if (it.isNull(1)) null else it.getLong(1)) } } } ?: emptyList()
                )
            }
            return SessionDetails(o.optJSONObject("pre")?.toStringMap() ?: emptyMap(), gear, o.optJSONObject("post")?.toStringMap() ?: emptyMap(), video)
        }
    }
}

/** Remembers the devices used last time per session type; setup offers them (one tap), never pre-fills. */
object LastSetup {
    private fun prefs(c: android.content.Context) = c.applicationContext.getSharedPreferences("sarahmonitor_last_setup", android.content.Context.MODE_PRIVATE)

    fun remember(c: android.content.Context, category: SessionCategory, d: SessionDetails) {
        prefs(c).edit().putString(category.name, SessionDetails(gear = d.gear).toJson()).apply()
    }

    fun lastGear(c: android.content.Context, category: SessionCategory): List<GearItem> =
        SessionDetails.parse(prefs(c).getString(category.name, null)).gear.map { it.copy(id = UUID.randomUUID().toString()) }
}
