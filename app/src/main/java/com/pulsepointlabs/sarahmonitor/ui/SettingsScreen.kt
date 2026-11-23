package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.material.icons.filled.*
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
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
    CompositionLocalProvider(LocalCollapsibleSections provides true) { AdaptiveColumns(
        listOf(
            PageSection(6) { QuickControls() },
            PageSection(10) { OverlaySettingsSection() },
            PageSection(10) { HeartbeatSoundSection() },
            PageSection(5) { SarahVoiceSection() },
            PageSection(8) { AlertsSection() },
            PageSection(14) { AlertListSection() },
            PageSection(6) { StressVoiceSection() },
            PageSection(7) { com.pulsepointlabs.sarahmonitor.response.CoachSettingsSection() },
            PageSection(5) { VoiceSection(onMessage) },
            PageSection(6) { ObsSection() },
            PageSection(6) { ApiKeysSection(onMessage) },
            PageSection(3) { Column { extra() } }
        ),
        modifier
    ) }
}

/** On the Settings page, sections fold into a tappable header (closed by default) so the page stays tidy. */
val LocalCollapsibleSections = androidx.compose.runtime.compositionLocalOf { false }

@Composable
fun SettingsSection(
    title: String, color: Color, subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val collapsible = LocalCollapsibleSections.current
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(!collapsible) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = if (collapsible && !open) 4.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().then(if (collapsible) Modifier.heightIn(min = 44.dp).clickable { open = !open } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (icon != null) {
                Box(Modifier.size(30.dp).background(color.copy(alpha = 0.16f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                }
            }
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (collapsible) androidx.compose.material3.Icon(
                if (open) androidx.compose.material.icons.Icons.Default.ExpandLess else androidx.compose.material.icons.Icons.Default.ExpandMore,
                contentDescription = if (open) "Collapse" else "Expand", tint = MonitorColors.label
            )
        }
        if (open) content()
    }
}

/** The switches you reach for most, always open at the top of Settings. */
@Composable
private fun QuickControls() {
    val context = LocalContext.current
    val overlaysOn by OverlaySettings.on.collectAsState()
    val profile by OverlaySettings.profile.collectAsState()
    val beep by com.pulsepointlabs.sarahmonitor.audio.AudioSettings.beepEnabled.collectAsState()
    val alerts by com.pulsepointlabs.sarahmonitor.audio.AudioSettings.alertsEnabled.collectAsState()
    val announce by com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.announce.collectAsState()
    val relax by com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.relax.collectAsState()
    val coach by com.pulsepointlabs.sarahmonitor.response.ResponseSettings.coachOn.collectAsState()
    val handsFree by com.pulsepointlabs.sarahmonitor.session.HandsFreeSettings.enabled.collectAsState()
    val I = androidx.compose.material.icons.Icons.Default
    CompositionLocalProvider(LocalCollapsibleSections provides false) {
        SettingsSection("Quick controls", MonitorColors.text, icon = I.ToggleOn) {
            QuickRow(I.Layers, MonitorColors.pelvic, "Overlays", "On ${profile.label.lowercase()}", overlaysOn) {
                OverlaySettings.toggle(context); MonitorService.start(context)
            }
            QuickRow(I.MonitorHeart, MonitorColors.hr, "Heartbeat beep", null, beep) { com.pulsepointlabs.sarahmonitor.audio.AudioSettings.setBeepEnabled(it) }
            QuickRow(I.NotificationsActive, MonitorColors.warn, "Alerts", null, alerts) { com.pulsepointlabs.sarahmonitor.audio.AudioSettings.setAlertsEnabled(it) }
            QuickRow(I.Campaign, StressColor, "Stress announcements", null, announce) { com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.setAnnounce(it) }
            QuickRow(I.SelfImprovement, StressColor, "Relaxation coaching", null, relax) { com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.setRelax(it) }
            QuickRow(I.RecordVoiceOver, Color(0xFFFF7AB6), "Sarah live", "Sexual sessions", coach) { com.pulsepointlabs.sarahmonitor.response.ResponseSettings.setCoachOn(it) }
            QuickRow(I.Hearing, MonitorColors.sdnn, "Hands-free Sarah", "Say \"Sarah, …\" to log an event", handsFree) { com.pulsepointlabs.sarahmonitor.session.HandsFreeSettings.setEnabled(it) }
        }
    }
}

@Composable
private fun QuickRow(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, label: String, detail: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = if (on) color else MonitorColors.dim, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (detail != null) Text(detail, color = MonitorColors.label, fontSize = 12.sp)
        }
        SettingSwitch(on, onChange)
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
        "Floating overlays", MonitorColors.pelvic, icon = androidx.compose.material.icons.Icons.Default.Layers, subtitle =
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
        }
        SettingRow("Overlays on ${profile.label}") {
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
