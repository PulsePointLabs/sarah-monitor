package com.pulsepointlabs.sarahmonitor.kegel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.audio.BeatSound
import com.pulsepointlabs.sarahmonitor.core.CaptureKind
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.ui.ChoiceChip
import com.pulsepointlabs.sarahmonitor.ui.ChoiceRow
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import com.pulsepointlabs.sarahmonitor.ui.SettingSlider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val squeezeColor = Color(0xFFFF7AB6)
private val relaxColor = MonitorColors.pelvic

private enum class Cues(val label: String) { Voice("Sarah's voice"), Tones("Tones"), Silent("Silent") }

@Composable
private fun Card(title: String, color: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(MonitorColors.panel, RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        content()
    }
}

/** Guided pelvic-floor training: pick a routine, follow the breathing ring, get scored live from the CIVET. */
@Composable
fun KegelScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { KegelHistory.load(context) }
    val pelvic by MonitorHub.pelvic.collectAsState()
    var routine by remember { mutableStateOf(KegelRoutine.Holds) }
    var reps by remember { mutableIntStateOf(routine.defaults().first) }
    var hold by remember { mutableStateOf(routine.defaults().second) }
    var rest by remember { mutableStateOf(routine.defaults().third) }
    var cues by remember { mutableStateOf(Cues.Voice) }
    var session by remember { mutableStateOf<KegelSession?>(null) }
    var result by remember { mutableStateOf<KegelResult?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()

    // Clock + scoring loop while a set runs.
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            val p = MonitorHub.pelvic.value
            s.sample(now, p.aboveRestKpa, p.referenceKpa, p.thresholdKpa)
            val n = s.at(now)
            if (n.index != lastIndex) {
                lastIndex = n.index
                if (!n.done) {
                    val step = n.step
                    when (cues) {
                        Cues.Silent -> Unit
                        Cues.Tones -> MonitorHub.audio.previewBeat(if (step.kind == StepKind.Relax) BeatSound.SoftTick else BeatSound.WoodBlock)
                        Cues.Voice -> {
                            MonitorHub.audio.previewBeat(if (step.kind == StepKind.Relax) BeatSound.SoftTick else BeatSound.WoodBlock)
                            // Short steps only get the tone — words would lag behind the clock.
                            if (step.seconds >= 2.5) {
                                val words = when {
                                    routine == KegelRoutine.Elevator && step.targetPct != null -> "${step.targetPct} percent"
                                    step.kind == StepKind.Ready -> "Get ready. ${routine.label}."
                                    else -> step.kind.cue + if (step.seconds >= 5) ", ${step.seconds.toInt()} seconds" else ""
                                }
                                scope.launch { MonitorHub.audio.say(words) }
                            }
                        }
                    }
                }
            }
            if (n.done) {
                val r = s.result(now)
                KegelHistory.add(context, r)
                if (MonitorHub.session.value != null) {
                    MonitorHub.markEvent(now, setOf("activity"), "Kegels: ${r.routine.label}, ${r.reps} reps, score ${r.score}")
                }
                if (cues == Cues.Voice) scope.launch { MonitorHub.audio.say("Set complete. Score ${r.score}.") }
                result = r
                session = null
                lastIndex = -1
                break
            }
            delay(50L)
        }
    }

    fun start() {
        result = null
        lastIndex = -1
        session = KegelSession(routine, reps, hold, rest, System.currentTimeMillis())
    }

    val setup: @Composable () -> Unit = {
        Card("Routine", squeezeColor, Modifier.fillMaxWidth()) {
            ChoiceRow {
                KegelRoutine.entries.forEach { r ->
                    ChoiceChip(r.label, r == routine, squeezeColor) {
                        if (session == null) {
                            routine = r
                            val (a, b, c) = r.defaults()
                            reps = a; hold = b; rest = c
                        }
                    }
                }
            }
            Text(routine.detail, color = MonitorColors.label, fontSize = 13.sp)
            SettingSlider("Reps", "$reps", reps.toFloat(), 1f..30f) { if (session == null) reps = it.toInt() }
            SettingSlider(if (routine == KegelRoutine.Elevator) "Seconds per floor" else if (routine == KegelRoutine.Reverse) "Release" else "Squeeze / hold", "${"%.0f".format(hold)} s", hold.toFloat(), 1f..60f) { if (session == null) hold = it.toInt().toDouble() }
            SettingSlider("Rest", "${"%.0f".format(rest)} s", rest.toFloat(), 1f..60f) { if (session == null) rest = it.toInt().toDouble() }
            Text("Cues", color = MonitorColors.text, fontSize = 15.sp)
            ChoiceRow { Cues.entries.forEach { c -> ChoiceChip(c.label, c == cues, squeezeColor) { cues = c } } }
        }
        Card("Calibration (CIVET)", relaxColor, Modifier.fillMaxWidth()) {
            Text(
                when {
                    pelvic.pressureKpa == null -> "CIVET not streaming — the timer still guides you, scoring needs the sensor."
                    pelvic.referenceKpa == null -> "No reference hold yet: scoring uses the hold threshold. Take a reference (a comfortable firm 5 s squeeze) for % strength."
                    else -> "Reference hold ${"%.1f".format(pelvic.referenceKpa)} kPa above rest — targets are % of this."
                },
                color = MonitorColors.text, fontSize = 13.sp
            )
            pelvic.capture?.let { Text(it.message, color = MonitorColors.warn, fontSize = 13.sp) }
            ChoiceRow {
                ChoiceChip("Capture rest (5 s relaxed)", false, relaxColor) { MonitorHub.startPelvicCapture(CaptureKind.Rest) }
                ChoiceChip("Capture reference (5 s squeeze)", false, relaxColor) { MonitorHub.startPelvicCapture(CaptureKind.Reference) }
            }
        }
    }

    val trainer: @Composable (Modifier) -> Unit = { m ->
        val s = session
        val n = s?.at(now)
        val livePct = pelvic.aboveRestKpa?.let { a -> pelvic.referenceKpa?.takeIf { it > 0.3 }?.let { a / it * 100 } ?: (a / pelvic.thresholdKpa * 50) }
        Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BreathingRing(n?.step, n?.stepProgress ?: 0.0, n?.remainingS, livePct, Modifier.fillMaxWidth().widthIn(max = 520.dp).aspectRatio(1f))
            if (s != null && n != null) {
                RepDots(s, n.step.rep)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceChip("■ Stop", false, MonitorColors.alarm) { session = null; lastIndex = -1 }
                }
            } else {
                Text(
                    "▶ Start ${routine.label.lowercase()}",
                    color = MonitorColors.background, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier
                        .background(Brush.horizontalGradient(listOf(squeezeColor, relaxColor)), RoundedCornerShape(28.dp))
                        .clickable { start() }
                        .padding(horizontal = 28.dp, vertical = 14.dp)
                )
                result?.let { ResultCard(it) }
            }
        }
    }

    BoxWithConstraints(modifier) {
        if (maxWidth >= 1000.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { setup() }
                Box(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) { trainer(Modifier.fillMaxWidth()) }
                Column(Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { HistoryCard() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                trainer(Modifier.fillMaxWidth())
                setup()
                HistoryCard()
            }
        }
    }
}

/**
 * The guide: a disc that swells as you squeeze and shrinks as you relax, timed to the step, with the step's
 * progress around the edge and your live pressure as an inner ring (dashed line = target).
 */
@Composable
private fun BreathingRing(step: KegelStep?, progress: Double, remainingS: Double?, livePct: Double?, modifier: Modifier) {
    val squeezing = step != null && step.kind in setOf(StepKind.Squeeze, StepKind.Hold)
    val targetScale = when {
        step == null -> 0.62f
        step.kind == StepKind.Ready -> 0.62f
        squeezing -> 0.5f + 0.45f * ((step.targetPct ?: 80) / 100f)
        step.kind == StepKind.PushOut -> 0.4f
        else -> 0.55f
    }
    val dur = ((step?.seconds ?: 1.0) * 1000 * 0.6).toInt().coerceIn(250, 2500)
    val scale by animateFloatAsState(targetScale, tween(dur), label = "disc")
    val color by animateColorAsState(if (squeezing) squeezeColor else if (step?.kind == StepKind.PushOut) MonitorColors.sdnn else relaxColor, tween(400), label = "ringColor")
    val live by animateFloatAsState(((livePct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1.2f), tween(120), label = "live")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val maxR = size.minDimension / 2
            val stroke = maxR * 0.06f
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.08f)), c, maxR * scale), maxR * scale, c)
            drawCircle(color.copy(alpha = 0.15f), maxR - stroke, c, style = Stroke(stroke))
            if (step != null) drawArc(color, -90f, (progress * 360).toFloat(), false, Offset(c.x - maxR + stroke, c.y - maxR + stroke), Size(2 * (maxR - stroke), 2 * (maxR - stroke)), style = Stroke(stroke, cap = StrokeCap.Round))
            // live pressure ring and target
            val r2 = maxR * 0.78f
            drawArc(MonitorColors.text.copy(alpha = 0.1f), -90f, 360f, false, Offset(c.x - r2, c.y - r2), Size(2 * r2, 2 * r2), style = Stroke(stroke * 0.45f))
            drawArc(MonitorColors.text.copy(alpha = 0.9f), -90f, live.coerceAtMost(1f) * 360f, false, Offset(c.x - r2, c.y - r2), Size(2 * r2, 2 * r2), style = Stroke(stroke * 0.45f, cap = StrokeCap.Round))
            step?.targetPct?.let { t ->
                val a = Math.toRadians(-90.0 + t * 3.6)
                val p1 = Offset(c.x + ((r2 - stroke) * Math.cos(a)).toFloat(), c.y + ((r2 - stroke) * Math.sin(a)).toFloat())
                val p2 = Offset(c.x + ((r2 + stroke) * Math.cos(a)).toFloat(), c.y + ((r2 + stroke) * Math.sin(a)).toFloat())
                drawLine(MonitorColors.warn, p1, p2, strokeWidth = stroke * 0.35f, cap = StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                step?.let { if (it.targetPct != null && it.kind != StepKind.Ready) "${it.kind.label} ${it.targetPct}%" else it.kind.label } ?: "Ready when you are",
                color = MonitorColors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold
            )
            remainingS?.let { Text("%.0f".format(kotlin.math.ceil(it)), color = color, fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
            livePct?.let { Text("live ${it.toInt()}%", color = MonitorColors.label, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun RepDots(s: KegelSession, rep: Int) {
    val total = s.steps.maxOf { it.rep }
    val scores = s.repScores().associateBy { it.rep }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Rep ${rep.coerceAtLeast(1)} / $total", color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        (1..total).forEach { r ->
            val sc = scores[r]?.onTargetPct
            val col = when {
                r == rep -> squeezeColor
                sc == null -> MonitorColors.panelBorder
                sc >= 70 -> MonitorColors.good
                sc >= 40 -> MonitorColors.warn
                else -> MonitorColors.alarm
            }
            Box(Modifier.height(12.dp).aspectRatio(1f).background(col, RoundedCornerShape(6.dp)))
        }
    }
}

@Composable
private fun ResultCard(r: KegelResult) {
    Card("Set complete · ${r.routine.label}", MonitorColors.good, Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Big("Score", "${r.score}", MonitorColors.good)
            Big("Strength", r.avgPctOfRef?.let { "$it%" } ?: "--", squeezeColor)
            Big("Relax", r.relaxQualityPct?.let { "$it%" } ?: "--", relaxColor)
        }
        Text("Score = time on target during squeezes (80%) + clean relaxation between them (20%). Strength is % of your reference hold.", color = MonitorColors.label, fontSize = 12.sp)
    }
}

@Composable
private fun Big(label: String, value: String, color: Color) {
    Column {
        Text(label, color = MonitorColors.label, fontSize = 12.sp)
        Text(value, color = color, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HistoryCard() {
    val results by KegelHistory.results.collectAsState()
    Card("Progress", MonitorColors.rmssd, Modifier.fillMaxWidth()) {
        if (results.isEmpty()) {
            Text("Finish a set to start your progress chart.", color = MonitorColors.label, fontSize = 13.sp)
            return@Card
        }
        val last = results.takeLast(30)
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val w = size.width / last.size
            last.forEachIndexed { i, r ->
                val h = size.height * r.score / 100f
                drawRoundRect(
                    (if (r.score >= 70) MonitorColors.good else if (r.score >= 40) MonitorColors.warn else MonitorColors.alarm).copy(alpha = 0.85f),
                    Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), androidx.compose.ui.geometry.CornerRadius(3f)
                )
            }
        }
        val fmt = DateTimeFormatter.ofPattern("MMM d HH:mm")
        results.takeLast(8).reversed().forEach { r ->
            Row {
                Text(Instant.ofEpochMilli(r.timestampMs).atZone(ZoneId.systemDefault()).format(fmt), color = MonitorColors.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(r.routine.label, color = MonitorColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${r.score}", color = MonitorColors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        val byRoutine = results.groupBy { it.routine }
        Text(
            byRoutine.entries.joinToString("   ") { (k, v) -> "${k.label}: best ${v.maxOf { it.score }}" },
            color = MonitorColors.label, fontSize = 12.sp
        )
    }
}
