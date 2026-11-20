package com.pulsepointlabs.sarahmonitor.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BpEntry(
    val timestampMs: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    val irregular: Boolean?,
    val source: String
) {
    val meanArterial: Double get() = diastolic + (systolic - diastolic) / 3.0
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

    override fun fromJson(json: JSONObject) = BpEntry(
        timestampMs = json.getLong("t"),
        systolic = json.getInt("sys"),
        diastolic = json.getInt("dia"),
        pulse = if (json.isNull("pulse")) null else json.getInt("pulse"),
        irregular = if (json.isNull("irregular")) null else json.getBoolean("irregular"),
        source = json.optString("source", "")
    )

    override fun timestampOf(item: BpEntry) = item.timestampMs

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
