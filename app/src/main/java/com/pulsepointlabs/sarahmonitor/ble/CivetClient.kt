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

/** A Bluetooth device seen while scanning; [likelyCivet] when its name or services match the sensor. */
data class NearbyDevice(val name: String?, val address: String, val rssi: Int, val likelyCivet: Boolean)

/** One pressure reading from the CIVET pelvic sensor. */
data class CivetSample(
    val timestampMs: Long,
    val monotonicMs: Long,
    val pressureKpa: Double,
    val rawPacketHex: String
)

/**
 * DG-LAB Civet Edging Sensor over BLE, per DG-LAB's open protocol
 * (github.com/DG-LAB-OPENSOURCE/DG-LAB-OPENSOURCE, civet-edging-sensor) and as used by Sarah:
 * - service 0x180C: commands to 0x150A (write), all messages from 0x150B (notify)
 * - `50 01 D0` + 14 zero bytes starts pressure reporting, `50 01 00` + 14 zero bytes stops it
 * - D0 messages arrive every 100 ms; pressure = int16 LE at bytes 8..9, in 0.01 kPa
 * - `66` + 9 zero bytes + `00` + `0002` resets the pressure reading (we only send it with the sensor deflated,
 *   outside the body); `66` + 9 zero bytes + `01`/`03` + `0000` flips the sensor's display
 * - battery: service 0x180A, characteristic 0x1500 (1 byte, %)
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
    private var lastBatteryReadMono = 0L
    private var displayRotation = 1

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
            } else if (lastPacketMono > 0 && now - lastBatteryReadMono > BATTERY_REFRESH_MS) {
                readBattery()
            }
            handler.postDelayed(this, 3_000L)
        }
    }

    /** Everything nearby from the last scan (for "Show all nearby devices"), strongest signal first. */
    private val _allDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val allDevices: StateFlow<List<NearbyDevice>> = _allDevices
    private val everything = linkedMapOf<String, NearbyDevice>()

    private val _scanStatus = MutableStateFlow<String?>(null)
    /** What the last scan did ("Scanning…", "Found 1 CIVET", a failure reason). */
    val scanStatus: StateFlow<String?> = _scanStatus

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleResult)

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _scanStatus.value = when (errorCode) {
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "Android refused the scan (often too many scans in a short time). Wait 30 s and try again."
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone's Bluetooth doesn't support that scan; retrying the basic way."
                else -> "Scan failed (code $errorCode). Toggle Bluetooth off and on, then try again."
            }
            if (errorCode == SCAN_FAILED_FEATURE_UNSUPPORTED && !legacyOnly) {
                legacyOnly = true
                handler.post { scan() }
            } else if (_state.value == ConnectionState.Scanning) {
                _state.value = ConnectionState.NotConfigured
            }
        }
    }

    private var legacyOnly = false

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
        val services = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
        // Match by name (DG-LAB: "47L124000") or by the CIVET service 0x180C in the advertisement.
        val isCivet = (name != null && isCivetName(name)) || services.contains(SERVICE_UUID)
        handler.post {
            everything[result.device.address] = NearbyDevice(name, result.device.address, result.rssi, isCivet)
            _allDevices.value = everything.values.sortedWith(compareByDescending<NearbyDevice> { it.likelyCivet }.thenByDescending { it.rssi })
            if (isCivet) {
                found[result.device.address] = BleDevice(name ?: "CIVET", result.device.address)
                _devices.value = found.values.toList()
                _scanStatus.value = "Found ${found.size} CIVET sensor${if (found.size == 1) "" else "s"} — tap Connect"
            }
        }
    }

    private val scanTimeout = Runnable { stopScan() }

    fun rememberedAddress(): String? = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() }
    fun rememberedName(): String? = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!hasScanPermission()) {
            _scanStatus.value = "Sarah Monitor needs the \"Nearby devices\" permission to scan (Settings → Apps → Sarah Monitor → Permissions)."
            return
        }
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            _scanStatus.value = "Bluetooth is off. Turn it on and scan again."
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            _scanStatus.value = "Bluetooth scanner unavailable. Toggle Bluetooth off and on."
            return
        }
        found.clear(); everything.clear()
        _devices.value = emptyList(); _allDevices.value = emptyList()
        handler.removeCallbacks(scanTimeout)
        if (scanning) runCatching { scanner.stopScan(scanCallback) }
        // Report extended (Bluetooth 5) advertisements as well as legacy ones, on every PHY the phone supports.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .apply {
                if (Build.VERSION.SDK_INT >= 26 && !legacyOnly) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        val started = runCatching { scanner.startScan(null, settings, scanCallback) }
        scanning = started.isSuccess
        if (!scanning) {
            _scanStatus.value = "Couldn't start scanning (${started.exceptionOrNull()?.message ?: "unknown error"})."
            return
        }
        _scanStatus.value = "Scanning for 20 s… (sensor in pairing mode: Bluetooth icon yellow)"
        if (!enabled) _state.value = ConnectionState.Scanning
        handler.postDelayed(scanTimeout, 20_000L)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        val wasScanning = scanning
        scanning = false
        if (wasScanning && found.isEmpty()) {
            _scanStatus.value = "No CIVET found (${everything.size} other Bluetooth device${if (everything.size == 1) "" else "s"} seen). " +
                "Check it's in pairing mode and not connected to another app, or use \"Show all nearby devices\"."
        }
        if (_state.value == ConnectionState.Scanning) _state.value = ConnectionState.NotConfigured
    }

    /** Connect to a device picked from the "all nearby devices" list. */
    fun connect(device: NearbyDevice) = connect(BleDevice(device.name ?: "CIVET", device.address))

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
        val current = gatt
        gatt = null
        // Ask the sensor to stop reporting before dropping the link (best effort), then close.
        val command = current?.services?.firstNotNullOfOrNull { it.getCharacteristic(COMMAND_UUID) }
        if (current != null && command != null && lastPacketMono > 0) runCatching { write(current, command, STOP_COMMAND) }
        handler.postDelayed({
            runCatching { current?.disconnect() }
            runCatching { current?.close() }
        }, if (command != null) 250L else 0L)
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

    /** Flips the sensor's own display between its two orientations. */
    @SuppressLint("MissingPermission")
    fun flipDisplay(): Boolean {
        val current = gatt ?: return false
        val command = current.services?.firstNotNullOfOrNull { it.getCharacteristic(COMMAND_UUID) } ?: return false
        displayRotation = if (displayRotation == 1) 3 else 1
        return write(current, command, rotateCommand(displayRotation))
    }

    @SuppressLint("MissingPermission")
    private fun readBattery() {
        lastBatteryReadMono = SystemClock.elapsedRealtime()
        val current = gatt ?: return
        current.services?.firstNotNullOfOrNull { it.getCharacteristic(BATTERY_UUID) }?.let { runCatching { current.readCharacteristic(it) } }
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
                // Battery (0x180A/0x1500) is read after streaming starts, then every few minutes by the watchdog.
                readBattery()
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

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
        private const val BATTERY_REFRESH_MS = 5 * 60_000L
        private fun uuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        /** DG-LAB: Civet service 0x180C carries the command and pressure characteristics. */
        val SERVICE_UUID: UUID = uuid("180c")
        val COMMAND_UUID: UUID = uuid("150a")
        val PRESSURE_UUID: UUID = uuid("150b")
        val BATTERY_UUID: UUID = uuid("1500")
        val CCCD_UUID: UUID = uuid("2902")
        /** 0x50 header, 01 indicator colour (yellow), D0 = start pressure reporting, 14 bytes padding. */
        val START_COMMAND: ByteArray = byteArrayOf(0x50, 0x01, 0xD0.toByte()) + ByteArray(14)
        val STOP_COMMAND: ByteArray = byteArrayOf(0x50, 0x01, 0x00) + ByteArray(14)
        val ZERO_COMMAND: ByteArray = CivetPacket.fromHex("66000000000000000000000002")

        /** 0x66 header, 9 bytes padding, orientation 01/03, 0000 (no pressure reset). */
        fun rotateCommand(rotation: Int): ByteArray = byteArrayOf(0x66) + ByteArray(9) + byteArrayOf(rotation.toByte(), 0x00, 0x00)

        /** The sensor advertises as "47L124000" (DG-LAB); Sarah also accepts names containing "civet". */
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
