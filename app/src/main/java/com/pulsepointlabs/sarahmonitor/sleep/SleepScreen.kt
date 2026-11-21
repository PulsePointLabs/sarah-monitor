package com.pulsepointlabs.sarahmonitor.sleep

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.ui.ChoiceChip
import com.pulsepointlabs.sarahmonitor.ui.ChoiceRow
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import com.pulsepointlabs.sarahmonitor.vitals.SpO2Color
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val SleepColor = Color(0xFF818CF8)

fun stageColor(s: SleepStage): Color = when (s) {
    SleepStage.Wake -> Color(0xFFFBBF24)
    SleepStage.Rem -> Color(0xFF38BDF8)
    SleepStage.Light -> Color(0xFF818CF8)
    SleepStage.Deep -> Color(0xFF4338CA)
}

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val day = DateTimeFormatter.ofPattern("EEE MMM d")
private fun at(ms: Long, f: DateTimeFormatter) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(f)
private fun hm(min: Int) = "${min / 60}h ${"%02d".format(min % 60)}m"

@Composable
private fun Panel(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(MonitorColors.panel, RoundedCornerShape(12.dp)).border(1.dp, SleepColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = SleepColor, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/** Sleep mode: start at bedtime, a dim night view, then a scored night with a hypnogram and breakdowns. */
@Composable
fun SleepScreen(modifier: Modifier = Modifier) {
    val sleeping by MonitorHub.sleeping.collectAsState()
    val live by MonitorHub.sleepLive.collectAsState()
    val nights by MonitorHub.sleepStore.nights.collectAsState()
    var viewing by remember { mutableStateOf<Long?>(null) }
    var night by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val shown = (viewing ?: nights.lastOrNull()?.startMs)?.let { id -> nights.firstOrNull { it.startMs == id } }
    val epochs = remember(shown?.startMs) { shown?.let { MonitorHub.sleepStore.epochs(it.startMs) }.orEmpty() }

    if (night) NightView { night = false }

    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 1000.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Panel(if (sleeping != null) "Sleep mode is on" else "Sleep mode", Modifier.weight(1f)) {
                    if (sleeping != null) LiveNight(sleeping!!, live) else {
                        Text("Wear the H10 to bed. Heart rate, HRV, movement, position and breathing (from your heart rhythm) are scored in 30 s epochs; imported SpO2 for the night is added to the summary.", color = MonitorColors.text, fontSize = 13.sp)
                    }
                    message?.let { Text(it, color = MonitorColors.warn, fontSize = 13.sp) }
                    ChoiceRow {
                        if (sleeping == null) ChoiceChip("🌙 Start sleep mode", true, SleepColor) { message = null; MonitorHub.startSleep() }
                        else {
                            ChoiceChip("Night view", false, SleepColor) { night = true }
                            ChoiceChip("☀ I'm up — end night", true, SleepColor) {
                                val s = MonitorHub.stopSleep()
                                message = if (s == null) "Night was too short to score (under 10 minutes)." else null
                                viewing = s?.startMs
                            }
                        }
                    }
                }
                if (wide) CalibrationPanel(Modifier.weight(1f))
            }
            if (!wide) CalibrationPanel(Modifier.fillMaxWidth())
            if (shown != null) NightDetail(shown, epochs, wide)
            if (nights.isNotEmpty()) History(nights, shown?.startMs) { viewing = it }
        }
    }
}

@Composable
private fun LiveNight(startMs: Long, live: List<SleepEpoch>) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000L); now = System.currentTimeMillis() } }
    val heart by MonitorHub.heart.collectAsState()
    val last = live.lastOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Big("Since", at(startMs, clock), MonitorColors.text)
        Big("Elapsed", hm(((now - startMs) / 60_000).toInt()), MonitorColors.text)
        Big("HR", heart.heartRate?.toString() ?: "--", MonitorColors.hr)
        Big("Stage", last?.stage?.label ?: "…", last?.let { stageColor(it.stage) } ?: MonitorColors.label)
    }
    Text(
        listOfNotNull(last?.breathsPerMin?.let { "breathing %.0f/min".format(it) }, last?.position?.takeIf { it != BodyPosition.Unknown }?.label, last?.rmssd?.let { "RMSSD %.0f".format(it) })
            .joinToString(" · ").ifBlank { "First epochs arrive after 30 s" },
        color = MonitorColors.label, fontSize = 13.sp
    )
    if (live.size >= 4) Hypnogram(live, Modifier.fillMaxWidth().height(90.dp))
}

@Composable
private fun Big(label: String, value: String, color: Color) {
    Column {
        Text(label, color = MonitorColors.label, fontSize = 12.sp)
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CalibrationPanel(modifier: Modifier) {
    val back by PositionCalibration.back.collectAsState()
    val right by PositionCalibration.right.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    Panel("Body position (one-time setup)", modifier) {
        Text(
            if (back != null && right != null) "Calibrated ✓ — positions are tracked. Redo it if you wear the strap differently."
            else "Lie on your back with the strap on and tap the first button, then roll onto your right side and tap the second. It's remembered for later nights.",
            color = MonitorColors.text, fontSize = 13.sp
        )
        note?.let { Text(it, color = MonitorColors.warn, fontSize = 12.sp) }
        ChoiceRow {
            ChoiceChip((if (back != null) "✓ " else "") + "I'm on my back now", false, SleepColor) {
                val g = MonitorHub.currentGravity()
                if (g == null) note = "No accelerometer data from the H10 yet." else { PositionCalibration.set("back", g); note = null }
            }
            ChoiceChip((if (right != null) "✓ " else "") + "I'm on my right side now", false, SleepColor) {
                val g = MonitorHub.currentGravity()
                if (g == null) note = "No accelerometer data from the H10 yet." else { PositionCalibration.set("right", g); note = null }
            }
        }
    }
}

@Composable
private fun NightDetail(n: NightSummary, epochs: List<SleepEpoch>, wide: Boolean) {
    Panel("${at(n.startMs, day)} · ${at(n.startMs, clock)}–${at(n.endMs, clock)}", Modifier.fillMaxWidth(), trailing = {
        ChoiceChip("Delete", false, MonitorColors.alarm) { MonitorHub.sleepStore.delete(n.startMs) }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(n.score, Modifier.size(if (wide) 130.dp else 104.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Big("Asleep", hm(n.asleepMin), MonitorColors.text)
                    Big("Efficiency", "${n.efficiency}%", MonitorColors.text)
                    if (wide) Big("To fall asleep", "${n.latencyMin} min", MonitorColors.text)
                    if (wide) Big("Awake after onset", "${n.wasoMin} min", MonitorColors.text)
                }
                StageBar(n)
            }
        }
        if (epochs.isNotEmpty()) {
            Text("Stages", color = MonitorColors.label, fontSize = 12.sp)
            Hypnogram(epochs, Modifier.fillMaxWidth().height(if (wide) 140.dp else 110.dp))
            Lines(epochs, n, Modifier.fillMaxWidth().height(if (wide) 200.dp else 170.dp))
        }
        val tiles = listOfNotNull(
            "Avg HR asleep" to (n.avgHr?.let { "%.0f".format(it) } ?: "--"),
            "Lowest HR" to (n.minHr?.let { "%.0f".format(it) } ?: "--"),
            "RMSSD" to (n.avgRmssd?.let { "%.0f ms".format(it) } ?: "--"),
            "Breathing" to (n.avgBreaths?.let { "%.1f /min".format(it) } ?: "--"),
            "Wake bouts" to "${n.wakeBouts}",
            "Position changes" to "${n.positionChanges}",
            n.spo2Avg?.let { "SpO2 avg" to "%.1f%%".format(it) },
            n.spo2Min?.let { "SpO2 low" to "$it%" },
            n.spo2Below90Min?.let { "SpO2 < 90%" to "$it min" }
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEach { (l, v) ->
                Column(Modifier.background(MonitorColors.background, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(l, color = MonitorColors.label, fontSize = 11.sp)
                    Text(v, color = MonitorColors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
        val pos = n.positionMin.filter { it.value > 0 }
        if (pos.isNotEmpty()) Text("Position: " + pos.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key.label} ${hm(it.value)}" }, color = MonitorColors.label, fontSize = 12.sp)
        Text("Stages are estimated from heart rate, HRV, movement and breathing — useful for trends, not a sleep-lab study.", color = MonitorColors.dim, fontSize = 11.sp)
    }
}

@Composable
private fun ScoreRing(score: Int, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = size.minDimension * 0.1f
            drawArc(SleepColor.copy(alpha = 0.18f), -90f, 360f, false, Offset(st / 2, st / 2), Size(size.width - st, size.height - st), style = Stroke(st))
            drawArc(SleepColor, -90f, 360f * score / 100f, false, Offset(st / 2, st / 2), Size(size.width - st, size.height - st), style = Stroke(st, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", color = MonitorColors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("sleep score", color = MonitorColors.label, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StageBar(n: NightSummary) {
    val total = n.stageMin.values.sum().coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        var x = 0f
        listOf(SleepStage.Deep, SleepStage.Light, SleepStage.Rem, SleepStage.Wake).forEach { s ->
            val w = size.width * (n.stageMin[s] ?: 0) / total
            if (w > 0) drawRoundRect(stageColor(s), Offset(x, 0f), Size((w - 2f).coerceAtLeast(1f), size.height), CornerRadius(5f))
            x += w
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(SleepStage.Deep, SleepStage.Light, SleepStage.Rem, SleepStage.Wake).forEach { s ->
            Text("${s.label} ${hm(n.stageMin[s] ?: 0)}", color = stageColor(s), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Classic hypnogram: awake on top, deep at the bottom. */
@Composable
private fun Hypnogram(epochs: List<SleepEpoch>, modifier: Modifier) {
    Canvas(modifier) {
        val t0 = epochs.first().startMs
        val t1 = epochs.last().startMs + 30_000L
        val lane = size.height / 4
        fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * size.width
        for (i in 0..3) drawLine(MonitorColors.trendGrid, Offset(0f, lane * i + lane / 2), Offset(size.width, lane * i + lane / 2), 1f)
        epochs.forEach { e ->
            drawRoundRect(stageColor(e.stage), Offset(x(e.startMs), lane * e.stage.lane + lane * 0.15f), Size((x(e.startMs + 30_000L) - x(e.startMs)).coerceAtLeast(1f) + 0.5f, lane * 0.7f), CornerRadius(2f))
        }
        hourTicks(t0, t1)
    }
}

private fun DrawScope.hourTicks(t0: Long, t1: Long) {
    val hour = 3_600_000L
    var t = (t0 / hour + 1) * hour
    while (t < t1) {
        val x = (t - t0).toFloat() / (t1 - t0) * size.width
        drawLine(MonitorColors.label.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, size.height), 1f)
        t += hour
    }
}

/** HR, breathing and SpO2 lines with movement bars underneath. */
@Composable
private fun Lines(epochs: List<SleepEpoch>, n: NightSummary, modifier: Modifier) {
    val spo2 = MonitorHub.spo2.items.collectAsState().value.filter { it.timestampMs in n.startMs..n.endMs }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("HR", color = MonitorColors.hr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Breathing", color = MonitorColors.pelvic, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (spo2.isNotEmpty()) Text("SpO2", color = SpO2Color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Movement", color = MonitorColors.load, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val t0 = epochs.first().startMs
            val t1 = epochs.last().startMs + 30_000L
            fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * size.width
            val mvH = size.height * 0.2f
            val plotH = size.height - mvH - 4f
            fun line(points: List<Pair<Long, Double>>, color: Color, lo: Double, hi: Double) {
                if (points.size < 2) return
                val span = (hi - lo).takeIf { it > 1e-6 } ?: 1.0
                val p = Path()
                points.forEachIndexed { i, (t, v) -> val y = plotH - ((v - lo) / span).toFloat() * plotH; if (i == 0) p.moveTo(x(t), y) else p.lineTo(x(t), y) }
                drawPath(p, color, style = Stroke(1.8f * density, cap = StrokeCap.Round))
            }
            val hr = epochs.mapNotNull { e -> e.hr?.let { e.startMs to it } }
            hr.takeIf { it.isNotEmpty() }?.let { line(it, MonitorColors.hr, it.minOf { p -> p.second } - 3, it.maxOf { p -> p.second } + 3) }
            val br = epochs.mapNotNull { e -> e.breathsPerMin?.let { e.startMs to it } }
            line(br, MonitorColors.pelvic, 6.0, 30.0)
            line(spo2.map { it.timestampMs to it.spo2.toDouble() }, SpO2Color, 80.0, 100.0)
            val maxMv = epochs.maxOf { it.movementMg }.coerceAtLeast(30.0)
            epochs.forEach { e ->
                val h = (e.movementMg / maxMv).toFloat() * mvH
                if (h > 0.5f) drawRect(MonitorColors.load.copy(alpha = 0.8f), Offset(x(e.startMs), size.height - h), Size((x(e.startMs + 30_000L) - x(e.startMs)).coerceAtLeast(1f), h))
            }
            hourTicks(t0, t1)
        }
    }
}

@Composable
private fun History(nights: List<NightSummary>, selected: Long?, onPick: (Long) -> Unit) {
    Panel("History", Modifier.fillMaxWidth()) {
        val last = nights.takeLast(30)
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = minOf(size.width / last.size, 36.dp.toPx())
            last.forEachIndexed { i, n ->
                val h = size.height * n.score / 100f
                drawRoundRect(if (n.startMs == selected) SleepColor else SleepColor.copy(alpha = 0.45f), Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(3f))
            }
        }
        nights.takeLast(10).reversed().forEach { n ->
            Row(Modifier.fillMaxWidth().clickable { onPick(n.startMs) }.padding(vertical = 3.dp)) {
                Text(at(n.startMs, day), color = if (n.startMs == selected) SleepColor else MonitorColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(hm(n.asleepMin), color = MonitorColors.label, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                Text("${n.score}", color = MonitorColors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Near-black full screen with a dim red clock and heart rate; keeps the screen on. Tap to close. */
@Composable
private fun NightView(onClose: () -> Unit) {
    val context = LocalContext.current
    val heart by MonitorHub.heart.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000L); now = System.currentTimeMillis() } }
    DisposableEffect(Unit) {
        val w = (context as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val old = w?.attributes?.screenBrightness
        w?.attributes = w?.attributes?.apply { screenBrightness = 0.01f }
        onDispose {
            w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w?.attributes = w?.attributes?.apply { screenBrightness = old ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            val dim = Color(0xFF7F1D1D)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(at(now, clock), color = dim, fontSize = 72.sp, fontFamily = FontFamily.Monospace)
                Text("♥ ${heart.heartRate ?: "--"}", color = dim.copy(alpha = 0.8f), fontSize = 28.sp, fontFamily = FontFamily.Monospace)
                Text("tap to close", color = dim.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}
