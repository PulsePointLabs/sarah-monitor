package com.pulsepointlabs.sarahmonitor.session

import com.pulsepointlabs.sarahmonitor.core.PressureIntegrity
import com.pulsepointlabs.sarahmonitor.core.PelvicProcessor
import java.io.OutputStream
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Research export: every stored sample at its native rate, with timestamps, source and quality, in one ZIP.
 * Nothing is interpolated or resampled. The ordinary one-row-per-second CSV and the summary are included too.
 */
object ResearchExport {
    /** Bump when a column, a definition or a derived value changes. */
    const val FORMAT_VERSION = 1

    suspend fun write(out: OutputStream, dao: SessionDao, data: SessionData, episodes: List<com.pulsepointlabs.sarahmonitor.response.ResponseEpisode>) {
        val s = data.session
        val ecg = dao.getEcg(s.id, Long.MIN_VALUE, Long.MAX_VALUE)
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, block: (Writer) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                val w = zip.writer()
                block(w)
                w.flush()
                zip.closeEntry()
            }
            entry("README.txt") { it.write(readme(data, ecg.sumOf { c -> c.sampleCount })) }
            entry("summary.md") { it.write(SessionExport.markdown(data)) }
            entry("session-1s.csv") { it.write(SessionExport.csv(data)) }
            entry("pressure.csv") { w -> pressure(w, data) }
            entry("hr.csv") { w -> hr(w, data) }
            entry("rr.csv") { w -> rr(w, data) }
            if (ecg.isNotEmpty()) entry("ecg.csv") { w ->
                w.write("t_ms,elapsed_s,microvolts\n")
                ecg.forEach { chunk ->
                    val (ts, uv) = EcgCodec.decode(chunk)
                    for (i in ts.indices) w.write("${ts[i]},${sec(ts[i] - s.startTimeMs)},${uv[i]}\n")
                }
            }
            if (data.emg.isNotEmpty()) entry("emg.csv") { w ->
                w.write("t_ms,elapsed_s,ch1_raw,ch2_raw,ch1_pct_of_hold,ch2_pct_of_hold\n")
                data.emg.forEach { e -> w.write("${e.timestampMs},${sec(e.timestampMs - s.startTimeMs)},${d(e.ch1)},${d(e.ch2)},${d(e.pct1)},${d(e.pct2)}\n") }
            }
            entry("events.csv") { w -> events(w, data) }
            entry("sensor_events.csv") { w ->
                w.write("t_ms,elapsed_s,local_time,device,kind,detail\n")
                data.sensorEvents.forEach { e -> w.write("${e.timestampMs},${sec(e.timestampMs - s.startTimeMs)},${local(e.timestampMs)},${e.device},${e.kind},${q(e.detail)}\n") }
            }
            entry("bp.csv") { w ->
                w.write("t_ms,elapsed_s,local_time,timing,systolic,diastolic,pulse,irregular,site,posture,source\n")
                data.bp.forEach { b ->
                    w.write("${b.timestampMs},${sec(b.timestampMs - s.startTimeMs)},${local(b.timestampMs)},${q(SessionAnalysis.bpTiming(b.timestampMs, data) ?: "during recording")},${b.systolic},${b.diastolic},${b.pulse ?: ""},${b.irregular?.let { if (it) 1 else 0 } ?: ""},${q(b.site ?: "")},${q(b.posture ?: "")},${q(b.source)}\n")
                }
            }
            if (data.glucose.isNotEmpty()) entry("glucose.csv") { w ->
                w.write("t_ms,elapsed_s,local_time,mg_dl,source\n")
                data.glucose.forEach { g -> w.write("${g.timestampMs},${sec(g.timestampMs - s.startTimeMs)},${local(g.timestampMs)},${d(g.mgDl)},${q(g.source)}\n") }
            }
            if (episodes.isNotEmpty()) entry("episodes.csv") { w -> episodesCsv(w, data, episodes) }
        }
    }

    private val zone get() = ZoneId.systemDefault()
    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().toString()
    private fun sec(ms: Long) = "%.3f".format(Locale.US, ms / 1000.0)
    private fun d(v: Double?, places: Int = 3) = v?.let { "%.${places}f".format(Locale.US, it) } ?: ""
    private fun q(v: String) = if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun pressure(w: Writer, data: SessionData) {
        val start = data.session.startTimeMs
        val quality = data.pelvicQuality
        w.write("t_ms,elapsed_s,pressure_kpa,rest_kpa,above_rest_kpa,holding,quality,quality_source,usable\n")
        data.pelvic.forEachIndexed { i, p ->
            val rest = p.aboveRestKpa?.let { p.pressureKpa - it }
            val src = when {
                quality[i] == com.pulsepointlabs.sarahmonitor.core.PressureQuality.ReportedProblem && p.quality != quality[i].name -> "annotation"
                p.quality == null -> "replayed"
                else -> "live"
            }
            w.write("${p.timestampMs},${sec(p.timestampMs - start)},${d(p.pressureKpa, 2)},${d(rest, 3)},${d(p.aboveRestKpa, 3)},${if (p.holding) 1 else 0},${quality[i].name},$src,${if (quality[i].usable) 1 else 0}\n")
        }
    }

    private fun hr(w: Writer, data: SessionData) {
        val start = data.session.startTimeMs
        w.write("t_ms,elapsed_s,hr_bpm,rmssd_ms_rolling,sdnn_ms_rolling,pnn50_pct_rolling,load_0_100,stress_est_0_100,breaths_per_min,contact\n")
        data.hr.forEach { h ->
            w.write("${h.timestampMs},${sec(h.timestampMs - start)},${h.bpm},${d(h.rmssdMs, 1)},${d(h.sdnnMs, 1)},${d(h.pnn50Percent, 1)},${h.load ?: ""},${h.stress ?: ""},${d(h.breathsPerMin, 1)},${h.contact?.let { if (it) 1 else 0 } ?: ""}\n")
        }
    }

    /**
     * RR intervals arrive in H10 heart-rate packets (about 1/s); every interval in a packet carries the packet's
     * arrival time. The estimated beat time is the packet time minus the later intervals in the same packet.
     */
    private fun rr(w: Writer, data: SessionData) {
        val start = data.session.startTimeMs
        w.write("packet_t_ms,est_beat_t_ms,elapsed_s_est,raw_ms,clean_ms,accepted,artifact,ectopic,rejection_reason\n")
        var i = 0
        val rows = data.rr
        while (i < rows.size) {
            var j = i
            while (j + 1 < rows.size && rows[j + 1].timestampMs == rows[i].timestampMs) j++
            var after = 0L
            val est = LongArray(j - i + 1)
            for (k in j downTo i) { est[k - i] = rows[k].timestampMs - after; after += rows[k].rawMs }
            for (k in i..j) {
                val r = rows[k]
                val ectopic = com.pulsepointlabs.sarahmonitor.hrv.HrvCalculator.isEctopicReason(r.rejectionReason)
                w.write("${r.timestampMs},${est[k - i]},${sec(est[k - i] - start)},${r.rawMs},${r.cleanMs ?: ""},${if (r.accepted) 1 else 0},${if (r.artifact) 1 else 0},${if (ectopic) 1 else 0},${r.rejectionReason ?: ""}\n")
            }
            i = j + 1
        }
    }

    private fun events(w: Writer, data: SessionData) {
        val start = data.session.startTimeMs
        w.write("t_ms,end_t_ms,elapsed_s,local_time,types,label,note,source,retrospective,timing_uncertainty_s,feeling_1_5,self_stress_0_10,live_snapshot_json\n")
        data.markers.forEach { m ->
            val src = EventSource.of(m.source)
            w.write("${m.timestampMs},${m.endTimeMs ?: ""},${sec(m.timestampMs - start)},${local(m.timestampMs)},${q(m.types)},${q(m.label)},${q(m.note)},${src.name},${if (src.retrospective) 1 else 0},${m.uncertaintyS ?: ""},${m.wellbeing ?: ""},${m.selfStress ?: ""},${q(m.snapshot ?: "")}\n")
        }
    }

    private fun episodesCsv(w: Writer, data: SessionData, episodes: List<com.pulsepointlabs.sarahmonitor.response.ResponseEpisode>) {
        val start = data.session.startTimeMs
        w.write("n,kind,start_elapsed_s,peak_elapsed_s,end_elapsed_s,duration_s,trough_hr,peak_hr,rise_bpm,build_s,hr_rise_bpm_per_min,rmssd_before_ms,rmssd_at_peak_ms," +
            "pressure_peak_kpa,pressure_integral_kpa_s,pressure_usable_pct,contractions,load_peak,load_minutes,drop_30s_bpm,drop_60s_bpm,half_recovery_s,recovery_low_hr,recovery_fraction,next_gap_s,peak_approach_0_100,confidence,marked_events\n")
        episodes.forEachIndexed { i, e ->
            w.write(listOf(
                "${i + 1}", e.kind.name, sec(e.startMs - start), sec(e.peakMs - start), sec(e.endMs - start), sec(e.durationMs),
                d(e.troughHr, 1), d(e.peakHr, 1), d(e.riseBpm, 1), e.buildS?.toString() ?: "", d(e.hrRiseBpmPerMin, 1), d(e.rmssdBefore, 1), d(e.rmssdAtPeak, 1),
                d(e.pelvicPeakKpa, 2), d(e.pressureIntegralKpaS, 1), e.pressureUsablePct?.toString() ?: "", "${e.contractions}", e.loadPeak?.toString() ?: "", d(e.loadMinutes, 1),
                d(e.drop30, 1), d(e.drop60, 1), e.halfRecoveryS?.toString() ?: "", d(e.recoveryLowHr, 1), d(e.recoveryFraction, 2), e.nextGapS?.toString() ?: "",
                "${e.peakApproach}", e.confidence, q(e.markedEvents.joinToString(" | "))
            ).joinToString(",") + "\n")
        }
    }

    private fun readme(data: SessionData, ecgSamples: Int): String {
        val s = data.session
        val p = data.pelvicSummary
        val durS = (data.endMs - s.startTimeMs) / 1000.0
        val pressureRate = if (data.pelvic.size >= 2) data.pelvic.size / ((data.pelvic.last().timestampMs - data.pelvic.first().timestampMs) / 1000.0).coerceAtLeast(1.0) else null
        return """
Sarah Monitor research export, format $FORMAT_VERSION
Session: ${s.title}
Type: ${SessionCategory.of(s.category)?.label ?: "unknown"}
Start: ${local(s.startTimeMs)} (${zone.id}); t_ms is Unix epoch milliseconds, elapsed_s is from the session start.
Duration: ${"%.0f".format(Locale.US, durS)} s

WHAT IS HERE (all at native rate; nothing interpolated or resampled)
- pressure.csv: every CIVET sample (${data.pelvic.size}; about ${pressureRate?.let { "%.1f".format(Locale.US, it) } ?: "?"} per second over the time it was connected; the sensor sends 10/s).
- hr.csv: every H10 heart-rate packet (${data.hr.size}) with the rolling HRV, Load and stress at that moment.
- rr.csv: every RR interval (${data.rr.size}) with its artifact/ectopic classification.
- ecg.csv: ${if (ecgSamples > 0) "$ecgSamples raw H10 ECG samples (130 Hz, microvolts)." else "not recorded in this session."}
- emg.csv: ${if (data.emg.isNotEmpty()) "${data.emg.size} EMG samples (25 Hz averages). EMG is NOT validated in Sarah yet." else "not recorded."}
- events.csv: events you marked (live or added afterwards), with source and timing uncertainty.
- sensor_events.csv: connection changes, calibration, logging on/off, suspected displacement, resumes (${data.sensorEvents.size}; recorded from 0.25.0).
- bp.csv / glucose.csv: readings from 15 min before to 15 min after (BP) or 2 h before (glucose); "timing" says which were outside the recording.
- episodes.csv: candidate arousal episodes (sexual and exploration sessions), as shown in the review.
- session-1s.csv and summary.md: the ordinary export (one row per second, plus a written summary).

UNITS AND DEFINITIONS
- pressure_kpa: raw CIVET reading, kPa, relative to the sensor's own zero. Mechanical pressure, not calibrated muscle force.
- rest_kpa: the rest level in force (captured 5 s relaxed rest, or auto rest = 20th percentile of the last 30 s).
  A rest is a relative mechanical reference, not "zero anatomical pressure".
- above_rest_kpa = pressure_kpa - rest_kpa.
- holding: the live detector's hold state (threshold max(0.3 kPa, 5x rest noise), release below half the threshold for 0.2 s).
- quality: Valid (logging on, no fault), Preview (logging off), Calibrating, Stabilizing (5 s after a gap of 3 s or more),
  SuspectedDisplacement, BaselineShift, ReportedProblem (a "Sensor problem" event), Unknown (recorded before 0.25).
  quality_source: live = stored when recorded; replayed = recomputed now from the raw samples (older recordings);
  annotation = overridden by a "Sensor problem" event. usable = 1 when the sample may be analyzed.
- Contraction: a hold of 0.2 to ${PelvicProcessor.MAX_CONTRACTION_S.toInt()} s on usable samples. Longer holds are "sustained elevation", origin unknown.
- HRV (hr.csv): RMSSD/SDNN/pNN50 over a rolling 2-minute window of accepted normal-to-normal intervals. Short-window values are
  exploratory and not equivalent to standard 5-minute recordings. Whole-session values (summary.md) use all accepted NN intervals.
- RR: packet_t_ms is when the H10 packet arrived (several intervals share it). est_beat_t_ms subtracts the later intervals in the
  same packet: an estimate of when each beat ended, not a measured beat time.
- Load (0-100): relative physiological load from HR and HRV against a personal resting baseline. Not a standardized measure.
- stress_est: Sarah's estimate from HRV, unexplained HR, breathing, tension and movement. Not a validated measure.

SIGNAL-INTEGRITY RULE (PressureIntegrity v${PressureIntegrity.VERSION}; exploratory thresholds, not yet validated on recordings)
- Suspected displacement: above rest <= -max(${PressureIntegrity.DROP_MIN_KPA} kPa, ${PressureIntegrity.DROP_NOISE_MULT.toInt()}x rest noise), or pressure under ${(PressureIntegrity.PRELOAD_FRACTION * 100).toInt()}% of a rest of at least ${PressureIntegrity.PRELOAD_MIN_REST_KPA} kPa
  (and at least ${PressureIntegrity.PRELOAD_MIN_DROP_KPA} kPa below it), lasting ${PressureIntegrity.DROP_PERSIST_MS} ms.
- Rest shifted: with a captured rest, the 30 s floor (20th percentile) at least max(${PressureIntegrity.SHIFT_MIN_KPA} kPa, ${PressureIntegrity.SHIFT_NOISE_MULT.toInt()}x noise) below rest for ${PressureIntegrity.SHIFT_PERSIST_MS / 1000} s.
- Live, a fault stops analysis until the user recaptures rest or resumes. Replayed on older recordings, a fault is marked while
  the condition lasts plus ${PressureReview.REPLAY_TAIL_MS / 1000} s.
- A "Sensor problem" event excludes its range, or ${PressureReview.POINT_EXCLUSION_MS / 1000} s after a single moment.

THIS SESSION
- Pressure: ${p.loggedCount} logged samples, ${p.usableCount} usable${p.usableFraction?.let { " (${"%.0f".format(Locale.US, it * 100)}%)" } ?: ""}; ${p.faults.size} excluded stretch(es); ${p.pulses.size} contractions; ${p.sustained.size} sustained elevation(s).
- Events: ${data.markers.size} (${data.markers.count { EventSource.of(it.source).retrospective }} added afterwards).

Original recordings are never modified. Derived values here can be recomputed from the raw columns.
""".trimStart()
    }
}
