package com.pulsepointlabs.sarahmonitor.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How an alert is delivered. */
enum class AlertMode(val label: String, val tone: Boolean, val voice: Boolean) {
    Off("Off", false, false),
    Tone("Tone", true, false),
    Voice("Voice", false, true),
    Both("Tone + voice", true, true)
}

/** Live alerts. Rules match the session auto-flags, so what you hear live is what review shows afterwards. */
enum class AlertKind(val label: String, val detail: String, val defaultMode: AlertMode, val cooldownMs: Long) {
    HighHr("High heart rate", "15 s average above the limit for 30 s", AlertMode.Voice, 3 * 60_000L),
    LowHr("Low heart rate", "15 s average below the limit for 30 s", AlertMode.Voice, 3 * 60_000L),
    HrSurge("Heart-rate surge", "Up 25+ bpm within a minute", AlertMode.Voice, 2 * 60_000L),
    HrShift("Sustained HR change", "Last 45 s held 12+ bpm away from the 2 minutes before (SarahVS)", AlertMode.Tone, 10 * 60_000L),
    HrvDrop("HRV drop", "RMSSD at or below 60% of this session's median for a minute", AlertMode.Off, 10 * 60_000L),
    HighLoad("High load", "Load 58+ for a minute (82+ is high)", AlertMode.Voice, 10 * 60_000L),
    IrregularBeat("Irregular beat", "Premature/ectopic beat in the H10's RR intervals", AlertMode.Off, 5 * 60_000L),
    LongHold("Long pelvic hold", "A hold reaches the set length", AlertMode.Off, 60_000L),
    StrongContraction("Strong contraction", "Peak at least twice this session's typical (min 3 kPa)", AlertMode.Off, 60_000L),
    ContractionBurst("Contraction burst", "4+ quick contractions, each within 3 s of the last", AlertMode.Off, 2 * 60_000L),
    SignalLost("Sensor signal lost", "H10 contact or data gone for 10 s, or a sensor disconnects", AlertMode.Tone, 2 * 60_000L),
    HighBp("High blood pressure", "New reading at 140/90 or above", AlertMode.Voice, 0L),
    Glucose("Glucose out of range", "New reading below 70 or above 180 mg/dL", AlertMode.Voice, 0L)
}

/** What other apps (a movie, music) do while Sarah talks. */
enum class VoiceOverOthers(val label: String) { Lower("Lower other audio"), Pause("Pause other audio") }

enum class VoiceEngine(val label: String) {
    Sarah("Sarah · OpenAI HD Crisp"),
    Android("Android voice (offline)")
}

/**
 * Sound settings: the heartbeat beep, alert tones and Sarah's voice. Saved in preferences and published as flows
 * so the audio engine and the Settings page always agree.
 */
object AudioSettings {
    private const val PREFS = "sarahmonitor_audio"
    private var prefs: SharedPreferences? = null

    // Heartbeat beep
    val beepEnabled = MutableStateFlow(false)
    val beepSound = MutableStateFlow(BeatSound.Classic)
    val beepVolume = MutableStateFlow(0.8f)
    val beepPitch = MutableStateFlow(0)
    val beepEveryN = MutableStateFlow(1)
    val followRhythm = MutableStateFlow(true)
    val beepOnlyRecording = MutableStateFlow(true)
    val muteOnContactLoss = MutableStateFlow(true)
    val beepOverOtherAudio = MutableStateFlow(false)

    // Alerts
    val alertsEnabled = MutableStateFlow(true)
    val alertsOnlyRecording = MutableStateFlow(true)
    val alertTone = MutableStateFlow(AlertTone.Soft)
    val alertVolume = MutableStateFlow(0.9f)
    val voiceEngine = MutableStateFlow(VoiceEngine.Sarah)
    /** Sarah's voice level, 0–2 (above 1 is a loudness boost). Separate from the alert tones. */
    val voiceVolume = MutableStateFlow(1.3f)
    val voiceOverOthers = MutableStateFlow(VoiceOverOthers.Lower)
    private val _modes = MutableStateFlow(AlertKind.entries.associateWith { it.defaultMode })
    val modes: StateFlow<Map<AlertKind, AlertMode>> = _modes
    private val _vibrate = MutableStateFlow<Set<AlertKind>>(emptySet())
    val vibrate: StateFlow<Set<AlertKind>> = _vibrate
    /** 0 = automatic (session median + 25, at least 100 — same as the auto-flags). */
    val highHrLimit = MutableStateFlow(0)
    val lowHrLimit = MutableStateFlow(45)
    val longHoldSeconds = MutableStateFlow(10)

    @Synchronized
    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        beepEnabled.value = p.getBoolean("beep", false)
        beepSound.value = enumOr(p.getString("sound", null), BeatSound.Classic)
        beepVolume.value = p.getFloat("volume", 0.8f)
        beepPitch.value = p.getInt("pitch", 0)
        beepEveryN.value = p.getInt("every", 1)
        followRhythm.value = p.getBoolean("rhythm", true)
        beepOnlyRecording.value = p.getBoolean("beep_rec", true)
        muteOnContactLoss.value = p.getBoolean("mute_contact", true)
        beepOverOtherAudio.value = p.getBoolean("over_audio", false)
        alertsEnabled.value = p.getBoolean("alerts", true)
        alertsOnlyRecording.value = p.getBoolean("alerts_rec", true)
        alertTone.value = enumOr(p.getString("tone", null), AlertTone.Soft)
        alertVolume.value = p.getFloat("alert_volume", 0.9f)
        voiceEngine.value = enumOr(p.getString("voice", null), VoiceEngine.Sarah)
        voiceVolume.value = p.getFloat("voice_volume", 1.3f)
        voiceOverOthers.value = enumOr(p.getString("voice_over", null), VoiceOverOthers.Lower)
        _modes.value = AlertKind.entries.associateWith { enumOr(p.getString("mode_${it.name}", null), it.defaultMode) }
        _vibrate.value = AlertKind.entries.filter { p.getBoolean("vib_${it.name}", false) }.toSet()
        highHrLimit.value = p.getInt("high_hr", 0)
        lowHrLimit.value = p.getInt("low_hr", 45)
        longHoldSeconds.value = p.getInt("long_hold", 10)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        runCatching { enumValueOf<E>(name!!) }.getOrDefault(fallback)

    private fun edit(block: SharedPreferences.Editor.() -> Unit) { prefs?.edit()?.apply(block)?.apply() }

    fun setBeepEnabled(v: Boolean) { beepEnabled.value = v; edit { putBoolean("beep", v) } }
    fun setBeepSound(v: BeatSound) { beepSound.value = v; edit { putString("sound", v.name) } }
    fun setBeepVolume(v: Float) { beepVolume.value = v.coerceIn(0f, 1f); edit { putFloat("volume", beepVolume.value) } }
    fun setBeepPitch(v: Int) { beepPitch.value = v.coerceIn(-6, 6); edit { putInt("pitch", beepPitch.value) } }
    fun setBeepEveryN(v: Int) { beepEveryN.value = v; edit { putInt("every", v) } }
    fun setFollowRhythm(v: Boolean) { followRhythm.value = v; edit { putBoolean("rhythm", v) } }
    fun setBeepOnlyRecording(v: Boolean) { beepOnlyRecording.value = v; edit { putBoolean("beep_rec", v) } }
    fun setMuteOnContactLoss(v: Boolean) { muteOnContactLoss.value = v; edit { putBoolean("mute_contact", v) } }
    fun setBeepOverOtherAudio(v: Boolean) { beepOverOtherAudio.value = v; edit { putBoolean("over_audio", v) } }
    fun setAlertsEnabled(v: Boolean) { alertsEnabled.value = v; edit { putBoolean("alerts", v) } }
    fun setAlertsOnlyRecording(v: Boolean) { alertsOnlyRecording.value = v; edit { putBoolean("alerts_rec", v) } }
    fun setAlertTone(v: AlertTone) { alertTone.value = v; edit { putString("tone", v.name) } }
    fun setAlertVolume(v: Float) { alertVolume.value = v.coerceIn(0f, 1f); edit { putFloat("alert_volume", alertVolume.value) } }
    fun setVoiceEngine(v: VoiceEngine) { voiceEngine.value = v; edit { putString("voice", v.name) } }
    fun setVoiceVolume(v: Float) { voiceVolume.value = v.coerceIn(0.1f, 2f); edit { putFloat("voice_volume", voiceVolume.value) } }
    fun setVoiceOverOthers(v: VoiceOverOthers) { voiceOverOthers.value = v; edit { putString("voice_over", v.name) } }
    fun setMode(kind: AlertKind, mode: AlertMode) { _modes.value = _modes.value + (kind to mode); edit { putString("mode_${kind.name}", mode.name) } }
    fun setVibrate(kind: AlertKind, on: Boolean) {
        _vibrate.value = if (on) _vibrate.value + kind else _vibrate.value - kind
        edit { putBoolean("vib_${kind.name}", on) }
    }
    fun setHighHrLimit(v: Int) { highHrLimit.value = v; edit { putInt("high_hr", v) } }
    fun setLowHrLimit(v: Int) { lowHrLimit.value = v; edit { putInt("low_hr", v) } }
    fun setLongHoldSeconds(v: Int) { longHoldSeconds.value = v; edit { putInt("long_hold", v) } }

    fun mode(kind: AlertKind): AlertMode = _modes.value[kind] ?: kind.defaultMode
}
