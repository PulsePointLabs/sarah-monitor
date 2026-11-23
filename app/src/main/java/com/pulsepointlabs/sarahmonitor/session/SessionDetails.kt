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

/** One form field. [showIf] = (other key, value) that must be set for this field to apply. */
data class FieldSpec(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val options: List<String> = emptyList(),
    val unit: String? = null,
    val showIf: Pair<String, String>? = null,
    val hint: String? = null
)

private fun choice(key: String, label: String, vararg o: String, showIf: Pair<String, String>? = null) = FieldSpec(key, label, FieldKind.Choice, o.toList(), showIf = showIf)
private fun multi(key: String, label: String, vararg o: String, showIf: Pair<String, String>? = null) = FieldSpec(key, label, FieldKind.Multi, o.toList(), showIf = showIf)
private fun number(key: String, label: String, unit: String, showIf: Pair<String, String>? = null) = FieldSpec(key, label, FieldKind.Number, unit = unit, showIf = showIf)
private fun text(key: String, label: String, hint: String? = null, showIf: Pair<String, String>? = null) = FieldSpec(key, label, FieldKind.Text, showIf = showIf, hint = hint)
private fun toggle(key: String, label: String) = FieldSpec(key, label, FieldKind.Toggle)
private fun scale(key: String, label: String, hint: String? = null) = FieldSpec(key, label, FieldKind.Scale, hint = hint)

private val LUBES = arrayOf("Water-based", "Silicone", "Hybrid", "Oil", "Lidocaine gel", "Sterile surgical", "Saliva", "None")
private val PADS = arrayOf("Glans", "Frenulum", "Penile shaft", "Base of penis", "Scrotum", "Perineum", "Inner thigh", "Lower abdomen", "Buttock", "Anal electrode", "Urethral electrode", "Other")
private val MATERIALS = arrayOf("Silicone", "TPE", "Glass", "Stainless steel", "ABS plastic", "Other")

/** A device or product used in a session, with the details worth recording for it. */
enum class GearType(val label: String, val lane: Set<Lane>, val fields: List<FieldSpec>) {
    Foley("Foley catheter", setOf(Lane.Exploration), listOf(
        number("size_fr", "Size", "Fr"),
        choice("material", "Material", "Silicone", "Latex", "Silicone-coated latex", "Hydrophilic-coated", "PVC / vinyl"),
        choice("tip", "Tip", "Straight", "Coudé", "Council", "Tiemann"),
        number("balloon_ml", "Balloon fill", "mL"),
        choice("balloon_fluid", "Balloon filled with", "Sterile water", "Saline", "Air", "Not inflated"),
        toggle("lube_instilled", "Lubricant instilled into urethra"),
        choice("lube_type", "Instilled lubricant", *LUBES, showIf = "lube_instilled" to "true"),
        number("lube_ml", "Amount instilled", "mL", showIf = "lube_instilled" to "true"),
        choice("lube_method", "Instilled with", "Syringe", "Applicator tip", "Prefilled lube syringe", showIf = "lube_instilled" to "true"),
        choice("drainage", "Drainage", "Leg bag", "Night bag", "Catheter valve", "Plug / spigot", "Open"),
        number("depth_cm", "Inserted length", "cm"),
        text("notes", "Notes")
    )),
    Sound("Urethral sound", setOf(Lane.Exploration), listOf(
        choice("style", "Style", "Hegar", "Dittel", "Van Buren", "Rosebud", "Pratt", "Plug / penis plug", "Other"),
        number("size_mm", "Size", "mm"),
        number("size_fr", "Size", "Fr"),
        choice("material", "Material", "Stainless steel", "Titanium", "Silicone", "Other"),
        toggle("lube_instilled", "Lubricant instilled into urethra"),
        choice("lube_type", "Lubricant", *LUBES),
        number("lube_ml", "Amount instilled", "mL", showIf = "lube_instilled" to "true"),
        number("depth_cm", "Depth reached", "cm"),
        toggle("estim", "Used as an e-stim electrode"),
        text("notes", "Notes")
    )),
    Enema("Enema", setOf(Lane.Exploration), listOf(
        number("volume_ml", "Volume instilled", "mL"),
        multi("solution", "Solution", "Plain water", "Saline (isotonic)", "Castile soap", "Mineral oil", "Other additive"),
        text("additive", "Additive / amount", "e.g. 1 tbsp castile soap per litre"),
        choice("isotonic", "Tonicity", "Isotonic", "Hypotonic (plain water)", "Hypertonic", "Not sure"),
        choice("temperature", "Temperature", "Warm", "Body temperature", "Room temperature", "Cool", "Cold"),
        choice("delivery", "Delivery", "Bag & tube", "Bulb", "Shower / hose", "Syringe", "Other"),
        choice("nozzle", "Nozzle", "Rigid short", "Rigid long", "Soft flexible", "Deep / colon tube", "Inflatable (single balloon)", "Inflatable (double balloon)", "Bulb tip", "Other"),
        number("nozzle_depth_cm", "Nozzle depth", "cm"),
        choice("flow", "Flow", "Slow", "Moderate", "Fast", "Varied"),
        number("retention_min", "Retention target", "min"),
        choice("lube_type", "Lubricant", *LUBES),
        number("rounds", "Rounds", "×"),
        text("notes", "Comfort / notes")
    )),
    Lubricant("Lubricant", setOf(Lane.Exploration, Lane.Sexual), listOf(
        choice("type", "Type", *LUBES),
        text("brand", "Brand / product"),
        choice("amount", "Amount", "Light", "Moderate", "Generous", "Reapplied"),
        text("notes", "Notes")
    )),
    Sleeve("Sleeve / stroker", setOf(Lane.Sexual), listOf(
        choice("material", "Material", "Silicone", "TPE", "Cyberskin", "Other"),
        text("brand", "Brand / model"),
        choice("tightness", "Fit", "Loose", "Snug", "Tight"),
        toggle("warmed", "Warmed"),
        text("notes", "Notes")
    )),
    AnalToy("Anal toy", setOf(Lane.Sexual, Lane.Exploration), listOf(
        choice("kind", "Kind", "Plug", "Dilator", "Dildo", "Prostate massager", "Vibrator", "Beads", "Other"),
        text("size", "Size", "e.g. 3.5 cm diameter / set #4"),
        choice("material", "Material", *MATERIALS),
        toggle("vibration", "Vibration on"),
        choice("lube_type", "Lubricant", *LUBES),
        text("notes", "Notes")
    )),
    Vibrator("Vibrator / wand", setOf(Lane.Sexual), listOf(
        text("model", "Model"),
        text("placement", "Placement", "e.g. frenulum, perineum"),
        text("setting", "Setting / pattern"),
        text("notes", "Notes")
    )),
    Estim("E-stim (Howl)", setOf(Lane.Sexual, Lane.Exploration), listOf(
        multi("pads_a", "Channel A pads", *PADS),
        multi("pads_b", "Channel B pads", *PADS),
        choice("electrode_kind", "Electrodes", "Adhesive pads", "Conductive rubber", "Loops / rings", "Insertable", "Mixed"),
        text("mode", "Mode / pattern"),
        number("start_a", "Channel A start", "level"),
        number("max_a", "Channel A max", "level"),
        number("start_b", "Channel B start", "level"),
        number("max_b", "Channel B max", "level"),
        number("frequency_hz", "Frequency", "Hz"),
        text("notes", "Notes")
    )),
    Other("Other device", setOf(Lane.Sexual, Lane.Exploration), listOf(
        text("name", "What it is"),
        text("details", "Details"),
        text("notes", "Notes")
    ));

    /** Short summary for lists: "16 Fr Silicone · lube 10 mL". */
    fun summary(v: Map<String, String>): String = fields.filter { it.key != "notes" && !v[it.key].isNullOrBlank() && it.kind != FieldKind.Toggle }
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
        scale("feeling", "How you feel", "0 awful · 10 great"),
        scale("stress", "Stress", "0 none · 10 extreme"),
        scale("arousal", "Arousal right now"),
        scale("energy", "Energy"),
        choice("hydration", "Hydration", "Low", "OK", "Well hydrated"),
        choice("last_ejaculation", "Last ejaculation", "Today", "Yesterday", "2–3 days", "4–7 days", "Over a week"),
        multi("substances", "In the last few hours", "Caffeine", "Nicotine", "Alcohol", "THC", "Medication", "None"),
        text("purpose", "Plan / what you're exploring"),
        text("notes", "Notes")
    )

    val postSexual: List<FieldSpec> = listOf(
        choice("outcome", "Outcome", "Climax with ejaculation", "Climax, no ejaculation (dry)", "Edged, no climax", "Stopped early"),
        scale("climax_intensity", "Climax intensity"),
        scale("satisfaction", "Satisfaction"),
        scale("erection_stability", "Erection stability", "0 couldn't maintain · 10 firm throughout"),
        choice("ejac_volume", "Ejaculate volume", "None", "Small", "Moderate", "Large"),
        choice("ejac_consistency", "Consistency", "Watery", "Normal", "Thick", "Clumpy / gel"),
        choice("ejac_color", "Colour", "Clear", "White / grey", "Yellowish", "Pink or red-tinged"),
        choice("ejac_force", "Force", "Dribble", "Steady flow", "Spurts", "Strong spurts"),
        number("contractions", "Contractions felt", "×"),
        scale("discomfort", "Discomfort"),
        text("discomfort_where", "Where / what"),
        scale("feeling_after", "How you feel now"),
        text("notes", "Notes")
    )

    val postExploration: List<FieldSpec> = listOf(
        choice("result", "How it went", "Completed as planned", "Partly", "Stopped early"),
        scale("comfort", "Overall comfort", "0 very uncomfortable · 10 completely comfortable"),
        scale("max_discomfort", "Worst discomfort"),
        text("discomfort_where", "Where / what"),
        choice("aftereffects", "After-effects", "None", "Mild irritation", "Burning on urination", "Spotting / blood", "Cramping", "Other"),
        number("retention_min", "Enema retention achieved", "min"),
        text("findings", "Findings / what you noticed"),
        scale("feeling_after", "How you feel now"),
        text("notes", "Notes")
    )

    val postGeneral: List<FieldSpec> = listOf(
        scale("feeling_after", "How you feel now"),
        scale("stress_after", "Stress now"),
        text("notes", "Notes")
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
        fun fmt(specs: List<FieldSpec>, values: Map<String, String>) = specs.mapNotNull { f ->
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

/** Remembers the devices used last time for each session type, so setup starts pre-filled (check-in stays blank). */
object LastSetup {
    private fun prefs(c: android.content.Context) = c.applicationContext.getSharedPreferences("sarahmonitor_last_setup", android.content.Context.MODE_PRIVATE)

    fun remember(c: android.content.Context, category: SessionCategory, d: SessionDetails) {
        prefs(c).edit().putString(category.name, SessionDetails(gear = d.gear).toJson()).apply()
    }

    fun gearFor(c: android.content.Context, category: SessionCategory): SessionDetails {
        val last = SessionDetails.parse(prefs(c).getString(category.name, null))
        return SessionDetails(gear = last.gear.map { it.copy(id = UUID.randomUUID().toString()) })
    }
}
