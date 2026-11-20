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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.pulsepointlabs.sarahmonitor.core.DiagnosticsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Batches that could not be handed to the app's consumers in time (see [BluetoothHrClient.droppedBatchCounts]). */
data class DroppedBatchCounts(
    val heartRate: Int = 0,
    val ecg: Int = 0,
    val accelerometer: Int = 0
)

/** Repeated notifications dropped since the last [BluetoothHrClient.resetDuplicateCounts]. */
data class DuplicateFrameCounts(
    /** Heart-rate packets byte-identical to the previous one and delivered within 250 ms of it. */
    val heartRate: Int = 0,
    /** ECG frames that were exact repeats, or that the PMD parser rejected as repeated/older. */
    val ecg: Int = 0,
    /** Motion frames that were exact repeats, or that the PMD parser rejected as repeated/older. */
    val accelerometer: Int = 0
)

/** One strap link loss or failed connect attempt, with the GATT status code Android reported. */
data class StrapDisconnect(
    val timestampMs: Long,
    val gattStatus: Int,
    /** True when the link had been up; false for a connect attempt that never got through. */
    val wasConnected: Boolean
)

class BluetoothHrClient(private val context: Context) {
    private enum class SetupStage {
        Idle,
        HrMeasurementCccd,
        PmdDataCccd,
        PmdControlCccd
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default)
    private var gatt: BluetoothGatt? = null
    private var lastDevice: BluetoothDevice? = null
    private var subscribed = false
    private var staleJob: Job? = null
    private var shouldReconnect = false
    private var lastBatteryReadAttemptMs = 0L
    private var batteryReadScheduledAfterEcg = false
    private var accelerometerEnabled = true
    private var lastEcgDisplayUpdateMs = 0L
    private var lastAccelerometerDisplayUpdateMs = 0L
    @Volatile private var sensorInputAccepted = false
    private val ecgBuffer = ArrayDeque<EcgSample>()
    private val companionRPeakDetector = SarahVsRPeakDetector()
    private val accelBuffer = ArrayDeque<AccelerometerSample>()
    private var pmdControlCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingPmdCommand: Int? = null
    private var selectedAccelerometerRateHz = 25
    private var setupStage = SetupStage.Idle
    private val pmdClock = PmdHostClock()
    private val ecgParserState = PmdParserState(sampleRateHz = 130, clock = pmdClock)
    private val accelerometerParserState = PmdParserState(sampleRateHz = 25, clock = pmdClock)

    private val _state = MutableStateFlow(ConnectionState.NotConfigured)
    val state: StateFlow<ConnectionState> = _state

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices

    private var setupFailures = 0
    private var connectFailures = 0
    private var linkEstablished = false
    private var autoConnectPending = false
    private var batteryNotifyRequested = false
    private var lastLoggedBatteryPercent: Int? = null
    private var droppedBatches = 0
    private var lastDropLogMs = 0L
    private val droppedHr = AtomicInteger(0)
    private val droppedEcg = AtomicInteger(0)
    private val droppedAcc = AtomicInteger(0)
    // Some phones (seen after an OTA) deliver every notification twice; repeats are dropped and counted.
    private val duplicateHr = AtomicInteger(0)
    private val duplicateEcg = AtomicInteger(0)
    private val duplicateAcc = AtomicInteger(0)
    private var duplicatesSinceLog = 0
    private var lastDuplicateLogMs = 0L
    private var lastHrPacket: ByteArray? = null
    private var lastHrPacketAtMs = 0L
    private var lastEcgFrame: ByteArray? = null
    private var lastAccFrame: ByteArray? = null
    private val _readings = MutableSharedFlow<HrReading>(extraBufferCapacity = 128)
    val readings: SharedFlow<HrReading> = _readings

    private val _ecgSamples = MutableStateFlow<List<EcgSample>>(emptyList())
    val ecgSamples = _ecgSamples.asStateFlow()

    // Once the buffer is full the slowest subscriber holds every subscriber back, so size it for ~2 minutes
    // of frames (ECG arrives at ~2 frames/s): a lagging consumer can't make the raw recorder lose ECG.
    private val _ecgBatches = MutableSharedFlow<List<EcgSample>>(extraBufferCapacity = STREAM_BUFFER_BATCHES)
    val ecgBatches: SharedFlow<List<EcgSample>> = _ecgBatches

    private val _accelerometerSamples = MutableStateFlow<List<AccelerometerSample>>(emptyList())
    val accelerometerSamples = _accelerometerSamples.asStateFlow()

    private val _accelerometerBatches = MutableSharedFlow<List<AccelerometerSample>>(extraBufferCapacity = STREAM_BUFFER_BATCHES)
    val accelerometerBatches: SharedFlow<List<AccelerometerSample>> = _accelerometerBatches

    private val _pmdStreamStatus = MutableStateFlow(PmdStreamStatus())
    val pmdStreamStatus = _pmdStreamStatus.asStateFlow()

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent = _batteryPercent.asStateFlow()

    private val _disconnects = MutableSharedFlow<StrapDisconnect>(extraBufferCapacity = 32)
    /** Every link loss / failed connect attempt with its GATT status, for per-session health accounting. */
    val disconnects: SharedFlow<StrapDisconnect> = _disconnects

    // Handler callbacks stop while the phone is in deep sleep, so a pending reconnect would stall all night.
    // Hold a short, always-timed partial wake lock while a reconnect is scheduled or a direct attempt runs.
    private val reconnectWakeLock: PowerManager.WakeLock? = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SarahVS:strapReconnect")
            .apply { setReferenceCounted(false) }
    }.getOrNull()

    private val found = linkedMapOf<String, BluetoothDevice>()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val reconnectRunnable = Runnable { attemptReconnect() }

    // If the link comes up but no heart-rate packet ever arrives (setup stalled), restart the link.
    private val firstPacketWatchdog = Runnable {
        if (_state.value == ConnectionState.Connected && shouldReconnect) restartLinkAfterSetupFailure("No heart-rate data after connecting")
    }

    // Bluetooth turned off and on again: no disconnect callback or retry is guaranteed, so react to the
    // adapter state directly instead of waiting on a reconnect that can never succeed.
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (gatt == null && _state.value != ConnectionState.Reconnecting) return
                    handler.removeCallbacks(reconnectRunnable)
                    handler.removeCallbacks(firstPacketWatchdog)
                    staleJob?.cancel()
                    runCatching { gatt?.close() }
                    gatt = null
                    linkEstablished = false
                    autoConnectPending = false
                    connectFailures = 0
                    releaseReconnectWakeLock()
                    resetLinkState()
                    markSensorInputUnavailable("Bluetooth turned off")
                    _state.value = if (shouldReconnect && lastDevice != null) ConnectionState.Reconnecting else ConnectionState.Disconnected
                }
                BluetoothAdapter.STATE_ON -> if (shouldReconnect && lastDevice != null && gatt == null) {
                    handler.removeCallbacks(reconnectRunnable)
                    acquireReconnectWakeLock(1_000L + DIRECT_ATTEMPT_WAKE_MS)
                    handler.postDelayed(reconnectRunnable, 1_000L)
                }
            }
        }
    }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                context,
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    fun setAccelerometerEnabled(enabled: Boolean) {
        accelerometerEnabled = enabled
        if (!enabled) {
            accelBuffer.clear()
            _accelerometerSamples.value = emptyList()
            _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                accelerometer = PmdStreamState.Unavailable,
                accelerometerMessage = "Power Saver"
            )
        } else {
            val activeGatt = gatt
            val control = pmdControlCharacteristic
            if (activeGatt != null && control != null) {
                _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                    accelerometer = PmdStreamState.Configuring,
                    accelerometerMessage = null
                )
                queryAccelerometerSettings(activeGatt, control)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!hasBluetoothPermission()) {
            _state.value = ConnectionState.Error
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.value = ConnectionState.Error
            return
        }
        found.clear()
        _devices.value = emptyList()
        _state.value = ConnectionState.Scanning
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        handler.postDelayed({
            scanner.stopScan(scanCallback)
            if (_state.value == ConnectionState.Scanning) {
                _state.value = if (found.isEmpty()) ConnectionState.Disconnected else ConnectionState.NotConfigured
            }
        }, 8000)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasBluetoothPermission()) {
            _state.value = ConnectionState.Error
            return
        }
        val device = found[address] ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        shouldReconnect = true
        lastDevice = device
        rememberDevice(device)
        setupFailures = 0
        connectFailures = 0
        autoConnectPending = false
        handler.removeCallbacks(reconnectRunnable)
        subscribed = false
        lastBatteryReadAttemptMs = 0L
        batteryReadScheduledAfterEcg = false
        batteryNotifyRequested = false
        lastLoggedBatteryPercent = null
        _batteryPercent.value = null
        ecgBuffer.clear()
        accelBuffer.clear()
        _ecgSamples.value = emptyList()
        _accelerometerSamples.value = emptyList()
        _pmdStreamStatus.value = PmdStreamStatus()
        markSensorInputUnavailable("Waiting for strap input")
        pmdControlCharacteristic = null
        pendingPmdCommand = null
        setupStage = SetupStage.Idle
        // Only one GATT client at a time: closing the old one also cancels any pending background connect.
        runCatching { gatt?.close() }
        gatt = null
        _state.value = ConnectionState.Connecting
        linkEstablished = false
        acquireReconnectWakeLock(DIRECT_ATTEMPT_WAKE_MS)
        gatt = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) {
            connectFailures += 1
            reconnectLater()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectRemembered() {
        if (!hasBluetoothPermission()) return
        if (_state.value == ConnectionState.Connected ||
            _state.value == ConnectionState.Connecting ||
            _state.value == ConnectionState.Reconnecting
        ) return
        val address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return
        connect(address)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        shouldReconnect = false
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(firstPacketWatchdog)
        staleJob?.cancel()
        // disconnect() + close() also cancels a pending background (autoConnect) request.
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        linkEstablished = false
        autoConnectPending = false
        connectFailures = 0
        setupFailures = 0
        releaseReconnectWakeLock()
        subscribed = false
        pmdControlCharacteristic = null
        pendingPmdCommand = null
        setupStage = SetupStage.Idle
        markSensorInputUnavailable("Disconnected")
        _state.value = ConnectionState.Disconnected
    }

    private fun reconnectLater() {
        if (lastDevice == null || !shouldReconnect) {
            releaseReconnectWakeLock()
            return
        }
        _state.value = ConnectionState.Reconnecting
        handler.removeCallbacks(reconnectRunnable)
        val delayMs = ReconnectPolicy.backoffDelayMs(maxOf(setupFailures, connectFailures - 1))
        acquireReconnectWakeLock(delayMs + DIRECT_ATTEMPT_WAKE_MS)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun attemptReconnect() {
        val device = lastDevice
        if (device == null || !shouldReconnect || !hasBluetoothPermission()) {
            releaseReconnectWakeLock()
            return
        }
        // With Bluetooth off the attempt cannot work; the adapter-state receiver retries when it is back on.
        if (adapter?.isEnabled != true) {
            _state.value = ConnectionState.Reconnecting
            releaseReconnectWakeLock()
            return
        }
        subscribed = false
        // Only one GATT client at a time: close the old one (and any pending autoConnect) first.
        runCatching { gatt?.close() }
        gatt = null
        val useAutoConnect = ReconnectPolicy.useAutoConnect(connectFailures)
        linkEstablished = false
        gatt = runCatching { device.connectGatt(context, useAutoConnect, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        // connectGatt returns null when the Bluetooth stack isn't ready; no callback will follow, so retry.
        if (gatt == null) {
            connectFailures += 1
            reconnectLater()
            return
        }
        if (useAutoConnect) {
            // The Bluetooth controller now waits for the strap by itself and wakes us when it is back, so the
            // CPU may sleep: no wake lock and no timer are needed until the connection callback arrives.
            if (!autoConnectPending) {
                DiagnosticsStore.breadcrumb(context, "ble", "Strap not reachable; waiting for it in the background (autoConnect)")
            }
            autoConnectPending = true
            releaseReconnectWakeLock()
        } else {
            autoConnectPending = false
            acquireReconnectWakeLock(DIRECT_ATTEMPT_WAKE_MS)
        }
    }

    private fun acquireReconnectWakeLock(timeoutMs: Long) {
        runCatching { reconnectWakeLock?.acquire(timeoutMs.coerceIn(1_000L, MAX_WAKE_LOCK_MS)) }
    }

    private fun releaseReconnectWakeLock() {
        runCatching {
            val lock = reconnectWakeLock
            if (lock != null && lock.isHeld) lock.release()
        }
    }

    private fun resetLinkState() {
        subscribed = false
        pmdControlCharacteristic = null
        pendingPmdCommand = null
        setupStage = SetupStage.Idle
    }

    /**
     * A failed GATT setup step leaves the strap connected but sending nothing (or HR without ECG). Drop the
     * link so the normal reconnect runs setup again from scratch. Never gives up: retries back off from 2.5 s
     * to once a minute, and the count resets as soon as ECG data flows (or on a manual connect).
     */
    @SuppressLint("MissingPermission")
    private fun restartLinkAfterSetupFailure(reason: String) {
        setupFailures += 1
        if (ReconnectPolicy.shouldLogFailure(setupFailures)) {
            DiagnosticsStore.breadcrumb(
                context,
                "ble",
                "Setup failed ($reason), attempt $setupFailures; retrying in ${ReconnectPolicy.backoffDelayMs(setupFailures) / 1000} s"
            )
        }
        val active = gatt
        if (active == null) {
            reconnectLater()
        } else {
            runCatching { active.disconnect() }.onFailure { reconnectLater() }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: "Heart Rate Sensor"
            found[device.address] = device
            _devices.value = found.values.map { BleDevice(it.name ?: name, it.address) }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // A callback from a GATT client we already replaced or closed must never drive the live link.
            if (gatt !== this@BluetoothHrClient.gatt) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { gatt.close() }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (autoConnectPending || connectFailures > 0) {
                    DiagnosticsStore.breadcrumb(context, "ble", "Strap link restored after $connectFailures failed attempt(s)")
                }
                linkEstablished = true
                autoConnectPending = false
                connectFailures = 0
                batteryReadScheduledAfterEcg = false
                batteryNotifyRequested = false
                // Keep the CPU up until the first heart-rate packet proves setup worked (or the watchdog fires).
                acquireReconnectWakeLock(FIRST_PACKET_TIMEOUT_MS + 5_000L)
                markSensorInputUnavailable("Waiting for strap input")
                _state.value = ConnectionState.Connected
                handler.removeCallbacks(firstPacketWatchdog)
                handler.postDelayed(firstPacketWatchdog, FIRST_PACKET_TIMEOUT_MS)
                // Only one GATT operation may be pending: discover services once the MTU exchange finishes.
                if (!gatt.requestMtu(247)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = linkEstablished
                linkEstablished = false
                if (!wasConnected) connectFailures += 1
                if (wasConnected) {
                    DiagnosticsStore.breadcrumb(context, "ble", "Strap link lost (GATT status $status)")
                } else if (ReconnectPolicy.shouldLogFailure(connectFailures)) {
                    DiagnosticsStore.breadcrumb(context, "ble", "Connect attempt $connectFailures failed (GATT status $status)")
                }
                _disconnects.tryEmit(StrapDisconnect(System.currentTimeMillis(), status, wasConnected))
                markSensorInputUnavailable("Disconnected")
                _state.value = ConnectionState.Disconnected
                resetLinkState()
                handler.removeCallbacks(firstPacketWatchdog)
                staleJob?.cancel()
                reconnectLater()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (isStale(gatt)) return
            if (!subscribed) gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (isStale(gatt)) return
            val characteristic = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                _state.value = ConnectionState.Error
                restartLinkAfterSetupFailure("Service discovery failed ($status)")
                return
            }
            if (subscribed) return
            gatt.setCharacteristicNotification(characteristic, true)
            characteristic.getDescriptor(CCCD)?.let { descriptor ->
                setupStage = SetupStage.HrMeasurementCccd
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
            subscribed = true
            _state.value = ConnectionState.Connected
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (isStale(gatt)) return
            if (descriptor.characteristic?.uuid == BATTERY_LEVEL) {
                // Battery notifications are optional; a failure here must not restart the ECG link.
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    DiagnosticsStore.breadcrumb(context, "ble", "Battery notifications unavailable (status $status); reading every 10 min instead")
                }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                    ecg = PmdStreamState.Failed,
                    accelerometer = if (accelerometerEnabled) PmdStreamState.Failed else PmdStreamState.Unavailable,
                    ecgMessage = "Descriptor write failed ($status)",
                    accelerometerMessage = if (accelerometerEnabled) "Descriptor write failed ($status)" else "Power Saver"
                )
                setupStage = SetupStage.Idle
                restartLinkAfterSetupFailure("Descriptor write failed ($status)")
                return
            }
            when (setupStage) {
                SetupStage.HrMeasurementCccd -> enablePolarEcg(gatt)
                SetupStage.PmdDataCccd -> enablePmdControlIndications(gatt)
                SetupStage.PmdControlCccd -> startEcgStream(gatt)
                SetupStage.Idle -> Unit
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (isStale(gatt)) return
            if (characteristic.uuid != PMD_CONTROL || status == BluetoothGatt.GATT_SUCCESS) return
            // Only restart while ECG isn't running yet; a failed motion command shouldn't cut a live ECG stream.
            if (_pmdStreamStatus.value.ecg != PmdStreamState.Streaming) restartLinkAfterSetupFailure("PMD command failed ($status)")
            _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                ecg = PmdStreamState.Failed,
                accelerometer = if (accelerometerEnabled) PmdStreamState.Failed else PmdStreamState.Unavailable,
                ecgMessage = "PMD command failed ($status)",
                accelerometerMessage = if (accelerometerEnabled) "PMD command failed ($status)" else "Power Saver"
            )
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (isStale(gatt)) return
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        // On Android 13+ the framework calls the 3-argument overloads above/below; the old ones must not
        // also handle the same data there (that would process every packet twice).
        @Deprecated("Deprecated by Android")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (isStale(gatt)) return
            handleCharacteristicRead(gatt, characteristic, characteristic.value ?: return, status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (isStale(gatt)) return
            handleCharacteristic(gatt.device, characteristic.uuid, value)
        }

        @Deprecated("Deprecated by Android")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (isStale(gatt)) return
            // Copy: on older Android the characteristic's value buffer can be overwritten by the next packet.
            handleCharacteristic(gatt.device, characteristic.uuid, characteristic.value?.copyOf() ?: return)
        }
    }

    /**
     * Reads the strap battery: 5 s after ECG starts on every (re)connection, then every 10 minutes, so
     * low-battery alerts can fire mid-session. Skipped while motion setup is still writing to the strap
     * (only one GATT operation may be in flight) and retried shortly after.
     */
    @SuppressLint("MissingPermission")
    private fun readBattery(gatt: BluetoothGatt) {
        if (!hasBluetoothPermission()) return
        val battery = gatt.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL) ?: return
        val now = System.currentTimeMillis()
        if (setupStage != SetupStage.Idle || _pmdStreamStatus.value.accelerometer == PmdStreamState.Configuring) {
            lastBatteryReadAttemptMs = now - BATTERY_REFRESH_MS + BATTERY_BUSY_RETRY_MS
            return
        }
        lastBatteryReadAttemptMs = now
        val started = runCatching { gatt.readCharacteristic(battery) }.getOrDefault(false)
        if (!started) lastBatteryReadAttemptMs = now - BATTERY_REFRESH_MS + BATTERY_FAILED_RETRY_MS
    }

    @SuppressLint("MissingPermission")
    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS || characteristic.uuid != BATTERY_LEVEL || value.isEmpty()) return
        updateBattery(value)
        // Also ask the strap to push changes, when it supports that (once per link).
        if (!batteryNotifyRequested &&
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 &&
            setupStage == SetupStage.Idle &&
            _pmdStreamStatus.value.accelerometer != PmdStreamState.Configuring
        ) {
            batteryNotifyRequested = true
            runCatching {
                gatt.setCharacteristicNotification(characteristic, true)
                characteristic.getDescriptor(CCCD)?.let { descriptor ->
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }
    }

    private fun updateBattery(value: ByteArray) {
        if (value.isEmpty()) return
        val percent = value[0].toInt() and 0xFF
        if (percent > 100) return
        _batteryPercent.value = percent
        if (percent != lastLoggedBatteryPercent) {
            lastLoggedBatteryPercent = percent
            DiagnosticsStore.breadcrumb(context, "battery", "Strap battery $percent%")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enablePolarEcg(gatt: BluetoothGatt) {
        val pmd = gatt.getService(PMD_SERVICE) ?: return
        val data = pmd.getCharacteristic(PMD_DATA)
        val control = pmd.getCharacteristic(PMD_CONTROL)
        pmdControlCharacteristic = control
        if (data != null) {
            gatt.setCharacteristicNotification(data, true)
            data.getDescriptor(CCCD)?.let { descriptor ->
                setupStage = SetupStage.PmdDataCccd
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }
        if (control != null) {
            _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                ecg = PmdStreamState.Configuring,
                accelerometer = if (accelerometerEnabled) PmdStreamState.Configuring else PmdStreamState.Unavailable,
                accelerometerMessage = if (accelerometerEnabled) null else "Power Saver"
            )
        } else {
            _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                ecg = PmdStreamState.Unavailable,
                accelerometer = PmdStreamState.Unavailable,
                ecgMessage = "PMD control unavailable",
                accelerometerMessage = "PMD control unavailable"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun enablePmdControlIndications(gatt: BluetoothGatt) {
        val control = pmdControlCharacteristic ?: return
        gatt.setCharacteristicNotification(control, true)
        control.getDescriptor(CCCD)?.let { descriptor ->
            setupStage = SetupStage.PmdControlCccd
            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startEcgStream(gatt: BluetoothGatt) {
        val control = pmdControlCharacteristic ?: return
        setupStage = SetupStage.Idle
        runCatching {
            pendingPmdCommand = 0x02
            control.value = START_ECG_STREAM
            gatt.writeCharacteristic(control)
        }.onFailure {
            _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                ecg = PmdStreamState.Failed,
                ecgMessage = it.message
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun queryAccelerometerSettings(gatt: BluetoothGatt, control: BluetoothGattCharacteristic) {
        runCatching {
            pendingPmdCommand = 0x01
            control.value = PolarPmdControl.querySettings(PmdMeasurement.Accelerometer)
            gatt.writeCharacteristic(control)
        }.onFailure {
            _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                accelerometer = PmdStreamState.Failed,
                accelerometerMessage = it.message
            )
        }
    }

    private fun handleCharacteristic(device: BluetoothDevice, uuid: UUID, value: ByteArray) {
        when (uuid) {
            PMD_DATA -> handlePmdData(value)
            PMD_CONTROL -> handlePmdControl(value)
            HR_MEASUREMENT -> handlePacket(device, value)
            BATTERY_LEVEL -> updateBattery(value)
            else -> return
        }
    }

    /** A callback from a GATT client we already replaced or closed: it must never feed data or drive the link. */
    private fun isStale(callbackGatt: BluetoothGatt): Boolean = callbackGatt !== gatt

    /**
     * True when [value] repeats the previous heart-rate packet byte for byte within 250 ms: the same
     * notification delivered twice (real packets arrive about once a second and carry new RR intervals).
     */
    @Synchronized
    private fun isDuplicateHrPacket(value: ByteArray, nowMs: Long): Boolean {
        val previous = lastHrPacket
        val duplicate = previous != null && nowMs - lastHrPacketAtMs in 0..HR_DUPLICATE_WINDOW_MS && previous.contentEquals(value)
        if (!duplicate) {
            lastHrPacket = value.copyOf()
            lastHrPacketAtMs = nowMs
        }
        return duplicate
    }

    /** True when a PMD frame is byte-identical to the previous frame of the same stream (it carries the strap timestamp). */
    @Synchronized
    private fun isDuplicatePmdFrame(value: ByteArray, ecg: Boolean): Boolean {
        val previous = if (ecg) lastEcgFrame else lastAccFrame
        if (previous != null && previous.contentEquals(value)) return true
        if (ecg) lastEcgFrame = value.copyOf() else lastAccFrame = value.copyOf()
        return false
    }

    /** Counts a dropped repeat and logs a breadcrumb at most every 30 s. */
    @Synchronized
    private fun countDuplicate(counter: AtomicInteger) {
        counter.incrementAndGet()
        duplicatesSinceLog += 1
        val now = System.currentTimeMillis()
        if (now - lastDuplicateLogMs >= DUPLICATE_LOG_INTERVAL_MS) {
            lastDuplicateLogMs = now
            DiagnosticsStore.breadcrumb(
                context,
                "ble",
                "Dropped $duplicatesSinceLog repeated notification(s) (totals: HR ${duplicateHr.get()}, ECG ${duplicateEcg.get()}, motion ${duplicateAcc.get()})"
            )
            duplicatesSinceLog = 0
        }
    }

    /** Repeated notifications dropped since the last [resetDuplicateCounts]. */
    fun duplicateCounts(): DuplicateFrameCounts =
        DuplicateFrameCounts(heartRate = duplicateHr.get(), ecg = duplicateEcg.get(), accelerometer = duplicateAcc.get())

    fun resetDuplicateCounts() {
        duplicateHr.set(0)
        duplicateEcg.set(0)
        duplicateAcc.set(0)
    }

    @SuppressLint("MissingPermission")
    private fun handlePacket(device: BluetoothDevice, value: ByteArray) {
        if (isDuplicateHrPacket(value, System.currentTimeMillis())) {
            countDuplicate(duplicateHr)
            return
        }
        val packet = runCatching { HeartRatePacketParser.parse(value) }.getOrNull() ?: return
        if (packet.heartRateBpm !in 25..240) return
        val now = System.currentTimeMillis()
        if (packet.contactDetected == false) {
            markSensorInputUnavailable("Strap contact lost")
        } else {
            sensorInputAccepted = true
        }
        _state.value = ConnectionState.Connected
        emitOrCountDrop(STREAM_HR, _readings.tryEmit(
            HrReading(
                deviceName = device.name ?: "Polar H10",
                deviceAddress = device.address,
                timestampMs = now,
                heartRateBpm = packet.heartRateBpm,
                rrIntervalsMs = packet.rrIntervalsMs,
                contactDetected = packet.contactDetected,
                connectionState = ConnectionState.Connected
            )
        ))
        sendCompanionUdp("PPHR1|HR|${packet.heartRateBpm}|${packet.rrIntervalsMs.lastOrNull() ?: -1}")
        runCatching {
            context.sendBroadcast(
                Intent("com.pulsepointlabs.polarwiz.action.SARAHVS_HR_SAMPLE").apply {
                    setPackage("com.pulsepointlabs.polarwiz")
                    putExtra("bpm", packet.heartRateBpm)
                    putExtra("rr_ms", packet.rrIntervalsMs.lastOrNull() ?: -1)
                },
                "com.pulsepointlabs.polarwiz.permission.HR_FEED"
            )
        }
        handler.removeCallbacks(firstPacketWatchdog)
        releaseReconnectWakeLock()
        staleJob?.cancel()
        staleJob = scope.launch {
            delay(5000)
            if (_state.value == ConnectionState.Connected) {
                markSensorInputUnavailable("No live heart-rate input")
                _state.value = ConnectionState.Stale
            }
        }
    }

    private fun handlePmdData(value: ByteArray) {
        val measurement = runCatching { PolarPmdParser.parseFrame(value).measurement }.getOrNull() ?: return
        when (measurement) {
            PmdMeasurement.Ecg -> if (isDuplicatePmdFrame(value, ecg = true)) countDuplicate(duplicateEcg) else handleEcg(value)
            PmdMeasurement.Accelerometer -> if (isDuplicatePmdFrame(value, ecg = false)) countDuplicate(duplicateAcc) else handleAccelerometer(value)
        }
    }

    @Synchronized
    private fun handleEcg(value: ByteArray) {
        val result = runCatching { PolarPmdParser.parseEcg(value, ecgParserState) }
            .onFailure {
                _pmdStreamStatus.value = _pmdStreamStatus.value.copy(ecg = PmdStreamState.Failed, ecgMessage = it.message)
            }
        val parsed = result.getOrDefault(emptyList())
        if (parsed.isEmpty()) {
            // Parsed fine but nothing new: the parser dropped a repeated or older frame.
            if (result.isSuccess) countDuplicate(duplicateEcg)
            return
        }
        sensorInputAccepted = true
        // Feed every sample: stopping at the first beat skipped the rest of the frame, missing a second
        // beat in it and putting the detector's sample clock out of step.
        val beatDetected = parsed.map { companionRPeakDetector.add(it.microvolts) }.any { it }
        if (beatDetected) {
            sendCompanionUdp("PPHR1|BEAT")
            runCatching {
                context.sendBroadcast(
                    Intent("com.pulsepointlabs.polarwiz.action.SARAHVS_HR_SAMPLE").apply {
                        setPackage("com.pulsepointlabs.polarwiz")
                        putExtra("r_peak", true)
                    },
                    "com.pulsepointlabs.polarwiz.permission.HR_FEED"
                )
            }
        }
        setupFailures = 0
        _pmdStreamStatus.value = _pmdStreamStatus.value.copy(ecg = PmdStreamState.Streaming, ecgMessage = null)
        emitOrCountDrop(STREAM_ECG, _ecgBatches.tryEmit(parsed))
        ecgBuffer.addAll(parsed)
        while (ecgBuffer.size > 900) ecgBuffer.removeFirst()
        val now = System.currentTimeMillis()
        if (now - lastEcgDisplayUpdateMs >= DISPLAY_UPDATE_MS) {
            lastEcgDisplayUpdateMs = now
            _ecgSamples.value = ecgBuffer.toList()
        }
        val activeGatt = gatt
        if (activeGatt != null) {
            if (!batteryReadScheduledAfterEcg) {
                // First read on every (re)connection, once ECG is flowing.
                batteryReadScheduledAfterEcg = true
                lastBatteryReadAttemptMs = now
                handler.postDelayed({
                    if (this.gatt === activeGatt) readBattery(activeGatt)
                }, 5000L)
            } else if (now - lastBatteryReadAttemptMs >= BATTERY_REFRESH_MS) {
                lastBatteryReadAttemptMs = now
                handler.post { if (this.gatt === activeGatt) readBattery(activeGatt) }
            }
        }
    }

    private fun sendCompanionUdp(message: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = message.toByteArray(Charsets.UTF_8)
                DatagramSocket().use { socket ->
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), COMPANION_PORT))
                }
            }
        }
    }

    @Synchronized
    private fun handleAccelerometer(value: ByteArray) {
        if (!accelerometerEnabled) return
        val result = runCatching { PolarPmdParser.parseAccelerometer(value, accelerometerParserState) }
            .onFailure {
                _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                    accelerometer = PmdStreamState.Failed,
                    accelerometerMessage = it.message
                )
            }
        val parsed = result.getOrDefault(emptyList())
        if (parsed.isEmpty()) {
            if (result.isSuccess) countDuplicate(duplicateAcc)
            return
        }
        _pmdStreamStatus.value = _pmdStreamStatus.value.copy(accelerometer = PmdStreamState.Streaming, accelerometerMessage = null)
        emitOrCountDrop(STREAM_ACC, _accelerometerBatches.tryEmit(parsed))
        accelBuffer.addAll(parsed)
        while (accelBuffer.size > 300) accelBuffer.removeFirst()
        val now = System.currentTimeMillis()
        if (now - lastAccelerometerDisplayUpdateMs >= DISPLAY_UPDATE_MS) {
            lastAccelerometerDisplayUpdateMs = now
            _accelerometerSamples.value = accelBuffer.toList()
        }
    }

    /** Dropped batches per stream since the last [resetDroppedBatchCounts]. */
    fun droppedBatchCounts(): DroppedBatchCounts =
        DroppedBatchCounts(heartRate = droppedHr.get(), ecg = droppedEcg.get(), accelerometer = droppedAcc.get())

    fun resetDroppedBatchCounts() {
        droppedHr.set(0)
        droppedEcg.set(0)
        droppedAcc.set(0)
    }

    /** Batches the recorder could not take in time are lost; log them so a session's missing data is explained. */
    private fun emitOrCountDrop(stream: String, emitted: Boolean) {
        if (emitted) return
        when (stream) {
            STREAM_HR -> droppedHr.incrementAndGet()
            STREAM_ECG -> droppedEcg.incrementAndGet()
            else -> droppedAcc.incrementAndGet()
        }
        droppedBatches += 1
        val now = System.currentTimeMillis()
        if (now - lastDropLogMs >= DROP_LOG_INTERVAL_MS) {
            lastDropLogMs = now
            DiagnosticsStore.breadcrumb(context, "ble", "Phone busy: dropped $stream data ($droppedBatches batches so far)")
        }
    }

    @Synchronized
    private fun markSensorInputUnavailable(message: String) {
        sensorInputAccepted = false
        companionRPeakDetector.reset()
        ecgParserState.resetClock()
        accelerometerParserState.resetClock()
        ecgBuffer.clear()
        accelBuffer.clear()
        _ecgSamples.value = emptyList()
        _accelerometerSamples.value = emptyList()
        _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
            ecgMessage = message,
            accelerometerMessage = message
        )
    }

    @SuppressLint("MissingPermission")
    private fun handlePmdControl(value: ByteArray) {
        val response = runCatching { PmdControlResponse.parse(value) }.getOrNull() ?: return
        if (!response.success) {
            if (!accelerometerEnabled && response.measurement == PmdMeasurement.Accelerometer.id) return
            if (response.measurement == PmdMeasurement.Ecg.id) {
                _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                    ecg = PmdStreamState.Failed,
                    ecgMessage = "PMD status ${response.status}"
                )
            }
            if (response.measurement == PmdMeasurement.Accelerometer.id) {
                _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                    accelerometer = PmdStreamState.Unsupported,
                    accelerometerMessage = "PMD status ${response.status}"
                )
            }
            return
        }
        if (!accelerometerEnabled && response.measurement == PmdMeasurement.Accelerometer.id) return
        if (response.command == 0x02 && response.measurement == PmdMeasurement.Ecg.id) {
            _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                ecg = PmdStreamState.Configuring,
                ecgMessage = "Awaiting ECG samples"
            )
            if (accelerometerEnabled) {
                val control = pmdControlCharacteristic ?: return
                val activeGatt = gatt ?: return
                queryAccelerometerSettings(activeGatt, control)
            }
        } else if (response.command == 0x01 && response.measurement == PmdMeasurement.Accelerometer.id) {
            val settings = runCatching { PmdSettings.parse(response.parameters) }.getOrNull()
            if (settings == null) {
                _pmdStreamStatus.value = _pmdStreamStatus.value.copy(
                    accelerometer = PmdStreamState.Failed,
                    accelerometerMessage = "ACC settings parse failed"
                )
                return
            }
            val selected = settings.select(preferredSampleRate = 25, preferredRange = 2)
            selectedAccelerometerRateHz = selected[PmdSettingType.SampleRate] ?: 25
            accelerometerParserState.sampleRateHz = selectedAccelerometerRateHz
            accelerometerParserState.accelerometerFactor = settings.available[PmdSettingType.Factor]?.firstOrNull()?.let(Float::fromBits)
            val control = pmdControlCharacteristic ?: return
            val activeGatt = gatt ?: return
            runCatching {
                pendingPmdCommand = 0x02
                control.value = PolarPmdControl.startMeasurement(PmdMeasurement.Accelerometer, selected)
                activeGatt.writeCharacteristic(control)
            }.onFailure {
                _pmdStreamStatus.value = _pmdStreamStatus.value.copy(accelerometer = PmdStreamState.Failed, accelerometerMessage = it.message)
            }
        } else if (response.command == 0x02 && response.measurement == PmdMeasurement.Accelerometer.id) {
            _pmdStreamStatus.value = _pmdStreamStatus.value.copy(accelerometer = PmdStreamState.Streaming, accelerometerMessage = null)
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun rememberDevice(device: BluetoothDevice) {
        prefs.edit()
            .putString(KEY_ADDRESS, device.address)
            .putString(KEY_NAME, device.name ?: "Polar H10")
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
        private const val COMPANION_PORT = 48_511
        private const val DROP_LOG_INTERVAL_MS = 30_000L
        private const val FIRST_PACKET_TIMEOUT_MS = 20_000L
        // A direct connect attempt times out after ~30 s; keep the CPU awake a little longer than that.
        private const val DIRECT_ATTEMPT_WAKE_MS = 40_000L
        private const val MAX_WAKE_LOCK_MS = 2 * 60_000L
        private const val BATTERY_REFRESH_MS = 10 * 60_000L
        private const val BATTERY_BUSY_RETRY_MS = 5_000L
        private const val BATTERY_FAILED_RETRY_MS = 30_000L
        private const val STREAM_BUFFER_BATCHES = 256
        private const val HR_DUPLICATE_WINDOW_MS = 250L
        private const val DUPLICATE_LOG_INTERVAL_MS = 30_000L
        private const val STREAM_HR = "heart rate"
        private const val STREAM_ECG = "ECG"
        private const val STREAM_ACC = "motion"
        private const val PREFS = "sarahmonitor_hr_device"
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val PMD_SERVICE: UUID = UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8")
        val PMD_CONTROL: UUID = UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8")
        val PMD_DATA: UUID = UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val START_ECG_STREAM = byteArrayOf(0x02, 0x00, 0x00, 0x01, 0x82.toByte(), 0x00, 0x01, 0x01, 0x0E, 0x00)
        private const val DISPLAY_UPDATE_MS = 250L
    }
}
