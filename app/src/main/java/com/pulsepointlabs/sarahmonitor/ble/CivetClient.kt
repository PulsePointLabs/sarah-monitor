package com.pulsepointlabs.sarahmonitor.ble

import android.Manifest
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** One pressure reading from the CIVET pelvic sensor. */
data class CivetSample(
    val timestampMs: Long,
    val monotonicMs: Long,
    val pressureKpa: Double,
    val rawPacketHex: String
)

/**
 * CIVET pelvic pressure sensor over BLE, using the same wire protocol as Sarah:
 * - pressure notifications on characteristic 0x150B: byte 0 = 0xD0, pressure = int16 LE at bytes 8..9, in 0.01 kPa
 * - start streaming by writing `50 01 D0` + 14 zero bytes to command characteristic 0x150A
 * - hardware zero (sensor deflated, outside the body only) writes `66 00 .. 00 02` to 0x150A
 * The link is watched: no pressure for 7 s triggers a reconnect. The last sensor is remembered.
 */
class CivetClient(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(ConnectionState.NotConfigured)
    val state: StateFlow<ConnectionState> = _state

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices

    private val _samples = MutableSharedFlow<CivetSample>(extraBufferCapacity = 256)
    val samples: SharedFlow<CivetSample> = _samples

    private val _battery = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _battery

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var gatt: BluetoothGatt? = null
    private var enabled = false
    private var address: String? = null
    private var generation = 0
    @Volatile private var lastPacketMono = 0L
    private var connectedAtMono = 0L
    private var scanning = false
    private val found = linkedMapOf<String, BleDevice>()
    private var pendingZero: ((Boolean, String?) -> Unit)? = null

    private val watchdog = object : Runnable {
        override fun run() {
            if (!enabled) return
            val now = SystemClock.elapsedRealtime()
            val last = maxOf(lastPacketMono, connectedAtMono)
            if (_state.value != ConnectionState.Connecting && (gatt == null || now - last > STALL_MS)) {
                if (lastPacketMono > 0) _error.value = "No pressure for ${STALL_MS / 1000} s; reconnecting."
                reopen()
            } else if (_state.value == ConnectionState.Connected && lastPacketMono > 0 && now - lastPacketMono > STALE_MS) {
                _state.value = ConnectionState.Stale
            }
            handler.postDelayed(this, 3_000L)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!isCivetName(name)) return
            found[result.device.address] = BleDevice(name, result.device.address)
            _devices.value = found.values.toList()
        }
    }

    fun rememberedAddress(): String? = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() }
    fun rememberedName(): String? = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!hasPermission()) {
            _state.value = ConnectionState.Error
            _error.value = "Bluetooth permission is needed."
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _error.value = "Bluetooth is off."
            return
        }
        found.clear()
        _devices.value = emptyList()
        if (scanning) runCatching { scanner.stopScan(scanCallback) }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = runCatching { scanner.startScan(null, settings, scanCallback) }.isSuccess
        if (!enabled) _state.value = ConnectionState.Scanning
        handler.postDelayed({ stopScan() }, 12_000L)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        if (_state.value == ConnectionState.Scanning) _state.value = ConnectionState.NotConfigured
    }

    fun connect(device: BleDevice) {
        prefs.edit().putString(KEY_ADDRESS, device.address).putString(KEY_NAME, device.name).apply()
        stopScan()
        address = device.address
        enabled = true
        _error.value = null
        reopen()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 3_000L)
    }

    fun connectRemembered() {
        if (enabled) return
        val remembered = rememberedAddress() ?: return
        connect(BleDevice(rememberedName() ?: "CIVET", remembered))
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        enabled = false
        generation++
        handler.removeCallbacks(watchdog)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        lastPacketMono = 0
        _state.value = ConnectionState.Disconnected
    }

    fun forget() {
        disconnect()
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
        _state.value = ConnectionState.NotConfigured
    }

    /**
     * Hardware zero. Only valid with the sensor deflated and outside the body; the UI must confirm that first.
     */
    @SuppressLint("MissingPermission")
    fun hardwareZero(onDone: (Boolean, String?) -> Unit) {
        val current = gatt
        val command = current?.services?.firstNotNullOfOrNull { it.getCharacteristic(COMMAND_UUID) }
        if (current == null || command == null || lastPacketMono == 0L) {
            onDone(false, "Wait for live pressure before zeroing.")
            return
        }
        pendingZero = onDone
        if (!write(current, command, ZERO_COMMAND)) {
            pendingZero = null
            onDone(false, "Zero command could not be sent.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun reopen() {
        if (!enabled || !hasPermission()) return
        val target = address ?: return
        val token = ++generation
        runCatching { gatt?.close() }
        gatt = null
        lastPacketMono = 0
        connectedAtMono = SystemClock.elapsedRealtime()
        _state.value = if (_state.value == ConnectionState.Connected || _state.value == ConnectionState.Stale) {
            ConnectionState.Reconnecting
        } else {
            ConnectionState.Connecting
        }
        val device = runCatching { adapter?.getRemoteDevice(target) }.getOrNull() ?: return
        gatt = runCatching {
            device.connectGatt(context, false, GattCallback(token), BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
    }

    private inner class GattCallback(private val token: Int) : BluetoothGattCallback() {
        private var commandCharacteristic: BluetoothGattCharacteristic? = null

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (token != generation) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedAtMono = SystemClock.elapsedRealtime()
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    if (enabled) {
                        _state.value = ConnectionState.Reconnecting
                        _error.value = "Sensor disconnected. Check power; reconnecting."
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (token != generation) return@post
                val service = g.services.firstOrNull {
                    it.getCharacteristic(PRESSURE_UUID) != null && it.getCharacteristic(COMMAND_UUID) != null
                }
                if (service == null) {
                    _state.value = ConnectionState.Error
                    _error.value = "This device has no CIVET pressure service."
                    return@post
                }
                commandCharacteristic = service.getCharacteristic(COMMAND_UUID)
                val pressure = service.getCharacteristic(PRESSURE_UUID)
                g.setCharacteristicNotification(pressure, true)
                val cccd = pressure.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    startStreaming(g)
                } else if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (token != generation) return@post
                if (descriptor.uuid == CCCD_UUID) startStreaming(g)
            }
        }

        private fun startStreaming(g: BluetoothGatt) {
            val command = commandCharacteristic ?: return
            if (!write(g, command, START_COMMAND)) {
                _error.value = "Could not start pressure streaming."
            }
            _state.value = ConnectionState.Connected
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (token != generation) return@post
                val zero = pendingZero
                if (zero != null && characteristic.uuid == COMMAND_UUID) {
                    pendingZero = null
                    zero(status == BluetoothGatt.GATT_SUCCESS, if (status == BluetoothGatt.GATT_SUCCESS) null else "Zero failed (status $status)")
                }
                // Battery is optional; read it once after streaming starts.
                g.services.firstNotNullOfOrNull { it.getCharacteristic(BATTERY_UUID) }?.let { runCatching { g.readCharacteristic(it) } }
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) onRead(characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onRead(characteristic, value, status)
        }

        private fun onRead(characteristic: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
            if (characteristic.uuid == BATTERY_UUID && status == BluetoothGatt.GATT_SUCCESS && value != null && value.isNotEmpty()) {
                _battery.value = value[0].toInt() and 0xFF
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onPacket(characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onPacket(characteristic, value)
        }

        private fun onPacket(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
            if (characteristic.uuid != PRESSURE_UUID || value == null) return
            val wall = System.currentTimeMillis()
            val mono = SystemClock.elapsedRealtime()
            val kpa = CivetPacket.pressureKpa(value)
            handler.post {
                if (token != generation) return@post
                if (kpa == null) {
                    _error.value = "Unrecognized CIVET packet; check firmware compatibility."
                    return@post
                }
                lastPacketMono = mono
                if (_state.value != ConnectionState.Connected) _state.value = ConnectionState.Connected
                _error.value = null
                _samples.tryEmit(CivetSample(wall, mono, kpa, CivetPacket.hex(value)))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun write(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(characteristic, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = type
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PREFS = "sarahmonitor_civet_device"
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val STALL_MS = 7_000L
        private const val STALE_MS = 1_500L
        private fun uuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        val COMMAND_UUID: UUID = uuid("150a")
        val PRESSURE_UUID: UUID = uuid("150b")
        val BATTERY_UUID: UUID = uuid("1500")
        val CCCD_UUID: UUID = uuid("2902")
        val START_COMMAND: ByteArray = byteArrayOf(0x50, 0x01, 0xD0.toByte()) + ByteArray(14)
        val ZERO_COMMAND: ByteArray = CivetPacket.fromHex("66000000000000000000000002")

        /** Sarah's scan rule: the sensor advertises as "47L124…" or with "civet" in its name. */
        fun isCivetName(name: String): Boolean = name.startsWith("47L124") || name.contains("civet", ignoreCase = true)
    }
}

/** CIVET pressure packet decoding (same rule as Sarah's CivetPacket / civet_ble.py). */
object CivetPacket {
    /** Pressure in kPa, or null when the packet is not a pressure report. */
    fun pressureKpa(packet: ByteArray): Double? {
        if (packet.size !in 10..20 || (packet[0].toInt() and 0xFF) != 0xD0) return null
        val raw = (packet[8].toInt() and 0xFF) or (packet[9].toInt() shl 8)
        return raw.toShort().toInt() / 100.0
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun fromHex(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
