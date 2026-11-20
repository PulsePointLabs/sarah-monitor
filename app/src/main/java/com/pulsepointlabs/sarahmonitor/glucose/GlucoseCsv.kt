package com.pulsepointlabs.sarahmonitor.glucose

import com.pulsepointlabs.sarahmonitor.core.GlucoseEntry
import java.time.LocalDateTime
import java.time.ZoneId

data class GlucoseCsvResult(
    val rows: List<GlucoseEntry>,
    val skipped: Int,
    val error: String? = null
)

/**
 * Blood-glucose CSV import, ported from Sarah's parser (OneTouch Reveal exports and generic meter CSVs):
 * finds the header row, the date/time and glucose columns (or a mixed "item type / value" table), converts
 * mmol/L to mg/dL and reads device-local timestamps in several formats.
 */
object GlucoseCsv {
    private const val MMOL_TO_MGDL = 18.0182
    private val glucoseTerms = listOf("blood glucose", "blood sugar", "glucose", "glucose value", "glucose reading", "bg value", "bg reading", "result", "mg dl", "mmol")
    private val timeTerms = listOf("date", "time", "timestamp", "measured at", "measurement date", "measurement time", "device timestamp")

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        val zeros = bytes.take(200).count { it == 0.toByte() }
        return String(bytes, if (zeros > 10) Charsets.UTF_16LE else Charsets.UTF_8)
    }

    fun parse(text: String, sourceName: String, zone: ZoneId = ZoneId.systemDefault()): GlucoseCsvResult {
        val lines = text.split(Regex("\r?\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return GlucoseCsvResult(emptyList(), 0, "The file looks empty.")
        val delimiter = listOf(',', '\t', ';', '|').maxBy { d -> lines.take(60).maxOf { parseLine(it, d).size } }
        val header = lines.take(60).withIndex()
            .map { (i, line) -> Triple(i, parseLine(line, delimiter), headerScore(parseLine(line, delimiter))) }
            .sortedWith(compareByDescending<Triple<Int, List<String>, Int>> { it.third }.thenBy { it.first })
            .firstOrNull()
        if (header == null || header.third < 20) return GlucoseCsvResult(emptyList(), 0, "Couldn't find a glucose header row.")
        val headers = header.second.map(::normalize)
        val specific = indexOf(headers, listOf("blood glucose", "blood sugar", "glucose value", "glucose reading", "blood glucose result", "bg value", "bg reading", "glucose", "result"))
        val typeIndex = indexOf(headers, listOf("item type", "event type", "record type", "reading type", "measurement type", "type"))
        val generic = indexOf(headers, listOf("event value", "reading value", "measurement value", "value"))
        val valueIndex = if (specific >= 0) specific else generic
        val unitIndex = indexOf(headers, listOf("glucose units", "glucose unit", "unit", "units"))
        if (valueIndex < 0 || (specific < 0 && typeIndex < 0)) return GlucoseCsvResult(emptyList(), 0, "Couldn't find date/time and glucose columns.")
        val data = lines.drop(header.first + 1)
        var skipped = 0
        val rows = data.mapIndexedNotNull { index, line ->
            val columns = parseLine(line, delimiter)
            if (typeIndex >= 0 && !isGlucoseEvent(columns.getOrNull(typeIndex))) return@mapIndexedNotNull null
            val time = timestampText(columns, headers)?.let { parseLocalTimestamp(it, zone) }
            var value = number(columns.getOrNull(valueIndex))
            val unit = "${columns.getOrNull(unitIndex).orEmpty()} ${header.second.getOrNull(valueIndex).orEmpty()}".lowercase()
            if (value != null && unit.contains("mmol")) value *= MMOL_TO_MGDL
            if (time == null || value == null || value < 20 || value > 700) {
                skipped++
                return@mapIndexedNotNull null
            }
            GlucoseEntry(time, Math.round(value * 10) / 10.0, "CSV · $sourceName", "")
        }
        return if (rows.isEmpty()) GlucoseCsvResult(emptyList(), skipped, "No valid glucose rows were found.") else GlucoseCsvResult(rows, skipped)
    }

    internal fun parseLine(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { value.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { out += value.toString().trim(); value.clear() }
                else -> value.append(c)
            }
            i++
        }
        out += value.toString().trim()
        return out
    }

    private fun normalize(value: String): String =
        value.removePrefix("﻿").lowercase().replace(Regex("[%()/_-]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun headerScore(headers: List<String>): Int {
        val normalized = headers.map(::normalize)
        val vital = normalized.count { h -> glucoseTerms.any { h == it || h.contains(it) } }
        val time = normalized.count { h -> timeTerms.any { h == it || h.contains(it) } }
        val mixed = normalized.any { h -> listOf("item type", "event type", "record type", "reading type", "measurement type").any { h.contains(it) } } &&
            normalized.any { h -> listOf("event value", "reading value", "measurement value", "value").any { h == it || h.contains(it) } }
        return vital * 20 + time * 5 + (if (mixed) 40 else 0) + minOf(headers.size, 12)
    }

    private fun indexOf(headers: List<String>, aliases: List<String>): Int {
        aliases.forEach { alias -> headers.indexOf(alias).takeIf { it >= 0 }?.let { return it } }
        aliases.forEach { alias ->
            val pattern = Regex("(?:^|\\s)" + alias.split(" ").joinToString("\\s+") { Regex.escape(it) } + "(?:$|\\s)")
            headers.indexOfFirst { pattern.containsMatchIn(it) }.takeIf { it >= 0 }?.let { return it }
        }
        return -1
    }

    private fun number(value: String?): Double? = value?.replace(Regex("[^\\d.+-]"), "")?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    private fun isGlucoseEvent(value: String?): Boolean {
        val type = normalize(value.orEmpty())
        return type == "bg" || type.contains("blood glucose") || type.contains("blood sugar") || type.contains("glucose")
    }

    private fun timestampText(columns: List<String>, headers: List<String>): String? {
        val combined = indexOf(headers, listOf("device timestamp", "date and time", "date time", "datetime", "time stamp", "timestamp", "measured at", "event time"))
        if (combined >= 0) return columns.getOrNull(combined)
        val date = indexOf(headers, listOf("reading date", "measurement date", "record date", "event date", "device date", "date"))
        val time = indexOf(headers, listOf("reading time", "measurement time", "record time", "device time", "time"))
        return if (date >= 0 && time >= 0) "${columns.getOrNull(date).orEmpty()} ${columns.getOrNull(time).orEmpty()}"
        else columns.getOrNull(if (time >= 0) time else date)
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Device-local timestamp → epoch ms, or null. */
    fun parseLocalTimestamp(value: String, zone: ZoneId): Long? {
        val raw = value.trim().replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+(?:EST|EDT|CST|CDT|MST|MDT|PST|PDT|UTC|GMT)(?:[+-]\\d{1,2})?$", RegexOption.IGNORE_CASE), "").trim()
        if (Regex("^\\d{10,13}$").matches(raw)) {
            val n = raw.toLong()
            return if (raw.length == 10) n * 1000 else n
        }
        fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long? =
            runCatching { LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        fun hour(h: Int, ampm: String?): Int = when {
            ampm.equals("pm", true) && h < 12 -> h + 12
            ampm.equals("am", true) && h == 12 -> 0
            else -> h
        }
        Regex("^(\\d{4})(\\d{2})(\\d{2})[ T_-]?(\\d{2})(\\d{2})(\\d{2})?$").matchEntire(raw)?.let { m ->
            val g = m.groupValues
            return local(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        }
        val tail = "[ T]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(AM|PM)?(?:\\s*(?:Z|[+-]\\d{2}:?\\d{2}))?$"
        val yearFirst = Regex("^(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})$tail", RegexOption.IGNORE_CASE).matchEntire(raw)
        val dayFirst = Regex("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})$tail", RegexOption.IGNORE_CASE).matchEntire(raw)
        (yearFirst ?: dayFirst)?.let { m ->
            val g = m.groupValues
            val isYearFirst = g[1].length == 4
            var year = (if (isYearFirst) g[1] else g[3]).toInt()
            if (year < 100) year += 2000
            val first = g[1].toInt()
            val second = g[2].toInt()
            val month = if (isYearFirst) g[2].toInt() else if (first > 12) second else first
            val day = if (isYearFirst) g[3].toInt() else if (first > 12) first else second
            return local(year, month, day, hour(g[4].toInt(), g[7].ifEmpty { null }), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        }
        val namedTail = "(?:\\s+at\\s+|[ ,T]+)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(AM|PM)?$"
        val monthFirst = Regex("^([A-Za-z]{3,9})[ .-]+(\\d{1,2}),?[ .-]+(\\d{4})$namedTail", RegexOption.IGNORE_CASE).matchEntire(raw)
        val dayFirstNamed = Regex("^(\\d{1,2})[ .-]+([A-Za-z]{3,9})[ .,-]+(\\d{4})$namedTail", RegexOption.IGNORE_CASE).matchEntire(raw)
        (monthFirst ?: dayFirstNamed)?.let { m ->
            val g = m.groupValues
            val isMonthFirst = g[1].first().isLetter()
            val month = months.indexOf((if (isMonthFirst) g[1] else g[2]).take(3).lowercase()) + 1
            if (month <= 0) return null
            val day = (if (isMonthFirst) g[2] else g[1]).toInt()
            return local(g[3].toInt(), month, day, hour(g[4].toInt(), g[7].ifEmpty { null }), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        }
        return runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }
}
