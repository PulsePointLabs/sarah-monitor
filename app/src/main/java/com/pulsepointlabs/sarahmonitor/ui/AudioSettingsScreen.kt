package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.audio.AlertKind
import com.pulsepointlabs.sarahmonitor.audio.AlertMode
import com.pulsepointlabs.sarahmonitor.audio.AlertTone
import com.pulsepointlabs.sarahmonitor.audio.AudioSettings
import com.pulsepointlabs.sarahmonitor.audio.BeatSound
import com.pulsepointlabs.sarahmonitor.audio.VoiceEngine
import com.pulsepointlabs.sarahmonitor.core.MonitorHub

/** A pill-shaped choice; filled when selected. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, color: Color = MonitorColors.pelvic, onClick: () -> Unit) {
    Text(
        (if (selected) "✓ " else "") + label,
        color = if (selected) MonitorColors.background else MonitorColors.text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .background(if (selected) color else Color.Transparent, RoundedCornerShape(16.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
fun HeartbeatSoundSection() {
    val enabled by AudioSettings.beepEnabled.collectAsState()
    val sound by AudioSettings.beepSound.collectAsState()
    val volume by AudioSettings.beepVolume.collectAsState()
    val pitch by AudioSettings.beepPitch.collectAsState()
    val everyN by AudioSettings.beepEveryN.collectAsState()
    val rhythm by AudioSettings.followRhythm.collectAsState()
    val onlyRec by AudioSettings.beepOnlyRecording.collectAsState()
    val muteContact by AudioSettings.muteOnContactLoss.collectAsState()
    val over by AudioSettings.beepOverOtherAudio.collectAsState()
    SettingsSection("Heartbeat sound", MonitorColors.hr, icon = androidx.compose.material.icons.Icons.Default.MonitorHeart) {
        SettingRow("Beep with each heartbeat") { SettingSwitch(enabled) { AudioSettings.setBeepEnabled(it) } }
        ChoiceRow {
            BeatSound.entries.forEach { s ->
                ChoiceChip(s.label, s == sound, MonitorColors.hr) {
                    AudioSettings.setBeepSound(s)
                    MonitorHub.audio.previewBeat(s)
                }
            }
        }
        SettingSlider("Volume", "${(volume * 100).toInt()}%", volume, 0f..1f) { AudioSettings.setBeepVolume(it) }
        SettingSlider("Pitch", (if (pitch > 0) "+" else "") + "$pitch semitones", pitch.toFloat(), -6f..6f, steps = 11) {
            AudioSettings.setBeepPitch(it.toInt())
        }
        Text("Play", color = MonitorColors.text, fontSize = 15.sp)
        ChoiceRow {
            listOf(1 to "Every beat", 2 to "Every 2nd", 4 to "Every 4th").forEach { (n, label) ->
                ChoiceChip(label, everyN == n, MonitorColors.hr) { AudioSettings.setBeepEveryN(n) }
            }
        }
        SettingRow("Follow the real rhythm", if (rhythm) "Beats use the strap's RR intervals — extra beats and pauses are audible (about 1 s behind)" else "Steady beat at the current heart rate") {
            SettingSwitch(rhythm) { AudioSettings.setFollowRhythm(it) }
        }
        SettingRow("Only while recording") { SettingSwitch(onlyRec) { AudioSettings.setBeepOnlyRecording(it) } }
        SettingRow("Mute when the strap loses contact") { SettingSwitch(muteContact) { AudioSettings.setMuteOnContactLoss(it) } }
        SettingRow("Dip other audio for each beat", "Music and videos lower briefly under the beep") { SettingSwitch(over) { AudioSettings.setBeepOverOtherAudio(it) } }
    }
}

@Composable
fun AlertsSection() {
    val enabled by AudioSettings.alertsEnabled.collectAsState()
    val onlyRec by AudioSettings.alertsOnlyRecording.collectAsState()
    val tone by AudioSettings.alertTone.collectAsState()
    val volume by AudioSettings.alertVolume.collectAsState()
    val highHr by AudioSettings.highHrLimit.collectAsState()
    val lowHr by AudioSettings.lowHrLimit.collectAsState()
    val hold by AudioSettings.longHoldSeconds.collectAsState()
    SettingsSection("Alerts", MonitorColors.warn, icon = androidx.compose.material.icons.Icons.Default.NotificationsActive) {
        SettingRow("Alerts on") { SettingSwitch(enabled) { AudioSettings.setAlertsEnabled(it) } }
        SettingRow("Only while recording") { SettingSwitch(onlyRec) { AudioSettings.setAlertsOnlyRecording(it) } }
        Text("Alert tone", color = MonitorColors.text, fontSize = 15.sp)
        ChoiceRow {
            AlertTone.entries.forEach { t ->
                ChoiceChip(t.label, t == tone, MonitorColors.warn) { AudioSettings.setAlertTone(t); MonitorHub.audio.previewTone(t) }
            }
        }
        SettingSlider("Alert tone volume", "${(volume * 100).toInt()}%", volume, 0f..1f) { AudioSettings.setAlertVolume(it) }
        SettingSlider("High heart rate alert", if (highHr == 0) "Auto" else "$highHr bpm", if (highHr == 0) 80f else highHr.toFloat(), 80f..220f) {
            AudioSettings.setHighHrLimit(if (it < 90f) 0 else Math.round(it))
        }
        Text(
            if (highHr == 0) "Auto: 25 above this session's typical heart rate (never below 100), once it has a few minutes to learn. Slide left to the end for Auto."
            else "Alerts when heart rate stays above $highHr for 30 seconds. Slide left to the end for Auto.",
            color = MonitorColors.label, fontSize = 12.sp
        )
        SettingSlider("Low heart rate alert", "$lowHr bpm", lowHr.toFloat(), 35f..110f) { AudioSettings.setLowHrLimit(Math.round(it)) }
        Text(
            if (lowHr >= 60) "A target: Sarah tells you when you've been under $lowHr for 30 seconds." else "Alerts when heart rate stays under $lowHr for 30 seconds.",
            color = MonitorColors.label, fontSize = 12.sp
        )
        SettingSlider("Long pelvic hold", "$hold s", hold.toFloat(), 5f..30f) { AudioSettings.setLongHoldSeconds(it.toInt()) }
    }
}

/** Each alert's delivery: off / tone / voice / both, plus vibrate, and a ▶ sample. */
@Composable
fun AlertListSection() {
    val modes by AudioSettings.modes.collectAsState()
    val vibrate by AudioSettings.vibrate.collectAsState()
    SettingsSection("Which alerts", MonitorColors.warn, icon = androidx.compose.material.icons.Icons.Default.Checklist) {
        AlertKind.entries.forEach { kind ->
            val mode = modes[kind] ?: kind.defaultMode
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MonitorColors.panel, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(kind.label, color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(kind.detail, color = MonitorColors.label, fontSize = 12.sp)
                    }
                    Text(
                        "▶", color = MonitorColors.warn, fontSize = 18.sp,
                        modifier = Modifier.clickable { MonitorHub.audio.testAlert(kind) }.padding(8.dp)
                    )
                }
                ChoiceRow {
                    AlertMode.entries.forEach { m -> ChoiceChip(m.label, m == mode, MonitorColors.warn) { AudioSettings.setMode(kind, m) } }
                    ChoiceChip("Vibrate", kind in vibrate, MonitorColors.warn) { AudioSettings.setVibrate(kind, kind !in vibrate) }
                }
            }
        }
    }
}

/** Sarah's stress voice: announcements and relaxation coaching (general sessions and outside recordings only). */
@Composable
fun StressVoiceSection() {
    val announce by com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.announce.collectAsState()
    val relax by com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.relax.collectAsState()
    val threshold by com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.threshold.collectAsState()
    val gap by com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.gapMin.collectAsState()
    val onlyRec by com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.onlyRecording.collectAsState()
    val status by MonitorHub.stressVoice.status.collectAsState()
    val last by MonitorHub.stressVoice.last.collectAsState()
    val s = com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings
    SettingsSection("Sarah · stress", StressColor, icon = androidx.compose.material.icons.Icons.Default.SelfImprovement) {
        SettingRow("Announce stress changes", "Rising and easing, with the number") { SettingSwitch(announce) { s.setAnnounce(it) } }
        SettingRow("Relaxation coaching", "A calm cue when it climbs or stays high") { SettingSwitch(relax) { s.setRelax(it) } }
        SettingSlider("Speak when it changes by", "$threshold pts", threshold.toFloat(), 5f..25f) { s.setThreshold(Math.round(it)) }
        SettingSlider("At most every", "$gap min", gap.toFloat(), 1f..15f) { s.setGapMin(Math.round(it)) }
        SettingRow("Only while recording") { SettingSwitch(onlyRec) { s.setOnlyRecording(it) } }
        ChoiceRow { ChoiceChip("▶ Test", false, StressColor) { MonitorHub.stressVoice.test() } }
        val shown = when {
            !announce && !relax -> "Off"
            status == "Off" -> "Waiting for heart data"
            else -> status
        }
        Text(last?.let { "“$it”" } ?: shown, color = if (last != null) MonitorColors.text else MonitorColors.label, fontSize = 13.sp)
    }
}


/** How Sarah sounds: which voice, how loud, and what other apps do while she talks. */
@Composable
fun SarahVoiceSection() {
    val engine by AudioSettings.voiceEngine.collectAsState()
    val volume by AudioSettings.voiceVolume.collectAsState()
    val over by AudioSettings.voiceOverOthers.collectAsState()
    val problem by MonitorHub.audio.voiceProblem.collectAsState()
    val color = androidx.compose.ui.graphics.Color(0xFFFF7AB6)
    SettingsSection("Sarah's voice", color, icon = androidx.compose.material.icons.Icons.Default.GraphicEq) {
        ChoiceRow { VoiceEngine.entries.forEach { e -> ChoiceChip(e.label, e == engine, color) { AudioSettings.setVoiceEngine(e) } } }
        SettingSlider("Volume", "${(volume * 100).toInt()}%" + if (volume > 1f) " boost" else "", volume, 0.1f..2f) { AudioSettings.setVoiceVolume((it * 20).toInt() / 20f) }
        Text("Follows your media volume. Above 100% boosts her over loud audio.", color = MonitorColors.label, fontSize = 12.sp)
        Text("While she talks", color = MonitorColors.text, fontSize = 15.sp)
        ChoiceRow { com.pulsepointlabs.sarahmonitor.audio.VoiceOverOthers.entries.forEach { v -> ChoiceChip(v.label, v == over, color) { AudioSettings.setVoiceOverOthers(v) } } }
        ChoiceRow { ChoiceChip("▶ Test voice", false, color) { MonitorHub.audio.testVoice() } }
        if (problem != null) Text(problem!!, color = MonitorColors.warn, fontSize = 13.sp)
    }
}
