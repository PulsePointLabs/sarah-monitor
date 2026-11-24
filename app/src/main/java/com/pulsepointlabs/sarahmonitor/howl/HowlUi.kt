package com.pulsepointlabs.sarahmonitor.howl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pulsepointlabs.sarahmonitor.ui.LocalCardScale
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import kotlinx.coroutines.launch

val HowlA = Color(0xFFFF7AB6)
val HowlB = Color(0xFF7DD3FC)
val HowlColor = Color(0xFFE879F9)

/** Low power reads green, the middle amber, near your ceiling red. */
fun howlLevelColor(power: Int, allowed: Int): Color {
    val f = if (allowed <= 0) 0f else power.toFloat() / allowed
    return when {
        power == 0 -> MonitorColors.label
        f < 0.4f -> MonitorColors.good
        f < 0.75f -> MonitorColors.warn
        else -> MonitorColors.alarm
    }
}

/** Top-bar / focus-mode button: only exists while Howl is connected. Shows both channels; tap for the controls. */
@Composable
fun HowlButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val s by Howl.state.collectAsState()
    if (!s.connected) return
    Row(
        modifier.height(38.dp).background(HowlColor.copy(alpha = 0.16f), RoundedCornerShape(10.dp)).border(1.dp, HowlColor.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("⚡", fontSize = 16.sp)
        Text("${s.powerA}", color = HowlA, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text("·", color = MonitorColors.label, fontSize = 16.sp)
        Text("${s.powerB}", color = HowlB, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        if (s.muted) Text("muted", color = MonitorColors.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** The full controls as a big panel over everything (phone or DeX). */
@Composable
fun HowlSheet(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(10.dp).background(MonitorColors.background, RoundedCornerShape(16.dp))
                .border(1.dp, HowlColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Howl", color = HowlColor, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Big("Close", MonitorColors.text, filled = false, modifier = Modifier.width(110.dp), onClick = onDismiss)
            }
            HowlPanel(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** A big, plain button: never small, never truncated (labels are short). */
@Composable
private fun Big(label: String, color: Color, filled: Boolean, modifier: Modifier = Modifier, height: Int = 56, size: TextUnit = 20.sp, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = height.dp).background(if (filled) color else color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.5.dp, color.copy(alpha = 0.85f), RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (filled) MonitorColors.background else color, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}

/** Everything: status, both channels with big −/+, both together, live trace, activities, play/pause, mute, STOP. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HowlPanel(modifier: Modifier = Modifier) {
    val s by Howl.state.collectAsState()
    val ceiling by Howl.ceiling.collectAsState()
    val step by Howl.stepUp.collectAsState()
    val status: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).background(if (s.connected) (if (s.playing) MonitorColors.good else MonitorColors.warn) else MonitorColors.alarm, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(if (!s.connected) "Not connected" else s.activity?.label ?: s.title ?: "No activity loaded", color = MonitorColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    if (!s.connected) s.error ?: "Turn Howl on in Settings" else listOfNotNull(if (s.playing) "Playing" else "Paused", if (s.muted) "Muted" else null, "ceiling $ceiling · +$step per tap").joinToString(" · "),
                    color = if (s.muted) MonitorColors.warn else MonitorColors.label, fontSize = 14.sp, maxLines = 2
                )
            }
        }
    }
    val channels: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelPanel("A", s.powerA, Howl.allowed(s, HowlChannel.A), HowlA, Modifier.weight(1f).fillMaxHeight()) { Howl.step(HowlChannel.A, it) }
                ChannelPanel("B", s.powerB, Howl.allowed(s, HowlChannel.B), HowlB, Modifier.weight(1f).fillMaxHeight()) { Howl.step(HowlChannel.B, it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Big("− Both", MonitorColors.text, false, Modifier.weight(1f)) { Howl.step(HowlChannel.Both, -1) }
                Big("+ Both", HowlColor, false, Modifier.weight(1f)) { Howl.step(HowlChannel.Both, 1) }
            }
        }
    }
    val transport: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Big(if (s.playing) "Pause" else "Play", MonitorColors.good, false, Modifier.weight(1f)) { if (s.playing) Howl.pause() else Howl.play() }
            Big(if (s.muted) "Unmute" else "Mute", MonitorColors.warn, false, Modifier.weight(1f)) { Howl.setMuted(!s.muted) }
            Big("STOP", MonitorColors.alarm, true, Modifier.weight(1.3f)) { Howl.stopAll() }
        }
    }
    val activities: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Activity", color = MonitorColors.label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HowlActivity.entries.filter { !it.name.startsWith("CALIBRATION") }.forEach { a ->
                    val on = s.activity == a
                    Text(
                        a.label, color = if (on) MonitorColors.background else HowlColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.heightIn(min = 44.dp).background(if (on) HowlColor else HowlColor.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                            .border(1.dp, HowlColor.copy(alpha = 0.6f), RoundedCornerShape(22.dp)).clickable { Howl.loadActivity(a) }.padding(horizontal = 14.dp, vertical = 11.dp)
                    )
                }
            }
        }
    }
    BoxWithConstraints(modifier) {
        if (maxWidth >= 760.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    status(); channels(Modifier.fillMaxWidth().weight(1f)); transport()
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // The trace takes whatever height the activities leave.
                    PowerTrace(Modifier.fillMaxWidth().weight(1f)); activities()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                status(); channels(Modifier.fillMaxWidth().height(300.dp)); transport()
                PowerTrace(Modifier.fillMaxWidth().height(120.dp)); activities()
            }
        }
    }
}

/** One channel: big number coloured by level, a bar against what's allowed, big − and +. */
@Composable
private fun ChannelPanel(name: String, power: Int, allowed: Int, color: Color, modifier: Modifier, onStep: (Int) -> Unit) {
    val level = howlLevelColor(power, allowed)
    Column(
        modifier.background(color.copy(alpha = 0.07f), RoundedCornerShape(14.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Channel $name", color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("of $allowed", color = MonitorColors.label, fontSize = 14.sp)
        }
        // The number grows with its panel (big on DeX, still big on a phone), centred in the space.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val size = minOf(maxHeight.value * 0.62f, maxWidth.value * 0.42f).coerceIn(40f, 150f)
            Text("$power", color = level, fontSize = size.sp, lineHeight = size.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).background(MonitorColors.trendGrid, RoundedCornerShape(5.dp))) {
            Box(Modifier.fillMaxWidth((power.toFloat() / allowed.coerceAtLeast(1)).coerceIn(0f, 1f)).height(10.dp).background(level, RoundedCornerShape(5.dp)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Big("−", MonitorColors.text, false, Modifier.weight(1f), height = 64, size = 30.sp) { onStep(-1) }
            Big("+", color, true, Modifier.weight(1f), height = 64, size = 30.sp) { onStep(1) }
        }
    }
}

/** Both channels over the last two minutes. */
@Composable
fun PowerTrace(modifier: Modifier) {
    val h by Howl.history.collectAsState()
    val ceiling by Howl.ceiling.collectAsState()
    Column(modifier.background(MonitorColors.panel, RoundedCornerShape(12.dp)).border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Row {
            Text("Last 2 min", color = MonitorColors.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("A", color = HowlA, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("  B", color = HowlB, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Canvas(Modifier.fillMaxSize().padding(top = 4.dp)) {
            if (h.size < 2) return@Canvas
            val end = h.last().first; val start = end - 120_000L
            val top = maxOf(ceiling, h.maxOf { maxOf(it.second, it.third) }, 10).toFloat()
            fun x(t: Long) = (t - start).toFloat() / 120_000f * size.width
            fun y(v: Int) = size.height - v / top * size.height
            drawLine(MonitorColors.trendGrid, Offset(0f, y(ceiling)), Offset(size.width, y(ceiling)), 1.5f)
            listOf(HowlA to { p: Triple<Long, Int, Int> -> p.second }, HowlB to { p: Triple<Long, Int, Int> -> p.third }).forEach { (c, v) ->
                val path = Path(); var first = true
                h.filter { it.first >= start }.forEach { p -> if (first) { path.moveTo(x(p.first), y(v(p))); first = false } else path.lineTo(x(p.first), y(v(p))) }
                drawPath(path, c, style = Stroke(3f, cap = StrokeCap.Round))
            }
        }
    }
}

/** The dashboard card: both channels, −/+ and Stop, what's playing. Opens the full controls on tap of its title. */
@Composable
fun HowlCard(onOpen: () -> Unit) {
    val s by Howl.state.collectAsState()
    val sc = LocalCardScale.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sc.dp(6f))) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Text("Howl", color = HowlColor, fontSize = sc.sp(16f), fontWeight = FontWeight.Bold)
            Text("  " + (s.activity?.label ?: if (s.connected) "No activity" else "Not connected"), color = MonitorColors.label, fontSize = sc.sp(13f), maxLines = 1, modifier = Modifier.weight(1f))
            Text(if (s.playing) "▶" else "❚❚", color = if (s.playing) MonitorColors.good else MonitorColors.label, fontSize = sc.sp(15f))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(sc.dp(6f))) {
            listOf(Triple("A", s.powerA, HowlChannel.A), Triple("B", s.powerB, HowlChannel.B)).forEach { (n, p, ch) ->
                val allowed = Howl.allowed(s, ch)
                val c = if (ch == HowlChannel.A) HowlA else HowlB
                Column(Modifier.weight(1f).fillMaxHeight().background(c.copy(alpha = 0.07f), RoundedCornerShape(10.dp)).padding(sc.dp(6f)), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n, color = c, fontSize = sc.sp(14f), fontWeight = FontWeight.Bold)
                    Text("$p", color = howlLevelColor(p, allowed), fontSize = sc.sp(38f), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(sc.dp(4f))) {
                        Big("−", MonitorColors.text, false, Modifier.weight(1f), height = 40, size = sc.sp(22f)) { Howl.step(ch, -1) }
                        Big("+", c, true, Modifier.weight(1f), height = 40, size = sc.sp(22f)) { Howl.step(ch, 1) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(sc.dp(6f))) {
            Big("Controls", HowlColor, false, Modifier.weight(1f), height = 40, size = sc.sp(16f), onClick = onOpen)
            Big("STOP", MonitorColors.alarm, true, Modifier.weight(1f), height = 40, size = sc.sp(16f)) { Howl.stopAll() }
        }
    }
}

/** Settings: turn Howl on, where it is, the key, a test, and the safety limits. */
@Composable
fun HowlSettingsSection() {
    val enabled by Howl.enabled.collectAsState()
    val url by Howl.url.collectAsState()
    val key by Howl.key.collectAsState()
    val ceiling by Howl.ceiling.collectAsState()
    val step by Howl.stepUp.collectAsState()
    val s by Howl.state.collectAsState()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    com.pulsepointlabs.sarahmonitor.ui.SettingsSection(
        "Howl e-stim", HowlColor,
        subtitle = if (s.connected) "Connected · ${s.describe()}" else "In Howl: Settings → Allow remote access. Same Wi-Fi, or this phone (127.0.0.1)."
    ) {
        com.pulsepointlabs.sarahmonitor.ui.SettingRow("Use Howl", "Controls and recording appear only while it's connected") { com.pulsepointlabs.sarahmonitor.ui.SettingSwitch(enabled) { Howl.setEnabled(it) } }
        OutlinedTextField(url, { Howl.setUrl(it) }, label = { Text("Howl address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { Howl.setKey(it) }, label = { Text("Remote access key (if Howl shows one)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Big(if (testing) "Testing…" else "Test connection", HowlColor, false, Modifier.width(200.dp), height = 46, size = 16.sp) {
                testing = true; scope.launch { Howl.testNow(); testing = false }
            }
            Text(if (s.connected) "✓ Connected" else s.error ?: "", color = if (s.connected) MonitorColors.good else MonitorColors.warn, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
        com.pulsepointlabs.sarahmonitor.ui.SettingSlider("Highest power Sarah Monitor will set", "$ceiling of ${Howl.MAX_POWER}", ceiling.toFloat(), 0f..Howl.MAX_POWER.toFloat()) { Howl.setCeiling(Math.round(it)) }
        com.pulsepointlabs.sarahmonitor.ui.SettingSlider("Power added per + tap", "+$step (− lowers twice as fast)", step.toFloat(), 1f..10f, steps = 8) { Howl.setStepUp(Math.round(it)) }
    }
}

/** Whether the full Howl controls are open (any screen can open them). */
object HowlSheetState { val open = kotlinx.coroutines.flow.MutableStateFlow(false) }

