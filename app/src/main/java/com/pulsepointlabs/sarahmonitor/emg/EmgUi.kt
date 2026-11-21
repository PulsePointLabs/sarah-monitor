package com.pulsepointlabs.sarahmonitor.emg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.ui.ChoiceChip
import com.pulsepointlabs.sarahmonitor.ui.ChoiceRow
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors

val EmgColor = Color(0xFFF472B6)
val Emg2Color = Color(0xFFC084FC)

/** Live EMG trace (last 20 s): % of hold once calibrated, otherwise raw ADC as % of full scale. */
@Composable
fun EmgTrace(snap: EmgSnapshot, modifier: Modifier) {
    Canvas(modifier) {
        val pts = snap.trace
        if (pts.size < 2) return@Canvas
        val calibrated = snap.channels.any { it.calibrated }
        val top = if (calibrated) maxOf(120.0, pts.maxOf { it.second.maxOrNull() ?: 0.0 }) else 100.0
        listOf(50.0, 100.0).filter { it < top }.forEach { lvl ->
            val y = size.height * (1 - (lvl / top).toFloat())
            drawLine(MonitorColors.trendGrid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        val t1 = pts.last().first
        val t0 = t1 - 20_000L
        for (ch in 0 until (pts.last().second.size)) {
            val p = Path()
            var started = false
            pts.forEach { (t, v) ->
                val x = (t - t0).toFloat() / 20_000f * size.width
                val y = size.height * (1 - (v.getOrElse(ch) { 0.0 } / top).toFloat().coerceIn(0f, 1f))
                if (!started) { p.moveTo(x, y); started = true } else p.lineTo(x, y)
            }
            drawPath(p, if (ch == 0) EmgColor else Emg2Color, style = Stroke(2f * density, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun ChannelMeter(c: EmgChannel, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.name, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(c.pct?.let { "${it.toInt()}%" } ?: "—", color = MonitorColors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            drawRoundRect(MonitorColors.panelBorder, size = size, cornerRadius = CornerRadius(6f))
            val frac = c.pct?.let { (it / 120).toFloat() } ?: ((c.smooth ?: 0.0) / 1023).toFloat()
            drawRoundRect(color, size = Size(size.width * frac.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(6f))
            c.rest?.let { r -> if (c.pct == null) drawLine(MonitorColors.text, Offset(size.width * (r / 1023).toFloat(), 0f), Offset(size.width * (r / 1023).toFloat(), size.height), 2f) }
        }
        Text(
            "raw ${c.raw ?: "--"} (2 s ${c.rawMin ?: "--"}–${c.rawMax ?: "--"})" +
                (c.rest?.let { " · rest %.0f".format(it) } ?: "") + (c.hold?.let { " · hold %.0f".format(it) } ?: ""),
            color = MonitorColors.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace
        )
        if (c.nearLimit) Text("Near ADC limit", color = MonitorColors.warn, fontSize = 12.sp)
    }
}

/** Sensors page: connection, names, guided calibration and raw diagnostics. */
@Composable
fun EmgSection() {
    val context = LocalContext.current
    val link by MonitorHub.emgClient.state.collectAsState()
    val detail by MonitorHub.emgClient.detail.collectAsState()
    val snap by MonitorHub.emg.collectAsState()
    val names by EmgSettings.names.collectAsState()
    var copied by remember { mutableStateOf(false) }
    com.pulsepointlabs.sarahmonitor.ui.SettingsSection(
        "EMG (MyoWare via Arduino USB)", EmgColor,
        "Plug the Arduino running Sarah's sketch into the phone's USB-C (OTG adapter) or the DeX dock — no PC or Python needed. MyoWare ENV outputs go to A0 (and A1). ENV is an envelope, not raw EMG."
    ) {
        Text(link.label + (detail?.let { " · $it" } ?: "") + if (snap.fresh) " · %.0f Hz".format(snap.sampleHz) else "", color = if (snap.fresh) MonitorColors.good else MonitorColors.warn, fontSize = 13.sp)
        ChoiceRow {
            ChoiceChip(if (link == EmgLink.Streaming) "Reconnect" else "Connect", false, EmgColor) { MonitorHub.emgClient.disconnect(); MonitorHub.emgClient.connect() }
            ChoiceChip(if (copied) "✓ Sketch copied" else "Copy Arduino sketch", false, EmgColor) {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Sarah EMG sketch", EmgClient.SKETCH))
                copied = true
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { i, n ->
                var draft by remember(n) { mutableStateOf(n) }
                OutlinedTextField(
                    draft, { draft = it; EmgSettings.setName(i, it.take(24), MonitorHub.emgProcessor) },
                    label = { Text("A$i name") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
        }
        snap.channels.forEachIndexed { i, c -> ChannelMeter(c, if (i == 0) EmgColor else Emg2Color) }
        if (snap.channels.isNotEmpty()) EmgTrace(snap, Modifier.fillMaxWidth().height(110.dp))
        snap.capture?.let { cap ->
            Text("${cap.phase}: ${cap.message} · ${"%.0f".format(kotlin.math.ceil(cap.remainingS))} s", color = MonitorColors.warn, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        snap.message?.let { Text(it, color = MonitorColors.label, fontSize = 13.sp) }
        ChoiceRow {
            val now = { System.currentTimeMillis() }
            ChoiceChip("1 · Rest", false, EmgColor) { MonitorHub.emgProcessor.startCapture(EmgStep.Rest, now()) }
            ChoiceChip("2 · Hold ${names[0]}", false, EmgColor) { MonitorHub.emgProcessor.startCapture(EmgStep.Hold1, now()) }
            if (snap.channels.size > 1) ChoiceChip("3 · Hold ${names[1]}", false, Emg2Color) { MonitorHub.emgProcessor.startCapture(EmgStep.Hold2, now()) }
            if (snap.capture != null) ChoiceChip("Cancel", false, MonitorColors.alarm) { MonitorHub.emgProcessor.cancelCapture() }
        }
    }
}

/** Dashboard card and Response panel content. */
@Composable
fun EmgCardContent(compactNote: Boolean = false) {
    val snap by MonitorHub.emg.collectAsState()
    val link by MonitorHub.emgClient.state.collectAsState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (snap.channels.isEmpty() || !snap.fresh) {
            Text(
                if (link == EmgLink.NoDevice) "No EMG signal" else "${link.label}…",
                color = MonitorColors.label, fontSize = 13.sp
            )
            return@Column
        }
        snap.channels.forEachIndexed { i, c -> ChannelMeter(c, if (i == 0) EmgColor else Emg2Color) }
        EmgTrace(snap, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
fun EmgCard() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        com.pulsepointlabs.sarahmonitor.ui.CardTitle("EMG", EmgColor, "% hold")
        Column(Modifier.weight(1f)) { EmgCardContent(compactNote = true) }
    }
}

