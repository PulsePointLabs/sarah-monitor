package com.pulsepointlabs.sarahmonitor.response

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.review.markerColor
import com.pulsepointlabs.sarahmonitor.session.MarkerTypes
import com.pulsepointlabs.sarahmonitor.ui.CardType
import com.pulsepointlabs.sarahmonitor.ui.ChoiceChip
import com.pulsepointlabs.sarahmonitor.ui.ChoiceRow
import com.pulsepointlabs.sarahmonitor.ui.MonitorCard
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun phaseColor(p: ResponsePhase): Color = when (p) {
    ResponsePhase.Baseline -> MonitorColors.label
    ResponsePhase.Warming -> MonitorColors.sdnn
    ResponsePhase.Building -> MonitorColors.good
    ResponsePhase.Plateau -> MonitorColors.rmssd
    ResponsePhase.Approaching -> MonitorColors.load
    ResponsePhase.Climax -> Color(0xFFFF7AB6)
    ResponsePhase.BackingOff -> MonitorColors.pnn50
    ResponsePhase.Recovery -> MonitorColors.pelvic
}

/**
 * The Sexual Response board: the same arrangeable card grid as Monitor (Cards / Arrange in the top bar), with
 * response cards — phase, near-climax watch, threshold matrix, timelines, recovery, respiration, Sarah and marks —
 * next to the pelvic, HRV, EMG and heart cards. A pulsing frame shows near climax and climax.
 */
@Composable
fun ResponseScreen(
    slots: List<com.pulsepointlabs.sarahmonitor.ui.CardSlot>,
    arranging: Boolean,
    onArrangingChange: (Boolean) -> Unit,
    onChange: (List<com.pulsepointlabs.sarahmonitor.ui.CardSlot>) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        com.pulsepointlabs.sarahmonitor.ui.Dashboard(slots, arranging, onArrangingChange, onChange, Modifier.fillMaxSize())
        UrgencyFrame()
    }
}

/** Settings: Sarah's brain (Claude) and how often she speaks. */
@Composable
fun CoachSettingsSection() {
    val key by ResponseSettings.apiKey.collectAsState()
    val model by ResponseSettings.model.collectAsState()
    val cadence by ResponseSettings.cadenceS.collectAsState()
    val profile by ResponseSettings.profile.collectAsState()
    var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    com.pulsepointlabs.sarahmonitor.ui.SettingsSection(
        "Sarah live feedback", Color(0xFFFF7AB6),
        "During a recording on the Response tab, Sarah speaks short lines about what your body is doing. Claude is her brain (your Anthropic key); her voice is the HD Crisp OpenAI voice."
    ) {
        Text(
            if (key.isBlank()) "No Anthropic key saved yet." else "Anthropic key saved: ${key.take(7)}…${key.takeLast(4)}",
            color = if (key.isBlank()) MonitorColors.warn else MonitorColors.good, fontSize = 13.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = draft, onValueChange = { draft = it.trim() },
                label = { Text("Anthropic API key (sk-ant-…)") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.TextButton(enabled = draft.isNotBlank(), onClick = { ResponseSettings.setApiKey(draft); draft = "" }) { Text("Save") }
            if (key.isNotBlank()) androidx.compose.material3.TextButton(onClick = { ResponseSettings.setApiKey("") }) { Text("Remove", color = MonitorColors.alarm) }
        }
        Text("Brain", color = MonitorColors.text, fontSize = 15.sp)
        ChoiceRow { CoachModel.entries.forEach { m -> ChoiceChip(m.label, m == model, Color(0xFFFF7AB6)) { ResponseSettings.setModel(m) } } }
        com.pulsepointlabs.sarahmonitor.ui.SettingSlider("Check in every", "$cadence s", cadence.toFloat(), 20f..300f) { ResponseSettings.setCadence(it.toInt()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (profile.learned) "Climax detector: ${profile.samples.size} climaxes · typical +${profile.climaxHrDelta.toInt()} bpm"
                else "Climax detector: ${profile.samples.size} marked climaxes",
                color = MonitorColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f)
            )
            if (profile.samples.isNotEmpty()) androidx.compose.material3.TextButton(onClick = { ResponseSettings.resetProfile() }) { Text("Reset", color = MonitorColors.alarm) }
        }
    }
}
