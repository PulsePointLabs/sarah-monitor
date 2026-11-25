package com.pulsepointlabs.sarahmonitor.gear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.pulsepointlabs.sarahmonitor.core.ClaudeText
import com.pulsepointlabs.sarahmonitor.response.ResponseSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Kinds of gear, so the catalog stays easy to browse. Stored by name. */
enum class GearCategory(val label: String) {
    Sounds("Urethral sounds"),
    Catheters("Catheter supplies"),
    Enema("Enema gear"),
    Anal("Anal toys & plugs"),
    Sleeves("Sleeves & strokers"),
    Vibrators("Vibrators & wands"),
    Estim("E-stim & TENS"),
    Sensors("Sensors & monitors"),
    Lube("Lube & prep"),
    Other("Other");

    companion object {
        fun of(name: String?): GearCategory = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Other
    }
}

/** One measurement as he'd say it: "Insertable length" → "16 in". [estimated]: read off a photo, not measured. */
data class Measurement(val label: String, val value: String, val estimated: Boolean = false)

/**
 * A device or supply he owns. Photos are files in the app's own storage (never shared). Sarah's read of it (what it
 * is, where it sits in the body, prep, cautions) is kept with it, and everything stays editable.
 */
data class CatalogItem(
    val id: String = UUID.randomUUID().toString(),
    val category: GearCategory = GearCategory.Other,
    val name: String = "",
    val photos: List<String> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    val material: String = "",
    val about: String = "",
    val placement: String = "",
    val prep: String = "",
    val cautions: String = "",
    val notes: String = "",
    val sarahSays: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /** "16 in long · 0.75 in wide · balloons 2 in" for cards and for Sarah. */
    fun keyFacts(max: Int = 3): String = measurements.take(max).joinToString(" · ") { "${it.label} ${it.value}" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("category", category.name).put("name", name)
        .put("photos", JSONArray(photos))
        .put("measurements", JSONArray().apply { measurements.forEach { put(JSONObject().put("label", it.label).put("value", it.value).put("estimated", it.estimated)) } })
        .put("material", material).put("about", about).put("placement", placement).put("prep", prep).put("cautions", cautions)
        .put("notes", notes).put("sarah_says", sarahSays).put("created_at", createdAt)

    /** What Sarah is told about it (no photos, no file names). */
    fun forSarah(): JSONObject = JSONObject()
        .put("name", name).put("category", category.label)
        .put("measurements", JSONArray().apply { measurements.forEach { put("${it.label}: ${it.value}" + if (it.estimated) " (estimated from a photo)" else "") } })
        .apply {
            if (material.isNotBlank()) put("material", material)
            if (about.isNotBlank()) put("about", about)
            if (placement.isNotBlank()) put("placement", placement)
            if (notes.isNotBlank()) put("his_notes", notes)
        }

    companion object {
        fun fromJson(o: JSONObject): CatalogItem = CatalogItem(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            category = GearCategory.of(o.optString("category")),
            name = o.optString("name"),
            photos = o.optJSONArray("photos")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            measurements = o.optJSONArray("measurements")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Measurement(it.optString("label"), it.optString("value"), it.optBoolean("estimated")) } }
            } ?: emptyList(),
            material = o.optString("material"), about = o.optString("about"), placement = o.optString("placement"),
            prep = o.optString("prep"), cautions = o.optString("cautions"), notes = o.optString("notes"),
            sarahSays = o.optString("sarah_says"), createdAt = o.optLong("created_at", System.currentTimeMillis())
        )
    }
}

/** The gear catalog: kept as one JSON file plus a photo folder in the app's private storage. */
object GearCatalog {
    private val _items = MutableStateFlow<List<CatalogItem>>(emptyList())
    val items: StateFlow<List<CatalogItem>> = _items
    private var dir: File? = null

    @Synchronized
    fun load(c: Context) {
        if (dir != null) return
        val d = File(c.applicationContext.filesDir, "gear").apply { mkdirs() }
        File(d, "photos").mkdirs()
        dir = d
        _items.value = runCatching {
            val a = JSONArray(File(d, "catalog.json").takeIf { it.exists() }?.readText() ?: "[]")
            (0 until a.length()).map { CatalogItem.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val d = dir ?: return
        val a = JSONArray().apply { _items.value.forEach { put(it.toJson()) } }
        val tmp = File(d, "catalog.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(File(d, "catalog.json"))
    }

    @Synchronized
    fun save(item: CatalogItem) {
        _items.value = _items.value.filterNot { it.id == item.id } + item
        persist()
    }

    @Synchronized
    fun delete(item: CatalogItem) {
        _items.value = _items.value.filterNot { it.id == item.id }
        item.photos.forEach { photoFile(it)?.delete() }
        persist()
    }

    fun photoFile(name: String): File? = dir?.let { File(File(it, "photos"), name) }

    /** Copies a picked or captured photo into the catalog (longest side 1600 px, JPEG). Returns its file name. */
    suspend fun importPhoto(c: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bmp = decode(c, uri, 1600) ?: return@withContext null
        val name = "${UUID.randomUUID()}.jpg"
        val f = photoFile(name) ?: return@withContext null
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        name
    }

    /** A photo for display, scaled down to about [maxPx]. */
    fun thumbnail(name: String, maxPx: Int): Bitmap? {
        val f = photoFile(name)?.takeIf { it.exists() } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun decode(c: Context, uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxPx.toFloat() / maxOf(raw.width, raw.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
        // Photos from the camera come rotated per their EXIF tag.
        val exif = runCatching { c.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it) } }.getOrNull()
        val deg = when (exif?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg == 0f) sized else Bitmap.createBitmap(sized, 0, 0, sized.width, sized.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
    }.getOrNull()

    private fun jpegForSarah(name: String): ByteArray? {
        val bmp = thumbnail(name, 1280) ?: return null
        return ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray() }
    }

    /** Short list for Sarah's context: everything he owns, in a line each. */
    fun forSarah(): JSONArray = JSONArray().apply { _items.value.forEach { put(it.forSarah()) } }

    /**
     * Sarah looks at the photos (and whatever he said about it) and fills in the card. Returns the filled item, or null
     * with [ClaudeText.lastProblem] set. Fields he already typed are kept; numbers he gave win over estimates.
     */
    suspend fun identify(draft: CatalogItem, whatHeSaid: String): CatalogItem? {
        val images = draft.photos.take(4).mapNotNull(::jpegForSarah)
        val text = JSONObject()
            .put("what_he_said", whatHeSaid.ifBlank { "(nothing, just the photos)" })
            .put("category_he_picked", if (draft.category == GearCategory.Other) JSONObject.NULL else draft.category.name)
            .put("name_he_typed", draft.name.ifBlank { JSONObject.NULL as Any })
            .put("categories", JSONArray(GearCategory.entries.map { "${it.name}: ${it.label}" }))
            .toString()
        val out = ClaudeText.askWithImages(IDENTIFY_PROMPT, text, images, ResponseSettings.model.value.id) ?: return null
        val o = runCatching { JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1)) }.getOrNull() ?: return null
        val sarahMeasures = o.optJSONArray("measurements")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Measurement(it.optString("label"), it.optString("value"), it.optBoolean("estimated")) } }
                .filter { it.label.isNotBlank() && it.value.isNotBlank() }
        } ?: emptyList()
        // His own measurements stay; Sarah's add the ones he didn't give.
        val mine = draft.measurements.filter { it.label.isNotBlank() && it.value.isNotBlank() }
        val merged = mine + sarahMeasures.filter { s -> mine.none { it.label.equals(s.label, ignoreCase = true) } }
        return draft.copy(
            category = if (draft.category != GearCategory.Other) draft.category else GearCategory.of(o.optString("category")),
            name = draft.name.ifBlank { o.optString("name") },
            measurements = merged,
            material = draft.material.ifBlank { o.optString("material") },
            about = o.optString("about").ifBlank { draft.about },
            placement = o.optString("placement").ifBlank { draft.placement },
            prep = o.optString("prep").ifBlank { draft.prep },
            cautions = o.optString("cautions").ifBlank { draft.cautions },
            sarahSays = o.optString("sarah_says")
        )
    }

    /** He asks Sarah about one item ("how do I prep for this?", "how deep does it go?"). */
    suspend fun ask(item: CatalogItem, question: String): String? {
        val ctx = JSONObject()
            .put("his_question", question)
            .put("this_item", item.forSarah().apply { if (item.prep.isNotBlank()) put("prep", item.prep); if (item.cautions.isNotBlank()) put("cautions", item.cautions) })
            .put("rest_of_his_gear", JSONArray(_items.value.filter { it.id != item.id }.map { "${it.name} (${it.category.label})" }))
        val images = item.photos.take(2).mapNotNull(::jpegForSarah)
        return ClaudeText.askWithImages(ASK_PROMPT, ctx.toString(), images, ResponseSettings.model.value.id, maxTokens = 1_500L)?.trim()?.trim('"')
    }

    private const val WHO = """You are Sarah, Ben's companion and research partner in Sarah Monitor, his physiology app. Ben is an adult paramedic and EMS educator with strong anatomy, physiology and pathophysiology knowledge; he's American (inches, ounces, quarts, cc; French sizes for catheters). This is his private catalog of the devices and supplies he uses in body-exploration and sexual sessions (urethral sounds, catheters, enema nozzles and bags, anal toys, sleeves, vibrators, e-stim/TENS, sensors, lube). Treat it as ordinary adult health and sexuality: matter-of-fact, warm, accurate, never crude, never judgmental."""

    private const val IDENTIFY_PROMPT = WHO + """

He's adding an item. Look at the photos and what he said, then fill in the catalog card.
- Identify what it is as precisely as the photos allow (for example "double-balloon inflatable enema nozzle", "Hegar sound set", "16 Fr silicone Foley").
- Measurements: use what he said first (never contradict his numbers). Add useful ones you can read from the photos (insertable length, shaft/nozzle diameter, balloon size empty and inflated, bag volume, sound sizes, French size, cord length) only if a printed label, ruler, packaging or a familiar object gives scale; mark those "estimated": true. Never invent a number with nothing to go on: leave it out. Values in his units with the unit written ("16 in", "0.5 in (13 mm)", "2 qt", "16 Fr", "10 cc").
- placement: where it sits in the body when in use, in plain clinical terms (for example where an internal balloon rests relative to the anal canal and rectum, or how far a sound travels along the urethra), only as far as it's knowable from the device.
- prep: practical preparation, cleaning and lube; for enema gear include sensible enema prep (water temperature, volume, positioning, going slow, letting it settle). Short sentences.
- cautions: the one or two safety points that matter for this kind of device (for example never inflate a retention balloon past comfort, never force against resistance, sterile technique for anything urethral, electrical safety for e-stim). No lectures.
- sarah_says: one warm line in your own voice, at most 25 words, as if he just showed it to you.
Reply with JSON only: {"category": one of the category ids, "name": short name, "measurements": [{"label": "...", "value": "...", "estimated": true/false}], "material": "...", "about": "1-2 sentences: what it is and how it's used", "placement": "...", "prep": "...", "cautions": "...", "sarah_says": "..."}. Empty string for anything you can't say."""

    private const val ASK_PROMPT = WHO + """

He's asking you about one item from his catalog (you have its card, maybe its photos, and the names of the rest of his gear). Answer directly as a knowledgeable peer in at most about 120 words: plain sentences, practical, accurate, honest about uncertainty. Prep, sizing, how it sits anatomically, how to use it well and safely, how it compares to his other gear: all fair. If something sounds risky, say so plainly, medic to medic. No markdown, no lists, no preamble."""
}
