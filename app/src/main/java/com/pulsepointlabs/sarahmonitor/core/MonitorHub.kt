package com.pulsepointlabs.sarahmonitor.core

import android.content.Context
import android.util.Log
import com.pulsepointlabs.sarahmonitor.ble.BluetoothHrClient
import com.pulsepointlabs.sarahmonitor.ble.CivetClient
import com.pulsepointlabs.sarahmonitor.ble.OmronBleClient
import com.pulsepointlabs.sarahmonitor.session.MarkerPreferences
import com.pulsepointlabs.sarahmonitor.session.SessionEntity
import com.pulsepointlabs.sarahmonitor.session.SessionRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object DiagnosticsStore {
    fun breadcrumb(context: Context, category: String, message: String) {
        Log.i("SarahMonitor", "[$category] $message")
    }
}

/**
 * Owns every sensor link and the live processing. The activity, the foreground service and the floating
 * overlays all read the same flows, so a reading shows up everywhere at once.
 */
object MonitorHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var initialized = false

    lateinit var h10: BluetoothHrClient; private set
    lateinit var civet: CivetClient; private set
    lateinit var omron: OmronBleClient; private set
    lateinit var bp: BpStore; private set
    lateinit var glucose: GlucoseStore; private set
    lateinit var recorder: SessionRecorder; private set

    /** The session being recorded, or null. */
    val session: StateFlow<SessionEntity?> get() = recorder.active

    private val heartEngine = HeartEngine()
    private val pelvicProcessor = PelvicProcessor()
    private val trendRecorder = TrendRecorder()

    private val _heart = MutableStateFlow(HeartSnapshot())
    val heart: StateFlow<HeartSnapshot> = _heart

    private val _pelvic = MutableStateFlow(PelvicSnapshot())
    val pelvic: StateFlow<PelvicSnapshot> = _pelvic

    private val _trends = MutableStateFlow<List<TrendPoint>>(emptyList())
    val trends: StateFlow<List<TrendPoint>> = _trends

    /** Wall-clock time of the last BP reading that arrived live from the cuff (for the "new" highlight). */
    private val _lastLiveBpAt = MutableStateFlow<Long?>(null)
    val lastLiveBpAt: StateFlow<Long?> = _lastLiveBpAt

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        h10 = BluetoothHrClient(app)
        civet = CivetClient(app)
        omron = OmronBleClient(app)
        bp = BpStore(app)
        glucose = GlucoseStore(app)
        recorder = SessionRecorder(app, scope)

        scope.launch {
            h10.readings.collect { reading ->
                val snapshot = heartEngine.add(reading)
                trendRecorder.addHeart(reading.timestampMs, snapshot)
                _heart.value = snapshot
                recorder.onHeart(snapshot, heartEngine.lastRr)
            }
        }
        scope.launch {
            h10.accelerometerBatches.collect { heartEngine.addAccelerometer(it) }
        }
        scope.launch {
            h10.ecgBatches.collect { recorder.onEcg(it) }
        }
        scope.launch {
            var lastPublish = 0L
            civet.samples.collect { sample ->
                val snapshot = pelvicProcessor.ingest(sample.timestampMs, sample.pressureKpa)
                trendRecorder.addPelvic(sample.timestampMs, snapshot.aboveRestKpa)
                recorder.onPelvic(sample.timestampMs, sample.pressureKpa, snapshot.aboveRestKpa, snapshot.holding)
                // Pressure can arrive faster than the screen refreshes; publish at most every 33 ms.
                if (sample.monotonicMs - lastPublish >= 33L || snapshot.capture != null) {
                    lastPublish = sample.monotonicMs
                    _pelvic.value = snapshot
                }
            }
        }
        scope.launch {
            omron.readings.collect { reading ->
                val added = bp.addAll(
                    listOf(
                        BpEntry(
                            timestampMs = reading.timestampMs,
                            systolic = reading.systolic,
                            diastolic = reading.diastolic,
                            pulse = reading.pulse,
                            irregular = reading.irregular,
                            source = reading.deviceName
                        )
                    )
                )
                if (added > 0) _lastLiveBpAt.value = System.currentTimeMillis()
            }
        }
        scope.launch {
            while (isActive) {
                _trends.value = trendRecorder.snapshot()
                delay(5_000L)
            }
        }
    }

    /** Reconnects every remembered sensor. Safe to call repeatedly (launch, resume, service start). */
    fun connectRemembered() {
        if (!initialized) return
        h10.connectRemembered()
        civet.connectRemembered()
        omron.armRemembered()
    }

    fun startPelvicCapture(kind: CaptureKind): String? = pelvicProcessor.startCapture(kind, System.currentTimeMillis())

    fun clearPelvicCalibration() = pelvicProcessor.clearCalibration()

    /** Starts recording a session; live statistics and trends restart with it. */
    fun startSession() {
        scope.launch {
            if (recorder.active.value == null) resetSession()
            recorder.start()
        }
    }

    fun stopSession(onStopped: (SessionEntity?) -> Unit = {}) {
        scope.launch { onStopped(recorder.stop()) }
    }

    /** Marks an event at [timestampMs] (the moment the button was pressed). Returns false if not recording. */
    fun markEvent(timestampMs: Long, types: Set<String>, note: String, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            val marker = recorder.addMarker(timestampMs, types, note)
            if (marker != null) MarkerPreferences.remember(appContext, types, note)
            onDone(marker != null)
        }
    }

    private lateinit var appContext: Context

    /** Starts a fresh monitoring period: clears HR/HRV/Load stats, pelvic counts and the trend graphs. */
    fun resetSession() {
        heartEngine.reset()
        pelvicProcessor.resetStats()
        trendRecorder.clear()
        _heart.value = HeartSnapshot()
        _trends.value = emptyList()
    }
}
