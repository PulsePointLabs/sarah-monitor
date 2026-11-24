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
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

/**
 * Guided pelvic-floor training: pick a routine, follow the breathing ring, get scored live from the CIVET.
 * During a set: Focus (the ring and little else) or Detail (live graph, per-squeeze bars, tiles).
 * [focusOnly]: the app's focus mode; only the trainer shows.
 */
@Composable
fun KegelScreen(modifier: Modifier = Modifier, focusOnly: Boolean = false) {
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
    var detail by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // The set's pressure, kept so the detailed review opens the moment it ends.
    val setPoints = remember { androidx.compose.runtime.mutableStateListOf<KegelPoint>() }
    var lastSet by remember { mutableStateOf<Pair<KegelRecord, List<KegelPoint>>?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    // A set started while nothing was recording gets its own session, stopped when the set ends.
    var autoSession by remember { mutableStateOf(false) }
    val autoRef = androidx.compose.runtime.rememberUpdatedState(autoSession)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (autoRef.value) MonitorHub.stopSession() } }

    fun finish(s: KegelSession, at: Long, early: Boolean) {
        val r = s.result(at)
        KegelHistory.add(context, r)
        val wasAuto = autoSession
        autoSession = false
        val p = MonitorHub.pelvic.value
        val json = s.toJson(r, early, p.referenceKpa, p.thresholdKpa)
        KegelRecord.fromJson("live", json, at)?.let { lastSet = it to setPoints.toList() }
        if (MonitorHub.session.value != null) {
            MonitorHub.markEvent(
                at, setOf("activity"), "Kegels: ${r.routine.label}, ${r.reps} reps, score ${r.score}" + if (early) " (stopped early)" else "",
                extra = "kegel" to json
            ) { if (wasAuto) MonitorHub.stopSession() }
        }
        result = r
        session = null
        lastIndex = -1
    }

    // Clock + scoring loop while a set runs.
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            val p = MonitorHub.pelvic.value
            s.sample(now, p.aboveRestKpa, p.referenceKpa, p.thresholdKpa)
            // Keep every new pressure sample of the set (10/s) for the review.
            val lastT = setPoints.lastOrNull()?.t ?: (s.startMs - 3_000L)
            p.trace.forEach { q -> if (q.timestampMs > lastT) q.aboveRestKpa?.let { setPoints += KegelPoint(q.timestampMs, KegelAnalysis.toValue(it, p.referenceKpa)) } }
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
                if (cues == Cues.Voice) scope.launch { MonitorHub.audio.say("Set complete. Score ${s.result(now).score}.") }
                finish(s, now, early = false)
                break
            }
            delay(50L)
        }
    }

    fun start() {
        result = null
        lastIndex = -1
        setPoints.clear(); lastSet = null
        // Pressure counts only while logging is on (0.25): a set switches it on so its squeezes are recorded.
        if (!MonitorHub.pelvic.value.logging) MonitorHub.startPelvicLogging()
        if (MonitorHub.session.value == null) {
            val clock = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a"))
            MonitorHub.startSession(com.pulsepointlabs.sarahmonitor.session.SessionCategory.Kegel, "Kegels · ${routine.label} · $clock")
            autoSession = true
        }
        session = KegelSession(routine, reps, hold, rest, System.currentTimeMillis())
    }

    val running = session != null
    val setupPanel: @Composable () -> Unit = {
        Column(Modifier.width(340.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(routine.detail, color = MonitorColors.label, fontSize = 13.sp)
            SettingSlider("Reps", "$reps", reps.toFloat(), 1f..30f) { if (!running) reps = Math.round(it) }
            SettingSlider(if (routine == KegelRoutine.Elevator) "Seconds per floor" else if (routine == KegelRoutine.Reverse) "Release" else "Squeeze / hold", "${"%.0f".format(hold)} s", hold.toFloat(), 1f..60f) { if (!running) hold = Math.round(it).toDouble() }
            SettingSlider("Rest", "${"%.0f".format(rest)} s", rest.toFloat(), 1f..60f) { if (!running) rest = Math.round(it).toDouble() }
            Text("Cues", color = MonitorColors.text, fontSize = 14.sp)
            ChoiceRow { Cues.entries.forEach { c -> ChoiceChip(c.label, c == cues, squeezeColor) { cues = c } } }
        }
    }
    val calibrationPanel: @Composable () -> Unit = {
        Column(Modifier.width(340.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    pelvic.pressureKpa == null -> "CIVET not connected"
                    pelvic.referenceKpa == null -> "No reference hold yet"
                    else -> "Reference ${"%.1f".format(pelvic.referenceKpa)} kPa · rest ${pelvic.restSource.name.lowercase()}"
                },
                color = MonitorColors.text, fontSize = 13.sp
            )
            pelvic.capture?.let { Text(it.message, color = MonitorColors.warn, fontSize = 13.sp) }
            ChoiceChip("Capture rest (5 s relaxed)", false, relaxColor) { MonitorHub.startPelvicCapture(CaptureKind.Rest) }
            ChoiceChip("Capture reference (5 s squeeze)", false, relaxColor) { MonitorHub.startPelvicCapture(CaptureKind.Reference) }
        }
    }

    val s0 = session
    if (s0 != null && (detail && !focusOnly)) {
        DetailTrainer(s0, now, pelvic, routine, onFocus = { detail = false }, onStop = { finish(s0, System.currentTimeMillis(), early = true) }, modifier = modifier)
        return
    }
    if (reviewing) lastSet?.let { (rec, pts) -> KegelSetReview(rec, pts) { reviewing = false } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running && !focusOnly) ModeSwitch(detail) { detail = it }
        // Routine + three dropdowns; everything else stays out of the way of the circle.
        val routines: @Composable () -> Unit = {
            ChoiceRow {
                KegelRoutine.entries.forEach { r ->
                    ChoiceChip(r.label, r == routine, squeezeColor) {
                        if (!running) { routine = r; val (a, b, c) = r.defaults(); reps = a; hold = b; rest = c }
                    }
                }
            }
        }
        val panels: @Composable () -> Unit = {
            PanelButton(androidx.compose.material.icons.Icons.Default.Tune, "Setup", squeezeColor) { setupPanel() }
            PanelButton(androidx.compose.material.icons.Icons.Default.Compress, "Calibrate", if (pelvic.referenceKpa == null) MonitorColors.warn else relaxColor) { calibrationPanel() }
            PanelButton(androidx.compose.material.icons.Icons.Default.ShowChart, "Progress", MonitorColors.rmssd) { Box(Modifier.width(380.dp).padding(horizontal = 8.dp)) { HistoryCard() } }
        }
        if (!running && !focusOnly) BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 900.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { routines() }
                    panels()
                }
            } else {
                // Phone: routines get the full width; the three dropdowns sit in their own row.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    routines()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) { panels() }
                }
            }
        }
        if (!focusOnly) Text(
            "${routine.label} · $reps reps · ${"%.0f".format(hold)} s ${if (routine == KegelRoutine.Reverse) "release" else "hold"} · ${"%.0f".format(rest)} s rest",
            color = MonitorColors.label, fontSize = 13.sp
        )
        val s = session
        val n = s?.at(now)
        val livePct = pelvic.aboveRestKpa?.let { a -> pelvic.referenceKpa?.takeIf { it > 0.3 }?.let { a / it * 100 } ?: (a / pelvic.thresholdKpa * 50) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // As big as fits, never wider or taller than the space.
            BreathingRing(n?.step, n?.stepProgress ?: 0.0, n?.remainingS, livePct, Modifier.size(minOf(maxWidth, maxHeight)))
        }
        // Live feedback under the circle.
        Column(Modifier.fillMaxWidth().widthIn(max = 620.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s != null && n != null) {
                Text(kegelCoach(n.step, livePct), color = if (n.step.kind in setOf(StepKind.Squeeze, StepKind.Hold)) squeezeColor else relaxColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                RepDots(s, n.step.rep)
                val (onTarget, strength, relax) = s.soFar()
                val left = (s.totalS - (now - s.startMs) / 1000.0).coerceAtLeast(0.0)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiveStat("On target", onTarget?.let { "$it%" }, MonitorColors.good, Modifier.weight(1f))
                    LiveStat("Strength", strength?.let { "$it%" }, squeezeColor, Modifier.weight(1f))
                    LiveStat("Relax", relax?.let { "$it%" }, relaxColor, Modifier.weight(1f))
                    LiveStat("Left", "${(left / 60).toInt()}:${"%02d".format((left % 60).toInt())}", MonitorColors.text, Modifier.weight(1f))
                }
                LiveTrace(pelvic, n.step.targetPct, Modifier.fillMaxWidth().height(54.dp))
                ChoiceChip("■ Stop", false, MonitorColors.alarm) { finish(s, System.currentTimeMillis(), early = true) }
            } else {
                Text(
                    "▶ Start ${routine.label.lowercase()}",
                    color = MonitorColors.background, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier
                        .background(Brush.horizontalGradient(listOf(squeezeColor, relaxColor)), RoundedCornerShape(28.dp))
                        .clickable { start() }
                        .padding(horizontal = 28.dp, vertical = 12.dp)
                )
                result?.let { r -> ResultCard(r, onDetails = lastSet?.let { { reviewing = true } }) }
            }
        }
    }
}

/** A toggle chip that opens its panel as a dropdown, so settings never crowd the trainer. */
@Composable
private fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(34.dp)
                .background(if (open) color.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(8.dp))
                .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MonitorColors.panel) { content() }
    }
}

@Composable
private fun LiveStat(label: String, value: String?, color: Color, modifier: Modifier) {
    Column(modifier.background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(label, color = MonitorColors.label, fontSize = 11.sp, maxLines = 1)
        Text(value ?: "--", color = if (value == null) MonitorColors.dim else color, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/** The last 20 s of squeeze (% of reference) with the current target as a dashed line. */
@Composable
private fun LiveTrace(p: com.pulsepointlabs.sarahmonitor.core.PelvicSnapshot, targetPct: Int?, modifier: Modifier) {
    val pts = p.trace.takeLast(400)
    Canvas(modifier.background(MonitorColors.panel, RoundedCornerShape(8.dp)).border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(8.dp))) {
        val end = pts.lastOrNull()?.timestampMs ?: return@Canvas
        val start = end - 20_000L
        fun pct(a: Double) = p.referenceKpa?.takeIf { it > 0.3 }?.let { a / it * 100 } ?: (a / p.thresholdKpa * 50)
        fun y(v: Double) = size.height - (v / 120.0).toFloat().coerceIn(0f, 1f) * (size.height - 6f) - 3f
        targetPct?.let { t ->
            drawLine(MonitorColors.warn, Offset(0f, y(t.toDouble())), Offset(size.width, y(t.toDouble())), 1.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        val path = androidx.compose.ui.graphics.Path(); var started = false
        pts.filter { it.timestampMs >= start }.forEach { q ->
            val a = q.aboveRestKpa ?: return@forEach
            val x = (q.timestampMs - start).toFloat() / 20_000f * size.width
            if (!started || q.gapBefore) path.moveTo(x, y(pct(a))) else path.lineTo(x, y(pct(a)))
            started = true
        }
        drawPath(path, squeezeColor, style = Stroke(2.2f, cap = StrokeCap.Round))
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
private fun ResultCard(r: KegelResult, onDetails: (() -> Unit)?) {
    Card("Set complete · ${r.routine.label}", qualityColor(r.score), Modifier.fillMaxWidth().widthIn(max = 620.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KTile("Score", "${r.score}", qualityColor(r.score), Modifier.weight(1f))
            KTile("Strength", r.avgPctOfRef?.toString() ?: "--", squeezeColor, Modifier.weight(1f), "%")
            KTile("Relaxed", r.relaxQualityPct?.toString() ?: "--", qualityColor(r.relaxQualityPct), Modifier.weight(1f), "%")
        }
        if (onDetails != null) Text(
            "See every squeeze in detail", color = MonitorColors.background, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(squeezeColor, RoundedCornerShape(10.dp)).clickable(onClick = onDetails).padding(vertical = 11.dp)
        )
    }
}

/** Focus (the ring) or Detail (graphs and numbers) while a set runs. */
@Composable
private fun ModeSwitch(detail: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(10.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(false to "Focus", true to "Detail").forEach { (d, label) ->
            val on = d == detail
            Text(
                label, color = if (on) MonitorColors.background else MonitorColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(if (on) squeezeColor else Color.Transparent, RoundedCornerShape(8.dp)).clickable { onChange(d) }.padding(horizontal = 18.dp, vertical = 7.dp)
            )
        }
    }
}

/**
 * Detail mode: the ring beside (DeX) or above (phone) a live graph of the last 30 s with each squeeze shaded and
 * its target, tiles with live numbers and trend arrows, and a bar for every squeeze so far.
 */
@Composable
internal fun DetailTrainer(
    s: KegelSession, now: Long, pelvic: com.pulsepointlabs.sarahmonitor.core.PelvicSnapshot, routine: KegelRoutine,
    onFocus: () -> Unit, onStop: () -> Unit, modifier: Modifier
) {
    val n = s.at(now)
    val ref = pelvic.referenceKpa?.takeIf { it > 0.3 }
    val livePct = pelvic.aboveRestKpa?.let { a -> ref?.let { a / it * 100 } ?: (a / pelvic.thresholdKpa * 50) }
    // Squeezes so far, measured the same way as the review (recomputed once a second).
    val tick = now / 1000
    val soFar = remember(tick) {
        val json = s.toJson(s.result(now), false, ref, pelvic.thresholdKpa)
        val rec = KegelRecord.fromJson("live", json, now)
        val pts = pelvic.trace.mapNotNull { q -> q.aboveRestKpa?.let { KegelPoint(q.timestampMs, KegelAnalysis.toValue(it, ref)) } }
        rec?.let { KegelAnalysis.contractions(it, pts).filter { c -> c.startMs <= now } } ?: emptyList()
    }
    val (onTarget, strength, relax) = s.soFar()
    val left = (s.totalS - (now - s.startMs) / 1000.0).coerceAtLeast(0.0)
    val current = soFar.lastOrNull { now in it.startMs..it.endMs }
    val tiles: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KTile("Live", livePct?.toInt()?.toString() ?: "--", squeezeColor, Modifier.weight(1f), if (ref != null) "%" else "")
                KTile("This squeeze", current?.peak?.toInt()?.toString() ?: "--", squeezeColor, Modifier.weight(1f), if (ref != null) "%" else "")
                KTile("Left", "${(left / 60).toInt()}:${"%02d".format((left % 60).toInt())}", MonitorColors.text, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KTile("On target", onTarget?.toString() ?: "--", qualityColor(onTarget), Modifier.weight(1f), "%")
                KTile("Strength", strength?.toString() ?: "--", squeezeColor, Modifier.weight(1f), "%", KegelAnalysis.fatigueTrend(soFar))
                KTile("Relaxed", relax?.toString() ?: "--", qualityColor(relax), Modifier.weight(1f), "%")
            }
        }
    }
    val graph: @Composable (Modifier) -> Unit = { m -> LiveSetGraph(pelvic, s, now, ref, m) }
    val bars: @Composable (Modifier) -> Unit = { m -> LiveBars(soFar, ref != null, s.steps.count { it.targetPct != null }, m) }
    val ring: @Composable (Modifier) -> Unit = { m ->
        BoxWithConstraints(m, contentAlignment = Alignment.Center) {
            BreathingRing(n.step, n.stepProgress, n.remainingS, livePct, Modifier.size(minOf(maxWidth, maxHeight)))
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val coach: @Composable (Modifier) -> Unit = { m ->
            Text(kegelCoach(n.step, livePct), color = if (n.step.kind in setOf(StepKind.Squeeze, StepKind.Hold)) squeezeColor else relaxColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = m)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 700.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeSwitch(true) { if (!it) onFocus() }
                coach(Modifier.weight(1f))
                RepDots(s, n.step.rep)
                ChoiceChip("■ Stop", false, MonitorColors.alarm) { onStop() }
            } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Phone: switch and Stop on top, the coaching line and rep dots below, nothing squeezed.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ModeSwitch(true) { if (!it) onFocus() }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    ChoiceChip("■ Stop", false, MonitorColors.alarm) { onStop() }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    coach(Modifier.weight(1f))
                    RepDots(s, n.step.rep)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (maxWidth >= 900.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ring(Modifier.weight(0.85f).fillMaxHeight())
                    Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles(Modifier.fillMaxWidth())
                        graph(Modifier.fillMaxWidth().weight(1.3f))
                        bars(Modifier.fillMaxWidth().weight(0.7f))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ring(Modifier.fillMaxWidth().weight(0.9f))
                    tiles(Modifier.fillMaxWidth())
                    graph(Modifier.fillMaxWidth().weight(0.8f))
                    bars(Modifier.fillMaxWidth().weight(0.45f))
                }
            }
        }
    }
}

/** The last 30 s: squeeze windows shaded, their targets dashed, your pressure on top. */
@Composable
private fun LiveSetGraph(p: com.pulsepointlabs.sarahmonitor.core.PelvicSnapshot, s: KegelSession, now: Long, ref: Double?, modifier: Modifier) {
    val windows = s.stepWindows().filter { it.first.targetPct != null }
    Column(modifier.background(MonitorColors.panel, RoundedCornerShape(10.dp)).border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(if (ref != null) "Last 30 s · % of reference · dashed = target" else "Last 30 s · kPa above rest", color = MonitorColors.label, fontSize = 12.sp)
        Canvas(Modifier.fillMaxSize().padding(top = 4.dp)) {
            val start = now - 30_000L
            val pts = p.trace.filter { it.timestampMs >= start }
            fun v(a: Double) = KegelAnalysis.toValue(a, ref)
            val top = maxOf(if (ref != null) 110.0 else 1.0, (pts.mapNotNull { it.aboveRestKpa?.let(::v) }.maxOrNull() ?: 0.0) * 1.08)
            fun x(t: Long) = ((t - start).toFloat() / 30_000f) * size.width
            fun y(value: Double) = size.height - (value / top).toFloat().coerceIn(0f, 1f) * size.height
            listOf(0.25, 0.5, 0.75, 1.0).forEach { f -> drawLine(MonitorColors.trendGrid, Offset(0f, y(top * f / 1.1)), Offset(size.width, y(top * f / 1.1)), 1f) }
            windows.filter { it.third >= start && it.second <= now + 30_000L }.forEach { (st, a, b) ->
                val xa = x(a).coerceAtLeast(0f); val xb = x(b).coerceAtMost(size.width)
                if (xb > xa) {
                    drawRect(squeezeColor.copy(alpha = 0.12f), Offset(xa, 0f), Size(xb - xa, size.height))
                    if (ref != null) st.targetPct?.let { t -> drawLine(MonitorColors.warn, Offset(xa, y(t.toDouble())), Offset(xb, y(t.toDouble())), 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 7f))) }
                }
            }
            val path = androidx.compose.ui.graphics.Path(); var started = false
            pts.forEach { q ->
                val a = q.aboveRestKpa ?: return@forEach
                if (!started || q.gapBefore) path.moveTo(x(q.timestampMs), y(v(a))) else path.lineTo(x(q.timestampMs), y(v(a)))
                started = true
            }
            drawPath(path, MonitorColors.text, style = Stroke(2.6f, cap = StrokeCap.Round))
        }
    }
}

/** A bar per squeeze so far (peak), coloured by time on target, with empty slots for the ones to come. */
@Composable
private fun LiveBars(cs: List<Contraction>, hasRef: Boolean, total: Int, modifier: Modifier) {
    Column(modifier.background(MonitorColors.panel, RoundedCornerShape(10.dp)).border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text("Each squeeze · height = peak · colour = on target", color = MonitorColors.label, fontSize = 12.sp)
        Canvas(Modifier.fillMaxSize().padding(top = 4.dp)) {
            val slots = total.coerceAtLeast(1)
            val w = size.width / slots
            val top = maxOf(if (hasRef) 110.0 else 1.0, (cs.mapNotNull { it.peak }.maxOrNull() ?: 0.0) * 1.08)
            for (i in 0 until slots) {
                val c = cs.getOrNull(i)
                drawRoundRect(MonitorColors.trendGrid, Offset(i * w + w * 0.18f, 0f), Size(w * 0.64f, size.height), androidx.compose.ui.geometry.CornerRadius(4f))
                val v = c?.peak ?: continue
                val h = (v / top).toFloat() * size.height
                drawRoundRect((if (hasRef) qualityColor(c.onTargetPct) else squeezeColor), Offset(i * w + w * 0.18f, size.height - h), Size(w * 0.64f, h), androidx.compose.ui.geometry.CornerRadius(4f))
            }
        }
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
