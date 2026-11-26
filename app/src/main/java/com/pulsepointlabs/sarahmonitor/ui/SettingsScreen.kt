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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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

/** The Settings pages: related things together, one page at a time. */
enum class SettingsPage(val label: String, val short: String, val blurb: String, val color: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Quick("Quick controls", "Quick", "The switches you reach for most", MonitorColors.text, androidx.compose.material.icons.Icons.Default.ToggleOn),
    Sarah("Sarah", "Sarah", "Personality, voice, hands-free, live feedback", Color(0xFFFF7AB6), androidx.compose.material.icons.Icons.Default.RecordVoiceOver),
    Overlays("Overlays", "Overlays", "Readings floating over other apps", MonitorColors.pelvic, androidx.compose.material.icons.Icons.Default.Layers),
    Sound("Sound & alerts", "Sound", "Heartbeat beep, alarms, which alerts", MonitorColors.warn, androidx.compose.material.icons.Icons.Default.NotificationsActive),
    Devices("Devices", "Devices", "H10, CIVET, Omron, EMG, Howl, glucose", MonitorColors.hr, androidx.compose.material.icons.Icons.Default.Bluetooth),
    Recording("Recording & keys", "Recording", "Dictation, OBS, API keys", MonitorColors.sdnn, androidx.compose.material.icons.Icons.Default.Videocam)
}

/**
 * App settings, one page per topic. Wide windows (DeX, phone landscape) get a side menu with the quick switches
 * under it and the page's sections packed into as many columns as fit, with no gaps; a phone in portrait gets a
 * two-row tab grid and one column. Overlays stay visible while this page is open, as a live preview.
 */
@Composable
fun SettingsScreen(onMessage: (String) -> Unit, modifier: Modifier = Modifier, extra: @Composable ColumnScope.() -> Unit = {}, onHealthConnect: () -> Unit = {}, onCsvImport: () -> Unit = {}) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        OverlaySettings.setPreviewing(true)
        MonitorService.start(context)
        onDispose { OverlaySettings.setPreviewing(false) }
    }
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsPage.Quick) }
    val sections: (SettingsPage) -> List<@Composable () -> Unit> = { p ->
        when (p) {
            SettingsPage.Quick -> listOf({ QuickControls() })
            SettingsPage.Sarah -> listOf(
                { com.pulsepointlabs.sarahmonitor.response.SarahPersonalitySection() },
                { HandsFreeSection() },
                { SarahVoiceSection() },
                { com.pulsepointlabs.sarahmonitor.response.CoachSettingsSection() },
                { StressVoiceSection() }
            )
            SettingsPage.Overlays -> listOf({ OverlaySettingsSection(look = false) }, { OverlayKindsSection() }, { OverlaySettingsSection(look = true) })
            SettingsPage.Sound -> listOf({ HeartbeatSoundSection() }, { AlertsSection() }, { AlertListSection() })
            SettingsPage.Devices -> deviceSections(onHealthConnect, onCsvImport, onMessage).map { it.content } +
                listOf<@Composable () -> Unit>({ com.pulsepointlabs.sarahmonitor.howl.HowlSettingsSection() })
            SettingsPage.Recording -> listOf({ VoiceSection(onMessage) }, { ObsSection() }, { ApiKeysSection(onMessage) }, { Column { extra() } })
        }
    }
    CompositionLocalProvider(LocalCollapsibleSections provides false) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
            val wide = maxWidth >= 700.dp
            if (wide) {
                // Side menu (quick switches underneath) + the page in columns.
                val shown = if (page == SettingsPage.Quick) SettingsPage.Sarah else page
                val railW = if (maxWidth >= 1100.dp) 280.dp else 230.dp
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(
                        Modifier.width(railW).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SettingsPage.entries.filter { it != SettingsPage.Quick }.forEach { p -> RailItem(p, p == shown) { page = p } }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                        QuickControls()
                    }
                    PageBody(shown, sections(shown), Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TabGrid(page) { page = it }
                    PageBody(page, sections(page), Modifier.fillMaxWidth().weight(1f), header = false)
                }
            }
        }
    }
}

@Composable
private fun RailItem(p: SettingsPage, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (selected) p.color.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) p.color.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(34.dp).background(p.color.copy(alpha = 0.18f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(p.icon, contentDescription = null, tint = p.color, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(p.label, color = if (selected) p.color else MonitorColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(p.blurb, color = MonitorColors.label, fontSize = 13.sp, maxLines = 2, lineHeight = 16.sp)
        }
    }
}

/** Phone portrait: every page visible at once in two even rows (nothing hidden off the side). */
@Composable
private fun TabGrid(page: SettingsPage, onPick: (SettingsPage) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsPage.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { p ->
                    val on = p == page
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(if (on) p.color.copy(alpha = 0.2f) else MonitorColors.panel, RoundedCornerShape(10.dp))
                            .border(1.dp, if (on) p.color else MonitorColors.panelBorder, RoundedCornerShape(10.dp))
                            .clickable { onPick(p) }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        androidx.compose.material3.Icon(p.icon, contentDescription = null, tint = p.color, modifier = Modifier.size(20.dp))
                        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                        Text(p.short, color = if (on) p.color else MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** One page: its title, then its sections packed into columns. */
@Composable
private fun PageBody(p: SettingsPage, sections: List<@Composable () -> Unit>, modifier: Modifier, header: Boolean = true) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val cols = (maxWidth / 340.dp).toInt().coerceIn(1, 3).coerceAtMost(sections.size.coerceAtLeast(1))
        androidx.compose.runtime.key(p) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (header) Column {
                    Text(p.label, color = p.color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(p.blurb, color = MonitorColors.label, fontSize = 14.sp)
                }
                Masonry(cols, Modifier.fillMaxWidth()) { sections.forEach { it() } }
            }
        }
    }
}

/**
 * Cards packed into [columns] like bricks: each goes under whichever column is shortest so far, so columns end
 * close together and there are no big black holes (cards keep their reading order left to right, top down).
 */
@Composable
fun Masonry(columns: Int, modifier: Modifier = Modifier, gap: androidx.compose.ui.unit.Dp = 12.dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val g = gap.roundToPx()
        val n = columns.coerceAtLeast(1)
        val colW = ((constraints.maxWidth - g * (n - 1)) / n).coerceAtLeast(0)
        val heights = IntArray(n)
        val placed = measurables.map { m ->
            val pl = m.measure(androidx.compose.ui.unit.Constraints(minWidth = colW, maxWidth = colW))
            val c = heights.indices.minBy { heights[it] }
            val y = heights[c]
            if (pl.height > 0) heights[c] += pl.height + g
            Triple(pl, c * (colW + g), y)
        }
        val h = ((heights.maxOrNull() ?: 0) - g).coerceAtLeast(0)
        layout(constraints.maxWidth, h) { placed.forEach { (pl, x, y) -> pl.place(x, y) } }
    }
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
        if (open) {
            if (subtitle != null) Text(subtitle, color = MonitorColors.label, fontSize = 13.sp, lineHeight = 17.sp)
            content()
        }
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

private val overlayEditing = mutableStateOf<OverlayProfile?>(null)

/** Two cards: which overlays show (per screen), and how they look. Both edit the same screen's set. */
@Composable
private fun OverlaySettingsSection(look: Boolean) {
    val context = LocalContext.current
    val current by OverlaySettings.profile.collectAsState()
    val states by OverlaySettings.states.collectAsState()
    var editing by overlayEditing
    val profile = editing ?: current
    val st = states[profile] ?: return
    val canDraw = OverlaySettings.canDraw(context)
    if (look) {
        SettingsSection("Look & feel", MonitorColors.pelvic, icon = androidx.compose.material.icons.Icons.Default.Tune, subtitle = "For the ${profile.label.lowercase()} overlays.") {
            OverlayLook(st, profile)
        }
        return
    }
    SettingsSection(
        "Floating overlays", MonitorColors.pelvic, icon = androidx.compose.material.icons.Icons.Default.Layers, subtitle =
        "Float over other apps: drag to move, drag to the circle to close, corner to resize. The phone and DeX each keep their own set, sizes and spots (portrait and landscape too). Long-press ＋ to show or hide the others. They hide while Sarah Monitor is in front, except on this page."
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
        SettingRow("Overlays on ${profile.label}") {
            SettingSwitch(st.on) {
                OverlaySettings.setOn(context, it, profile)
                MonitorService.start(context)
            }
        }
    }
}

/** Which readings float, for the screen picked on the Floating overlays card. */
@Composable
private fun OverlayKindsSection() {
    val context = LocalContext.current
    val current by OverlaySettings.profile.collectAsState()
    val states by OverlaySettings.states.collectAsState()
    val editing by overlayEditing
    val profile = editing ?: current
    val st = states[profile] ?: return
    SettingsSection("Which overlays", MonitorColors.pelvic, icon = androidx.compose.material.icons.Icons.Default.Checklist, subtitle = "On the ${profile.label.lowercase()}.") {
        OverlayKind.entries.forEach { kind ->
            SettingRow(kind.label) {
                SettingSwitch(kind in st.enabled) {
                    OverlaySettings.set(context, kind, it, profile)
                    MonitorService.start(context)
                }
            }
        }
    }
}

@Composable
private fun OverlayLook(st: com.pulsepointlabs.sarahmonitor.overlay.OverlayProfileState, profile: OverlayProfile) {
    val context = LocalContext.current
    run {
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
