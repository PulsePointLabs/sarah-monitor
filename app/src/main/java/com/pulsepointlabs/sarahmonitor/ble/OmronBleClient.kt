package com.pulsepointlabs.sarahmonitor.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class OmronBleClient(private val context: Context) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var lastDevice: BluetoothDevice? = null
    private var armed = false
    private var subscribed = false
    private var targetAddress: String? = null
    private var scanning = false
    // Consecutive waits without a reading; drives the 5 s -> 60 s retry backoff.
    private var retryFailures = 0
    private val scanTimeout = Runnable { finishScan() }
    private val retryRunnable = Runnable { retryConnection() }
    private val found = linkedMapOf<String, BluetoothDevice>()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(ConnectionState.NotConfigured)
    val state: StateFlow<ConnectionState> = _state

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices

    private val _readings = MutableSharedFlow<BpReading>(extraBufferCapacity = 16)
    val readings: SharedFlow<BpReading> = _readings

    @SuppressLint("MissingPermission")
    fun scanAndArm() {
        scanAndArm(preferRemembered = true)
    }

    @SuppressLint("MissingPermission")
    private fun scanAndArm(preferRemembered: Boolean) {
        if (!hasBluetoothPermission()) {
            _state.value = ConnectionState.Error
            return
        }
        armed = true
        targetAddress = if (preferRemembered) prefs.getString(KEY_ADDRESS, null) else null
        // A fresh scan replaces any pending connect, so a scan result can start exactly one new one.
        handler.removeCallbacks(retryRunnable)
        runCatching { gatt?.close() }
        gatt = null
        subscribed = false
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.value = ConnectionState.Error
            return
        }
        found.clear()
        _devices.value = emptyList()
        _state.value = ConnectionState.Scanning
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BP_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        handler.removeCallbacks(scanTimeout)
        if (scanning) runCatching { scanner.stopScan(scanCallback) }
        scanning = runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }.isSuccess
        handler.postDelayed(scanTimeout, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun finishScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
            scanning = false
        }
        if (_state.value == ConnectionState.Scanning) _state.value = ConnectionState.WaitingForCuff
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasBluetoothPermission()) {
            _state.value = ConnectionState.Error
            return
        }
        val device = found[address] ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        armed = true
        lastDevice = device
        rememberDevice(device)
        handler.removeCallbacks(retryRunnable)
        retryFailures = 0
        openGatt(device, autoConnect = false)
        _state.value = ConnectionState.Connecting
    }

    /** Only one GATT client at a time: closing the previous one also cancels its pending connect. */
    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice, autoConnect: Boolean) {
        subscribed = false
        runCatching { gatt?.close() }
        gatt = runCatching { device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
    }

    fun armRemembered() {
        if (!hasBluetoothPermission()) return
        val address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return
        if (_state.value == ConnectionState.Connected ||
            _state.value == ConnectionState.Connecting ||
            _state.value == ConnectionState.Receiving ||
            _state.value == ConnectionState.WaitingForCuff
        ) return
        targetAddress = address
        scanAndArm(preferRemembered = true)
    }

    fun disarm() {
        armed = false
        targetAddress = null
        subscribed = false
        handler.removeCallbacks(retryRunnable)
        finishScan()
        runCatching { gatt?.close() }
        gatt = null
        retryFailures = 0
        _state.value = ConnectionState.NotConfigured
    }

    /**
     * Waits for the next measurement. Retries back off from 5 s to once a minute so an armed cuff that is
     * switched off doesn't compete with the heart strap for the radio all night. After the first retry the
     * Bluetooth stack waits for the cuff in the background (autoConnect), which costs almost no radio time
     * and still picks the cuff up as soon as it advertises.
     */
    private fun reconnectOrWait() {
        if (!armed) return
        _state.value = ConnectionState.WaitingForCuff
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, ReconnectPolicy.cuffRetryDelayMs(retryFailures))
    }

    @SuppressLint("MissingPermission")
    private fun retryConnection() {
        if (!armed || !hasBluetoothPermission()) return
        val device = lastDevice
        if (device != null) {
            _state.value = ConnectionState.WaitingForCuff
            val useAutoConnect = retryFailures >= 1
            retryFailures += 1
            openGatt(device, autoConnect = useAutoConnect)
            if (gatt == null) reconnectOrWait()
        } else {
            retryFailures += 1
            scanAndArm()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: "Blood Pressure Cuff"
            found[device.address] = device
            _devices.value = found.values.map { BleDevice(it.name ?: name, it.address) }
            // Every advertisement fires this callback; connect once, to one device, then stop scanning.
            if (gatt != null || _state.value == ConnectionState.Connecting) return
            val rememberedMatch = targetAddress?.equals(device.address, ignoreCase = true) == true
            if (rememberedMatch || name.contains("Omron", true) || name.contains("BP", true) || found.size == 1) {
                finishScan()
                connect(device.address)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@OmronBleClient.gatt) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { gatt.close() }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = ConnectionState.Connected
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribed = false
                reconnectOrWait()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(BP_SERVICE)?.getCharacteristic(BP_MEASUREMENT)
            if (characteristic == null) {
                _state.value = ConnectionState.Error
                return
            }
            if (subscribed) return
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(BluetoothHrClient.CCCD)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
            subscribed = true
            _state.value = ConnectionState.WaitingForCuff
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handlePacket(gatt.device, value)
        }

        @Deprecated("Deprecated by Android")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handlePacket(gatt.device, characteristic.value ?: return)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handlePacket(device: BluetoothDevice, value: ByteArray) {
        _state.value = ConnectionState.Receiving
        val packet = runCatching { BloodPressurePacketParser.parse(value) }.getOrNull() ?: run {
            _state.value = ConnectionState.Error
            return
        }
        val movement = listOfNotNull(
            if (packet.bodyMovementDetected == true) "body_movement" else null,
            if (packet.cuffFitProblem == true) "cuff_fit" else null,
            if (packet.measurementPositionProblem == true) "position" else null
        ).joinToString("|").ifBlank { null }
        _readings.tryEmit(
            BpReading(
                deviceName = device.name ?: "Omron Evolv",
                deviceAddress = device.address,
                timestampMs = packet.timestampMs ?: System.currentTimeMillis(),
                systolic = packet.systolic,
                diastolic = packet.diastolic,
                pulse = packet.pulse,
                irregular = packet.irregularPulseDetected,
                movementStatus = movement
            )
        )
        _state.value = ConnectionState.ReadingSaved
        retryFailures = 0
        reconnectOrWait()
    }

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun rememberDevice(device: BluetoothDevice) {
        prefs.edit()
            .putString(KEY_ADDRESS, device.address)
            .putString(KEY_NAME, device.name ?: "Omron Evolv")
            .apply()
    }

    /** Remembered device, shown in the device panel and reconnected on launch. */
    fun rememberedAddress(): String? = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() }

    fun rememberedName(): String? = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    /** Forget the remembered device so it is no longer reconnected automatically. */
    fun forget() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
    }

    companion object {
        private const val PREFS = "sarahmonitor_omron_device"
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        val BP_SERVICE: UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
        val BP_MEASUREMENT: UUID = UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")
    }
}
