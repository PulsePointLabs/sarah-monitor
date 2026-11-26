package com.pulsepointlabs.sarahmonitor.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Blood pressure belongs to the session it was taken in: only readings since [sessionStartMs] count, and none when
 * nothing is recording (an old reading on the live card reads like a current one). Pure; unit-tested.
 */
fun List<BpEntry>.duringSession(sessionStartMs: Long?): List<BpEntry> =
    if (sessionStartMs == null) emptyList() else filter { it.timestampMs >= sessionStartMs }

data class BpEntry(
    val timestampMs: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    val irregular: Boolean?,
    val source: String,
    /** Cuff site: arm, wrist, thigh, calf (null = not recorded). */
    val site: String? = null,
    /** Body position: sitting, standing, supine, reclined (null = not recorded). */
    val posture: String? = null
) {
    val meanArterial: Double get() = diastolic + (systolic - diastolic) / 3.0
    /** "Arm · sitting", or null when neither is known. */
    val positionLabel: String? get() = listOfNotNull(site?.let { BpPosition.siteLabel(it) }, posture?.let { BpPosition.postureLabel(it) }).joinToString(" · ").ifBlank { null }
}

/** Cuff site and body position for BP readings, plus the defaults applied to new cuff readings. */
object BpPosition {
    val sites = listOf("arm" to "Upper arm", "wrist" to "Wrist", "thigh" to "Thigh", "calf" to "Calf / ankle")
    val postures = listOf("sitting" to "Sitting", "standing" to "Standing", "supine" to "Lying (supine)", "reclined" to "Reclined")
    fun siteLabel(id: String) = sites.firstOrNull { it.first == id }?.second ?: id
    fun postureLabel(id: String) = postures.firstOrNull { it.first == id }?.second ?: id

    val defaultSite = MutableStateFlow("arm")
    val defaultPosture = MutableStateFlow("sitting")
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("sarahmonitor_bp_position", Context.MODE_PRIVATE)
        prefs = p
        defaultSite.value = p.getString("site", "arm") ?: "arm"
        defaultPosture.value = p.getString("posture", "sitting") ?: "sitting"
    }

    fun setDefaults(site: String, posture: String) {
        defaultSite.value = site; defaultPosture.value = posture
        prefs?.edit()?.putString("site", site)?.putString("posture", posture)?.apply()
    }
}

data class GlucoseEntry(
    val timestampMs: Long,
    val mgDl: Double,
    val source: String,
    val note: String = ""
)

/** Small JSON-file store: readings survive restarts and every change is published at once. */
abstract class JsonListStore<T>(context: Context, fileName: String) {
    private val file = File(context.filesDir, fileName)
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items

    init {
        _items.value = runCatching {
            if (!file.exists()) emptyList() else {
                val array = JSONArray(file.readText())
                (0 until array.length()).mapNotNull { runCatching { fromJson(array.getJSONObject(it)) }.getOrNull() }
            }
        }.getOrDefault(emptyList()).sortedBy(::timestampOf)
    }

    protected abstract fun toJson(item: T): JSONObject
    protected abstract fun fromJson(json: JSONObject): T
    protected abstract fun timestampOf(item: T): Long
    protected abstract fun sameReading(a: T, b: T): Boolean

    /** Adds readings that are not already stored; returns how many were new. */
    @Synchronized
    fun addAll(newItems: List<T>): Int {
        val current = _items.value
        val fresh = newItems.filter { candidate -> current.none { sameReading(it, candidate) } }
            .distinctBy { timestampOf(it) }
        if (fresh.isEmpty()) return 0
        val next = (current + fresh).sortedBy(::timestampOf).takeLast(MAX_ITEMS)
        _items.value = next
        save(next)
        return fresh.size
    }

    @Synchronized
    fun remove(item: T) {
        val next = _items.value.filterNot { sameReading(it, item) }
        _items.value = next
        save(next)
    }

    private fun save(items: List<T>) {
        runCatching {
            val array = JSONArray()
            items.forEach { array.put(toJson(it)) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val MAX_ITEMS = 5_000
    }
}

class BpStore(context: Context) : JsonListStore<BpEntry>(context, "bp_readings.json") {
    override fun toJson(item: BpEntry) = JSONObject()
        .put("t", item.timestampMs).put("sys", item.systolic).put("dia", item.diastolic)
        .put("pulse", item.pulse ?: JSONObject.NULL).put("irregular", item.irregular ?: JSONObject.NULL).put("source", item.source)
        .put("site", item.site ?: JSONObject.NULL).put("posture", item.posture ?: JSONObject.NULL)

    override fun fromJson(json: JSONObject) = BpEntry(
        timestampMs = json.getLong("t"),
        systolic = json.getInt("sys"),
        diastolic = json.getInt("dia"),
        pulse = if (json.isNull("pulse")) null else json.getInt("pulse"),
        irregular = if (json.isNull("irregular")) null else json.getBoolean("irregular"),
        source = json.optString("source", ""),
        site = if (json.isNull("site")) null else json.optString("site").ifBlank { null },
        posture = if (json.isNull("posture")) null else json.optString("posture").ifBlank { null }
    )

    override fun timestampOf(item: BpEntry) = item.timestampMs

    /** Sets where the cuff was and how you were positioned for one reading. */
    fun setPosition(entry: BpEntry, site: String?, posture: String?) {
        remove(entry)
        addAll(listOf(entry.copy(site = site, posture = posture)))
    }

    // The cuff re-sends stored readings; treat the same values within a minute as the same reading.
    override fun sameReading(a: BpEntry, b: BpEntry) =
        a.systolic == b.systolic && a.diastolic == b.diastolic && kotlin.math.abs(a.timestampMs - b.timestampMs) < 60_000L
}

class GlucoseStore(context: Context) : JsonListStore<GlucoseEntry>(context, "glucose_readings.json") {
    override fun toJson(item: GlucoseEntry) = JSONObject()
        .put("t", item.timestampMs).put("mgdl", item.mgDl).put("source", item.source).put("note", item.note)

    override fun fromJson(json: JSONObject) = GlucoseEntry(
        timestampMs = json.getLong("t"),
        mgDl = json.getDouble("mgdl"),
        source = json.optString("source", ""),
        note = json.optString("note", "")
    )

    override fun timestampOf(item: GlucoseEntry) = item.timestampMs

    override fun sameReading(a: GlucoseEntry, b: GlucoseEntry) =
        kotlin.math.abs(a.mgDl - b.mgDl) < 0.05 && kotlin.math.abs(a.timestampMs - b.timestampMs) < 60_000L
}
