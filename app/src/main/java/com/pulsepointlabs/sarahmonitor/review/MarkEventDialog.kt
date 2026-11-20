package com.pulsepointlabs.sarahmonitor.review

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.pulsepointlabs.sarahmonitor.session.VoiceSettings
import com.pulsepointlabs.sarahmonitor.session.VoiceTextNormalizer
import com.pulsepointlabs.sarahmonitor.session.WhisperTranscriber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.session.MarkerEntity
import com.pulsepointlabs.sarahmonitor.session.MarkerPreferences
import com.pulsepointlabs.sarahmonitor.session.MarkerTypes
import com.pulsepointlabs.sarahmonitor.session.VoiceNoteRecognizer
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import com.pulsepointlabs.sarahmonitor.ui.fmt

/** Colour per event type, used for chips, chart marker lines and the event list. */
fun markerColor(types: String): Color = when (MarkerTypes.split(types).firstOrNull()) {
    "symptom" -> MonitorColors.warn
    "activity" -> MonitorColors.sdnn
    "position" -> Color(0xFF7DD3FC)
    "intake" -> MonitorColors.glucose
    "recovery" -> MonitorColors.good
    "stimulation" -> Color(0xFFF9A8D4)
    "arousal" -> MonitorColors.bp
    "plateau" -> Color(0xFFFB923C)
    "edge" -> MonitorColors.pelvic
    "orgasm" -> Color(0xFFFF7AB6)
    "ejaculation" -> Color(0xFFE879F9)
    "resolution" -> Color(0xFFA5B4FC)
    else -> Color(0xFFE6EEF5)
}

/**
 * Mark (or edit) an event. The time is the moment the dialog was opened, so typing a note doesn't move the
 * marker. Shows the live numbers at that moment, like SarahVS.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkEventDialog(
    pressedAtMs: Long,
    editing: MarkerEntity? = null,
    onSave: (types: Set<String>, note: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val heart by MonitorHub.heart.collectAsState()
    val pelvic by MonitorHub.pelvic.collectAsState()
    var types by remember { mutableStateOf(editing?.let { MarkerTypes.split(it.types) } ?: setOf("event")) }
    var note by remember { mutableStateOf(editing?.note ?: "") }
    var status by remember { mutableStateOf("") }
    val last = remember { if (editing == null) MarkerPreferences.last(context) else null }
    val recognizer = remember { VoiceNoteRecognizer(context) }
    val whisper = remember { WhisperTranscriber(context) }
    val useWhisper = remember { VoiceSettings.useWhisper(context) }
    var whisperRecording by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { recognizer.stop(); whisper.cancel() } }
    fun listen() {
        if (!useWhisper) {
            recognizer.start(note, onText = { note = it }, onStatus = { status = it }, onError = { status = it })
            return
        }
        // Whisper: tap once to record, tap again to send it for transcription.
        if (!whisperRecording) {
            runCatching { whisper.start() }
                .onSuccess { whisperRecording = true; status = "● Recording… tap the mic again when done" }
                .onFailure { status = "Couldn't start recording (${it.message})" }
        } else {
            whisperRecording = false
            status = "Transcribing with Whisper…"
            scope.launch {
                runCatching { whisper.stopAndTranscribe(VoiceSettings.apiKey(context)) }
                    .onSuccess { text ->
                        val clean = VoiceTextNormalizer.normalize(text)
                        note = if (note.isBlank()) clean else note.trimEnd() + " " + clean
                        status = "Whisper ✓"
                    }
                    .onFailure { status = it.message ?: "Whisper failed" }
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) listen() else status = "Microphone permission is needed for voice notes" }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MonitorColors.panel,
        title = {
            Column {
                Text(if (editing == null) "Mark event" else if (editing.id.isEmpty()) "Add event" else "Edit event", color = MonitorColors.text, fontWeight = FontWeight.Bold)
                Text(
                    "${clockLabel(pressedAtMs, seconds = true)}" +
                        if (editing == null) "  ·  HR ${heart.heartRate ?: "--"}  ·  load ${heart.load.score ?: "--"}  ·  pressure ${fmt(pelvic.aboveRestKpa, 1)} kPa" else "",
                    color = MonitorColors.label,
                    fontSize = 13.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                last?.let { l ->
                    Text(
                        "↻ Repeat last: ${l.label}",
                        color = MonitorColors.pelvic,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MonitorColors.pelvic.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .clickable { onSave(l.types, l.note) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
                MarkerTypes.Group.entries.forEach { group ->
                    Text(group.title, color = MonitorColors.label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarkerTypes.inGroup(group).forEach { type ->
                            val on = type.id in types
                            val color = markerColor(type.id)
                            Text(
                                (if (on) "✓ " else "") + type.label,
                                color = if (on) MonitorColors.background else color,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .heightIn(min = 40.dp)
                                    .background(if (on) color else color.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                                    .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                                    .clickable { types = if (on && types.size > 1) types - type.id else types + type.id }
                                    .padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note (optional)") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4
                    )
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        Icon(
                            if (whisperRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (whisperRecording) "Stop and transcribe" else "Voice note",
                            tint = if (whisperRecording) MonitorColors.alarm else MonitorColors.pelvic
                        )
                    }
                }
                if (status.isNotBlank()) Text(status, color = MonitorColors.label, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!whisperRecording) onSave(types, note) else {
                    // Saving mid-recording: finish the transcription first so the words aren't lost.
                    whisperRecording = false
                    status = "Transcribing with Whisper…"
                    scope.launch {
                        val text = runCatching { whisper.stopAndTranscribe(VoiceSettings.apiKey(context)) }.getOrNull()
                        val clean = text?.let { VoiceTextNormalizer.normalize(it) }.orEmpty()
                        onSave(types, if (clean.isBlank()) note else if (note.isBlank()) clean else note.trimEnd() + " " + clean)
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MonitorColors.alarm) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
