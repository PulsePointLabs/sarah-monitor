package com.pulsepointlabs.sarahmonitor.response

import androidx.compose.material.icons.filled.Face
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.ui.ChoiceChip
import com.pulsepointlabs.sarahmonitor.ui.ChoiceRow
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import com.pulsepointlabs.sarahmonitor.ui.SettingRow
import com.pulsepointlabs.sarahmonitor.ui.SettingSwitch
import com.pulsepointlabs.sarahmonitor.ui.SettingsSection
import kotlinx.coroutines.launch

private val Pink = Color(0xFFFF7AB6)

/** How Sarah talks: v1's tone presets, detail level, style switches and his standing instructions, plus her brain. */
@Composable
fun SarahPersonalitySection() {
    val tone by SarahPersonality.tone.collectAsState()
    val detail by SarahPersonality.detail.collectAsState()
    val warmth by SarahPersonality.feminineWarmth.collectAsState()
    val specific by SarahPersonality.sexualSpecificity.collectAsState()
    val story by SarahPersonality.sessionStory.collectAsState()
    val tts by SarahPersonality.ttsFriendly.collectAsState()
    val instructions by SarahPersonality.instructions.collectAsState()
    val model by ResponseSettings.model.collectAsState()
    val scope = rememberCoroutineScope()
    var sample by remember { mutableStateOf<String?>(null) }
    var trying by remember { mutableStateOf(false) }
    SettingsSection("Sarah's personality", Pink, icon = androidx.compose.material.icons.Icons.Default.Face) {
        Text("How she talks to you everywhere: replies to your notes, answers, and live feedback.", color = MonitorColors.label, fontSize = 13.sp)
        Text("Tone", color = MonitorColors.text, fontSize = 15.sp)
        ChoiceRow { SarahPersonality.Tone.entries.forEach { t -> ChoiceChip(t.label, t == tone, Pink) { SarahPersonality.setTone(t) } } }
        Text(tone.blurb, color = MonitorColors.label, fontSize = 13.sp)
        Text("Detail", color = MonitorColors.text, fontSize = 15.sp)
        ChoiceRow { SarahPersonality.Detail.entries.forEach { d -> ChoiceChip(d.label, d == detail, Pink) { SarahPersonality.setDetail(d) } } }
        Text(detail.blurb, color = MonitorColors.label, fontSize = 13.sp)
        SettingRow("Feminine warmth", "A gentle feminine touch in how she phrases things") { SettingSwitch(warmth) { SarahPersonality.setWarmth(it) } }
        SettingRow("Anatomically specific", "Sexual sessions: names what's responding, clinically, in the flow") { SettingSwitch(specific) { SarahPersonality.setSpecific(it) } }
        SettingRow("Follows the session story", "Connects what you just said to earlier in the session") { SettingSwitch(story) { SarahPersonality.setStory(it) } }
        SettingRow("Written for listening", "Short sentences; numbers and times said like a person") { SettingSwitch(tts) { SarahPersonality.setTts(it) } }
        Text("Your instructions to her", color = MonitorColors.text, fontSize = 15.sp)
        OutlinedTextField(
            instructions, { SarahPersonality.setInstructions(it) },
            modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10,
            textStyle = androidx.compose.ui.text.TextStyle(color = MonitorColors.text, fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pink, unfocusedBorderColor = MonitorColors.panelBorder)
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (instructions != SarahPersonality.DEFAULT_INSTRUCTIONS) TextButton(onClick = { SarahPersonality.resetInstructions() }) { Text("Reset to v1 default", color = Pink, fontSize = 14.sp) }
        }
        Text("Her brain", color = MonitorColors.text, fontSize = 15.sp)
        ChoiceRow { CoachModel.entries.forEach { m -> ChoiceChip(m.label.substringBefore(" ("), m == model, Pink) { ResponseSettings.setModel(m) } } }
        Text(model.label.substringAfter("(", "Most thoughtful").removeSuffix(")").replaceFirstChar { it.uppercase() }, color = MonitorColors.label, fontSize = 13.sp)
        // Hear the tuning: one short reply to a sample note, spoken (a single small Claude call).
        ChoiceRow {
            ChoiceChip(if (trying) "Thinking…" else "▶ Hear how she sounds", false, Pink) {
                if (trying) return@ChoiceChip
                trying = true
                scope.launch {
                    val r = SarahChat.reply("Just settling in for the evening, feeling pretty relaxed.", "note", "How I feel", null)
                    sample = r ?: "Couldn't reach Claude just now (check the Anthropic key)."
                    trying = false
                    r?.let { MonitorHub.audio.say(it, urgent = true) }
                }
            }
        }
        sample?.let { Text("“$it”", color = MonitorColors.text, fontSize = 14.sp) }
    }
}
