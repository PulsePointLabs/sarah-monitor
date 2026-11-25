package com.pulsepointlabs.sarahmonitor.response

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.review.markerColor
import com.pulsepointlabs.sarahmonitor.session.MarkerTypes
import com.pulsepointlabs.sarahmonitor.ui.BigReading
import com.pulsepointlabs.sarahmonitor.ui.CardTitle
import com.pulsepointlabs.sarahmonitor.ui.LocalCardScale
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import com.pulsepointlabs.sarahmonitor.ui.StatChip
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

val ClimaxPink = Color(0xFFFF7AB6)

/** Color for an approach level: building → plateau → near → threshold. */
fun approachColor(a: Int): Color = when {
    a >= 90 -> ClimaxPink
    a >= 75 -> MonitorColors.alarm
    a >= 45 -> MonitorColors.load
    a >= 25 -> MonitorColors.rmssd
    else -> MonitorColors.good
}

/** True when the Response board should flash: near climax, or a climax happening. */
fun ResponseState.urgent(): Boolean =
    ResponseSettings.context.value != SessionContext.Exploration && (approach >= 75 || phase == ResponsePhase.Climax)

private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val s = LocalCardScale.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MonitorColors.label, fontSize = s.sp(11f), maxLines = 1)
        Text(value, color = color, fontSize = s.sp(17f), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/** Exploration sessions read as a response, not a climax approach. */
private fun explorationState(s: ResponseState): Pair<String, Color> = when {
    (s.hrDelta ?: 0) >= 20 || (s.load ?: 0) >= 70 -> "Strong response" to MonitorColors.alarm
    (s.hrDelta ?: 0) >= 8 || (s.load ?: 0) >= 55 -> "Responding" to MonitorColors.load
    s.dropFromPeak >= 8 -> "Settling" to MonitorColors.pelvic
    else -> "Calm" to MonitorColors.good
}

@Composable
fun PhaseCard() {
    val st by MonitorHub.response.collectAsState()
    val ctx by ResponseSettings.context.collectAsState()
    val s = LocalCardScale.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000L); now = System.currentTimeMillis() } }
    val exploring = ctx == SessionContext.Exploration
    val (label, base) = if (exploring) explorationState(st) else st.phase.label to phaseColor(st.phase)
    val color by animateColorAsState(base, tween(600), label = "phaseColor")
    com.pulsepointlabs.sarahmonitor.ui.FitColumn(Modifier.fillMaxSize()) {
        fixed { CardTitle(if (exploring) "Response" else "Phase", color) {
            st.phaseSinceMs?.let { Text(mmss(now - it), color = MonitorColors.label, fontSize = s.sp(11f), fontFamily = FontFamily.Monospace) }
        } }
        // The phase name sized to the space it has (big on a big card), never cut off.
        flex(minDp = 34f) { androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val byWidth = maxWidth.value / (label.length.coerceAtLeast(6) * 0.62f)
            val sizeDp = minOf(byWidth, maxHeight.value * 0.62f, 64f).coerceAtLeast(14f)
            Text(label, color = color, fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { sizeDp.dp.toSp() }, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
        } }
        st.candidate?.takeIf { !exploring }?.let { c ->
            fixed { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Climax?", color = ClimaxPink, fontSize = s.sp(14f), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill("Yes", ClimaxPink, true) { MonitorHub.confirmClimax() }
                Pill("No", MonitorColors.label, false) { MonitorHub.dismissClimax() }
            } }
        }
        fixed(drop = 1) { com.pulsepointlabs.sarahmonitor.ui.ChipGrid(Modifier.fillMaxWidth()) {
            if (exploring) {
                StatChip("HR change", st.hrDelta?.let { "%+d".format(it) } ?: "--", MonitorColors.hr)
                StatChip("Peak", st.peakHr?.roundToInt()?.toString() ?: "--", MonitorColors.hr)
                StatChip("Load", st.load?.toString() ?: "--", MonitorColors.load)
            } else {
                StatChip("Edges", "${st.edges}", MonitorColors.pnn50)
                StatChip("Climaxes", "${st.climaxes}", ClimaxPink)
                StatChip("Build", if (st.buildSeconds > 0) mmss(st.buildSeconds * 1000L) else "--", MonitorColors.good)
                StatChip("Plateau", if (st.plateauSeconds > 0) mmss(st.plateauSeconds * 1000L) else "--", MonitorColors.rmssd)
            }
        } }
    }
}

/** A pill showing the current choice ("Session: Masturbation ▾"); tap for the list. */
@Composable
private fun MenuPill(title: String, current: String, options: List<String>, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val s = LocalCardScale.current
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().border(1.dp, ClimaxPink, RoundedCornerShape(14.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (title.isNotBlank()) Text(title, color = MonitorColors.label, fontSize = s.sp(11f), maxLines = 1, softWrap = false)
            Text(current, color = ClimaxPink, fontSize = s.sp(13f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("▾", color = ClimaxPink, fontSize = s.sp(13f))
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = MonitorColors.panel) {
            options.forEachIndexed { i, o ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(o, color = if (o == current) ClimaxPink else MonitorColors.text, fontWeight = if (o == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onPick(i) }
                )
            }
        }
    }
}

@Composable
private fun Pill(label: String, color: Color, filled: Boolean, onClick: () -> Unit) {
    val s = LocalCardScale.current
    Text(
        label, color = if (filled) MonitorColors.background else color, fontSize = s.sp(13f), fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(if (filled) color else Color.Transparent, RoundedCornerShape(14.dp))
            .border(1.dp, color, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/**
 * Near-climax watch: a big arc gauge with colored zones; at 75+ it flashes "NEAR CLIMAX", at climax "CLIMAX".
 * In body exploration it becomes the HR response (change from baseline).
 */
@Composable
fun NearClimaxCard() {
    val st by MonitorHub.response.collectAsState()
    val ctx by ResponseSettings.context.collectAsState()
    val s = LocalCardScale.current
    val exploring = ctx == SessionContext.Exploration
    val value = if (exploring) ((st.hrDelta ?: 0).coerceAtLeast(0) * 100 / 40).coerceAtMost(100) else st.approach
    val a by animateFloatAsState(value / 100f, tween(700), label = "gauge")
    val color by animateColorAsState(if (exploring) explorationState(st).second else approachColor(value), tween(500), label = "gaugeColor")
    val urgent = !exploring && st.urgent()
    // Only animate while it matters (saves battery the rest of the time).
    val pulse = if (urgent) pulseAlpha() else 1f
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle(if (exploring) "HR response" else "Near-climax watch", color, if (exploring) "bpm" else "%")
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.13f
                val r = minOf(size.width / 2 - stroke, size.height - stroke * 0.7f)
                val c = Offset(size.width / 2, size.height - stroke * 0.2f)
                val tl = Offset(c.x - r, c.y - r)
                val sz = Size(2 * r, 2 * r)
                val zones = if (exploring) listOf(0f to MonitorColors.good, 0.2f to MonitorColors.load, 0.5f to MonitorColors.alarm, 1f to MonitorColors.alarm)
                else listOf(0f to MonitorColors.good, 0.25f to MonitorColors.rmssd, 0.45f to MonitorColors.load, 0.75f to MonitorColors.alarm, 0.9f to ClimaxPink, 1f to ClimaxPink)
                for (i in 0 until zones.size - 1) drawArc(zones[i].second.copy(alpha = 0.2f), 180f + zones[i].first * 180f, (zones[i + 1].first - zones[i].first) * 180f, false, tl, sz, style = Stroke(stroke))
                drawArc(color.copy(alpha = if (urgent) pulse else 1f), 180f, a * 180f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                if (!exploring) listOf(0.45f, 0.75f, 0.9f).forEach { z ->
                    val ang = PI * (1 + z)
                    drawLine(MonitorColors.background, Offset(c.x + ((r - stroke * 0.6f) * cos(ang)).toFloat(), c.y + ((r - stroke * 0.6f) * sin(ang)).toFloat()),
                        Offset(c.x + ((r + stroke * 0.6f) * cos(ang)).toFloat(), c.y + ((r + stroke * 0.6f) * sin(ang)).toFloat()), strokeWidth = 3f)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (exploring) st.hrDelta?.let { "%+d".format(it) } ?: "--" else "$value",
                    color = MonitorColors.text, fontSize = s.sp(40f), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                Text(
                    when {
                        exploring -> st.hr?.let { "HR $it · baseline ${st.baselineHr ?: "--"}" } ?: ""
                        st.phase == ResponsePhase.Climax -> "CLIMAX"
                        value >= 75 -> "NEAR CLIMAX"
                        value >= 45 -> "BUILDING"
                        else -> ""
                    },
                    color = color.copy(alpha = if (urgent) pulse else 1f), fontSize = s.sp(if (urgent) 20f else 13f), fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** Bars for approach, plateau, recovery and signal agreement (Sarah's threshold load matrix). */
@Composable
fun MatrixCard() {
    val st by MonitorHub.response.collectAsState()
    val ctx by ResponseSettings.context.collectAsState()
    val s = LocalCardScale.current
    val exploring = ctx == SessionContext.Exploration
    val rows = if (exploring) listOf(
        Triple("HR change", ((st.hrDelta ?: 0).coerceAtLeast(0) * 100 / 40).coerceAtMost(100), MonitorColors.hr),
        Triple("Load", st.load ?: 0, MonitorColors.load),
        Triple("HRV tight", st.rmssdRatio?.let { ((1 - it) * 100).roundToInt().coerceIn(0, 100) } ?: 0, MonitorColors.rmssd),
        Triple("Pelvic tension", ((st.pelvicAvg30 ?: 0.0) / 3.0 * 100).roundToInt().coerceIn(0, 100), MonitorColors.pelvic)
    ) else listOf(
        Triple("Approach", st.approach, approachColor(st.approach)),
        Triple("Plateau", st.plateau, MonitorColors.rmssd),
        Triple("Recovery", st.recovery, MonitorColors.pelvic),
        Triple("Agreement", (st.confirmations * 100 / 5).coerceAtMost(100), MonitorColors.sdnn)
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        CardTitle(if (exploring) "Response matrix" else "Threshold matrix", MonitorColors.text)
        rows.forEach { (label, v, c) ->
            val f by animateFloatAsState(v / 100f, tween(600), label = label)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = MonitorColors.label, fontSize = s.sp(12f), modifier = Modifier.weight(0.34f), maxLines = 1)
                Canvas(Modifier.weight(0.52f).height(s.dp(14f))) {
                    drawRoundRect(MonitorColors.panelBorder, size = size, cornerRadius = CornerRadius(size.height / 2))
                    drawRoundRect(c, size = Size(size.width * f.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(size.height / 2))
                    if (!exploring) listOf(0.45f, 0.75f).forEach { z -> drawLine(MonitorColors.background, Offset(size.width * z, 0f), Offset(size.width * z, size.height), 2f) }
                }
                Text("$v", color = MonitorColors.text, fontSize = s.sp(15f), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.14f), textAlign = TextAlign.End)
            }
        }
    }
}

private fun DrawScope.series(raw: List<Pair<Long, Double?>>, t0: Long, t1: Long, lo: Double, hi: Double, color: Color, width: Float, top: Float, h: Float, dashed: Boolean = false) {
    val span = (hi - lo).takeIf { it > 1e-6 } ?: 1.0
    // Many more points than pixels turns a line into a solid zig-zag: average into buckets about 3 px wide.
    val buckets = (size.width / (3f * density)).toInt().coerceAtLeast(20)
    val points = if (raw.size <= buckets) raw else {
        val bucketMs = ((t1 - t0) / buckets).coerceAtLeast(1)
        raw.groupBy { (it.first - t0) / bucketMs }.toSortedMap().map { (_, g) ->
            val vs = g.mapNotNull { it.second }
            g.first().first to (if (vs.isEmpty()) null else vs.average())
        }
    }
    val p = Path()
    var started = false
    points.forEach { (t, v) ->
        if (v == null) { started = false; return@forEach }
        val x = (t - t0).toFloat() / (t1 - t0).coerceAtLeast(1) * size.width
        val y = top + h - ((v - lo) / span).toFloat().coerceIn(0f, 1f) * h
        if (!started) { p.moveTo(x, y); started = true } else p.lineTo(x, y)
    }
    drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
}

/** Phase band with approach, plateau and recovery lines; marked events as ticks. Exploration: HR change + RMSSD. */
@Composable
fun ResponseTimelineCard() {
    val st by MonitorHub.response.collectAsState()
    val ctx by ResponseSettings.context.collectAsState()
    val s = LocalCardScale.current
    val exploring = ctx == SessionContext.Exploration
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle(if (exploring) "Procedure response" else "Phase timeline", MonitorColors.text) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (exploring) listOf("HR Δ" to MonitorColors.hr, "RMSSD" to MonitorColors.rmssd)
                else listOf("Approach" to MonitorColors.load, "Plateau" to MonitorColors.rmssd, "Recovery" to MonitorColors.pelvic)).forEach { (l, c) ->
                    Text(l, color = c, fontSize = s.sp(11f), fontWeight = FontWeight.Bold)
                }
            }
        }
        val pts = st.timeline
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (pts.size < 2) return@Canvas
            val t0 = pts.first().timestampMs
            val t1 = maxOf(pts.last().timestampMs, t0 + 60_000L)
            val band = minOf(size.height * 0.14f, 16.dp.toPx())
            val h = size.height - band - 4f
            fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * size.width
            // Phase band: the most common phase in each ~3 px slice, so brief flickers don't turn it into noise.
            val slices = (size.width / (3f * density)).toInt().coerceAtLeast(20)
            val sliceMs = ((t1 - t0) / slices).coerceAtLeast(1)
            pts.groupBy { (it.timestampMs - t0) / sliceMs }.forEach { (i, g) ->
                val phase = g.groupingBy { it.phase }.eachCount().maxBy { it.value }.key
                drawRect(phaseColor(phase).copy(alpha = 0.8f), Offset(x(t0 + i * sliceMs), size.height - band), Size(x(t0 + (i + 1) * sliceMs) - x(t0 + i * sliceMs) + 0.5f, band))
            }
            if (exploring) {
                val d = pts.map { p -> p.timestampMs to (if (p.hr != null && p.baseline != null) p.hr - p.baseline else null) }
                series(d, t0, t1, -10.0, 40.0, MonitorColors.hr, 2.4f * density, 0f, h)
                val rm = pts.map { it.timestampMs to it.rmssd }
                val hi = rm.mapNotNull { it.second }.maxOrNull() ?: 50.0
                series(rm, t0, t1, 0.0, hi * 1.1, MonitorColors.rmssd, 1.8f * density, 0f, h)
            } else {
                listOf(45, 75).forEach { lvl -> drawLine(MonitorColors.trendGrid, Offset(0f, h * (1 - lvl / 100f)), Offset(size.width, h * (1 - lvl / 100f)), 1f) }
                series(pts.map { it.timestampMs to it.plateau.toDouble() }, t0, t1, 0.0, 100.0, MonitorColors.rmssd, 1.6f * density, 0f, h)
                series(pts.map { it.timestampMs to it.recovery.toDouble() }, t0, t1, 0.0, 100.0, MonitorColors.pelvic, 1.6f * density, 0f, h)
                series(pts.map { it.timestampMs to it.approach.toDouble() }, t0, t1, 0.0, 100.0, MonitorColors.load, 2.6f * density, 0f, h)
            }
        }
    }
}

/** HR with its baseline (dashed) and RMSSD over the last 10 minutes. */
@Composable
fun CardiacCard() {
    val st by MonitorHub.response.collectAsState()
    val s = LocalCardScale.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle("Cardiac timeline", MonitorColors.hr) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HR ${st.hr ?: "--"}", color = MonitorColors.hr, fontSize = s.sp(11f), fontWeight = FontWeight.Bold)
                Text("baseline ${st.baselineHr ?: "--"}", color = MonitorColors.label, fontSize = s.sp(11f))
                Text("RMSSD ${st.rmssd?.roundToInt() ?: "--"}", color = MonitorColors.rmssd, fontSize = s.sp(11f), fontWeight = FontWeight.Bold)
            }
        }
        val pts = st.timeline.filter { (st.timeline.lastOrNull()?.timestampMs ?: 0L) - it.timestampMs <= 600_000L }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (pts.size < 2) return@Canvas
            val t0 = pts.first().timestampMs
            val t1 = maxOf(pts.last().timestampMs, t0 + 60_000L)
            val hrs = pts.mapNotNull { it.hr } + pts.mapNotNull { it.baseline }
            val lo = (hrs.minOrNull() ?: 50.0) - 4
            val hi = (hrs.maxOrNull() ?: 120.0) + 4
            val h = size.height * 0.68f
            series(pts.map { it.timestampMs to it.baseline }, t0, t1, lo, hi, MonitorColors.label, 1.4f * density, 0f, h, dashed = true)
            series(pts.map { it.timestampMs to it.hr }, t0, t1, lo, hi, MonitorColors.hr, 2.4f * density, 0f, h)
            val rm = pts.map { it.timestampMs to it.rmssd }
            val rhi = rm.mapNotNull { it.second }.maxOrNull() ?: 50.0
            series(rm, t0, t1, 0.0, rhi * 1.1, MonitorColors.rmssd, 1.8f * density, h + 4f, size.height - h - 4f)
        }
    }
}

@Composable
fun RecoveryCard() {
    val st by MonitorHub.response.collectAsState()
    val ctx by ResponseSettings.context.collectAsState()
    val s = LocalCardScale.current
    val exploring = ctx == SessionContext.Exploration
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle(if (exploring) "Below peak" else "Recovery", MonitorColors.pelvic, "bpm")
        BigReading("%.0f".format(st.dropFromPeak.coerceAtLeast(0.0)), if (st.dropFromPeak >= 8) MonitorColors.pelvic else MonitorColors.text, Modifier.fillMaxWidth().weight(1f), maxChars = 3)
        Row(Modifier.fillMaxWidth()) {
            Stat("30 s", st.drop30?.let { "%+.0f".format(-it) } ?: "--", MonitorColors.text, Modifier.weight(1f))
            Stat("60 s", st.drop60?.let { "%+.0f".format(-it) } ?: "--", MonitorColors.text, Modifier.weight(1f))
            Stat("Peak", st.peakHr?.roundToInt()?.toString() ?: "--", MonitorColors.hr, Modifier.weight(1f))
        }
    }
}

@Composable
fun RespirationCard() {
    val st by MonitorHub.response.collectAsState()
    val s = LocalCardScale.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle("Respiration", MonitorColors.pelvic, "/min")
        BigReading(st.breathsPerMin?.let { "%.0f".format(it) } ?: "--", MonitorColors.pelvic, Modifier.fillMaxWidth().weight(1f), maxChars = 2)
        Row(Modifier.fillMaxWidth()) {
            val m = st.motionMg
            Stat("Motion", m?.let { "%.0f mg".format(it) } ?: "--", when {
                m == null -> MonitorColors.label; m >= 180 -> MonitorColors.alarm; m >= 90 -> MonitorColors.warn; else -> MonitorColors.good
            }, Modifier.weight(1f))
        }
    }
}

@Composable
fun SarahLiveCard() {
    val on by ResponseSettings.coachOn.collectAsState()
    val mode by ResponseSettings.mode.collectAsState()
    val ctx by ResponseSettings.context.collectAsState()
    val last by MonitorHub.coach.last.collectAsState()
    val status by MonitorHub.coach.status.collectAsState()
    val running = MonitorHub.session.collectAsState().value?.let { com.pulsepointlabs.sarahmonitor.session.SessionCategory.of(it.category) }
    val s = LocalCardScale.current
    com.pulsepointlabs.sarahmonitor.ui.FitColumn(Modifier.fillMaxSize(), gap = 5.dp) {
        fixed { CardTitle("Sarah", ClimaxPink) { Pill(if (on) "On" else "Off", ClimaxPink, on) { ResponseSettings.setCoachOn(!on) } } }
        flex(minDp = 20f) { Text(
            last?.let { "“$it”" } ?: if (!on) "Live feedback is off." else status, color = if (last != null) MonitorColors.text else MonitorColors.label,
            fontSize = s.sp(if (last != null) 15f else 12f), lineHeight = s.sp(if (last != null) 20f else 16f), maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxSize()
        ) }
        // Session type and how she talks: one tidy row each, choices in a menu (no stack of pills).
        // A general (non-sexual) recording says so, instead of a leftover "Masturbation" from the last sexual session.
        if (running != null && !running.sexual) fixed { MenuPill("Session", running.label, listOf(running.label), Modifier.fillMaxWidth()) { } }
        else fixed { MenuPill("Session", ctx.label, SessionContext.entries.map { it.label }, Modifier.fillMaxWidth()) { i -> ResponseSettings.setContext(SessionContext.entries[i]) } }
        fixed(drop = 1) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MenuPill("", mode.label, CoachMode.entries.map { it.label }, Modifier.weight(1f)) { i -> ResponseSettings.setMode(CoachMode.entries[i]) }
            Pill("▶ Ask", ClimaxPink, false) { MonitorHub.coach.askNow() }
        } }
    }
}

@Composable
fun QuickMarksCard() {
    val ctx by ResponseSettings.context.collectAsState()
    val session by MonitorHub.session.collectAsState()
    val s = LocalCardScale.current
    val ids = if (ctx == SessionContext.Exploration) listOf("catheter", "enema", "stimulation", "symptom", "resolution")
    else listOf("arousal", "plateau", "near", "edge", "orgasm", "ejaculation", "resolution")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CardTitle("Mark", MonitorColors.text)
        com.pulsepointlabs.sarahmonitor.ui.ChoiceRow {
            ids.forEach { id ->
                val type = MarkerTypes.all.firstOrNull { it.id == id } ?: return@forEach
                val color = markerColor(id)
                Text(
                    type.label, color = if (session != null) color else color.copy(alpha = 0.4f), fontSize = s.sp(14f), fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .background(color.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                        .border(1.dp, color.copy(alpha = if (session != null) 0.7f else 0.25f), RoundedCornerShape(18.dp))
                        .clickable(enabled = session != null) { MonitorHub.markEvent(System.currentTimeMillis(), setOf(id), "") }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun pulseAlpha(): Float {
    val v by rememberInfiniteTransition(label = "pulse").animateFloat(0.35f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "pulseA")
    return v
}

/** Pulsing pink frame around the whole Response board while near climax or at climax. */
@Composable
fun UrgencyFrame(modifier: Modifier = Modifier) {
    val st by MonitorHub.response.collectAsState()
    val ctx by ResponseSettings.context.collectAsState()
    if (ctx == SessionContext.Exploration || !st.urgent()) return
    val pulse by rememberInfiniteTransition(label = "frame").animateFloat(0.15f, 0.85f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "frameA")
    val color = if (st.phase == ResponsePhase.Climax || st.approach >= 90) ClimaxPink else MonitorColors.alarm
    Canvas(modifier.fillMaxSize()) {
        val w = 6.dp.toPx()
        drawRoundRect(color.copy(alpha = pulse), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(14f), style = Stroke(w))
    }
}

@Suppress("unused")
private fun unused(x: Double) = abs(x)
