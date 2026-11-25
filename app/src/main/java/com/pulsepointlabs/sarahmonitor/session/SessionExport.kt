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
        val session = if (stored.active) SessionAnalysis.summarize(stored, hr, rr, pelvic, markers.size, markers) else stored
        return SessionData(
            session = session,
            hr = hr,
            rr = rr,
            pelvic = pelvic,
            markers = markers,
            bp = MonitorHub.bp.items.value.filter { it.timestampMs in (session.startTimeMs - BP_BEFORE_MS)..(end + BP_AFTER_MS) },
            glucose = MonitorHub.glucose.items.value.filter { it.timestampMs in (session.startTimeMs - GLUCOSE_BEFORE_MS)..(end + BP_AFTER_MS) },
            hasEcg = dao.ecgChunkCount(id) > 0,
            emg = dao.getEmg(id),
            sensorEvents = dao.getSensorEvents(id)
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
        val pelvic = data.pelvicSummary
        val tz = zone.getDisplayName(TextStyle.FULL, Locale.US)
        return buildString {
            appendLine("# Sarah Monitor Session Review")
            appendLine()
            appendLine("## Prompt")
            appendLine(
                "Please review this Sarah Monitor session. Times are local ($tz) and elapsed times are from the session start. " +
                    "Summarize the heart rate, HRV, Load and pelvic pressure patterns, relate them to the events I marked and to any blood " +
                    "pressure or glucose readings, and point out data-quality limits. Pelvic pressure is mechanical pressure above my " +
                    "resting level from a CIVET sensor, not calibrated muscle force. Pressure marked as a sensor problem (suspected " +
                    "displacement, shifted rest, or reported by me) is excluded from contraction counts and statistics. Events added " +
                    "after the session are labeled with their source and timing uncertainty; treat them as my recollection, not as " +
                    "sensor detections. This is for personal insight, not a diagnosis. The attached CSV has one row per second; the " +
                    "app's research export has every native sample."
            )
            appendLine()
            appendLine("## Session")
            appendLine("- Title: ${s.title}")
            appendLine("- Started: ${at(s.startTimeMs).format(dateTime)} ($tz)")
            appendLine("- Ended: ${s.endTimeMs?.let { at(it).format(dateTime) } ?: "still recording"}")
            appendLine("- Duration: ${SessionAnalysis.durationWords(data.endMs - s.startTimeMs)}")
            if (s.notes.isNotBlank()) appendLine("- Notes: ${oneLine(s.notes)}")
            appendLine("- Data: HR ${data.hr.size} samples, RR ${data.rr.size}, pressure ${data.pelvic.size} samples, events ${data.markers.size}, sensor events ${data.sensorEvents.size}, BP ${data.bp.size}, glucose ${data.glucose.size}")
            SessionCategory.of(s.category)?.let { appendLine("- Type: ${it.label}") }
            appendLine()
            val details = SessionDetails.parse(s.details)
            if (!details.empty) {
                appendLine("## Session details (as I recorded them)")
                details.describe(SessionCategory.of(s.category)).forEach { appendLine("- ${oneLine(it)}") }
                appendLine()
            }
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
                appendLine("- Peak above rest: ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(pelvic.maxKpa)}; average above rest ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(pelvic.averageKpa)} (usable pressure only)")
                appendLine("- Contractions: ${pelvic.pulses.size}; longest hold ${f1(pelvic.longestHoldS)} s")
                pelvic.pulses.take(40).forEach { appendLine("  - ${elapsed(it.startMs)}: ${f1(it.durationS)} s, peak ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(it.peakKpa)}") }
                if (pelvic.pulses.size > 40) appendLine("  - …${pelvic.pulses.size - 40} more in the CSV")
                if (pelvic.sustained.isNotEmpty()) {
                    appendLine("- Sustained elevations (longer than ${com.pulsepointlabs.sarahmonitor.core.PelvicProcessor.MAX_CONTRACTION_S.toInt()} s; not counted as contractions, origin unknown):")
                    pelvic.sustained.forEach { appendLine("  - ${elapsed(it.startMs)}–${elapsed(it.endMs)}: peak ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(it.peakKpa)}") }
                }
                appendLine("- Quality: ${pelvic.usableFraction?.let { "%.0f".format(Locale.US, it * 100) + "% of logged pressure usable" } ?: "logging was never switched on (preview only)"}")
                pelvic.faults.forEach { appendLine("  - ${elapsed(it.startMs)}–${elapsed(it.endMs)}: ${it.quality.label}${if (it.replayed) " (re-checked older recording)" else ""}; excluded") }
            }
            val sensorLines = data.sensorEvents.filter { it.kind != "connection" || it.device == "civet" || it.device == "h10" }
            if (sensorLines.isNotEmpty()) {
                appendLine()
                appendLine("## Sensor log (recorded automatically)")
                sensorLines.take(80).forEach { appendLine("- ${SessionAnalysis.elapsedSigned(it.timestampMs - s.startTimeMs)} ${it.device}: ${it.kind.replace('_', ' ')}; ${oneLine(it.detail)}") }
                if (sensorLines.size > 80) appendLine("- …${sensorLines.size - 80} more in the research export")
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
                val src = EventSource.of(m.source)
                val timing = listOfNotNull(
                    m.endTimeMs?.let { "until ${elapsed(it)}" },
                    m.uncertaintyS?.let { "±$it s" },
                    src.takeIf { it.retrospective }?.let { "added afterwards: ${it.label.lowercase()}" }
                ).joinToString(", ").let { if (it.isEmpty()) "" else " [$it]" }
                appendLine(
                    "- ${elapsed(m.timestampMs)} (${at(m.timestampMs).format(clock)}): ${m.label}$timing" +
                        "; HR ${c.hrAt ?: "unknown"}" + (c.rmssdAt?.let { "; RMSSD ${f1(it)} ms" } ?: "") +
                        (c.pressureAt?.let { "; pressure ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(it)}" } ?: "") +
                        (c.peakPressureNext30s?.let { "; peak pressure next 30 s ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(it)}" } ?: "") +
                        (if (response.isNotEmpty()) "; $response" else "") +
                        (m.note.takeIf { it.isNotBlank() }?.let { "; note: ${oneLine(it)}" } ?: "") +
                        (com.pulsepointlabs.sarahmonitor.session.CheckIn.of(m).label?.let { "; check-in: $it" } ?: "") +
                        (com.pulsepointlabs.sarahmonitor.core.EventSnapshot.describe(m.snapshot)?.let { "; at that moment: $it" } ?: "") +
                        (data.hr.minByOrNull { kotlin.math.abs(it.timestampMs - m.timestampMs) }?.stress?.let { "; estimated stress $it/100" } ?: "")
                )
            }
            val category = SessionCategory.of(s.category)
            if (category != null && category.sexual) {
                val episodes = runCatching { com.pulsepointlabs.sarahmonitor.response.ResponseEpisodes.detect(data, com.pulsepointlabs.sarahmonitor.response.ResponseSettings.profile.value) }.getOrDefault(emptyList())
                appendLine()
                appendLine(if (category == SessionCategory.Exploration) "## Response episodes (candidate; body exploration, not assumed sexual)" else "## Candidate arousal episodes (detected from HR/HRV/pressure, not confirmed)")
                if (episodes.isEmpty()) appendLine("- None detected.") else {
                    appendLine("| # | time | kind | trough→peak HR | build | recovery | pressure peak | contractions | load peak | data quality |")
                    appendLine("|---:|---|---|---|---:|---:|---:|---:|---:|---|")
                    episodes.forEachIndexed { i, e ->
                        appendLine(
                            "| ${i + 1} | ${elapsed(e.startMs)}–${elapsed(e.endMs)} | ${e.kind.label} | ${f0(e.troughHr)}→${f0(e.peakHr)} | " +
                                "${e.buildS?.let { SessionAnalysis.durationWords(it * 1000L) } ?: ""} | ${e.recoveryFraction?.let { "%.0f".format(Locale.US, it * 100) + "%" } ?: ""} | " +
                                "${f1(e.pelvicPeakKpa)} | ${e.contractions} | ${e.loadPeak ?: ""} | ${e.confidence} |" + (e.stimAtPeak?.let { " Howl: $it" } ?: "")
                        )
                    }
                    val progression = com.pulsepointlabs.sarahmonitor.response.ResponseEpisodes.progression(episodes)
                    if (progression.isNotEmpty()) {
                        appendLine()
                        appendLine("Episode to episode:")
                        progression.forEach { appendLine("- $it") }
                    }
                }
            }
            appendLine()
            val flags = SessionFlags.detect(data)
            appendLine("## Automatic flags (found by the app, not entered by me)")
            if (flags.isEmpty()) appendLine("- Nothing flagged.")
            flags.forEach { f ->
                appendLine(
                    "- ${SessionAnalysis.elapsedSigned(f.startMs - s.startTimeMs)}" + (if (f.durationMs >= 1_000L) "–${SessionAnalysis.elapsedSigned(f.endMs - s.startTimeMs)}" else "") +
                        " (${at(f.startMs).format(clock)}): ${f.kind.title} [${f.severity.name.lowercase()}]; ${f.detail}" +
                        (if (f.alongside.isNotEmpty()) "; alongside ${f.alongside.joinToString(", ") { it.lowerTitle }}" else "")
                )
            }
            appendLine()
            appendLine("## Blood pressure")
            if (data.bp.isEmpty()) appendLine("- No readings during the session (±15 min).")
            // Readings before the start or after the end say so: they are context, not measurements taken during it.
            data.bp.forEach { appendLine("- ${at(it.timestampMs).format(clock)} (${SessionAnalysis.bpTiming(it.timestampMs, data) ?: elapsed(it.timestampMs)}): ${it.systolic}/${it.diastolic}${it.pulse?.let { p -> ", pulse $p" } ?: ""}${it.positionLabel?.let { p -> ", $p" } ?: ""}") }
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
            val p = data.pelvic.indices.filter { data.pelvic[it].timestampMs in from until to && data.pelvicQuality[it].usable }.mapNotNull { data.pelvic[it].aboveRestKpa }
            if (hr.isEmpty() && p.isEmpty()) return@mapNotNull null
            "| ${SessionAnalysis.elapsed(m * 60_000L)} | ${f0(hr.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average())} | ${hr.maxOfOrNull { it.bpm } ?: ""} | " +
                "${f1(hr.mapNotNull { it.rmssdMs }.lastOrNull())} | ${hr.mapNotNull { it.load }.lastOrNull() ?: ""} | " +
                "${f1(p.takeIf { it.isNotEmpty() }?.average())} | ${f1(p.maxOrNull())} |"
        }
    }

    /** One row per second: HR, rolling HRV, Load, pressure mean/max, and any event in that second. */
    fun csv(data: SessionData): String = buildString {
        val start = data.session.startTimeMs
        // New columns go at the end so existing column positions never move.
        appendLine("local_time,elapsed_s,hr_bpm,rmssd_ms,sdnn_ms,pnn50_pct,load,stress_est,breaths_per_min,pressure_kpa,above_rest_kpa_mean,above_rest_kpa_max,holding,event,event_note,feeling_1_5,self_stress_0_10,event_snapshot_json,bp,glucose_mg_dl,auto_flags,pressure_quality,rest_kpa,event_source")
        val flags = SessionFlags.detect(data)
        val quality = data.pelvicQuality
        // floorDiv everywhere, so a reading from before the start lands on its own negative second instead of second 0.
        fun second(t: Long) = Math.floorDiv(t - start, 1000L)
        val hrBySecond = data.hr.groupBy { second(it.timestampMs) }
        // Indices, so each sample keeps its quality.
        val pBySecond = data.pelvic.indices.groupBy { second(data.pelvic[it].timestampMs) }
        val mBySecond = data.markers.groupBy { second(it.timestampMs) }
        val bpBySecond = data.bp.groupBy { second(it.timestampMs) }
        val gBySecond = data.glucose.groupBy { second(it.timestampMs) }
        val seconds = (hrBySecond.keys + pBySecond.keys + mBySecond.keys + bpBySecond.keys + gBySecond.keys).sorted()
        seconds.forEach { sec ->
            val hr = hrBySecond[sec]?.lastOrNull()
            val pi = pBySecond[sec].orEmpty()
            val p = pi.map { data.pelvic[it] }
            // Raw above-rest values stay (pressure_quality says whether they may be analyzed); holds only on usable samples.
            val above = p.mapNotNull { it.aboveRestKpa }
            val heldUsable = pi.any { data.pelvic[it].holding && quality[it].usable }
            val worst = pi.map { quality[it] }.let { qs -> qs.firstOrNull { it.fault } ?: qs.firstOrNull { !it.usable } ?: qs.lastOrNull() }
            val markers = mBySecond[sec].orEmpty()
            fun q(v: String) = if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v
            append(at(start + sec * 1000).toLocalDateTime()).append(',').append(sec).append(',')
            append(hr?.bpm ?: "").append(',').append(hr?.rmssdMs?.let { "%.1f".format(Locale.US, it) } ?: "").append(',')
            append(hr?.sdnnMs?.let { "%.1f".format(Locale.US, it) } ?: "").append(',').append(hr?.pnn50Percent?.let { "%.1f".format(Locale.US, it) } ?: "").append(',')
            append(hr?.load ?: "").append(',')
            append(hr?.stress ?: "").append(',').append(hr?.breathsPerMin?.let { "%.1f".format(Locale.US, it) } ?: "").append(',')
            append(p.lastOrNull()?.pressureKpa?.let { "%.2f".format(Locale.US, it) } ?: "").append(',')
            append(above.takeIf { it.isNotEmpty() }?.average()?.let { "%.2f".format(Locale.US, it) } ?: "").append(',')
            append(above.maxOrNull()?.let { "%.2f".format(Locale.US, it) } ?: "").append(',')
            append(if (p.isEmpty()) "" else if (heldUsable) "1" else "0").append(',')
            append(q(markers.joinToString(" | ") { it.label })).append(',')
            append(q(markers.joinToString(" | ") { it.note })).append(',')
            append(markers.mapNotNull { it.wellbeing }.joinToString(" | ")).append(',')
            append(markers.mapNotNull { it.selfStress }.joinToString(" | ")).append(',')
            append(q(markers.mapNotNull { it.snapshot }.joinToString(" | "))).append(',')
            append(bpBySecond[sec]?.joinToString(" | ") { "${it.systolic}/${it.diastolic}" } ?: "").append(',')
            append(gBySecond[sec]?.joinToString(" | ") { "%.0f".format(Locale.US, it.mgDl) } ?: "").append(',')
            val t = start + sec * 1000
            // Each flag kind once per row: two overlapping flags of the same kind used to print the title twice.
            append(q(flags.filter { t + 999 >= it.startMs && t <= it.endMs }.map { it.kind.title }.distinct().joinToString(" | "))).append(',')
            append(worst?.name ?: "").append(',')
            append(p.lastOrNull()?.let { s -> s.aboveRestKpa?.let { "%.3f".format(Locale.US, s.pressureKpa - it) } } ?: "").append(',')
            append(q(markers.joinToString(" | ") { EventSource.of(it.source).name }))
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
