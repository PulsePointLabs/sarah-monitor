package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.core.MonitorService
import com.pulsepointlabs.sarahmonitor.overlay.OverlayKind
import com.pulsepointlabs.sarahmonitor.overlay.OverlayProfile
import com.pulsepointlabs.sarahmonitor.overlay.OverlaySettings

/** App settings: overlays, sound and voice. Overlays stay visible while this page is open, as a live preview. */
@Composable
fun SettingsScreen(onMessage: (String) -> Unit, modifier: Modifier = Modifier, extra: @Composable ColumnScope.() -> Unit = {}) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        OverlaySettings.setPreviewing(true)
        MonitorService.start(context)
        onDispose { OverlaySettings.setPreviewing(false) }
    }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OverlaySettingsSection()
            HeartbeatSoundSection()
            VoiceSection(onMessage)
            AlertsSection()
            extra()
        }
    }
}

@Composable
fun SettingsSection(title: String, color: Color, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (subtitle != null) Text(subtitle, color = MonitorColors.label, fontSize = 13.sp)
        content()
    }
}

@Composable
fun SettingRow(label: String, detail: String? = null, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MonitorColors.text, fontSize = 15.sp)
            if (detail != null) Text(detail, color = MonitorColors.label, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
fun SettingSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MonitorColors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = MonitorColors.pelvic, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = MonitorColors.pelvic, activeTrackColor = MonitorColors.pelvic)
        )
    }
}

@Composable
fun SettingSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = MonitorColors.pelvic))
}

@Composable
private fun OverlaySettingsSection() {
    val context = LocalContext.current
    val current by OverlaySettings.profile.collectAsState()
    val states by OverlaySettings.states.collectAsState()
    var editing by remember { mutableStateOf<OverlayProfile?>(null) }
    val profile = editing ?: current
    val st = states[profile] ?: return
    val canDraw = OverlaySettings.canDraw(context)
    SettingsSection(
        "Floating overlays", MonitorColors.pelvic,
        "Float over other apps; drag to move, corner grip to resize. The phone and DeX each keep their own overlays, sizes and positions. They hide while Sarah Monitor is the active window — except on this page, so you can see changes live."
    ) {
        if (!canDraw) {
            TextButton(onClick = { context.startActivity(OverlaySettings.permissionIntent(context)) }) {
                Text("Allow \"display over other apps\"", color = MonitorColors.warn)
            }
        }
        ChoiceRow {
            OverlayProfile.entries.forEach { p ->
                ChoiceChip(p.label + if (p == current) " (this screen)" else "", p == profile) { editing = p }
            }
        }
        if (profile != current) {
            Text("Editing the ${profile.label} setup — it shows when Sarah Monitor is on the ${profile.label} screen.", color = MonitorColors.label, fontSize = 12.sp)
        }
        SettingRow("Overlays on ${profile.label}", "Quick switch — also on the top bar, the notification and a Quick Settings tile") {
            SettingSwitch(st.on) {
                OverlaySettings.setOn(context, it, profile)
                MonitorService.start(context)
            }
        }
        OverlayKind.entries.forEach { kind ->
            SettingRow(kind.label) {
                SettingSwitch(kind in st.enabled) {
                    OverlaySettings.set(context, kind, it, profile)
                    MonitorService.start(context)
                }
            }
        }
        SettingSlider("Opacity (all overlays)", "${(st.opacity * 100).toInt()}%", st.opacity, OverlaySettings.MIN_OPACITY..1f) {
            OverlaySettings.setOpacity(context, it, profile)
        }
        SettingRow(
            "Fade readings too",
            if (st.fadeReadings) "Opacity fades the whole overlay" else "Only the background fades; numbers stay solid"
        ) { SettingSwitch(st.fadeReadings) { OverlaySettings.setFadeReadings(context, it, profile) } }
        SettingSlider("＋ button size", "${st.plusSizeDp} dp", st.plusSizeDp.toFloat(), OverlaySettings.MIN_PLUS_DP.toFloat()..OverlaySettings.MAX_PLUS_DP.toFloat()) {
            OverlaySettings.setPlusSize(context, it.toInt(), profile)
        }
        // In-page preview of the ＋ at its real size (the live one updates too while this page is open).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .size(st.plusSizeDp.dp)
                    .background(MonitorColors.warn.copy(alpha = st.opacity), CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = MonitorColors.background, fontSize = (st.plusSizeDp * 0.62f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
