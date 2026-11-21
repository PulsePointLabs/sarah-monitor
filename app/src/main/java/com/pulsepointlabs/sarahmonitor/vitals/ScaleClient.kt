package com.pulsepointlabs.sarahmonitor.vitals

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Reads an Etekcity ESF-551 directly over Bluetooth (no VeSync): scan → connect → notifications on FFF1 →
 * the final packet carries weight + impedance → body composition (Sarah's maths) → saved.
 */
@SuppressLint("MissingPermission") // the Sensors page only offers this once Bluetooth permission is granted
class ScaleClient(context: Context, private val onReading: (BodyEntry) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("sarahmonitor_scale", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow("Not read yet")
    val status: StateFlow<String> = _status
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var stableWeight: Double? = null
    private val adapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val timeout = Runnable {
        val w = stableWeight
        if (w != null) {
            // Weight came through but no impedance (socks, moved, or wet feet): keep the weight.
            onReading(BodyEntry(System.currentTimeMillis(), w, source = "Etekcity ESF-551 (weight only)"))
            finish("Saved ${BodyProfile.weightText(w)} — no composition this time (bare, dry feet on all four pads)")
        } else {
            finish("The scale didn't send a reading. Keep VeSync closed, start here first, then wake the scale and stand still barefoot.")
        }
    }

    fun measure() {
        if (_busy.value) return
        val a = adapter
        if (a == null || !a.isEnabled) { _status.value = "Turn Bluetooth on first"; return }
        _busy.value = true
        stableWeight = null
        _status.value = "Searching… close VeSync, then tap the scale to wake it"
        main.postDelayed(timeout, 75_000L)
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).apply {
            if (Build.VERSION.SDK_INT >= 26) { setLegacy(false); setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }
        }.build()
        scanning = true
        runCatching { a.bluetoothLeScanner?.startScan(null, settings, scan) }.onFailure { finish("Couldn't scan: ${it.message}") }
    }

    fun cancel() = finish("Cancelled")

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
            val remembered = prefs.getString("address", null)
            val hasService = result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE)) == true
            val looksLikeScale = name.contains("Etekcity", true) || name.contains("ESF", true) || name.contains("Fitness Scale", true)
            if (result.device.address == remembered || looksLikeScale || hasService) {
                stopScan()
                connect(result.device, name)
            }
        }

        override fun onScanFailed(errorCode: Int) = finish("Bluetooth scan failed ($errorCode) — try again in a few seconds")
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
    }

    private fun connect(device: BluetoothDevice, name: String) {
        _status.value = "Connecting to ${name.ifBlank { "the scale" }}…"
        gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                prefs.edit().putString("address", g.device.address).apply()
                main.post { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && _busy.value) {
                finish(stableWeight?.let { "Scale disconnected before composition; weight ${BodyProfile.weightText(it)} was seen" } ?: "Scale disconnected — wake it and try again")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.services.firstNotNullOfOrNull { s -> s.characteristics.firstOrNull { it.uuid == MEASUREMENT } }
            if (ch == null) { finish("That device isn't the ESF-551 (no measurement channel)"); return }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD) ?: run { finish("Scale measurement channel has no notify switch"); return }
            val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value) else @Suppress("DEPRECATION") run { cccd.value = value; g.writeDescriptor(cccd) }
            _status.value = "Connected. Step on barefoot and stay still until it finishes"
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") handle(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = handle(value)
    }

    private fun handle(bytes: ByteArray) {
        val m = Esf551.parse(bytes) ?: return
        stableWeight = m.weightKg
        val height = BodyProfile.heightCm.value
        val age = BodyProfile.age()
        if (m.impedanceOhms == null) {
            if (height == null || age == null) {
                // No profile: a weight-only reading is still worth keeping.
                onReading(BodyEntry(System.currentTimeMillis(), m.weightKg, source = "Etekcity ESF-551 (weight only)"))
                finish("Saved ${BodyProfile.weightText(m.weightKg)}. Add height and birth year for body composition.")
            } else {
                _status.value = "Stable ${BodyProfile.weightText(m.weightKg)} — keep bare feet on all four pads for composition"
            }
            return
        }
        if (height == null || age == null) {
            onReading(BodyEntry(System.currentTimeMillis(), m.weightKg, impedanceOhms = m.impedanceOhms, source = "Etekcity ESF-551 (weight only)"))
            finish("Saved ${BodyProfile.weightText(m.weightKg)}. Add height and birth year for body composition.")
            return
        }
        val entry = Esf551.composition(m.weightKg, m.impedanceOhms, height, age, BodyProfile.male.value, "Etekcity ESF-551", System.currentTimeMillis())
        onReading(entry)
        finish("Saved ${BodyProfile.weightText(entry.weightKg)} · body fat ${entry.bodyFatPct}%")
    }

    private fun finish(message: String) {
        main.removeCallbacks(timeout)
        stopScan()
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
        _busy.value = false
        _status.value = message
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val MEASUREMENT: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
