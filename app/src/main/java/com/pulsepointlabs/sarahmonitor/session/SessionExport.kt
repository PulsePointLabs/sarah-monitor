package com.pulsepointlabs.sarahmonitor.session

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object SessionRepository {
    /** Loads everything the review needs; BP/glucose are matched to the session by time. */
    suspend fun load(dao: SessionDao, id: String): SessionData? {
        val stored = dao.getSession(id) ?: return null
        val end = stored.endTimeMs ?: System.currentTimeMillis()
        val hr = dao.getHr(id)
        val rr = dao.getRr(id)
        val pelvic = dao.getPelvic(id)
        val markers = dao.getMarkers(id)
        // A session that's still recording has no saved totals yet; work them out from what's recorded so far.
        val session = if (stored.active) SessionAnalysis.summarize(stored, hr, rr, pelvic, markers.size) else stored
        return SessionData(
            session = session,
            hr = hr,
            rr = rr,
            pelvic = pelvic,
            markers = markers,
            bp = MonitorHub.bp.items.value.filter { it.timestampMs in (session.startTimeMs - BP_BEFORE_MS)..(end + BP_AFTER_MS) },
            glucose = MonitorHub.glucose.items.value.filter { it.timestampMs in (session.startTimeMs - GLUCOSE_BEFORE_MS)..(end + BP_AFTER_MS) },
            hasEcg = dao.ecgChunkCount(id) > 0
        )
    }

    const val BP_BEFORE_MS = 15 * 60_000L
    const val BP_AFTER_MS = 15 * 60_000L
    const val GLUCOSE_BEFORE_MS = 2 * 60 * 60_000L
}

object SessionExport {
    private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dateTime = DateTimeFormatter.ofPattern("EEE MMM d yyyy, h:mm:ss a")
    private val clock = DateTimeFormatter.ofPattern("h:mm:ss a")

    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
    private fun f0(v: Double?) = v?.let { "%.0f".format(Locale.US, it) } ?: "unknown"
    private fun f1(v: Double?) = v?.let { SessionAnalysis.num(it) } ?: "unknown"
    private fun oneLine(s: String) = s.replace('\n', ' ').trim()

    fun markdown(data: SessionData): String {
        val s = data.session
        val elapsed = { ms: Long -> SessionAnalysis.elapsed(ms - s.startTimeMs) }
        val pelvic = SessionAnalysis.pelvic(data.pelvic)
        val tz = zone.getDisplayName(TextStyle.FULL, Locale.US)
        return buildString {
            appendLine("# Sarah Monitor Session Review")
            appendLine()
            appendLine("## Prompt")
            appendLine(
                "Please review this Sarah Monitor session. Times are local ($tz) and elapsed times are from the session start. " +
                    "Summarize the heart rate, HRV, Load and pelvic pressure patterns, relate them to the events I marked and to any blood " +
                    "pressure or glucose readings, and point out data-quality limits. Pelvic pressure is mechanical pressure above my " +
                    "resting level from a CIVET sensor, not calibrated muscle force. This is for personal insight, not a diagnosis. " +
                    "The attached CSV has one row per second."
            )
            appendLine()
            appendLine("## Session")
            appendLine("- Title: ${s.title}")
            appendLine("- Started: ${at(s.startTimeMs).format(dateTime)} ($tz)")
            appendLine("- Ended: ${s.endTimeMs?.let { at(it).format(dateTime) } ?: "still recording"}")
            appendLine("- Duration: ${SessionAnalysis.durationWords(data.endMs - s.startTimeMs)}")
            if (s.notes.isNotBlank()) appendLine("- Notes: ${oneLine(s.notes)}")
            appendLine("- Data: HR ${data.hr.size} samples, RR ${data.rr.size}, pressure ${data.pelvic.size} samples, events ${data.markers.size}, BP ${data.bp.size}, glucose ${data.glucose.size}")
            appendLine()
            appendLine("## Summary")
            SessionAnalysis.plainSummary(data).forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Heart")
            appendLine("- Average HR: ${f0(s.averageHr)} bpm; min ${s.minHr ?: "unknown"}; max ${s.maxHr ?: "unknown"}")
            appendLine("- Whole-session HRV: RMSSD ${f1(s.rmssdMs)} ms; SDNN ${f1(s.sdnnMs)} ms; pNN50 ${f1(s.pnn50Percent)}%; quality ${s.hrvQuality ?: "unknown"}")
            val accepted = data.rr.count { it.accepted }
            appendLine("- RR accepted: $accepted/${data.rr.size}; artifacts ${data.rr.count { it.artifact }}")
            val loads = data.hr.mapNotNull { it.load }
            if (loads.isNotEmpty()) appendLine("- Load (0–100): average ${f0(loads.average())}, max ${loads.max()}")
            appendLine()
            appendLine("## Pelvic pressure")
            if (data.pelvic.isEmpty()) appendLine("- Not recorded.") else {
                appendLine("- Peak above rest: ${f1(pelvic.maxKpa)} kPa; average above rest ${f1(pelvic.averageKpa)} kPa")
                appendLine("- Contractions: ${pelvic.pulses.size}; longest hold ${f1(pelvic.longestHoldS)} s")
                pelvic.pulses.take(40).forEach { appendLine("  - ${elapsed(it.startMs)}: ${f1(it.durationS)} s, peak ${f1(it.peakKpa)} kPa") }
                if (pelvic.pulses.size > 40) appendLine("  - …${pelvic.pulses.size - 40} more in the CSV")
            }
            appendLine()
            appendLine("## Events (marked by me)")
            if (data.markers.isEmpty()) appendLine("- No events marked.")
            data.markers.forEach { m ->
                val c = SessionAnalysis.markerContext(m, data)
                val response = listOfNotNull(
                    c.delta1Min?.let { "HR 1 min after ${signed(it)} bpm" },
                    c.delta5Min?.let { "5 min after ${signed(it)} bpm" }
                ).joinToString(", ")
                appendLine(
                    "- ${elapsed(m.timestampMs)} (${at(m.timestampMs).format(clock)}): ${m.label}" +
                        "; HR ${c.hrAt ?: "unknown"}" + (c.rmssdAt?.let { "; RMSSD ${f1(it)} ms" } ?: "") +
                        (c.pressureAt?.let { "; pressure ${f1(it)} kPa" } ?: "") +
                        (c.peakPressureNext30s?.let { "; peak pressure next 30 s ${f1(it)} kPa" } ?: "") +
                        (if (response.isNotEmpty()) "; $response" else "") +
                        (m.note.takeIf { it.isNotBlank() }?.let { "; note: ${oneLine(it)}" } ?: "")
                )
            }
            appendLine()
            appendLine("## Blood pressure")
            if (data.bp.isEmpty()) appendLine("- No readings during the session (±15 min).")
            data.bp.forEach { appendLine("- ${at(it.timestampMs).format(clock)} (${elapsed(it.timestampMs)}): ${it.systolic}/${it.diastolic}${it.pulse?.let { p -> ", pulse $p" } ?: ""}") }
            appendLine()
            appendLine("## Glucose")
            if (data.glucose.isEmpty()) appendLine("- No readings from 2 h before to 15 min after.")
            data.glucose.forEach { appendLine("- ${at(it.timestampMs).format(dateTime)}: ${f0(it.mgDl)} mg/dL (${it.source})") }
            appendLine()
            appendLine("## Minute-by-minute")
            appendLine("| elapsed | avg HR | max HR | RMSSD | Load | avg pressure | max pressure |")
            appendLine("|---:|---:|---:|---:|---:|---:|---:|")
            minuteRows(data).forEach { appendLine(it) }
            appendLine()
            appendLine("## Data notes")
            appendLine("- HRV in the minute table is a rolling 2-minute value; whole-session HRV uses all accepted RR intervals.")
            appendLine("- Load is a relative physiological-load estimate from HR and HRV against a personal baseline.")
            appendLine("- Pressure above rest depends on the rest level (captured or automatic) at the time.")
        }
    }

    private fun signed(v: Double) = SessionAnalysis.signed(v)

    private fun minuteRows(data: SessionData): List<String> {
        val start = data.session.startTimeMs
        val minutes = ((data.endMs - start) / 60_000L + 1).toInt().coerceAtMost(24 * 60)
        val step = if (minutes > 90) (minutes + 89) / 90 else 1
        return (0 until minutes step step).mapNotNull { m ->
            val from = start + m * 60_000L
            val to = from + step * 60_000L
            val hr = data.hr.filter { it.timestampMs in from until to }
            val p = data.pelvic.filter { it.timestampMs in from until to }.mapNotNull { it.aboveRestKpa }
            if (hr.isEmpty() && p.isEmpty()) return@mapNotNull null
            "| ${SessionAnalysis.elapsed(m * 60_000L)} | ${f0(hr.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average())} | ${hr.maxOfOrNull { it.bpm } ?: ""} | " +
                "${f1(hr.mapNotNull { it.rmssdMs }.lastOrNull())} | ${hr.mapNotNull { it.load }.lastOrNull() ?: ""} | " +
                "${f1(p.takeIf { it.isNotEmpty() }?.average())} | ${f1(p.maxOrNull())} |"
        }
    }

    /** One row per second: HR, rolling HRV, Load, pressure mean/max, and any event in that second. */
    fun csv(data: SessionData): String = buildString {
        val start = data.session.startTimeMs
        appendLine("local_time,elapsed_s,hr_bpm,rmssd_ms,sdnn_ms,pnn50_pct,load,pressure_kpa,above_rest_kpa_mean,above_rest_kpa_max,holding,event,event_note,bp,glucose_mg_dl")
        val hrBySecond = data.hr.groupBy { (it.timestampMs - start) / 1000 }
        val pBySecond = data.pelvic.groupBy { (it.timestampMs - start) / 1000 }
        val mBySecond = data.markers.groupBy { (it.timestampMs - start) / 1000 }
        val bpBySecond = data.bp.groupBy { (it.timestampMs - start) / 1000 }
        val gBySecond = data.glucose.groupBy { (it.timestampMs - start) / 1000 }
        val seconds = (hrBySecond.keys + pBySecond.keys + mBySecond.keys + bpBySecond.keys + gBySecond.keys).sorted()
        seconds.forEach { sec ->
            val hr = hrBySecond[sec]?.lastOrNull()
            val p = pBySecond[sec].orEmpty()
            val above = p.mapNotNull { it.aboveRestKpa }
            val markers = mBySecond[sec].orEmpty()
            fun q(v: String) = if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v
            append(at(start + sec * 1000).toLocalDateTime()).append(',').append(sec).append(',')
            append(hr?.bpm ?: "").append(',').append(hr?.rmssdMs?.let { "%.1f".format(Locale.US, it) } ?: "").append(',')
            append(hr?.sdnnMs?.let { "%.1f".format(Locale.US, it) } ?: "").append(',').append(hr?.pnn50Percent?.let { "%.1f".format(Locale.US, it) } ?: "").append(',')
            append(hr?.load ?: "").append(',')
            append(p.lastOrNull()?.pressureKpa?.let { "%.2f".format(Locale.US, it) } ?: "").append(',')
            append(above.takeIf { it.isNotEmpty() }?.average()?.let { "%.2f".format(Locale.US, it) } ?: "").append(',')
            append(above.maxOrNull()?.let { "%.2f".format(Locale.US, it) } ?: "").append(',')
            append(if (p.isEmpty()) "" else if (p.any { it.holding }) "1" else "0").append(',')
            append(q(markers.joinToString(" | ") { it.label })).append(',')
            append(q(markers.joinToString(" | ") { it.note })).append(',')
            append(bpBySecond[sec]?.joinToString(" | ") { "${it.systolic}/${it.diastolic}" } ?: "").append(',')
            append(gBySecond[sec]?.joinToString(" | ") { "%.0f".format(Locale.US, it.mgDl) } ?: "")
            appendLine()
        }
    }

    fun fileStem(data: SessionData): String =
        "sarah-monitor-" + at(data.session.startTimeMs).format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))

    /** Hands the summary (text) and the per-second CSV (attachment) to ChatGPT, or to any app that accepts them. */
    fun askAi(context: Context, data: SessionData) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, fileStem(data) + ".csv").apply { writeText(csv(data)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "text/*"
            putExtra(Intent.EXTRA_SUBJECT, "Sarah Monitor session review")
            putExtra(Intent.EXTRA_TEXT, markdown(data))
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Session CSV", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent(base).setPackage(CHATGPT_PACKAGE)) }.onFailure {
            runCatching { context.startActivity(Intent.createChooser(base, "Ask AI about this session")) }.onFailure {
                Toast.makeText(context, "No app available to share this session", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
