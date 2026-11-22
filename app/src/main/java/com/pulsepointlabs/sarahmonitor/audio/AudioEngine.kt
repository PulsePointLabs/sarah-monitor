package com.pulsepointlabs.sarahmonitor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.pulsepointlabs.sarahmonitor.core.HeartSnapshot
import com.pulsepointlabs.sarahmonitor.core.PelvicSnapshot
import com.pulsepointlabs.sarahmonitor.hrv.HrvCalculator
import com.pulsepointlabs.sarahmonitor.hrv.RrSampleEntity
import com.pulsepointlabs.sarahmonitor.session.FlagSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * All of Sarah Monitor's sound: the heartbeat beep (following the real RR rhythm), alert tones and Sarah's spoken
 * alerts. Alerts queue instead of talking over each other, and the beeps duck while Sarah speaks.
 */
class AudioEngine(context: Context, private val isRecording: () -> Boolean) {
    private val app = context.applicationContext
    private val thread = HandlerThread("sarah-audio").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val beepAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(beepAttributes).build()
    private val beatIds = mutableMapOf<BeatSound, Int>()
    private val toneIds = mutableMapOf<AlertTone, Int>()
    private val cueIds = mutableMapOf<Boolean, Int>()
    private val loaded = mutableSetOf<Int>()
    private val beepFocus = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(beepAttributes)
            .setOnAudioFocusChangeListener({ }, handler)
            .build()
    } else null

    val voice = SarahVoice(app)
    val alerts = LiveAlertEngine {
        AlertLimits(AudioSettings.highHrLimit.value, AudioSettings.lowHrLimit.value, AudioSettings.longHoldSeconds.value)
    }
    private val scheduler = BeatScheduler()
    /** One voice at a time: alerts and Sarah's live feedback take turns. */
    private val speechLock = kotlinx.coroutines.sync.Mutex()
    private val queue = Channel<LiveAlert>(capacity = 6, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _lastAlert = MutableStateFlow<LiveAlert?>(null)
    /** Most recent alert that was delivered (for an on-screen banner). */
    val lastAlert: StateFlow<LiveAlert?> = _lastAlert
    private val _voiceProblem = MutableStateFlow<String?>(null)
    val voiceProblem: StateFlow<String?> = _voiceProblem

    @Volatile private var latestHr: Int? = null
    @Volatile private var lastHeartAt = 0L
    @Volatile private var contact: Boolean? = null
    @Volatile private var running = false
    private var beatCounter = 0L

    init {
        AudioSettings.load(app)
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        handler.post {
            val dir = File(app.filesDir, "sounds").apply { mkdirs() }
            BeatSound.entries.forEach { s ->
                val id = s.rawRes?.let { pool.load(app, it, 1) } ?: BeatSynth.render(s)?.let { pcm ->
                    val f = File(dir, "${s.name.lowercase()}-v1.wav")
                    if (!f.exists()) f.writeBytes(BeatSynth.wav(pcm))
                    pool.load(f.absolutePath, 1)
                }
                if (id != null) beatIds[s] = id
            }
            listOf(true, false).forEach { start ->
                val f = File(dir, "cue-${if (start) "listen" else "done"}-v1.wav")
                if (!f.exists()) f.writeBytes(BeatSynth.wav(BeatSynth.listenCue(start)))
                cueIds[start] = pool.load(f.absolutePath, 1)
            }
            AlertTone.entries.forEach { t ->
                val id = t.rawRes?.let { pool.load(app, it, 1) } ?: BeatSynth.render(t)?.let { pcm ->
                    val f = File(dir, "tone-${t.name.lowercase()}-v1.wav")
                    if (!f.exists()) f.writeBytes(BeatSynth.wav(pcm))
                    pool.load(f.absolutePath, 1)
                }
                if (id != null) toneIds[t] = id
            }
        }
        scope.launch { for (alert in queue) deliver(alert) }
        // Catch HR data stopping altogether.
        scope.launch {
            while (true) {
                delay(3_000L)
                dispatch(alerts.tick(System.currentTimeMillis(), h10Connected()))
                handler.post { updateBeepLoop() }
            }
        }
    }

    /** Set by MonitorHub: whether the H10 is currently connected. */
    var h10Connected: () -> Boolean = { false }

    fun onHeart(s: HeartSnapshot, rr: List<RrSampleEntity>) {
        val t = s.updatedAtMs ?: System.currentTimeMillis()
        latestHr = s.heartRate
        lastHeartAt = System.currentTimeMillis()
        contact = s.contact
        scheduler.onHeart(s.heartRate, rr.map { it.rawIntervalMs }, AudioSettings.followRhythm.value)
        handler.post { updateBeepLoop() }
        dispatch(alerts.onHeart(t, s.heartRate, s.rmssdMs, s.load.score, s.contact, rr.any { HrvCalculator.isEctopic(it) }))
    }

    fun onPelvic(t: Long, p: PelvicSnapshot) = dispatch(alerts.onPelvic(t, p.holding, p.holdSeconds, p.pulseCount, p.lastPulse))

    fun onBp(t: Long, systolic: Int, diastolic: Int, where: String? = null) = dispatch(alerts.onBp(t, systolic, diastolic, where))

    fun onGlucose(t: Long, mgDl: Double) = dispatch(alerts.onGlucose(t, mgDl))

    fun onDisconnected(sensor: String) = dispatch(alerts.onDisconnected(System.currentTimeMillis(), sensor))

    fun resetSession() {
        alerts.reset()
        scheduler.reset()
    }

    private fun dispatch(list: List<LiveAlert>) {
        if (list.isEmpty()) return
        if (!AudioSettings.alertsEnabled.value) return
        if (AudioSettings.alertsOnlyRecording.value && !isRecording()) return
        list.filter { AudioSettings.mode(it.kind) != AlertMode.Off || it.kind in AudioSettings.vibrate.value }
            .sortedByDescending { it.severity }
            .forEach { queue.trySend(it) }
    }

    private suspend fun deliver(alert: LiveAlert) {
        // Stale by the time its turn came (a backlog): only high-severity ones still get said.
        if (System.currentTimeMillis() - alert.atMs > 30_000L && alert.severity != FlagSeverity.High) return
        val mode = AudioSettings.mode(alert.kind)
        _lastAlert.value = alert
        if (alert.kind in AudioSettings.vibrate.value) vibrate(alert.severity)
        if (mode.tone) {
            playTone(AudioSettings.alertTone.value, AudioSettings.alertVolume.value)
            delay(if (AudioSettings.alertTone.value == AlertTone.Chime) 750L else 450L)
        }
        if (mode.voice) say(alert.spoken)
    }

    /** Speaks through the alert queue's voice, ducking the beeps. Also used for "Test voice". */
    suspend fun say(text: String) = speechLock.withLock {
        _speaking.value = true
        try {
            _voiceProblem.value = voice.speak(text, AudioSettings.voiceVolume.value, AudioSettings.voiceEngine.value)
        } finally {
            _speaking.value = false
        }
    }

    fun testVoice() {
        scope.launch { say("Hi, it's Sarah. Heart rate 72, steady. I'll speak up if anything changes.") }
    }

    fun testAlert(kind: AlertKind) {
        val sample = when (kind) {
            AlertKind.HighHr -> "Heart rate 142, up 30 in the last minute. Above 130 for 30 seconds."
            AlertKind.HrSurge -> "Heart rate surge: up 28 to 121 in 40 seconds."
            AlertKind.HighLoad -> "Load is elevated, 64, for a minute now."
            AlertKind.ContractionBurst -> "Contraction burst: 5 in a row, about 0.9 seconds apart."
            else -> kind.label + "."
        }
        scope.launch { deliver(LiveAlert(kind, FlagSeverity.Notable, sample, kind.label, System.currentTimeMillis())) }
    }

    // ---- heartbeat beep ----

    private fun shouldBeep(): Boolean {
        if (!AudioSettings.beepEnabled.value) return false
        if (latestHr == null || System.currentTimeMillis() - lastHeartAt > 5_000L) return false
        if (AudioSettings.beepOnlyRecording.value && !isRecording()) return false
        if (AudioSettings.muteOnContactLoss.value && contact == false) return false
        return true
    }

    private val beatLoop = object : Runnable {
        override fun run() {
            if (!running) return
            if (!shouldBeep()) { running = false; return }
            val n = AudioSettings.beepEveryN.value.coerceAtLeast(1)
            if (beatCounter++ % n == 0L) playBeat(AudioSettings.beepSound.value)
            handler.postDelayed(this, scheduler.nextIntervalMs())
        }
    }

    private fun updateBeepLoop() {
        val should = shouldBeep()
        if (should && !running) {
            running = true
            beatCounter = 0
            handler.removeCallbacks(beatLoop)
            handler.post(beatLoop)
        } else if (!should && running) {
            running = false
            handler.removeCallbacks(beatLoop)
        }
    }

    private fun isLoaded(id: Int) = synchronized(loaded) { id in loaded }

    private fun playBeat(sound: BeatSound, preview: Boolean = false) {
        val id = beatIds[sound]?.takeIf(::isLoaded) ?: return
        val duck = if (_speaking.value && !preview) 0.25f else 1f
        val volume = AudioSettings.beepVolume.value * duck
        val rate = BeatSynth.combinedRate(AudioSettings.beepPitch.value, if (sound.followsHr) BeatSynth.hrPitch(latestHr) else null)
        val over = AudioSettings.beepOverOtherAudio.value && !preview
        if (over) requestBeepFocus()
        pool.play(id, volume, volume, 1, 0, rate)
        if (over) handler.postDelayed({ abandonBeepFocus() }, sound.durationMs)
    }

    /** One beat of [sound] at the current settings, for the Settings page. */
    fun previewBeat(sound: BeatSound) = handler.post { playBeat(sound, preview = true) }

    fun previewTone(tone: AlertTone) = handler.post { playTone(tone, AudioSettings.alertVolume.value) }

    /** Voice notes: rising cue when listening starts, falling when it stops. */
    fun listenCue(start: Boolean) = handler.post {
        val id = cueIds[start]?.takeIf(::isLoaded) ?: return@post
        val v = AudioSettings.alertVolume.value
        pool.play(id, v, v, 3, 0, 1f)
    }

    private fun playTone(tone: AlertTone, volume: Float) {
        val id = toneIds[tone]?.takeIf(::isLoaded) ?: return
        pool.play(id, volume, volume, 2, 0, 1f)
    }

    private fun requestBeepFocus() {
        if (Build.VERSION.SDK_INT >= 26) beepFocus?.let(audioManager::requestAudioFocus)
    }

    private fun abandonBeepFocus() {
        if (Build.VERSION.SDK_INT >= 26) beepFocus?.let(audioManager::abandonAudioFocusRequest)
    }

    private fun vibrate(severity: FlagSeverity) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
        } ?: return
        val pattern = if (severity == FlagSeverity.High) longArrayOf(0, 250, 120, 250, 120, 250) else longArrayOf(0, 180, 120, 180)
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
    }
}
