package com.pulsepointlabs.sarahmonitor.vitals

import android.content.Context
import com.pulsepointlabs.sarahmonitor.core.JsonListStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ---------------------------------------------------------------- SpO2

data class SpO2Entry(val timestampMs: Long, val spo2: Int, val pulse: Int?, val perfusionIndex: Double?, val source: String)

class SpO2Store(context: Context) : JsonListStore<SpO2Entry>(context, "spo2_readings.json") {
    override fun toJson(item: SpO2Entry) = JSONObject().put("t", item.timestampMs).put("spo2", item.spo2)
        .put("pulse", item.pulse ?: JSONObject.NULL).put("pi", item.perfusionIndex ?: JSONObject.NULL).put("source", item.source)

    override fun fromJson(json: JSONObject) = SpO2Entry(
        json.getLong("t"), json.getInt("spo2"),
        if (json.isNull("pulse")) null else json.getInt("pulse"),
        if (json.isNull("pi")) null else json.getDouble("pi"),
        json.optString("source", "")
    )

    override fun timestampOf(item: SpO2Entry) = item.timestampMs
    override fun sameReading(a: SpO2Entry, b: SpO2Entry) = a.spo2 == b.spo2 && abs(a.timestampMs - b.timestampMs) < 1_000L
}

// ---------------------------------------------------------------- Body composition

/** One weigh-in. Everything except weight is optional (Health Connect may only have some of it). */
data class BodyEntry(
    val timestampMs: Long,
    val weightKg: Double,
    val bmi: Double? = null,
    val bodyFatPct: Double? = null,
    val leanMassKg: Double? = null,
    val muscleMassKg: Double? = null,
    val skeletalMusclePct: Double? = null,
    val bodyWaterPct: Double? = null,
    val boneMassKg: Double? = null,
    val visceralFat: Int? = null,
    val subcutaneousFatPct: Double? = null,
    val proteinPct: Double? = null,
    val bmrKcal: Int? = null,
    val metabolicAge: Int? = null,
    val impedanceOhms: Int? = null,
    val source: String = ""
)

class BodyStore(context: Context) : JsonListStore<BodyEntry>(context, "body_readings.json") {
    override fun toJson(item: BodyEntry) = JSONObject().apply {
        put("t", item.timestampMs); put("kg", item.weightKg); put("source", item.source)
        item.bmi?.let { put("bmi", it) }; item.bodyFatPct?.let { put("fat", it) }; item.leanMassKg?.let { put("lean", it) }
        item.muscleMassKg?.let { put("muscle", it) }; item.skeletalMusclePct?.let { put("skm", it) }; item.bodyWaterPct?.let { put("water", it) }
        item.boneMassKg?.let { put("bone", it) }; item.visceralFat?.let { put("visceral", it) }; item.subcutaneousFatPct?.let { put("subq", it) }
        item.proteinPct?.let { put("protein", it) }; item.bmrKcal?.let { put("bmr", it) }; item.metabolicAge?.let { put("metage", it) }
        item.impedanceOhms?.let { put("ohms", it) }
    }

    override fun fromJson(json: JSONObject): BodyEntry {
        fun d(k: String) = if (json.has(k)) json.getDouble(k) else null
        fun i(k: String) = if (json.has(k)) json.getInt(k) else null
        return BodyEntry(
            json.getLong("t"), json.getDouble("kg"), d("bmi"), d("fat"), d("lean"), d("muscle"), d("skm"), d("water"), d("bone"),
            i("visceral"), d("subq"), d("protein"), i("bmr"), i("metage"), i("ohms"), json.optString("source", "")
        )
    }

    override fun timestampOf(item: BodyEntry) = item.timestampMs
    override fun sameReading(a: BodyEntry, b: BodyEntry) = abs(a.weightKg - b.weightKg) < 0.05 && abs(a.timestampMs - b.timestampMs) < 120_000L
}

/** Height/age/sex for the scale's body-composition maths, and the display unit. */
object BodyProfile {
    val heightCm = MutableStateFlow<Double?>(null)
    val birthYear = MutableStateFlow<Int?>(null)
    val male = MutableStateFlow(true)
    val pounds = MutableStateFlow(true)
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun load(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("sarahmonitor_body", Context.MODE_PRIVATE)
        prefs = p
        heightCm.value = p.getFloat("height", -1f).takeIf { it > 0 }?.toDouble()
        birthYear.value = p.getInt("birth", -1).takeIf { it > 0 }
        male.value = p.getBoolean("male", true)
        pounds.value = p.getBoolean("lb", true)
    }

    fun save(height: Double?, birth: Int?, isMale: Boolean, lb: Boolean) {
        heightCm.value = height; birthYear.value = birth; male.value = isMale; pounds.value = lb
        prefs?.edit()?.putFloat("height", (height ?: -1.0).toFloat())?.putInt("birth", birth ?: -1)?.putBoolean("male", isMale)?.putBoolean("lb", lb)?.apply()
    }

    fun age(): Int? = birthYear.value?.let { LocalDate.now(ZoneId.systemDefault()).year - it }

    fun weightText(kg: Double): String = if (pounds.value) "%.1f lb".format(kg * 2.2046226218) else "%.1f kg".format(kg)
    fun massValue(kg: Double): Double = if (pounds.value) kg * 2.2046226218 else kg
    val massUnit: String get() = if (pounds.value) "lb" else "kg"
}

/**
 * Etekcity ESF-551 packet parsing and body-composition estimates — a line-for-line port of Sarah's
 * etekcityEsf551Metrics.js (same constants and rounding), so both apps agree on every number.
 */
object Esf551 {
    data class Measurement(val weightKg: Double, val impedanceOhms: Int?, val displayUnit: Int)

    private fun jsRound(v: Double, digits: Int = 0): Double { val f = 10.0.pow(digits); return floor(v * f + 0.5) / f }

    fun parse(b: ByteArray): Measurement? {
        if (b.size != 22) return null
        fun u(i: Int) = b[i].toInt() and 0xff
        if (u(0) != 0xa5 || u(1) != 0x02 || u(3) != 0x10 || u(4) != 0x00 || u(6) != 0x01 || u(7) != 0x61 || u(8) != 0xa1 || u(9) != 0x00 || u(19) != 0x01) return null
        val weightRaw = u(10) or (u(11) shl 8) or (u(12) shl 16)
        val impedance = u(13) or (u(14) shl 8)
        return Measurement(jsRound(weightRaw / 1000.0, 2), if (u(20) == 1 && impedance > 0) impedance else null, u(21))
    }

    fun composition(weight: Double, impedance: Int, heightCm: Double, ageYears: Int, male: Boolean, source: String, timestampMs: Long): BodyEntry {
        val sex = if (male) 0 else 1
        val height = heightCm / 100
        val bmi = floor(weight / (height * height) * 100) / 100
        val rawBodyFat = floor((doubleArrayOf(0.103, 0.097)[sex] * ageYears + doubleArrayOf(1.524, 1.545)[sex] * bmi - 500.0 / impedance - doubleArrayOf(22.0, 12.7)[sex]) * 10) / 10
        val bodyFat = rawBodyFat.coerceIn(5.0, 75.0)
        val fatFree = jsRound(weight * (1 - bodyFat / 100), 2)
        val visceral = (doubleArrayOf(0.8666, 0.8895)[sex] * bmi + doubleArrayOf(0.0082, 0.0943)[sex] * bodyFat +
            doubleArrayOf(0.026, -0.0534)[sex] * (weight - fatFree) - doubleArrayOf(14.2692, 16.215)[sex]).toInt().coerceIn(1, 30)
        val subq = jsRound(doubleArrayOf(0.965, 0.983)[sex] * bodyFat - doubleArrayOf(0.22, 0.303)[sex] * visceral, 1)
        val structuralFraction = doubleArrayOf(0.05, 0.06)[sex]
        val structural = max(1.0, structuralFraction * fatFree)
        val water = jsRound(doubleArrayOf(0.76, 0.73)[sex] * (fatFree - structural) / weight * 100, 1).coerceIn(10.0, 80.0)
        val skeletal = jsRound(doubleArrayOf(0.68, 0.62)[sex] * (fatFree - structural) / weight * 100, 1)
        val muscle = jsRound(fatFree - structural, 2)
        val bone = max(1.0, jsRound(structuralFraction * fatFree, 2))
        val protein = max(5.0, jsRound(100 - doubleArrayOf(1.0, 1.05)[sex] * bodyFat - bone / weight * 100 - water, 1))
        val bmr = (fatFree * 21.6 + 370).toInt().coerceIn(900, 2500)

        val ideal = doubleArrayOf(0.7, 0.45)[sex] * (doubleArrayOf(100.0, 137.0)[sex] * height - doubleArrayOf(80.0, 110.0)[sex])
        val weightScore = when {
            ideal <= weight -> if (ideal * 1.3 < weight) 50 else (100 - 50 * (weight - ideal) / (0.3 * ideal)).toInt()
            ideal * 0.7 < weight -> (100 - 50 * (ideal - weight) / (0.3 * ideal)).toInt()
            else -> (0 until 6).firstOrNull { ideal * it / 10 > weight }?.let { it * 10 } ?: 0
        }
        val idealFat = doubleArrayOf(16.0, 26.0)[sex]
        val fatScore = if (idealFat < bodyFat) (if (bodyFat >= 45) 50 else (100 - 50 * (bodyFat - idealFat) / (45 - idealFat)).toInt())
        else (100 - 50 * (idealFat - bodyFat) / (idealFat - 5)).toInt()
        val bmiScore = when {
            bmi >= 22 -> if (bmi >= 35) 50 else (100 - 3.85 * (bmi - 22)).toInt()
            bmi >= 15 -> (100 - 3.85 * (22 - bmi)).toInt()
            bmi >= 10 -> 40
            bmi >= 5 -> 30
            else -> 20
        }
        val health = floor((weightScore + fatScore + bmiScore) / 3.0).toInt()
        val adj = listOf(50, 60, 65, 68, 70, 73, 75, 80, 85, 88, 90, 93, 95, 97, 98, 99).indexOfFirst { health < it }.let { if (it < 0) 16 else it }
        return BodyEntry(
            timestampMs, weight, bmi, bodyFat, fatFree, muscle, skeletal, water, bone, visceral, subq, protein, bmr,
            max(18, ageYears + 8 - adj), impedance, source
        )
    }
}

// ---------------------------------------------------------------- CSV (SpO2), ported from Sarah's parsePulseOxCsv.js

data class SpO2CsvResult(val rows: List<SpO2Entry>, val skipped: Int, val error: String? = null)

object VitalCsv {
    /** "spo2", "body" or "glucose" (the default), from the headers and file name. */
    fun classify(text: String, fileName: String): String {
        val head = text.lineSequence().take(40).joinToString(" | ").lowercase()
        return when {
            Regex("spo2|sp02|oxygen saturation|blood oxygen|perfusion").containsMatchIn(head) -> "spo2"
            Regex("(?i)emay|spo2|sp02|pulse.?ox|oximeter|oxygen").containsMatchIn(fileName) && !head.contains("glucose") -> "spo2"
            Regex("blood glucose|blood sugar|glucose|mmol|mg dl|mg/dl").containsMatchIn(head) -> "glucose"
            Regex("body fat|lean body|visceral fat|muscle mass|body water|\\bbmi\\b").containsMatchIn(head) -> "body"
            else -> "glucose"
        }
    }

    private fun parseLine(line: String, d: Char): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> q = !q
                c == d && !q -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }

    private fun norm(v: String) = v.replace("﻿", "").replace('₂', '2').replace('₀', '0').trim().lowercase()
        .replace(Regex("[%()/_-]+"), " ").replace(Regex("\\s+"), " ")

    private fun find(headers: List<String>, aliases: List<String>) = headers.map(::norm).indexOfFirst { h -> aliases.any { h == it || h.contains(it) } }

    private fun number(v: String?) = v?.replace(Regex("[^\\d.+-]"), "")?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    private val TIME = listOf("time", "timestamp", "date time", "datetime", "record time")
    private val DATE = listOf("date", "record date", "measurement date")
    private val SPO2 = listOf("spo2", "sp02", "sp o2", "sp 02", "oxygen", "blood oxygen", "oxygen saturation", "o2")

    fun parseSpO2(text: String, source: String, zone: ZoneId = ZoneId.systemDefault()): SpO2CsvResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return SpO2CsvResult(emptyList(), 0, "CSV appears empty or has no data rows.")
        val delim = listOf(',', '\t', ';', '|').maxBy { d -> lines.take(30).maxOf { parseLine(it, d).size } }
        var headerIndex = 0
        for (i in 0 until min(40, lines.size)) {
            val h = parseLine(lines[i], delim)
            if ((find(h, TIME + "measurement time" + "measured at") >= 0 || find(h, DATE) >= 0) && find(h, SPO2) >= 0) { headerIndex = i; break }
        }
        val headers = parseLine(lines[headerIndex], delim)
        val timeIdx = find(headers, TIME)
        val dateIdx = find(headers, DATE)
        val spo2Idx0 = find(headers, SPO2)
        val pulseIdx0 = find(headers, listOf("pulse rate", "pulse", "pr", "bpm", "heart rate", "hr"))
        val piIdx = find(headers, listOf("pi", "perfusion", "perfusion index"))
        val emay = timeIdx >= 0 && spo2Idx0 < 0 && pulseIdx0 < 0 && headers.size >= 3
        val spo2Idx = if (spo2Idx0 >= 0) spo2Idx0 else if (emay) 1 else -1
        val pulseIdx = if (pulseIdx0 >= 0) pulseIdx0 else if (emay) 2 else -1
        if (timeIdx < 0 && dateIdx < 0) return SpO2CsvResult(emptyList(), lines.size - 1, "Could not find a time/timestamp column in the pulse-ox CSV.")
        if (spo2Idx < 0) return SpO2CsvResult(emptyList(), lines.size - 1, "Could not find a SpO2/oxygen saturation column in the pulse-ox CSV.")
        val rows = mutableListOf<SpO2Entry>()
        var skipped = 0
        lines.drop(headerIndex + 1).forEach { line ->
            val c = parseLine(line, delim)
            val stamp = if (dateIdx >= 0 && timeIdx >= 0 && dateIdx != timeIdx) "${c.getOrNull(dateIdx).orEmpty()} ${c.getOrNull(timeIdx).orEmpty()}"
            else c.getOrNull(if (timeIdx >= 0) timeIdx else dateIdx).orEmpty()
            val t = com.pulsepointlabs.sarahmonitor.glucose.GlucoseCsv.parseLocalTimestamp(stamp, zone)
                ?: runCatching { java.time.Instant.parse(stamp.trim()).toEpochMilli() }.getOrNull()
                // Some oximeter apps write times as 07.30.15
                ?: com.pulsepointlabs.sarahmonitor.glucose.GlucoseCsv.parseLocalTimestamp(stamp.replace(Regex("(\\d{1,2})\\.(\\d{2})(?:\\.(\\d{2}))?\\s*$"), "$1:$2:$3").replace(Regex(":$"), ""), zone)
            val s = number(c.getOrNull(spo2Idx))
            val p = if (pulseIdx >= 0) number(c.getOrNull(pulseIdx)) else null
            if (t == null || s == null || s < 50 || s > 100 || (p != null && (p < 20 || p > 240))) { skipped++; return@forEach }
            rows += SpO2Entry(t, Math.round(s).toInt(), p?.let { Math.round(it).toInt() }, if (piIdx >= 0) number(c.getOrNull(piIdx)) else null, source)
        }
        if (rows.isEmpty()) return SpO2CsvResult(emptyList(), skipped, "No valid pulse-ox rows found. Detected columns: ${headers.filter { it.isNotBlank() }.joinToString(", ")}.")
        return SpO2CsvResult(rows.sortedBy { it.timestampMs }, skipped)
    }
}
