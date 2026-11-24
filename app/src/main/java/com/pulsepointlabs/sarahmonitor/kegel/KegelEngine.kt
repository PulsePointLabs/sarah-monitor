package com.pulsepointlabs.sarahmonitor.kegel

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class StepKind(val label: String, val cue: String) {
    Squeeze("Squeeze", "Squeeze"),
    Hold("Hold", "Hold"),
    Relax("Relax", "Relax"),
    PushOut("Release", "Gently release and let it drop"),
    Ready("Get ready", "Get ready")
}

/** One timed step; [targetPct] is the squeeze strength aimed for, as % of the reference hold (null = relax). */
data class KegelStep(val kind: StepKind, val seconds: Double, val rep: Int, val targetPct: Int? = null)

/** A routine: what each rep looks like. */
enum class KegelRoutine(val label: String, val detail: String) {
    Flicks("Quick flicks", "Fast 1 s squeezes — power and reflex"),
    Holds("Long holds", "Steady holds — strength"),
    Endurance("Endurance", "Long sub-max holds — stamina"),
    Elevator("Elevator", "Step up 25 → 50 → 75 → 100 %, then down — control"),
    Reverse("Reverse kegel", "Release and lengthen — relaxation and down-training");

    fun defaults(): Triple<Int, Double, Double> = when (this) {
        Flicks -> Triple(10, 1.0, 2.0)
        Holds -> Triple(10, 5.0, 5.0)
        Endurance -> Triple(3, 30.0, 30.0)
        Elevator -> Triple(3, 3.0, 8.0)
        Reverse -> Triple(8, 5.0, 5.0)
    }

    fun steps(reps: Int, holdS: Double, restS: Double): List<KegelStep> {
        val out = mutableListOf(KegelStep(StepKind.Ready, 3.0, 0))
        for (r in 1..reps) {
            when (this) {
                Flicks -> out += KegelStep(StepKind.Squeeze, holdS, r, 80)
                Holds -> out += KegelStep(StepKind.Hold, holdS, r, 70)
                Endurance -> out += KegelStep(StepKind.Hold, holdS, r, 40)
                Elevator -> {
                    listOf(25, 50, 75, 100).forEach { out += KegelStep(StepKind.Squeeze, holdS, r, it) }
                    listOf(75, 50, 25).forEach { out += KegelStep(StepKind.Hold, holdS * 0.67, r, it) }
                }
                Reverse -> out += KegelStep(StepKind.PushOut, holdS, r, null)
            }
            if (r < reps) out += KegelStep(StepKind.Relax, restS, r)
        }
        out += KegelStep(StepKind.Relax, 3.0, reps)
        return out
    }
}

/** Score for one rep: how much of the squeeze time was on target, and how strong it was. */
data class RepScore(val rep: Int, val onTargetPct: Int, val avgPctOfRef: Int?)

data class KegelResult(
    val timestampMs: Long,
    val routine: KegelRoutine,
    val reps: Int,
    val score: Int,
    val avgPctOfRef: Int?,
    val relaxQualityPct: Int?
)

/**
 * Runs a guided set against the clock and scores it from CIVET pressure (above rest, as % of the reference hold;
 * without a reference, the hold threshold counts as "on"). Pure: the caller supplies the time and pressure.
 */
class KegelSession(val routine: KegelRoutine, val reps: Int, val holdS: Double, val restS: Double, val startMs: Long) {
    val steps = routine.steps(reps, holdS, restS)
    private val boundaries = steps.runningFold(0.0) { acc, s -> acc + s.seconds }
    val totalS: Double = boundaries.last()
    private val onTarget = mutableMapOf<Int, Pair<Int, Int>>()   // rep -> (on samples, squeeze samples)
    private val strength = mutableMapOf<Int, MutableList<Double>>()
    private var relaxGood = 0
    private var relaxN = 0

    data class Now(val index: Int, val step: KegelStep, val stepProgress: Double, val remainingS: Double, val done: Boolean)

    fun at(nowMs: Long): Now {
        val t = (nowMs - startMs) / 1000.0
        if (t >= totalS) return Now(steps.size - 1, steps.last(), 1.0, 0.0, true)
        var i = boundaries.indexOfLast { it <= t }.coerceIn(0, steps.size - 1)
        if (i >= steps.size) i = steps.size - 1
        val step = steps[i]
        val into = t - boundaries[i]
        return Now(i, step, (into / step.seconds).coerceIn(0.0, 1.0), step.seconds - into, false)
    }

    /** Feed a pressure sample (kPa above rest) taken at [nowMs]. */
    fun sample(nowMs: Long, aboveRestKpa: Double?, referenceKpa: Double?, thresholdKpa: Double) {
        val n = at(nowMs)
        if (n.done || aboveRestKpa == null) return
        val s = n.step
        val pct = referenceKpa?.takeIf { it > 0.3 }?.let { aboveRestKpa / it * 100 }
        when (s.kind) {
            StepKind.Squeeze, StepKind.Hold -> {
                if (n.stepProgress < 0.15 && s.kind == StepKind.Hold) return // give time to get there
                val target = s.targetPct ?: 50
                val on = if (pct != null) pct >= target * 0.7 else aboveRestKpa >= thresholdKpa
                val (a, b) = onTarget[s.rep] ?: (0 to 0)
                onTarget[s.rep] = (a + if (on) 1 else 0) to (b + 1)
                pct?.let { strength.getOrPut(s.rep) { mutableListOf() } += it }
            }
            StepKind.Relax, StepKind.PushOut -> {
                if (n.stepProgress < 0.3) return
                relaxN++
                if (aboveRestKpa < thresholdKpa * 0.6) relaxGood++
            }
            StepKind.Ready -> Unit
        }
    }

    /** Each step's wall-clock window (for the review graph). */
    fun stepWindows(): List<Triple<KegelStep, Long, Long>> =
        steps.mapIndexed { i, s -> Triple(s, startMs + (boundaries[i] * 1000).toLong(), startMs + (boundaries[i + 1] * 1000).toLong()) }

    /** Running totals while the set is going: on-target %, strength (% of reference) and relax quality. */
    fun soFar(): Triple<Int?, Int?, Int?> {
        val reps = repScores()
        return Triple(
            reps.takeIf { it.isNotEmpty() }?.map { it.onTargetPct }?.average()?.roundToInt(),
            reps.mapNotNull { it.avgPctOfRef }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            if (relaxN == 0) null else relaxGood * 100 / relaxN
        )
    }

    /** Everything about this set as JSON, frozen onto its event so the review can rebuild it. */
    fun toJson(r: KegelResult, stoppedEarly: Boolean, referenceKpa: Double? = null, thresholdKpa: Double? = null): JSONObject = JSONObject()
        .put("reference_kpa", referenceKpa ?: JSONObject.NULL).put("threshold_kpa", thresholdKpa ?: JSONObject.NULL)
        .put("routine", routine.name).put("routine_label", routine.label).put("reps", reps).put("hold_s", holdS).put("rest_s", restS)
        .put("start_ms", startMs).put("score", r.score).put("strength_pct", r.avgPctOfRef ?: JSONObject.NULL).put("relax_pct", r.relaxQualityPct ?: JSONObject.NULL)
        .put("stopped_early", stoppedEarly)
        .put("rep_scores", JSONArray().apply { repScores().forEach { put(JSONObject().put("rep", it.rep).put("on_target_pct", it.onTargetPct).put("strength_pct", it.avgPctOfRef ?: JSONObject.NULL)) } })
        .put("steps", JSONArray().apply { stepWindows().forEach { (s, a, b) -> put(JSONObject().put("kind", s.kind.name).put("rep", s.rep).put("target_pct", s.targetPct ?: JSONObject.NULL).put("start_ms", a).put("end_ms", b)) } })

    fun repScores(): List<RepScore> = onTarget.keys.sorted().map { r ->
        val (a, b) = onTarget[r]!!
        RepScore(r, if (b == 0) 0 else a * 100 / b, strength[r]?.takeIf { it.isNotEmpty() }?.average()?.roundToInt())
    }

    fun result(nowMs: Long): KegelResult {
        val reps = repScores()
        val onTargetAvg = if (reps.isEmpty()) 0.0 else reps.map { it.onTargetPct }.average()
        val relax = if (relaxN == 0) null else relaxGood * 100 / relaxN
        val score = if (routine == KegelRoutine.Reverse) (relax ?: 0) else ((onTargetAvg * 0.8) + (relax ?: 100) * 0.2).roundToInt()
        val strengths = reps.mapNotNull { it.avgPctOfRef }
        return KegelResult(nowMs, routine, steps.maxOf { it.rep }, score.coerceIn(0, 100), strengths.takeIf { it.isNotEmpty() }?.average()?.roundToInt(), relax)
    }
}

/** One short, plain coaching line for right now, from the step and the live squeeze (% of reference). */
fun kegelCoach(step: KegelStep?, livePct: Double?): String = when {
    step == null -> "Pick a routine and press start"
    step.kind == StepKind.Ready -> "Breathe out, let everything soften"
    step.kind == StepKind.PushOut -> if ((livePct ?: 0.0) > 30) "Softer, release downward, don't squeeze" else "Good, gentle release"
    step.kind == StepKind.Relax -> if ((livePct ?: 0.0) > 25) "Let it all go, fully relax" else "Good, fully relaxed"
    livePct == null -> step.kind.cue
    else -> {
        val target = (step.targetPct ?: 50).toDouble()
        when {
            livePct < target * 0.7 -> "A little stronger"
            livePct > target * 1.35 && target < 90 -> "Ease back a touch, steady at ${target.toInt()}%"
            else -> "Right on target, hold it there"
        }
    }
}

/** Saved results (last 200) for the progress chart. */
object KegelHistory {
    private const val PREFS = "sarahmonitor_kegel"
    val results = MutableStateFlow<List<KegelResult>>(emptyList())
    private var loaded = false

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val raw = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("results", "[]")
        results.value = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                runCatching {
                    KegelResult(
                        o.getLong("t"), KegelRoutine.valueOf(o.getString("routine")), o.getInt("reps"), o.getInt("score"),
                        o.optInt("strength", -1).takeIf { it >= 0 }, o.optInt("relax", -1).takeIf { it >= 0 }
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun add(c: Context, r: KegelResult) {
        val next = (results.value + r).takeLast(200)
        results.value = next
        val a = JSONArray()
        next.forEach {
            a.put(JSONObject().put("t", it.timestampMs).put("routine", it.routine.name).put("reps", it.reps).put("score", it.score)
                .put("strength", it.avgPctOfRef ?: -1).put("relax", it.relaxQualityPct ?: -1))
        }
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("results", a.toString()).apply()
    }
}
