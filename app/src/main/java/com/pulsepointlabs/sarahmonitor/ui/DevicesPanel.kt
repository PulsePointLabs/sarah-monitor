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

@Composable
fun DevicesPanel(
    onDismiss: () -> Unit,
    onHealthConnectImport: () -> Unit,
    onCsvImport: () -> Unit,
    onMessage: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 640.dp).padding(16.dp),
        containerColor = MonitorColors.panel,
        title = { Text("Sensors", color = MonitorColors.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).heightIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                H10Section()
                CivetSection(onMessage)
                OmronSection()
                Section("Glucose", MonitorColors.glucose, "OneTouch Verio Reflect: in the OneTouch Reveal app, share or export your results and pick Sarah Monitor (same as with Sarah). Health Connect and CSV files work too, and you can add a reading by hand on the Glucose card.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelButton("Import CSV", onCsvImport)
                        PanelButton("Health Connect", onHealthConnectImport)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
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
    OutlinedButton(onClick = onClick) { Text(label, fontSize = 13.sp) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    var confirmZero by remember { mutableStateOf(false) }
    Section("Civet edging sensor (pelvic pressure)", MonitorColors.pelvic, "Not showing up? Press its power button 5 times — the Bluetooth icon turns yellow when it's discoverable — then Scan. It's remembered and reconnects by itself.") {
        StateText(state, client.rememberedName(), battery)
        error?.let { Text(it, color = MonitorColors.warn, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("Scan") { client.scan() }
            if (client.rememberedAddress() != null) PanelButton("Reconnect") { client.connectRemembered() }
            PanelButton("Disconnect") { client.disconnect() }
            PanelButton("Forget") { client.forget() }
            PanelButton("Flip display") { if (!client.flipDisplay()) onMessage("Connect the sensor first.") }
        }
        DeviceList(devices) { client.connect(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
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
    Section("Omron BP cuff", MonitorColors.bp, "Pair once. After that, readings appear on the dashboard as soon as the cuff finishes a measurement.") {
        StateText(state, client.rememberedName())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("Pair / scan") { client.scanAndArm() }
            if (client.rememberedAddress() != null) PanelButton("Listen") { client.armRemembered() }
            PanelButton("Stop") { client.disarm() }
            PanelButton("Forget") { client.disarm(); client.forget() }
        }
        DeviceList(devices) { client.connect(it.address) }
    }
}
