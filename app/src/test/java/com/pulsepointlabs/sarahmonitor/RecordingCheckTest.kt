package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.session.PelvicSampleEntity
import com.pulsepointlabs.sarahmonitor.session.PressureReview
import com.pulsepointlabs.sarahmonitor.session.SessionAnalysis
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays exported session CSVs (the one-row-per-second export) through the pressure-integrity checks and writes
 * a plain report per file: suspected displacements / shifted rests found, and what happens to the "holding" time.
 * Skipped unless given a folder:  ./gradlew :app:testDebugUnitTest -Precordings=C:\path\to\csvs --tests "*RecordingCheck*"
 * Reports land in app/build/recording-check/. Uses only the CSV's own columns; nothing is invented.
 */
class RecordingCheckTest {
    @Test fun checkRecordings() {
        val dir = System.getProperty("recordings.dir")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("no -Precordings folder given", dir != null && dir.isDirectory)
        val out = File(System.getProperty("recording.report.dir") ?: "build/recording-check").apply { mkdirs() }
        dir!!.listFiles { f -> f.name.endsWith(".csv") }!!.sortedBy { it.name }.forEach { f -> File(out, f.nameWithoutExtension + ".txt").writeText(report(f)) }
    }

    private fun splitCsv(line: String): List<String> {
        val cells = mutableListOf<String>(); val sb = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { cells += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        cells += sb.toString()
        return cells
    }

    private fun report(f: File): String {
        val lines = f.readLines().filter { it.isNotBlank() }
        val head = splitCsv(lines.first())
        fun col(name: String) = head.indexOf(name)
        val iSec = col("elapsed_s"); val iP = col("pressure_kpa"); val iA = col("above_rest_kpa_mean"); val iH = col("holding"); val iF = col("auto_flags")
        if (iSec < 0 || iP < 0) return "Not a Sarah Monitor per-second CSV (no elapsed_s / pressure_kpa)."
        val rows = lines.drop(1).map(::splitCsv)
        val samples = rows.mapNotNull { r ->
            val sec = r.getOrNull(iSec)?.toLongOrNull() ?: return@mapNotNull null
            val p = r.getOrNull(iP)?.toDoubleOrNull() ?: return@mapNotNull null
            PelvicSampleEntity(sessionId = "csv", timestampMs = sec * 1000L, pressureKpa = p, aboveRestKpa = r.getOrNull(iA)?.toDoubleOrNull(), holding = r.getOrNull(iH) == "1", quality = null)
        }
        val holdingS = samples.count { it.holding }
        val q = PressureReview.qualities(samples)
        val faults = PressureReview.faultSpans(samples, q)
        val summary = SessionAnalysis.pelvic(samples, emptyList(), q)
        val oldFlags = if (iF >= 0) rows.flatMap { r -> r.getOrNull(iF).orEmpty().split(" | ").filter { it.isNotBlank() } }.groupingBy { it }.eachCount() else emptyMap()
        fun mmss(ms: Long) = SessionAnalysis.elapsedSigned(ms)
        return buildString {
            appendLine("File: ${f.name}")
            appendLine("Rows: ${rows.size}; seconds with pressure: ${samples.size}; seconds marked holding (old): $holdingS")
            appendLine("Note: this replays 1-second values (last raw pressure, mean above rest). The 10 Hz samples in the app's database are finer; treat timings as ±1 s.")
            appendLine()
            appendLine("Pressure range: ${samples.minOfOrNull { it.pressureKpa }} to ${samples.maxOfOrNull { it.pressureKpa }} kPa raw")
            appendLine("Rest in force (pressure - above rest) changes:")
            var lastRest: Double? = null
            samples.forEach { s ->
                val rest = s.aboveRestKpa?.let { s.pressureKpa - it } ?: return@forEach
                if (lastRest == null || kotlin.math.abs(rest - lastRest!!) >= 0.3) appendLine("  ${mmss(s.timestampMs)}: rest ≈ ${"%.2f".format(rest)} kPa")
                lastRest = rest
            }
            appendLine()
            appendLine("Integrity re-check (PressureIntegrity v${com.pulsepointlabs.sarahmonitor.core.PressureIntegrity.VERSION}):")
            if (faults.isEmpty()) appendLine("  nothing flagged")
            faults.forEach { appendLine("  ${mmss(it.startMs)}–${mmss(it.endMs)}: ${it.quality.label}") }
            appendLine()
            appendLine("After the re-check: ${summary.pulses.size} contractions (≤${com.pulsepointlabs.sarahmonitor.core.PelvicProcessor.MAX_CONTRACTION_S.toInt()} s), ${summary.sustained.size} sustained elevations:")
            summary.sustained.forEach { appendLine("  sustained ${mmss(it.startMs)}–${mmss(it.endMs)} (${it.durationS.toInt()} s), peak ${"%.1f".format(it.peakKpa)} kPa") }
            summary.pulses.forEach { appendLine("  contraction ${mmss(it.startMs)} (${it.durationS.toInt()} s), peak ${"%.1f".format(it.peakKpa)} kPa") }
            appendLine("Usable seconds: ${summary.usableCount} of ${summary.loggedCount}")
            if (oldFlags.isNotEmpty()) {
                appendLine()
                appendLine("Old automatic flags (row-seconds):")
                oldFlags.entries.sortedByDescending { it.value }.forEach { (k, n) -> appendLine("  $k: $n") }
            }
        }
    }
}
