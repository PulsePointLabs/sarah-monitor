package com.pulsepointlabs.sarahmonitor.ui

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
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            H10Section()
            CivetSection(onMessage)
            OmronSection()
            Section("Glucose", MonitorColors.glucose, "OneTouch Verio Reflect: in OneTouch Reveal, share or export your results and pick Sarah Monitor. Health Connect and CSV files work too, and you can add a reading by hand on the Glucose card.") {
                Buttons {
                    PanelButton("Import CSV", onCsvImport)
                    PanelButton("Health Connect", onHealthConnectImport)
                }
            }
            VoiceSection(onMessage)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}

@Composable
private fun Section(title: String, color: Color, subtitle: String? = null, content: @Composable () -> Unit) {
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
                Text("If the sensor shows up under a different name, pick it here. Strongest signal (closest) first.", color = MonitorColors.label, fontSize = 12.sp)
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
private fun VoiceSection(onMessage: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var engine by remember { mutableStateOf(VoiceSettings.engine(context)) }
    var saved by remember { mutableStateOf(VoiceSettings.apiKey(context)) }
    var key by remember { mutableStateOf("") }
    Section(
        "Voice notes",
        MonitorColors.text,
        "Speech-to-text for event notes. Whisper (OpenAI) is more accurate; it sends the short voice clip to OpenAI with your API key. Android's recognizer works without a key."
    ) {
        Buttons {
            VoiceSettings.Engine.entries.forEach { e ->
                val on = e == engine
                Text(
                    (if (on) "✓ " else "") + e.label,
                    color = if (on) MonitorColors.background else MonitorColors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(if (on) MonitorColors.pelvic else Color.Transparent, RoundedCornerShape(16.dp))
                        .border(1.dp, MonitorColors.pelvic.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .clickable { engine = e; VoiceSettings.setEngine(context, e) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        if (engine == VoiceSettings.Engine.Whisper) {
            Text(
                if (saved.isBlank()) "No API key saved yet; Android's recognizer is used until you add one."
                else "Key saved: ${saved.take(3)}…${saved.takeLast(4)}",
                color = if (saved.isBlank()) MonitorColors.warn else MonitorColors.good,
                fontSize = 13.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text("OpenAI API key (sk-…)") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
                TextButton(enabled = key.isNotBlank(), onClick = {
                    VoiceSettings.setApiKey(context, key); saved = key; key = ""
                    onMessage("Whisper key saved. Tap the mic in an event to record, tap again to transcribe.")
                }) { Text("Save") }
                if (saved.isNotBlank()) TextButton(onClick = { VoiceSettings.setApiKey(context, ""); saved = "" }) { Text("Remove", color = MonitorColors.alarm) }
            }
        }
    }
}
