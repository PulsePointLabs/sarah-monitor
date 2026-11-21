package com.pulsepointlabs.sarahmonitor.sleep

import android.content.Context
import com.pulsepointlabs.sarahmonitor.vitals.SpO2Entry
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class SleepStage(val label: String, val lane: Int) { Wake("Awake", 0), Rem("REM", 1), Light("Light", 2), Deep("Deep", 3) }

enum class BodyPosition(val label: String) { Back("Back"), Front("Front"), Left("Left side"), Right("Right side"), Upright("Upright"), Unknown("—") }

/** 30 s of night. */
data class SleepEpoch(
    val startMs: Long,
    val hr: Double?,
    val rmssd: Double?,
    val movementMg: Double,
    val position: BodyPosition,
    val breathsPerMin: Double?,
    val stage: SleepStage = SleepStage.Light
)

data class NightSummary(
    val startMs: Long,
    val endMs: Long,
    val score: Int,
    val asleepMin: Int,
    val inBedMin: Int,
    val latencyMin: Int,
    val wasoMin: Int,
    val wakeBouts: Int,
    val stageMin: Map<SleepStage, Int>,
    val avgHr: Double?,
    val minHr: Double?,
    val avgRmssd: Double?,
    val avgBreaths: Double?,
    val positionMin: Map<BodyPosition, Int>,
    val positionChanges: Int,
    val spo2Avg: Double?,
    val spo2Min: Int?,
    val spo2Below90Min: Int?
) {
    val efficiency: Int get() = if (inBedMin == 0) 0 else asleepMin * 100 / inBedMin
}

/** Strap orientation references for body position (captured with two taps at bedtime; kept for later nights). */
object PositionCalibration {
    val back = MutableStateFlow<DoubleArray?>(null)
    val right = MutableStateFlow<DoubleArray?>(null)
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun load(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("sarahmonitor_sleep_cal", Context.MODE_PRIVATE)
        prefs = p
        fun read(k: String) = p.getString(k, null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }?.toDoubleArray()
        back.value = read("back"); right.value = read("right")
    }

    fun set(which: String, g: DoubleArray) {
        if (which == "back") back.value = g else right.value = g
        prefs?.edit()?.putString(which, g.joinToString(","))?.apply()
    }

    val ready: Boolean get() = back.value != null && right.value != null
}

object SleepMath {
    fun unit(v: DoubleArray): DoubleArray { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 1e-9 } ?: 1.0; return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n) }
    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    fun angle(a: DoubleArray, b: DoubleArray) = Math.toDegrees(acos(dot(unit(a), unit(b)).coerceIn(-1.0, 1.0)))

    /** Body position from the gravity vector, using the calibrated back and right-side references. */
    fun position(g: DoubleArray, back: DoubleArray?, right: DoubleArray?): BodyPosition {
        if (back == null || right == null) return BodyPosition.Unknown
        val candidates = listOf(
            BodyPosition.Back to back, BodyPosition.Front to back.map { -it }.toDoubleArray(),
            BodyPosition.Right to right, BodyPosition.Left to right.map { -it }.toDoubleArray()
        )
        val (best, ang) = candidates.map { it.first to angle(g, it.second) }.minBy { it.second }
        // Far from every lying direction → gravity runs head-to-foot: sitting/standing.
        return if (ang > 55) BodyPosition.Upright else best
    }

    /**
     * Breathing rate from respiratory sinus arrhythmia: the RR tachogram resampled at 4 Hz, detrended, and the
     * strongest frequency between 0.1 and 0.5 Hz (6–30 breaths/min). Null when the peak isn't clear.
     */
    fun breathsFromRr(rrMs: List<Int>): Double? {
        if (rrMs.size < 40) return null
        val times = DoubleArray(rrMs.size)
        var acc = 0.0
        rrMs.forEachIndexed { i, r -> acc += r / 1000.0; times[i] = acc }
        val fs = 4.0
        val n = ((times.last() - times.first()) * fs).toInt()
        if (n < 160) return null
        val x = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val t = times.first() + k / fs
            while (j < times.size - 2 && times[j + 1] < t) j++
            val f = ((t - times[j]) / (times[j + 1] - times[j])).coerceIn(0.0, 1.0)
            x[k] = rrMs[j] + f * (rrMs[j + 1] - rrMs[j])
        }
        val mean = x.average()
        for (k in x.indices) x[k] -= mean
        var bestF = 0.0; var bestP = 0.0; var total = 0.0
        var f = 0.1
        while (f <= 0.5) {
            var re = 0.0; var im = 0.0
            for (k in x.indices) { val a = 2 * PI * f * k / fs; re += x[k] * cos(a); im -= x[k] * sin(a) }
            val p = re * re + im * im
            total += p
            if (p > bestP) { bestP = p; bestF = f }
            f += 0.005
        }
        // The peak should stand clearly above the band's average.
        val steps = ((0.5 - 0.1) / 0.005).toInt() + 1
        return if (bestP > total / steps * 4) bestF * 60 else null
    }

    fun rmssd(rr: List<Int>): Double? {
        if (rr.size < 10) return null
        val d = rr.zipWithNext { a, b -> (b - a).toDouble() }
        return sqrt(d.sumOf { it * it } / d.size)
    }

    private fun percentile(v: List<Double>, p: Double): Double? = v.sorted().let { if (it.isEmpty()) null else it[((it.size - 1) * p).roundToInt()] }

    /** Night-relative staging. Heuristic estimate from HR, HRV, movement and breathing — not polysomnography. */
    fun stage(epochs: List<SleepEpoch>): List<SleepEpoch> {
        if (epochs.isEmpty()) return epochs
        val hrs = epochs.mapNotNull { it.hr }
        val rms = epochs.mapNotNull { it.rmssd }
        val hrMed = percentile(hrs, 0.5) ?: 60.0
        // Deep sleep is the slowest, most regular part of the night: bottom 20% of HR, top 40% of HRV.
        val hrLow = percentile(hrs, 0.2) ?: hrMed
        val rmMed = percentile(rms, 0.5) ?: 30.0
        val rmHigh = percentile(rms, 0.6) ?: rmMed
        val raw = epochs.mapIndexed { i, e ->
            val window = epochs.subList((i - 2).coerceAtLeast(0), (i + 3).coerceAtMost(epochs.size))
            val wHr = window.mapNotNull { it.hr }
            val hrSd = if (wHr.size >= 3) sqrt(wHr.map { (it - wHr.average()).let { d -> d * d } }.average()) else 0.0
            val br = window.mapNotNull { it.breathsPerMin }
            val brCv = if (br.size >= 3) sqrt(br.map { (it - br.average()).let { d -> d * d } }.average()) / br.average() else 0.0
            val moving = e.movementMg > 60 || window.count { it.movementMg > 60 } >= 3
            val hr = e.hr ?: hrMed
            when {
                e.position == BodyPosition.Upright || moving || hr > hrMed + 12 -> SleepStage.Wake
                hr <= hrLow + 0.5 && (e.rmssd ?: 0.0) >= rmHigh && hrSd < 2.5 && brCv < 0.12 && e.movementMg < 25 -> SleepStage.Deep
                hrSd >= 3.0 && (e.rmssd ?: rmMed) <= rmMed && e.movementMg < 35 && (brCv >= 0.12 || br.size < 3) -> SleepStage.Rem
                else -> SleepStage.Light
            }
        }
        // Smooth single-epoch flickers; REM isn't scored in the first 45 min after sleep onset.
        val onset = raw.indices.firstOrNull { i -> raw.drop(i).take(20).count { it != SleepStage.Wake } >= 18 } ?: 0
        return epochs.mapIndexed { i, e ->
            var s = raw[i]
            if (i in 1 until raw.size - 1 && raw[i - 1] == raw[i + 1] && raw[i - 1] != s) s = raw[i - 1]
            if (i < onset) s = SleepStage.Wake
            if (s == SleepStage.Rem && i < onset + 90) s = SleepStage.Light
            e.copy(stage = s)
        }
    }

    fun summarize(staged: List<SleepEpoch>, endMs: Long, spo2: List<SpO2Entry>): NightSummary {
        val start = staged.firstOrNull()?.startMs ?: endMs
        val inBed = staged.size / 2
        val asleep = staged.count { it.stage != SleepStage.Wake } / 2
        val onset = staged.indexOfFirst { it.stage != SleepStage.Wake }.let { if (it < 0) staged.size else it }
        val lastSleep = staged.indexOfLast { it.stage != SleepStage.Wake }
        val waso = if (lastSleep > onset) staged.subList(onset, lastSleep).count { it.stage == SleepStage.Wake } / 2 else 0
        var bouts = 0
        var run = 0
        if (lastSleep > onset) staged.subList(onset, lastSleep).forEach { if (it.stage == SleepStage.Wake) run++ else { if (run >= 2) bouts++; run = 0 } }
        val stageMin = SleepStage.entries.associateWith { s -> staged.count { it.stage == s } / 2 }
        val positions = staged.filter { it.position != BodyPosition.Unknown }
        val posMin = BodyPosition.entries.filter { it != BodyPosition.Unknown }.associateWith { p -> positions.count { it.position == p } / 2 }
        val changes = positions.zipWithNext().count { (a, b) -> a.position != b.position }
        val night = spo2.filter { it.timestampMs in start..endMs }
        val spAvg = night.takeIf { it.isNotEmpty() }?.map { it.spo2 }?.average()
        val below90 = if (night.size >= 2) night.zipWithNext().filter { it.first.spo2 < 90 }.sumOf { (it.second.timestampMs - it.first.timestampMs).coerceAtMost(60_000L) } / 60_000L else null
        // Score: duration (40) + efficiency (25) + deep+REM share (20) + few awakenings (15).
        val duration = (asleep / 480.0).coerceAtMost(1.0) * 40
        val eff = (if (inBed == 0) 0.0 else asleep.toDouble() / inBed).let { ((it - 0.6) / 0.35).coerceIn(0.0, 1.0) } * 25
        val restorative = (stageMin.getValue(SleepStage.Deep) + stageMin.getValue(SleepStage.Rem)).toDouble() / asleep.coerceAtLeast(1)
        val restore = (restorative / 0.4).coerceAtMost(1.0) * 20
        val calm = (1 - bouts / 8.0).coerceIn(0.0, 1.0) * 15
        val hrs = staged.filter { it.stage != SleepStage.Wake }.mapNotNull { it.hr }
        return NightSummary(
            start, endMs, (duration + eff + restore + calm).roundToInt().coerceIn(0, 100), asleep, inBed,
            onset / 2, waso, bouts, stageMin,
            hrs.takeIf { it.isNotEmpty() }?.average(), hrs.minOrNull(),
            staged.mapNotNull { it.rmssd }.takeIf { it.isNotEmpty() }?.average(),
            staged.mapNotNull { it.breathsPerMin }.takeIf { it.isNotEmpty() }?.average(),
            posMin, changes, spAvg, night.minOfOrNull { it.spo2 }, below90?.toInt()
        )
    }
}

/**
 * Live overnight tracking from the H10: builds 30 s epochs (HR, HRV, movement, position, breathing) while sleep
 * mode is on. Lightweight — it doesn't need a full recording (no all-night ECG).
 */
class SleepEngine {
    private var startMs: Long? = null
    private var epochStart = 0L
    private val hr = mutableListOf<Int>()
    private val rr = mutableListOf<Int>()
    private val rrRecent = ArrayDeque<Int>()
    private val dyn = mutableListOf<Double>()
    private val grav = doubleArrayOf(0.0, 0.0, 0.0)
    private var gravN = 0
    private val epochs = mutableListOf<SleepEpoch>()
    @Volatile var lastGravity: DoubleArray? = null
        private set

    val running: Boolean get() = startMs != null

    @Synchronized
    fun start(now: Long) {
        startMs = now; epochStart = now; epochs.clear(); clearEpoch(); rrRecent.clear()
    }

    @Synchronized
    fun stop(): List<SleepEpoch> { close(System.currentTimeMillis()); startMs = null; return epochs.toList() }

    @Synchronized
    fun epochs(): List<SleepEpoch> = epochs.toList()

    private fun clearEpoch() { hr.clear(); rr.clear(); dyn.clear(); grav.fill(0.0); gravN = 0 }

    @Synchronized
    fun onHeart(t: Long, bpm: Int?, cleanRr: List<Int>) {
        if (startMs == null) return
        roll(t)
        bpm?.takeIf { it in 25..220 }?.let { hr += it }
        rr += cleanRr
        cleanRr.forEach { rrRecent.addLast(it) }
        while (rrRecent.size > 160) rrRecent.removeFirst()
    }

    @Synchronized
    fun onAcc(t: Long, x: Int, y: Int, z: Int) {
        // Keep the latest orientation for calibration taps even when sleep mode is off.
        lastGravity = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())
        if (startMs == null) return
        roll(t)
        val m = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)
        dyn += abs(m - 1000.0)
        grav[0] += x; grav[1] += y; grav[2] += z; gravN++
    }

    private fun roll(t: Long) {
        while (t - epochStart >= 30_000L) close(epochStart + 30_000L)
    }

    private fun close(endMs: Long) {
        if (startMs == null) return
        if (hr.isNotEmpty() || dyn.isNotEmpty()) {
            val g = if (gravN > 0) doubleArrayOf(grav[0] / gravN, grav[1] / gravN, grav[2] / gravN) else null
            epochs += SleepEpoch(
                epochStart,
                hr.takeIf { it.isNotEmpty() }?.average(),
                SleepMath.rmssd(rr),
                // 90th percentile of dynamic acceleration: brief turns count, sensor noise doesn't.
                dyn.sorted().let { if (it.isEmpty()) 0.0 else it[(it.size * 0.9).toInt().coerceAtMost(it.size - 1)] },
                g?.let { SleepMath.position(it, PositionCalibration.back.value, PositionCalibration.right.value) } ?: BodyPosition.Unknown,
                SleepMath.breathsFromRr(rrRecent.toList())
            )
        }
        clearEpoch()
        epochStart = endMs
    }
}

/** Saved nights: summaries plus the epochs for the hypnogram. */
class SleepStore(context: Context) {
    private val dir = File(context.filesDir, "sleep").apply { mkdirs() }
    val nights = MutableStateFlow<List<NightSummary>>(emptyList())

    init { nights.value = dir.listFiles()?.mapNotNull { f -> runCatching { readSummary(JSONObject(f.readText())) }.getOrNull() }?.sortedBy { it.startMs }.orEmpty() }

    fun save(summary: NightSummary, epochs: List<SleepEpoch>) {
        val o = JSONObject().put("summary", writeSummary(summary)).put("epochs", JSONArray().apply {
            epochs.forEach { e ->
                put(JSONArray().put(e.startMs).put(e.hr ?: JSONObject.NULL).put(e.rmssd ?: JSONObject.NULL).put(e.movementMg)
                    .put(e.position.name).put(e.breathsPerMin ?: JSONObject.NULL).put(e.stage.name))
            }
        })
        File(dir, "${summary.startMs}.json").writeText(o.toString())
        nights.value = (nights.value.filter { it.startMs != summary.startMs } + summary).sortedBy { it.startMs }
    }

    fun epochs(startMs: Long): List<SleepEpoch> = runCatching {
        val a = JSONObject(File(dir, "$startMs.json").readText()).getJSONArray("epochs")
        (0 until a.length()).map { i ->
            val e = a.getJSONArray(i)
            fun d(k: Int) = if (e.isNull(k)) null else e.getDouble(k)
            SleepEpoch(e.getLong(0), d(1), d(2), e.getDouble(3), BodyPosition.valueOf(e.getString(4)), d(5), SleepStage.valueOf(e.getString(6)))
        }
    }.getOrDefault(emptyList())

    fun delete(startMs: Long) {
        File(dir, "$startMs.json").delete()
        nights.value = nights.value.filter { it.startMs != startMs }
    }

    private fun writeSummary(s: NightSummary) = JSONObject().apply {
        put("start", s.startMs); put("end", s.endMs); put("score", s.score); put("asleep", s.asleepMin); put("inBed", s.inBedMin)
        put("latency", s.latencyMin); put("waso", s.wasoMin); put("bouts", s.wakeBouts)
        put("stages", JSONObject().apply { s.stageMin.forEach { (k, v) -> put(k.name, v) } })
        put("positions", JSONObject().apply { s.positionMin.forEach { (k, v) -> put(k.name, v) } })
        put("changes", s.positionChanges)
        s.avgHr?.let { put("avgHr", it) }; s.minHr?.let { put("minHr", it) }; s.avgRmssd?.let { put("rmssd", it) }; s.avgBreaths?.let { put("breaths", it) }
        s.spo2Avg?.let { put("spo2Avg", it) }; s.spo2Min?.let { put("spo2Min", it) }; s.spo2Below90Min?.let { put("spo2Below90", it) }
    }

    private fun readSummary(root: JSONObject): NightSummary {
        val o = root.getJSONObject("summary")
        fun d(k: String) = if (o.has(k)) o.getDouble(k) else null
        fun i(k: String) = if (o.has(k)) o.getInt(k) else null
        val st = o.getJSONObject("stages"); val ps = o.getJSONObject("positions")
        return NightSummary(
            o.getLong("start"), o.getLong("end"), o.getInt("score"), o.getInt("asleep"), o.getInt("inBed"), o.getInt("latency"), o.getInt("waso"), o.getInt("bouts"),
            SleepStage.entries.associateWith { st.optInt(it.name, 0) }, d("avgHr"), d("minHr"), d("rmssd"), d("breaths"),
            BodyPosition.entries.filter { it != BodyPosition.Unknown }.associateWith { ps.optInt(it.name, 0) }, o.optInt("changes", 0),
            d("spo2Avg"), i("spo2Min"), i("spo2Below90")
        )
    }
}
