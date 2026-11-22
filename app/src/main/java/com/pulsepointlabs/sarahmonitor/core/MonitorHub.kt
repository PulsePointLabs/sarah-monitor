package com.pulsepointlabs.sarahmonitor.core

import android.content.Context
import android.util.Log
import com.pulsepointlabs.sarahmonitor.audio.AudioEngine
import com.pulsepointlabs.sarahmonitor.ble.BluetoothHrClient
import com.pulsepointlabs.sarahmonitor.ble.ConnectionState
import com.pulsepointlabs.sarahmonitor.response.ArousalEngine
import com.pulsepointlabs.sarahmonitor.response.ResponseSettings
import com.pulsepointlabs.sarahmonitor.response.ResponseState
import com.pulsepointlabs.sarahmonitor.response.SarahCoach
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
    lateinit var spo2: com.pulsepointlabs.sarahmonitor.vitals.SpO2Store; private set
    lateinit var body: com.pulsepointlabs.sarahmonitor.vitals.BodyStore; private set
    /** Etekcity ESF-551 direct reads. */
    lateinit var scale: com.pulsepointlabs.sarahmonitor.vitals.ScaleClient; private set
    /** Heartbeat beep, alert tones and Sarah's spoken alerts. */
    lateinit var audio: AudioEngine; private set

    /** The session being recorded, or null. */
    val session: StateFlow<SessionEntity?> get() = recorder.active

    private lateinit var heartEngine: HeartEngine
    private val pelvicProcessor = PelvicProcessor()
    private val trendRecorder = TrendRecorder()

    private val arousal = ArousalEngine()
    private val _response = MutableStateFlow(ResponseState())
    /** Live sexual-response read: phase, arousal, approach to climax. */
    val response: StateFlow<ResponseState> = _response
    /** Sarah's live spoken feedback (Claude brain, Sarah's voice). */
    lateinit var coach: SarahCoach; private set
    lateinit var stressVoice: StressVoice; private set

    val emgProcessor = com.pulsepointlabs.sarahmonitor.emg.EmgProcessor()
    /** Arduino + MyoWare EMG over USB. */
    lateinit var emgClient: com.pulsepointlabs.sarahmonitor.emg.EmgClient; private set
    private val _emg = MutableStateFlow(com.pulsepointlabs.sarahmonitor.emg.EmgSnapshot())
    val emg: StateFlow<com.pulsepointlabs.sarahmonitor.emg.EmgSnapshot> = _emg

    private val sleepEngine = com.pulsepointlabs.sarahmonitor.sleep.SleepEngine()
    lateinit var sleepStore: com.pulsepointlabs.sarahmonitor.sleep.SleepStore; private set
    private val _sleeping = MutableStateFlow<Long?>(null)
    /** When sleep mode started, or null. */
    val sleeping: StateFlow<Long?> = _sleeping
    private val _sleepLive = MutableStateFlow<List<com.pulsepointlabs.sarahmonitor.sleep.SleepEpoch>>(emptyList())
    /** Tonight's epochs so far (staged on the fly). */
    val sleepLive: StateFlow<List<com.pulsepointlabs.sarahmonitor.sleep.SleepEpoch>> = _sleepLive

    private val stressTracker = StressTracker()
    private val _stress = MutableStateFlow(StressReading())
    /** Estimated overall stress (HRV, unexplained HR, breathing, tension, restlessness vs your resting baseline). */
    val stress: StateFlow<StressReading> = _stress
    @Volatile private var restingBaseline: com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressBaseline? = null

    private val _heart = MutableStateFlow(HeartSnapshot())
    val heart: StateFlow<HeartSnapshot> = _heart

    private val _pelvic = MutableStateFlow(PelvicSnapshot())
    val pelvic: StateFlow<PelvicSnapshot> = _pelvic

    private val recentRecorder = RecentHistoryRecorder()
    private val _recent = MutableStateFlow(RecentHistory())
    /** Last 2 minutes of each metric, for trend lines and trend colours. */
    val recent: StateFlow<RecentHistory> = _recent

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
        spo2 = com.pulsepointlabs.sarahmonitor.vitals.SpO2Store(app)
        body = com.pulsepointlabs.sarahmonitor.vitals.BodyStore(app)
        com.pulsepointlabs.sarahmonitor.vitals.BodyProfile.load(app)
        scale = com.pulsepointlabs.sarahmonitor.vitals.ScaleClient(app) { body.addAll(listOf(it)) }
        recorder = SessionRecorder(app, scope)
        val baselineStore = LoadBaselineStore(app)
        restingBaseline = baselineStore.load()
        heartEngine = HeartEngine(restingBaseline) { b -> restingBaseline = b; baselineStore.save(b) }
        audio = AudioEngine(app) { recorder.active.value != null }
        BpPosition.load(app)
        com.pulsepointlabs.sarahmonitor.sleep.PositionCalibration.load(app)
        sleepStore = com.pulsepointlabs.sarahmonitor.sleep.SleepStore(app)
        com.pulsepointlabs.sarahmonitor.emg.EmgSettings.load(app, emgProcessor)
        StressVoiceSettings.load(app)
        var emgAccN = 0; var emgAcc1 = 0.0; var emgAcc2 = 0.0; var emgLastPublish = 0L
        emgClient = com.pulsepointlabs.sarahmonitor.emg.EmgClient(app) { now, rows ->
            rows.forEachIndexed { i, v ->
                // Lines in one USB read arrived 10 ms apart (100 Hz sketch).
                val t = now - (rows.size - 1 - i) * 10L
                emgProcessor.add(t, v)
                emgAcc1 += v[0]; emgAcc2 += v.getOrElse(1) { 0 }; emgAccN++
                if (emgAccN == 4) { // 25 Hz averages into the recording
                    val snap = emgProcessor.snapshot(t)
                    recorder.onEmg(com.pulsepointlabs.sarahmonitor.session.EmgSampleEntity(
                        sessionId = "", timestampMs = t, ch1 = emgAcc1 / 4, ch2 = if (v.size > 1) emgAcc2 / 4 else null,
                        pct1 = snap.channels.getOrNull(0)?.pct, pct2 = snap.channels.getOrNull(1)?.pct
                    ))
                    emgAcc1 = 0.0; emgAcc2 = 0.0; emgAccN = 0
                }
            }
            if (now - emgLastPublish >= 50L) {
                emgLastPublish = now
                val snap = emgProcessor.snapshot(now)
                _emg.value = snap
                arousal.onEmg(now, snap.peakPct)
            }
        }
        scope.launch {
            emgClient.connect()
            while (isActive) {
                delay(2_000L)
                emgClient.check()
                _emg.value = emgProcessor.snapshot()
            }
        }
        ResponseSettings.load(app)
        arousal.setProfile(ResponseSettings.profile.value)
        scope.launch { ResponseSettings.profile.collect { arousal.setProfile(it) } }
        coach = SarahCoach({ _response.value }, { recorder.active.value != null }, { audio.say(it) }, scope)
        coach.start()
        stressVoice = StressVoice({ audio.say(it) }, scope)
        audio.h10Connected = { h10.state.value == ConnectionState.Connected || h10.state.value == ConnectionState.Stale }

        scope.launch {
            h10.readings.collect { reading ->
                val snapshot = heartEngine.add(reading)
                trendRecorder.addHeart(reading.timestampMs, snapshot)
                _heart.value = snapshot
                audio.onHeart(snapshot, heartEngine.lastRr)
                sleepEngine.onHeart(reading.timestampMs, snapshot.heartRate, heartEngine.lastRr.filter { it.accepted }.mapNotNull { it.cleanIntervalMs })
                arousal.onRr(heartEngine.lastRr.filter { it.accepted }.mapNotNull { it.cleanIntervalMs })
                _response.value = arousal.onHeart(reading.timestampMs, snapshot.heartRate, snapshot.rmssdMs, snapshot.load.score)
                val resp = _response.value
                // Arousal and pelvic/EMG tension go together in sexual sessions; don't count them as stress there.
                val category = com.pulsepointlabs.sarahmonitor.session.SessionCategory.of(recorder.active.value?.category)
                // Kegel sets squeeze on purpose: that's training, not tension.
                val sexual = category != null && ((category.sexual && category != com.pulsepointlabs.sarahmonitor.session.SessionCategory.Hybrid) || category == com.pulsepointlabs.sarahmonitor.session.SessionCategory.Kegel)
                val st = stressTracker.update(reading.timestampMs, StressInputs(
                    hr = snapshot.heartRate, rmssd = snapshot.rmssdMs,
                    restingHr = restingBaseline?.hr, restingRmssd = restingBaseline?.rmssdMs,
                    breathsPerMin = resp.breathsPerMin, motionMg = resp.motionMg,
                    pelvicKpa = if (sexual) null else _pelvic.value.average30s,
                    emgPct = if (sexual) null else _emg.value.peakPct.takeIf { _emg.value.fresh }
                ))
                _stress.value = st
                stressVoice.onStress(reading.timestampMs, st, stressQuietReason(category, resp))
                recorder.onHeart(snapshot, heartEngine.lastRr, st.score, st.breathsPerMin)
                val t = reading.timestampMs
                recentRecorder.add(Metric.Hr, t, snapshot.heartRate?.toDouble())
                recentRecorder.add(Metric.Rmssd, t, snapshot.rmssdMs)
                recentRecorder.add(Metric.Sdnn, t, snapshot.sdnnMs)
                recentRecorder.add(Metric.Pnn50, t, snapshot.pnn50Percent)
                recentRecorder.add(Metric.Load, t, snapshot.load.score?.toDouble())
                recentRecorder.add(Metric.Stress, t, st.score?.toDouble())
                _recent.value = recentRecorder.snapshot()
            }
        }
        scope.launch {
            h10.accelerometerBatches.collect { batch ->
                heartEngine.addAccelerometer(batch)
                batch.forEach {
                    sleepEngine.onAcc(it.timestampMs, it.xMilliG, it.yMilliG, it.zMilliG)
                    arousal.onMotion(it.timestampMs, kotlin.math.abs(kotlin.math.sqrt(it.xMilliG.toDouble() * it.xMilliG + it.yMilliG.toDouble() * it.yMilliG + it.zMilliG.toDouble() * it.zMilliG) - 1000.0))
                }
            }
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
                audio.onPelvic(sample.timestampMs, snapshot)
                arousal.onPelvic(sample.timestampMs, snapshot.aboveRestKpa, snapshot.holding, snapshot.pulseCount, snapshot.lastPulse)
                // Pressure can arrive faster than the screen refreshes; publish at most every 33 ms.
                if (sample.monotonicMs - lastPublish >= 33L || snapshot.capture != null) {
                    lastPublish = sample.monotonicMs
                    _pelvic.value = snapshot
                    if (recentRecorder.add(Metric.Pelvic, sample.timestampMs, snapshot.aboveRestKpa)) _recent.value = recentRecorder.snapshot()
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
                            source = reading.deviceName,
                            site = BpPosition.defaultSite.value,
                            posture = BpPosition.defaultPosture.value
                        )
                    )
                )
                if (added > 0) _lastLiveBpAt.value = System.currentTimeMillis()
            }
        }
        // New BP and glucose readings (cuff, Health Connect, share-to-app, manual) go to the live alerts.
        scope.launch {
            var seen = bp.items.value.maxOfOrNull { it.timestampMs } ?: 0L
            bp.items.collect { list ->
                list.filter { it.timestampMs > seen && System.currentTimeMillis() - it.timestampMs < 15 * 60_000L }
                    .forEach { audio.onBp(it.timestampMs, it.systolic, it.diastolic, it.positionLabel?.lowercase()) }
                seen = maxOf(seen, list.maxOfOrNull { it.timestampMs } ?: 0L)
            }
        }
        scope.launch {
            var seen = glucose.items.value.maxOfOrNull { it.timestampMs } ?: 0L
            glucose.items.collect { list ->
                list.filter { it.timestampMs > seen && System.currentTimeMillis() - it.timestampMs < 15 * 60_000L }
                    .forEach { audio.onGlucose(it.timestampMs, it.mgDl) }
                seen = maxOf(seen, list.maxOfOrNull { it.timestampMs } ?: 0L)
            }
        }
        // A sensor that was connected and drops.
        listOf("Heart strap" to h10.state, "CIVET" to civet.state).forEach { (name, state) ->
            scope.launch {
                var wasConnected = false
                state.collect { st ->
                    if (wasConnected && st in setOf(ConnectionState.Disconnected, ConnectionState.Reconnecting, ConnectionState.Error)) audio.onDisconnected(name)
                    wasConnected = st == ConnectionState.Connected
                }
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
    /** Starts recording a [category] session; live statistics and trends restart with it. */
    fun startSession(category: com.pulsepointlabs.sarahmonitor.session.SessionCategory = com.pulsepointlabs.sarahmonitor.session.SessionCategory.last(appContext), title: String? = null) {
        if (category.pickable) com.pulsepointlabs.sarahmonitor.session.SessionCategory.remember(appContext, category)
        // The Response board follows the session type.
        when (category) {
            com.pulsepointlabs.sarahmonitor.session.SessionCategory.Masturbation -> ResponseSettings.setContext(com.pulsepointlabs.sarahmonitor.response.SessionContext.Masturbation)
            com.pulsepointlabs.sarahmonitor.session.SessionCategory.Sexual -> ResponseSettings.setContext(com.pulsepointlabs.sarahmonitor.response.SessionContext.Sexual)
            com.pulsepointlabs.sarahmonitor.session.SessionCategory.Exploration -> ResponseSettings.setContext(com.pulsepointlabs.sarahmonitor.response.SessionContext.Exploration)
            else -> Unit
        }
        scope.launch {
            if (recorder.active.value == null) resetSession()
            recorder.start(category, title)
        }
    }

    fun stopSession(onStopped: (SessionEntity?) -> Unit = {}) {
        scope.launch { onStopped(recorder.stop()) }
    }

    /** Marks an event at [timestampMs] (the moment the button was pressed). Returns false if not recording. */
    fun markEvent(timestampMs: Long, types: Set<String>, note: String, checkIn: com.pulsepointlabs.sarahmonitor.session.CheckIn = com.pulsepointlabs.sarahmonitor.session.CheckIn(), extra: Pair<String, org.json.JSONObject>? = null, onDone: (Boolean) -> Unit = {}) {
        // Marked climaxes teach the arousal engine this person's climax HR rise.
        if (recorder.active.value != null && ("orgasm" in types || "ejaculation" in types)) arousal.climaxSample()?.let { ResponseSettings.addClimax(it) }
        arousal.onMarker(timestampMs, types)
        _response.value = arousal.current()
        // Freeze the moment now (not when the save finishes).
        val snapshot = runCatching { EventSnapshot.capture(timestampMs).apply { extra?.let { put(it.first, it.second) } }.toString() }.getOrNull()
        scope.launch {
            val marker = recorder.addMarker(timestampMs, types, note, checkIn, snapshot)
            if (marker != null) {
                MarkerPreferences.remember(appContext, types, note)
                synchronized(recentEvents) {
                    recentEvents.addLast(RecentEvent(timestampMs, types, note, checkIn.label))
                    while (recentEvents.size > 5) recentEvents.removeFirst()
                }
            }
            onDone(marker != null)
        }
    }

    private lateinit var appContext: Context

    /**
     * Stress voice stays in its lane: never in masturbation / sexual activity / body exploration sessions, and in a
     * hybrid session it pauses while things look sexual (a sexual event in the last 20 min, or near climax).
     */
    private fun stressQuietReason(category: com.pulsepointlabs.sarahmonitor.session.SessionCategory?, resp: com.pulsepointlabs.sarahmonitor.response.ResponseState): String? {
        val rec = recorder.active.value != null
        if (!rec && StressVoiceSettings.onlyRecording.value) return "Waiting for a recording"
        return when (category) {
            null, com.pulsepointlabs.sarahmonitor.session.SessionCategory.General -> null
            com.pulsepointlabs.sarahmonitor.session.SessionCategory.Hybrid -> {
                val sexualEvent = recentEvents().any { e ->
                    System.currentTimeMillis() - e.timestampMs < 20 * 60_000L &&
                        com.pulsepointlabs.sarahmonitor.session.MarkerTypes.all.any { it.id in e.types && it.group == com.pulsepointlabs.sarahmonitor.session.MarkerTypes.Group.Sexual }
                }
                // Phase alone isn't enough (stress raises HR too), so only the climax-specific phases count.
                val nearClimax = resp.phase in setOf(com.pulsepointlabs.sarahmonitor.response.ResponsePhase.Approaching, com.pulsepointlabs.sarahmonitor.response.ResponsePhase.Climax, com.pulsepointlabs.sarahmonitor.response.ResponsePhase.BackingOff)
                if (sexualEvent || nearClimax) "Paused during sexual activity" else null
            }
            else -> "Quiet in ${category.label.lowercase()} sessions"
        }
    }

    /** Last few events this session, so Sarah can refer to what you just logged. */
    data class RecentEvent(val timestampMs: Long, val types: Set<String>, val note: String, val checkIn: String?)
    private val recentEvents = ArrayDeque<RecentEvent>()
    fun recentEvents(): List<RecentEvent> = synchronized(recentEvents) { recentEvents.toList() }

    /** Sleep mode: overnight epochs from the H10 (no full recording needed). */
    fun startSleep() {
        if (_sleeping.value != null) return
        val now = System.currentTimeMillis()
        sleepEngine.start(now)
        _sleeping.value = now
        _sleepLive.value = emptyList()
        scope.launch {
            while (_sleeping.value == now) {
                delay(30_000L)
                if (_sleeping.value == now) _sleepLive.value = com.pulsepointlabs.sarahmonitor.sleep.SleepMath.stage(sleepEngine.epochs())
            }
        }
    }

    /** Ends sleep mode, stages the night and saves it. Returns the summary (null if too short to score). */
    fun stopSleep(): com.pulsepointlabs.sarahmonitor.sleep.NightSummary? {
        if (_sleeping.value == null) return null
        val epochs = com.pulsepointlabs.sarahmonitor.sleep.SleepMath.stage(sleepEngine.stop())
        _sleeping.value = null
        _sleepLive.value = emptyList()
        if (epochs.size < 20) return null
        val summary = com.pulsepointlabs.sarahmonitor.sleep.SleepMath.summarize(epochs, System.currentTimeMillis(), spo2.items.value)
        sleepStore.save(summary, epochs)
        return summary
    }

    /** Latest strap orientation, for the position calibration taps. */
    fun currentGravity(): DoubleArray? = sleepEngine.lastGravity

    /** The user confirms a detected climax: marks it at the moment it was detected. */
    fun confirmClimax(onDone: (Boolean) -> Unit = {}) {
        val c = _response.value.candidate ?: return
        markEvent(c.timestampMs, setOf("orgasm"), "Climax signature confirmed: ${c.reason}", onDone = onDone)
    }

    fun dismissClimax() {
        arousal.dismissCandidate()
        _response.value = arousal.current().copy(candidate = null)
    }

    /** Starts a fresh monitoring period: clears HR/HRV/Load stats, pelvic counts and the trend graphs. */
    fun resetSession() {
        heartEngine.reset()
        audio.resetSession()
        arousal.reset()
        stressTracker.reset()
        synchronized(recentEvents) { recentEvents.clear() }
        if (::stressVoice.isInitialized) stressVoice.reset()
        _stress.value = StressReading()
        _response.value = ResponseState()
        pelvicProcessor.resetStats()
        trendRecorder.clear()
        recentRecorder.clear()
        _recent.value = RecentHistory()
        _heart.value = HeartSnapshot()
        _trends.value = emptyList()
    }
}
