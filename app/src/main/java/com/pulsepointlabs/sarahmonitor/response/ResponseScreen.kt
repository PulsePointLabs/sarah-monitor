package com.pulsepointlabs.sarahmonitor.response

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.review.markerColor
import com.pulsepointlabs.sarahmonitor.session.MarkerTypes
import com.pulsepointlabs.sarahmonitor.ui.CardType
import com.pulsepointlabs.sarahmonitor.ui.ChoiceChip
import com.pulsepointlabs.sarahmonitor.ui.ChoiceRow
import com.pulsepointlabs.sarahmonitor.ui.MonitorCard
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun phaseColor(p: ResponsePhase): Color = when (p) {
    ResponsePhase.Baseline -> MonitorColors.label
    ResponsePhase.Warming -> MonitorColors.sdnn
    ResponsePhase.Building -> MonitorColors.good
    ResponsePhase.Plateau -> MonitorColors.rmssd
    ResponsePhase.Approaching -> MonitorColors.load
    ResponsePhase.Climax -> Color(0xFFFF7AB6)
    ResponsePhase.BackingOff -> MonitorColors.pnn50
    ResponsePhase.Recovery -> MonitorColors.pelvic
}

@Composable
private fun Panel(title: String, color: Color, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(MonitorColors.panel, RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/**
 * The Sexual Response dashboard: phase and approach-to-climax up top, the phase timeline, the pelvic floor and HRV
 * (the signals that matter most here), why the read is what it is, quick marks, and Sarah's live feedback.
 */
@Composable
fun ResponseScreen(modifier: Modifier = Modifier) {
    val s by MonitorHub.response.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000L); now = System.currentTimeMillis() } }
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 1000.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhaseHeader(s, now)
                    CandidateBanner(s)
                    TimelinePanel(s, now, Modifier.fillMaxWidth().weight(1f).heightIn(min = 170.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MonitorCard(CardType.Pelvic, Modifier.fillMaxWidth().weight(1.25f))
                    MonitorCard(CardType.Hrv, Modifier.fillMaxWidth().weight(0.8f))
                    EmgPanel(Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VitalsRow(s)
                    CoachPanel()
                    QuickMarks()
                    SignalsPanel(s)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PhaseHeader(s, now)
                CandidateBanner(s)
                VitalsRow(s)
                TimelinePanel(s, now, Modifier.fillMaxWidth().height(150.dp))
                MonitorCard(CardType.Pelvic, Modifier.fillMaxWidth().height(300.dp))
                MonitorCard(CardType.Hrv, Modifier.fillMaxWidth().height(170.dp))
                QuickMarks()
                CoachPanel()
                SignalsPanel(s)
                EmgPanel(Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PhaseHeader(s: ResponseState, now: Long) {
    val color by animateColorAsState(phaseColor(s.phase), tween(600), label = "phase")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.22f), MonitorColors.panel)), RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        val narrow = maxWidth < 520.dp
        val info: @Composable () -> Unit = {
            Text("PHASE", color = MonitorColors.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(s.phase.label, color = color, fontSize = if (narrow) 28.sp else 34.sp, fontWeight = FontWeight.Bold, lineHeight = if (narrow) 30.sp else 36.sp)
            Text(s.phase.blurb + (s.phaseSinceMs?.let { " · ${mmss(now - it)}" } ?: ""), color = MonitorColors.text, fontSize = 14.sp)
        }
        val stats: @Composable () -> Unit = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                Stat("Edges", "${s.edges}", MonitorColors.pnn50)
                Stat("Climaxes", "${s.climaxes}", Color(0xFFFF7AB6))
                Stat("Building", if (s.buildSeconds > 0) mmss(s.buildSeconds * 1000L) else "--", MonitorColors.good)
                Stat("Plateau", if (s.plateauSeconds > 0) mmss(s.plateauSeconds * 1000L) else "--", MonitorColors.rmssd)
            }
        }
        if (narrow) {
            Column {
                info()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { stats() }
                    ApproachGauge(s.approach, s.arousal, Modifier.weight(1.1f).aspectRatio(1.4f))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { info(); stats() }
                ApproachGauge(s.approach, s.arousal, Modifier.heightIn(max = 170.dp).aspectRatio(1.25f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(label, color = MonitorColors.label, fontSize = 12.sp)
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/** Semicircle gauge: approach to climax 0–100, zones building → plateau → near → threshold; arousal as an inner arc. */
@Composable
private fun ApproachGauge(approach: Int, arousal: Int, modifier: Modifier) {
    val a by animateFloatAsState(approach / 100f, tween(800), label = "approach")
    val ar by animateFloatAsState(arousal / 100f, tween(800), label = "arousal")
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.11f
            val r = (size.width / 2 - stroke).coerceAtMost(size.height - stroke)
            val c = Offset(size.width / 2, size.height - stroke / 2)
            val zones = listOf(0f to MonitorColors.good, 0.45f to MonitorColors.rmssd, 0.75f to MonitorColors.load, 0.9f to Color(0xFFFF7AB6), 1f to Color(0xFFFF7AB6))
            for (i in 0 until zones.size - 1) {
                val (from, col) = zones[i]
                val to = zones[i + 1].first
                drawArc(col.copy(alpha = 0.22f), 180f + from * 180f, (to - from) * 180f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(stroke))
            }
            val col = when { a >= 0.9f -> Color(0xFFFF7AB6); a >= 0.75f -> MonitorColors.load; a >= 0.45f -> MonitorColors.rmssd; else -> MonitorColors.good }
            drawArc(col, 180f, a * 180f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round))
            val r2 = r - stroke * 1.35f
            drawArc(MonitorColors.bp.copy(alpha = 0.25f), 180f, 180f, false, Offset(c.x - r2, c.y - r2), Size(2 * r2, 2 * r2), style = Stroke(stroke * 0.35f))
            drawArc(MonitorColors.bp, 180f, ar * 180f, false, Offset(c.x - r2, c.y - r2), Size(2 * r2, 2 * r2), style = Stroke(stroke * 0.35f, cap = StrokeCap.Round))
            val ang = PI * (1 + a)
            drawLine(MonitorColors.text, c, Offset(c.x + (r * 0.92f * cos(ang)).toFloat(), c.y + (r * 0.92f * sin(ang)).toFloat()), strokeWidth = stroke * 0.18f, cap = StrokeCap.Round)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 2.dp)) {
            Text("$approach", color = MonitorColors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("approach · arousal $arousal", color = MonitorColors.label, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CandidateBanner(s: ResponseState) {
    val c = s.candidate ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFF7AB6).copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFFFF7AB6), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text("${c.confidence.replaceFirstChar { it.uppercase() }} climax detected", color = Color(0xFFFF7AB6), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(c.reason + ". Confirming marks it and teaches the detector your pattern.", color = MonitorColors.text, fontSize = 13.sp)
        }
        ChoiceChip("Yes, mark it", true, Color(0xFFFF7AB6)) { MonitorHub.confirmClimax() }
        ChoiceChip("Not it", false, MonitorColors.label) { MonitorHub.dismissClimax() }
    }
}

/** Phase band over the session with the approach line on top and marked events as ticks. */
@Composable
private fun TimelinePanel(s: ResponseState, now: Long, modifier: Modifier) {
    val session by MonitorHub.session.collectAsState()
    Panel("Response timeline", MonitorColors.text, modifier, trailing = {
        Text("approach line · phase band", color = MonitorColors.label, fontSize = 12.sp)
    }) {
        val points = s.timeline
        Canvas(Modifier.fillMaxSize()) {
            if (points.size < 2) return@Canvas
            val t0 = points.first().timestampMs
            val t1 = maxOf(now, points.last().timestampMs)
            val span = (t1 - t0).coerceAtLeast(60_000L).toFloat()
            fun x(t: Long) = (t - t0) / span * size.width
            val bandH = minOf(size.height * 0.16f, 22.dp.toPx())
            points.zipWithNext().forEach { (a, b) ->
                drawRect(phaseColor(a.phase).copy(alpha = 0.75f), Offset(x(a.timestampMs), size.height - bandH), Size((x(b.timestampMs) - x(a.timestampMs)).coerceAtLeast(1f), bandH))
            }
            val plotH = size.height - bandH - 6f
            listOf(45, 75, 90).forEach { lvl ->
                val y = plotH * (1 - lvl / 100f)
                drawLine(MonitorColors.trendGrid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            val arousal = Path(); val approach = Path()
            points.forEachIndexed { i, p ->
                val px = x(p.timestampMs)
                val ya = plotH * (1 - p.approach / 100f)
                val yr = plotH * (1 - p.arousal / 100f)
                if (i == 0) { approach.moveTo(px, ya); arousal.moveTo(px, yr) } else { approach.lineTo(px, ya); arousal.lineTo(px, yr) }
            }
            drawPath(arousal, MonitorColors.bp.copy(alpha = 0.55f), style = Stroke(1.6f * density))
            drawPath(approach, MonitorColors.load, style = Stroke(2.4f * density, cap = StrokeCap.Round))
        }
        if (session == null) Text("Start recording to build the timeline and enable marks.", color = MonitorColors.label, fontSize = 12.sp)
    }
}

@Composable
private fun SignalsPanel(s: ResponseState) {
    Panel("Why it reads this way", MonitorColors.text, Modifier.fillMaxWidth()) {
        s.signals.forEach { Text("• $it", color = MonitorColors.text, fontSize = 14.sp) }
        Text(
            "Phase, approach and climax detection are estimates from HR, HRV and pelvic pressure — they learn from the climaxes you mark.",
            color = MonitorColors.label, fontSize = 12.sp
        )
    }
}

@Composable
private fun VitalsRow(s: ResponseState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Vital("HR", s.hr?.toString() ?: "--", s.hrDelta?.let { "%+d vs %d".format(it, s.baselineHr ?: 0) } ?: "learning baseline", MonitorColors.hr, Modifier.weight(1f))
        Vital("RMSSD", s.rmssd?.let { "%.0f".format(it) } ?: "--", s.rmssdRatio?.let { "${(it * 100).toInt()}% of median · ${s.hrvSignal}" } ?: s.hrvSignal, MonitorColors.rmssd, Modifier.weight(1f))
        Vital("Pelvic", s.pelvicAvg30?.let { "%.1f".format(it) } ?: "--", "${s.pulsesPerMin}/min" + (s.rhythmSpacingS?.let { " · %.1f s rhythm".format(it) } ?: ""), MonitorColors.pelvic, Modifier.weight(1f))
        Vital("Load", s.load?.toString() ?: "--", "0–100", MonitorColors.load, Modifier.weight(1f))
    }
}

@Composable
private fun Vital(label: String, value: String, detail: String, color: Color, modifier: Modifier) {
    Column(
        modifier
            .background(MonitorColors.panel, RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(value, color = MonitorColors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(detail, color = MonitorColors.label, fontSize = 11.sp, maxLines = 2)
    }
}

@Composable
private fun EmgPanel(modifier: Modifier) {
    Panel("EMG", com.pulsepointlabs.sarahmonitor.emg.EmgColor, modifier) {
        Column(Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 220.dp)) { com.pulsepointlabs.sarahmonitor.emg.EmgCardContent() }
    }
}

@Composable
fun CoachPanel() {
    val on by ResponseSettings.coachOn.collectAsState()
    val mode by ResponseSettings.mode.collectAsState()
    val ctx by ResponseSettings.context.collectAsState()
    val last by MonitorHub.coach.last.collectAsState()
    val status by MonitorHub.coach.status.collectAsState()
    Panel("Sarah, live", Color(0xFFFF7AB6), Modifier.fillMaxWidth(), trailing = {
        ChoiceChip(if (on) "On" else "Off", on, Color(0xFFFF7AB6)) { ResponseSettings.setCoachOn(!on) }
    }) {
        Text(status, color = MonitorColors.label, fontSize = 12.sp)
        last?.let {
            Text(
                "“$it”", color = MonitorColors.text, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0xFFFF7AB6).copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(8.dp)
            )
        }
        Text("Session", color = MonitorColors.label, fontSize = 12.sp)
        ChoiceRow { SessionContext.entries.forEach { c -> ChoiceChip(c.label, c == ctx, Color(0xFFFF7AB6)) { ResponseSettings.setContext(c) } } }
        Text("Sarah's focus", color = MonitorColors.label, fontSize = 12.sp)
        ChoiceRow {
            CoachMode.entries.forEach { m -> ChoiceChip(m.label, m == mode, Color(0xFFFF7AB6)) { ResponseSettings.setMode(m) } }
            ChoiceChip("▶ Ask Sarah now", false, Color(0xFFFF7AB6)) { MonitorHub.coach.askNow() }
        }
    }
}

@Composable
private fun QuickMarks() {
    val ctx by ResponseSettings.context.collectAsState()
    val session by MonitorHub.session.collectAsState()
    val ids = listOf("arousal", "plateau", "near", "edge", "orgasm", "ejaculation", "resolution") +
        if (ctx == SessionContext.Exploration) listOf("catheter", "enema") else emptyList()
    Panel("Quick marks", MonitorColors.text, Modifier.fillMaxWidth()) {
        if (session == null) Text("Start recording to mark events.", color = MonitorColors.label, fontSize = 12.sp)
        ChoiceRow {
            ids.forEach { id ->
                val type = MarkerTypes.all.firstOrNull { it.id == id } ?: return@forEach
                val color = markerColor(id)
                Text(
                    type.label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .background(color.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                        .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                        .clickable(enabled = session != null) { MonitorHub.markEvent(System.currentTimeMillis(), setOf(id), "") }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
        }
    }
}

private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Settings: Sarah's brain (Claude) and how often she speaks. */
@Composable
fun CoachSettingsSection() {
    val key by ResponseSettings.apiKey.collectAsState()
    val model by ResponseSettings.model.collectAsState()
    val cadence by ResponseSettings.cadenceS.collectAsState()
    val profile by ResponseSettings.profile.collectAsState()
    var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    com.pulsepointlabs.sarahmonitor.ui.SettingsSection(
        "Sarah live feedback", Color(0xFFFF7AB6),
        "During a recording on the Response tab, Sarah speaks short lines about what your body is doing. Claude is her brain (your Anthropic key); her voice is the HD Crisp OpenAI voice."
    ) {
        Text(
            if (key.isBlank()) "No Anthropic key saved yet." else "Anthropic key saved: ${key.take(7)}…${key.takeLast(4)}",
            color = if (key.isBlank()) MonitorColors.warn else MonitorColors.good, fontSize = 13.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = draft, onValueChange = { draft = it.trim() },
                label = { Text("Anthropic API key (sk-ant-…)") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.TextButton(enabled = draft.isNotBlank(), onClick = { ResponseSettings.setApiKey(draft); draft = "" }) { Text("Save") }
            if (key.isNotBlank()) androidx.compose.material3.TextButton(onClick = { ResponseSettings.setApiKey("") }) { Text("Remove", color = MonitorColors.alarm) }
        }
        Text("Brain", color = MonitorColors.text, fontSize = 15.sp)
        ChoiceRow { CoachModel.entries.forEach { m -> ChoiceChip(m.label, m == model, Color(0xFFFF7AB6)) { ResponseSettings.setModel(m) } } }
        com.pulsepointlabs.sarahmonitor.ui.SettingSlider("Check in every", "$cadence s", cadence.toFloat(), 20f..300f) { ResponseSettings.setCadence(it.toInt()) }
        Text("Phase changes speak sooner (at most every 15 s).", color = MonitorColors.label, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (profile.learned) "Climax detector calibrated from ${profile.samples.size} marked climaxes (typical rise +${profile.climaxHrDelta.toInt()} bpm)."
                else "Climax detector: generic until you've marked a couple of climaxes (${profile.samples.size} so far).",
                color = MonitorColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f)
            )
            if (profile.samples.isNotEmpty()) androidx.compose.material3.TextButton(onClick = { ResponseSettings.resetProfile() }) { Text("Reset", color = MonitorColors.alarm) }
        }
    }
}
