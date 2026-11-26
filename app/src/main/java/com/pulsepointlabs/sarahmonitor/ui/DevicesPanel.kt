package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.pulsepointlabs.sarahmonitor.ble.BleDevice
import com.pulsepointlabs.sarahmonitor.ble.ConnectionState
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.session.VoiceSettings
import androidx.compose.foundation.clickable

/**
 * Sensor setup as a full screen (a tab next to Monitor and Sessions), so every sensor — including the BP
 * cuff — is easy to find on a phone. Buttons wrap onto extra lines instead of squashing.
 */
@Composable
fun SensorsScreen(
    onHealthConnectImport: () -> Unit,
    onCsvImport: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AdaptiveColumns(deviceSections(onHealthConnectImport, onCsvImport, onMessage), modifier)
}

/** Every device's section (shown in Settings as dropdowns). */
fun deviceSections(onHealthConnectImport: () -> Unit, onCsvImport: () -> Unit, onMessage: (String) -> Unit): List<PageSection> =
        listOf(
            PageSection(7) { H10Section() },
            PageSection(10) { CivetSection(onMessage) },
            PageSection(6) { OmronSection() },
            PageSection(10) { com.pulsepointlabs.sarahmonitor.emg.EmgSection() },
            PageSection(3) {
                Section("Glucose", MonitorColors.glucose, "OneTouch Verio Reflect: in OneTouch Reveal, share or export your results and pick Sarah Monitor. Health Connect and CSV files work too, and you can add a reading by hand on the Glucose card.") {
                    Buttons {
                        PanelButton("Import CSV", onCsvImport)
                        PanelButton("Health Connect", onHealthConnectImport)
                    }
                }
            }
        )

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}

/** A device section: the same tappable dropdown as every other Settings section. */
@Composable
private fun Section(title: String, color: Color, subtitle: String? = null, content: @Composable () -> Unit) {
    SettingsSection(title, color, subtitle) { content() }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.heightIn(min = 40.dp)
    ) { Text(label, fontSize = 14.sp, maxLines = 1, softWrap = false) }
}

@Composable
private fun StateText(state: ConnectionState, remembered: String?, battery: Int? = null) {
    val (text, color) = when (state) {
        ConnectionState.Connected, ConnectionState.Receiving -> "Connected" to MonitorColors.good
        ConnectionState.Connecting, ConnectionState.Reconnecting -> "Connecting…" to MonitorColors.warn
        ConnectionState.Scanning -> "Scanning…" to MonitorColors.warn
        ConnectionState.WaitingForCuff -> "Ready — waiting for the cuff" to MonitorColors.good
        ConnectionState.Stale -> "Connected, no data" to MonitorColors.warn
        ConnectionState.NotConfigured -> "Not set up" to MonitorColors.dim
        ConnectionState.Error -> "Error — check Bluetooth permission" to MonitorColors.alarm
        else -> "Disconnected" to MonitorColors.alarm
    }
    Text(
        listOfNotNull(text, remembered?.let { "Remembered: $it" }, battery?.let { "Battery $it%" }).joinToString(" · "),
        color = color,
        fontSize = 13.sp
    )
}

@Composable
private fun DeviceList(devices: List<BleDevice>, onPick: (BleDevice) -> Unit) {
    devices.forEach { d ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${d.name}  ·  ${d.address}", color = MonitorColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { onPick(d) }) { Text("Connect") }
        }
    }
}

@Composable
private fun H10Section() {
    val client = MonitorHub.h10
    val state by client.state.collectAsState()
    val devices by client.devices.collectAsState()
    val battery by client.batteryPercent.collectAsState()
    Section("Polar H10", MonitorColors.hr, "HR, RR, ECG and motion. Remembered and reconnected automatically.") {
        StateText(state, client.rememberedName(), battery)
        Buttons {
            PanelButton("Scan") { client.scan() }
            if (client.rememberedAddress() != null) PanelButton("Reconnect") { client.connectRemembered() }
            PanelButton("Disconnect") { client.disconnect() }
            PanelButton("Forget") { client.disconnect(); client.forget() }
        }
        DeviceList(devices) { client.connect(it.address) }
    }
}

@Composable
private fun CivetSection(onMessage: (String) -> Unit) {
    val client = MonitorHub.civet
    val state by client.state.collectAsState()
    val devices by client.devices.collectAsState()
    val battery by client.batteryPercent.collectAsState()
    val error by client.error.collectAsState()
    val scanStatus by client.scanStatus.collectAsState()
    val nearby by client.allDevices.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    var confirmZero by remember { mutableStateOf(false) }
    val remembered = client.rememberedAddress() != null
    Section(
        "Civet edging sensor (pelvic pressure)", MonitorColors.pelvic,
        "To pair: make sure no other app (Sarah, DG-LAB) is connected to it, press its power button 5 times until the Bluetooth icon is yellow, then Scan."
    ) {
        StateText(state, client.rememberedName(), battery)
        error?.takeIf { remembered }?.let { Text(it, color = MonitorColors.warn, fontSize = 13.sp) }
        Buttons {
            PanelButton("Scan") { client.scan() }
            if (remembered) PanelButton("Reconnect") { client.connectRemembered() }
            if (remembered) PanelButton("Disconnect") { client.disconnect() }
            if (remembered) PanelButton("Forget") { client.forget() }
            if (state == ConnectionState.Connected) PanelButton("Flip display") { if (!client.flipDisplay()) onMessage("Connect the sensor first.") }
        }
        scanStatus?.let { Text(it, color = if (devices.isEmpty()) MonitorColors.warn else MonitorColors.good, fontSize = 13.sp) }
        DeviceList(devices) { client.connect(it) }
        if (nearby.isNotEmpty()) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Hide other nearby devices" else "Show all nearby devices (${nearby.size})", fontSize = 13.sp)
            }
            if (showAll) {
                nearby.take(25).forEach { d ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name ?: "Unnamed device", color = if (d.likelyCivet) MonitorColors.pelvic else MonitorColors.text, fontSize = 14.sp, maxLines = 1)
                            Text("${d.address} · signal ${d.rssi} dBm", color = MonitorColors.label, fontSize = 12.sp)
                        }
                        TextButton(onClick = { client.connect(d) }) { Text("Connect") }
                    }
                }
            }
        }
        if (state == ConnectionState.Connected) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = confirmZero,
                onCheckedChange = { confirmZero = it },
                colors = CheckboxDefaults.colors(checkedColor = MonitorColors.pelvic)
            )
            Text("Sensor is deflated and outside the body", color = MonitorColors.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(enabled = confirmZero, onClick = {
                client.hardwareZero { ok, message ->
                    if (ok) MonitorHub.noteSensorEvent("civet", "hardware_zero", "hardware zero sent (sensor deflated, outside the body)")
                    onMessage(if (ok) "Hardware zero sent. Capture a new rest." else message ?: "Zero failed")
                }
                confirmZero = false
            }) { Text("Hardware zero") }
        }
    }
}

@Composable
private fun OmronSection() {
    val client = MonitorHub.omron
    val state by client.state.collectAsState()
    val devices by client.devices.collectAsState()
    Section("Omron BP cuff", MonitorColors.bp, "To pair: tap Pair / scan, then on the cuff hold the Bluetooth (transfer) button until a flashing \"P\" appears, and pick it below. After that, each reading appears on the dashboard as soon as the cuff finishes.") {
        StateText(state, client.rememberedName())
        Buttons {
            PanelButton("Pair / scan") { client.scanAndArm() }
            if (client.rememberedAddress() != null) PanelButton("Listen") { client.armRemembered() }
            PanelButton("Stop") { client.disarm() }
            PanelButton("Forget") { client.disarm(); client.forget() }
        }
        DeviceList(devices) { client.connect(it.address) }
    }
}


@Composable
fun VoiceSection(onMessage: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var engine by remember { mutableStateOf(VoiceSettings.engine(context)) }
    SettingsSection("Dictation (speech to text)", MonitorColors.text, icon = androidx.compose.material.icons.Icons.Default.Mic) {
        SettingGroup {
            Text("For typing event notes by voice. OpenAI live types as you talk; Whisper types after you stop; Android works offline.", color = MonitorColors.label, fontSize = 13.sp)
            ChoiceRow { VoiceSettings.Engine.entries.forEach { e -> ChoiceChip(e.label, e == engine) { engine = e; VoiceSettings.setEngine(context, e) } } }
        }
        var autoStop by remember { mutableStateOf(VoiceSettings.autoStopSeconds(context)) }
        var headset by remember { mutableStateOf(VoiceSettings.preferHeadsetMic(context)) }
        SettingSlider("Stop after quiet", if (autoStop < 1f) "Off" else "%.1f s".format(autoStop), autoStop, 0f..10f) {
            autoStop = if (it < 1f) 0f else (it * 2).toInt() / 2f
            VoiceSettings.setAutoStopSeconds(context, autoStop)
        }
        SettingRow("Dictate through the headset mic", "Switches the headset to call-quality audio while you dictate") { SettingSwitch(headset) { headset = it; VoiceSettings.setPreferHeadsetMic(context, it) } }
        var tidy by remember { mutableStateOf(VoiceSettings.tidyWithClaude(context)) }
        SettingRow("Tidy punctuation with Claude", "Same words, cleaner punctuation (Anthropic key)") { SettingSwitch(tidy) { tidy = it; VoiceSettings.setTidyWithClaude(context, it) } }
    }
}

/** Hands-free Sarah: "Sarah, …" while recording logs a note (and she answers). */
@Composable
fun HandsFreeSection() {
    SettingsSection("Hands-free Sarah", MonitorColors.sdnn, icon = androidx.compose.material.icons.Icons.Default.Hearing) {
        val hf by com.pulsepointlabs.sarahmonitor.session.HandsFreeSettings.enabled.collectAsState()
        val sayBack by com.pulsepointlabs.sarahmonitor.session.HandsFreeSettings.sayBack.collectAsState()
        val hfStatus by com.pulsepointlabs.sarahmonitor.core.MonitorHub.handsFree.status.collectAsState()
        SettingRow("Listen for \"Sarah\"", "Say \"Sarah, …\" while recording. The phone listens for her name; only what follows is sent") { SettingSwitch(hf) { com.pulsepointlabs.sarahmonitor.session.HandsFreeSettings.setEnabled(it) } }
        if (hf) {
            val talk by com.pulsepointlabs.sarahmonitor.session.HandsFreeSettings.talk.collectAsState()
            SettingRow("Sarah talks with you", if (talk) "She answers in her own words and remembers what you tell her" else "Plain confirmations only") { SettingSwitch(talk) { com.pulsepointlabs.sarahmonitor.session.HandsFreeSettings.setTalk(it) } }
            if (!talk) SettingRow("Say back what she logged") { SettingSwitch(sayBack) { com.pulsepointlabs.sarahmonitor.session.HandsFreeSettings.setSayBack(it) } }
            val hfHeadset by com.pulsepointlabs.sarahmonitor.session.HandsFreeSettings.headsetMic.collectAsState()
            SettingRow("Listen through the headset mic", if (hfHeadset) "Headset audio is call quality while listening" else "Phone mic: music and Sarah stay crisp") {
                SettingSwitch(hfHeadset) { com.pulsepointlabs.sarahmonitor.session.HandsFreeSettings.setHeadsetMic(it); com.pulsepointlabs.sarahmonitor.core.MonitorHub.handsFree.restart() }
            }
            Text((if (hfStatus.listening) "● " else "○ ") + hfStatus.message, color = if (hfStatus.listening) MonitorColors.good else MonitorColors.label, fontSize = 13.sp)
            hfStatus.lastLogged?.let { Text("Last: $it", color = MonitorColors.text, fontSize = 13.sp) }
        }
    }
}


/** All API keys in one place, at the bottom of Settings, with what each one powers. */
@Composable
fun ApiKeysSection(onMessage: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var openAi by remember { mutableStateOf(VoiceSettings.apiKey(context)) }
    val anthropic by com.pulsepointlabs.sarahmonitor.response.ResponseSettings.apiKey.collectAsState()
    SettingsSection("API keys", MonitorColors.label, icon = androidx.compose.material.icons.Icons.Default.Key) {
        KeyField(
            "OpenAI", "Live voice notes and Sarah's HD voice", openAi, "sk-…",
            onSave = { VoiceSettings.setApiKey(context, it); openAi = it; onMessage("OpenAI key saved.") },
            onRemove = { VoiceSettings.setApiKey(context, ""); openAi = "" }
        )
        androidx.compose.material3.HorizontalDivider(color = MonitorColors.panelBorder)
        KeyField(
            "Anthropic", "Sarah's brain: live feedback and stress coaching", anthropic, "sk-ant-…",
            onSave = { com.pulsepointlabs.sarahmonitor.response.ResponseSettings.setApiKey(it); onMessage("Anthropic key saved.") },
            onRemove = { com.pulsepointlabs.sarahmonitor.response.ResponseSettings.setApiKey("") }
        )
    }
}

@Composable
private fun KeyField(name: String, powers: String, saved: String, hint: String, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(powers, color = MonitorColors.label, fontSize = 12.sp)
        }
        Text(
            if (saved.isBlank()) "Not set" else "✓ ${saved.take(6)}…${saved.takeLast(4)}",
            color = if (saved.isBlank()) MonitorColors.warn else MonitorColors.good, fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(
            value = draft, onValueChange = { draft = it.trim() },
            label = { Text(if (saved.isBlank()) "Paste $name key ($hint)" else "Replace $name key") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.weight(1f)
        )
        TextButton(enabled = draft.isNotBlank(), onClick = { onSave(draft); draft = "" }) { Text("Save") }
        if (saved.isNotBlank()) TextButton(onClick = onRemove) { Text("Remove", color = MonitorColors.alarm) }
    }
}

/** OBS link: sessions start and stop with OBS recording, and the video is linked for later sync. */
@Composable
fun ObsSection() {
    val enabled by com.pulsepointlabs.sarahmonitor.core.ObsSettings.enabled.collectAsState()
    val url by com.pulsepointlabs.sarahmonitor.core.ObsSettings.url.collectAsState()
    val password by com.pulsepointlabs.sarahmonitor.core.ObsSettings.password.collectAsState()
    val autoStop by com.pulsepointlabs.sarahmonitor.core.ObsSettings.autoStop.collectAsState()
    val category by com.pulsepointlabs.sarahmonitor.core.ObsSettings.category.collectAsState()
    val status by com.pulsepointlabs.sarahmonitor.core.MonitorHub.obs.status.collectAsState()
    var urlDraft by remember(url) { mutableStateOf(url) }
    var pwDraft by remember(password) { mutableStateOf(password) }
    val pink = Color(0xFFFF7AB6)
    SettingsSection("OBS recording", pink, icon = androidx.compose.material.icons.Icons.Default.Videocam) {
        SettingRow("Start sessions with OBS", "Recording starts the session; stopping ends it") {
            SettingSwitch(enabled) { com.pulsepointlabs.sarahmonitor.core.ObsSettings.setEnabled(it) }
        }
        if (enabled) {
            Text(
                (if (status.connected) "● " else "○ ") + status.message + if (status.recording) (if (status.paused) " · paused" else " · recording") else "",
                color = if (status.connected) MonitorColors.good else MonitorColors.warn, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
            androidx.compose.material3.OutlinedTextField(
                value = urlDraft, onValueChange = { urlDraft = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("OBS address") }, placeholder = { Text("192.168.1.10:4455") }
            )
            androidx.compose.material3.OutlinedTextField(
                value = pwDraft, onValueChange = { pwDraft = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("WebSocket password") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("In OBS: Tools → WebSocket Server Settings.", color = MonitorColors.label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    com.pulsepointlabs.sarahmonitor.core.ObsSettings.setUrl(com.pulsepointlabs.sarahmonitor.core.ObsSettings.normalize(urlDraft))
                    com.pulsepointlabs.sarahmonitor.core.ObsSettings.setPassword(pwDraft)
                    com.pulsepointlabs.sarahmonitor.core.MonitorHub.obs.reconnect()
                }) { Text(if (urlDraft != url || pwDraft != password) "Save & connect" else "Reconnect") }
            }
            SettingRow("End the session when OBS stops") { SettingSwitch(autoStop) { com.pulsepointlabs.sarahmonitor.core.ObsSettings.setAutoStop(it) } }
            Text("If OBS starts without a prepared setup, record as", color = MonitorColors.text, fontSize = 14.sp)
            ChoiceRow {
                com.pulsepointlabs.sarahmonitor.session.SessionCategory.entries.filter { it.pickable }.forEach { c ->
                    ChoiceChip(c.label, c == category, pink) { com.pulsepointlabs.sarahmonitor.core.ObsSettings.setCategory(c) }
                }
            }
        }
    }
}
