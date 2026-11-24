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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.pulsepointlabs.sarahmonitor.session.VoiceSettings
import com.pulsepointlabs.sarahmonitor.session.VoiceTextNormalizer
import com.pulsepointlabs.sarahmonitor.session.WhisperTranscriber
import com.pulsepointlabs.sarahmonitor.session.RealtimeTranscriber
import com.pulsepointlabs.sarahmonitor.ui.MicLevelMeter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

/** "33:15" or "1:02:05" (elapsed from the session start) → ms, or null if it doesn't parse. */
internal fun parseElapsed(text: String): Long? {
    val parts = text.trim().split(':').map { it.trim().toIntOrNull() ?: return null }
    if (parts.isEmpty() || parts.size > 3 || parts.any { it < 0 } || parts.drop(1).any { it >= 60 }) return null
    return parts.fold(0L) { acc, v -> acc * 60 + v } * 1000L
}

internal fun formatElapsed(ms: Long): String = com.pulsepointlabs.sarahmonitor.session.SessionAnalysis.elapsedSigned(ms)

/**
 * When an event happened, for events added or corrected afterwards: elapsed time (typed, from video or notes),
 * an optional end for a range, where it came from, and how sure the time is. Sensor data is never touched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimingEditor(timing: com.pulsepointlabs.sarahmonitor.session.EventTiming, sessionStartMs: Long, onChange: (com.pulsepointlabs.sarahmonitor.session.EventTiming) -> Unit) {
    var at by remember { mutableStateOf(formatElapsed(timing.startMs - sessionStartMs)) }
    var until by remember { mutableStateOf(timing.endMs?.let { formatElapsed(it - sessionStartMs) } ?: "") }
    val atOk = parseElapsed(at) != null
    val untilMs = parseElapsed(until)
    val untilOk = until.isBlank() || (untilMs != null && sessionStartMs + untilMs > timing.startMs)
    val accent = Color(0xFF93C5FD)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("When", color = MonitorColors.label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = at, singleLine = true, isError = !atOk,
                onValueChange = { v -> at = v; parseElapsed(v)?.let { onChange(timing.copy(startMs = sessionStartMs + it)) } },
                label = { Text("At (m:ss)") }, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = until, singleLine = true, isError = !untilOk,
                onValueChange = { v ->
                    until = v
                    if (v.isBlank()) onChange(timing.copy(endMs = null)) else parseElapsed(v)?.let { onChange(timing.copy(endMs = sessionStartMs + it)) }
                },
                label = { Text("Until (optional)") }, modifier = Modifier.weight(1f)
            )
        }
        if (!atOk || !untilOk) Text(if (!atOk) "Type the time as minutes:seconds, e.g. 33:15" else "The end must be after the start", color = MonitorColors.alarm, fontSize = 12.sp)
        val sources = com.pulsepointlabs.sarahmonitor.session.EventSource.entries.filter {
            it != com.pulsepointlabs.sarahmonitor.session.EventSource.SensorCandidate &&
                (it != com.pulsepointlabs.sarahmonitor.session.EventSource.Live || timing.source == com.pulsepointlabs.sarahmonitor.session.EventSource.Live)
        }
        Text("Source", color = MonitorColors.label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEach { s -> TimingChip(s.label, s == timing.source, accent) { onChange(timing.copy(source = s)) } }
        }
        Text("Timing", color = MonitorColors.label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null to "Exact", 10 to "±10 s", 30 to "±30 s", 60 to "±1 min", 120 to "±2 min", 300 to "±5 min").forEach { (s, label) ->
                TimingChip(label, timing.uncertaintyS == s, accent) { onChange(timing.copy(uncertaintyS = s)) }
            }
        }
    }
}

@Composable
private fun TimingChip(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Text(
        (if (on) "✓ " else "") + label,
        color = if (on) MonitorColors.background else color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier
            .heightIn(min = 34.dp)
            .background(if (on) color else color.copy(alpha = 0.08f), RoundedCornerShape(17.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    )
}

/** Colour per event type, used for chips, chart marker lines and the event list. */
fun markerColor(types: String): Color = when (MarkerTypes.split(types).firstOrNull()) {
    "symptom" -> MonitorColors.warn
    "sensor_issue" -> MonitorColors.alarm
    "activity" -> MonitorColors.sdnn
    "position" -> Color(0xFF7DD3FC)
    "intake" -> MonitorColors.glucose
    "recovery" -> MonitorColors.good
    "stimulation" -> Color(0xFFF9A8D4)
    "arousal" -> MonitorColors.bp
    "plateau" -> Color(0xFFFB923C)
    "edge" -> MonitorColors.pelvic
    "near" -> Color(0xFFFF9F43)
    "catheter" -> Color(0xFF93C5FD)
    "enema" -> Color(0xFF86EFAC)
    "orgasm" -> Color(0xFFFF7AB6)
    "ejaculation" -> Color(0xFFE879F9)
    "resolution" -> Color(0xFFA5B4FC)
    "prep" -> Color(0xFF94A3B8)
    "insert", "seated" -> Color(0xFF60A5FA)
    "resistance", "adjust" -> Color(0xFFFBBF24)
    "balloon" -> Color(0xFF38BDF8)
    "urine" -> Color(0xFFFDE047)
    "instill", "instill_end" -> Color(0xFF4ADE80)
    "hold" -> Color(0xFF2DD4BF)
    "urge" -> Color(0xFFF97316)
    "release" -> Color(0xFFA3E635)
    "remove" -> Color(0xFFC4B5FD)
    "discomfort" -> Color(0xFFF87171)
    "estim_change" -> Color(0xFFBD94FF)
    "sensation" -> Color(0xFFF472B6)
    "finding" -> Color(0xFF67E8F9)
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
    onSave: (types: Set<String>, note: String, checkIn: com.pulsepointlabs.sarahmonitor.session.CheckIn) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    /** Drawn as a floating overlay panel (no activity behind it). */
    inOverlay: Boolean = false,
    /** Which event groups to offer (from the session's category); null = the live session's. */
    category: com.pulsepointlabs.sarahmonitor.session.SessionCategory? = null,
    /** Review only: the event's time, range, source and timing uncertainty, editable. Null while marking live. */
    timing: com.pulsepointlabs.sarahmonitor.session.EventTiming? = null,
    onTiming: (com.pulsepointlabs.sarahmonitor.session.EventTiming) -> Unit = {},
    /** Session start, so times can be typed as elapsed ("33:15"). */
    sessionStartMs: Long = 0L
) {
    val context = LocalContext.current
    val heart by MonitorHub.heart.collectAsState()
    val pelvic by MonitorHub.pelvic.collectAsState()
    var checkIn by remember { mutableStateOf(editing?.let { com.pulsepointlabs.sarahmonitor.session.CheckIn.of(it) } ?: com.pulsepointlabs.sarahmonitor.session.CheckIn()) }
    val sessionNow by MonitorHub.session.collectAsState()
    val groups = (category ?: com.pulsepointlabs.sarahmonitor.session.SessionCategory.of(sessionNow?.category) ?: com.pulsepointlabs.sarahmonitor.session.SessionCategory.Hybrid).groups
    val allowed = MarkerTypes.offered(groups).map { it.id }.toSet()
    // A new event starts with the type(s) used last time (if they fit this session); editing keeps its own.
    var types by remember {
        mutableStateOf(
            editing?.takeIf { it.id.isNotEmpty() }?.let { MarkerTypes.split(it.types) }
                ?: MarkerPreferences.last(context)?.types?.filter { it in allowed }?.toSet()?.takeIf { it.isNotEmpty() }
                ?: setOf(allowed.first())
        )
    }
    var note by remember { mutableStateOf(editing?.note ?: "") }
    // Live dictation: the raw words as they stream in, and Claude's punctuated version of the part it has seen.
    var liveRaw by remember { mutableStateOf("") }
    var polished by remember { mutableStateOf<Pair<String, String>?>(null) }
    var tidyBusy by remember { mutableStateOf(false) }
    // Matched without trailing punctuation: when you keep talking, the end of the earlier text can change ("." → ",").
    fun stem(t: String) = t.trimEnd { it in ".,…!?;: " }
    fun shown(raw: String): String = polished?.let { (src, out) -> stem(src).let { k -> if (raw.startsWith(k)) stem(out) + raw.removePrefix(k) else null } } ?: raw
    var status by remember { mutableStateOf("") }
    val last = remember { if (editing == null) MarkerPreferences.last(context) else null }
    val recognizer = remember { VoiceNoteRecognizer(context) }
    val whisper = remember { WhisperTranscriber(context) }
    val realtime = remember { RealtimeTranscriber(context) }
    var micLabel by remember { mutableStateOf("") }
    val engine = remember { VoiceSettings.effective(context) }
    var whisperRecording by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose {
            recognizer.stop(); whisper.cancel(); realtime.release()
            MonitorHub.handsFree.pause(false, MonitorHub.session.value != null)
            // Saved or cancelled: whatever was playing when you started dictating carries on.
            com.pulsepointlabs.sarahmonitor.audio.MediaHold.release("mark-event")
        }
    }
    /** Claude tidies punctuation (same words) when that's on and a key is saved. */
    suspend fun tidied(text: String): String {
        if (!VoiceSettings.tidyWithClaude(context) || !com.pulsepointlabs.sarahmonitor.core.ClaudeText.available || text.isBlank()) return text
        status = "Tidying punctuation…"
        return com.pulsepointlabs.sarahmonitor.core.TranscriptTidy.tidy(text) ?: text
    }
    fun ui(block: () -> Unit) = main.post(block)
    fun stopRealtime(then: (String) -> Unit = {}) {
        if (!realtime.active) return
        MonitorHub.audio.listenCue(false)
        scope.launch {
            val before = note
            status = "Finishing…"
            val raw = realtime.finish()
            val text = if (polished?.first == raw) polished!!.second else tidied(raw)
            listening = false; level = 0f
            if (text.isNotBlank()) note = text
            status = if (text.isNotBlank() && text.trim() != before.trim()) "Transcribed ✓" else realtime.lastProblem ?: "No speech recognized"
            then(note)
        }
    }
    fun stopWhisper(then: (String) -> Unit = {}) {
        if (!whisperRecording) return
        MonitorHub.audio.listenCue(false)
        whisperRecording = false; listening = false; level = 0f
        status = "Transcribing…"
        scope.launch {
            runCatching { whisper.stopAndTranscribe(VoiceSettings.apiKey(context)) }
                .onSuccess { text ->
                    val clean = VoiceTextNormalizer.normalize(text, fixStrayCaps = false)
                    note = tidied(VoiceTextNormalizer.normalize(if (note.isBlank()) clean else note.trimEnd() + " " + clean, fixStrayCaps = false))
                    status = if (clean.isBlank()) "No speech recognized" else "Transcribed ✓"
                }
                .onFailure { status = it.message ?: "Transcription failed" }
            then(note)
        }
    }
    fun listen() {
        // The mic can serve one listener at a time: hands-free steps aside while you dictate here.
        MonitorHub.handsFree.pause(true, MonitorHub.session.value != null)
        // Quiet while you talk: Sarah stops mid-line and music/video pauses until the event is saved.
        if (!listening) {
            MonitorHub.audio.hush()
            com.pulsepointlabs.sarahmonitor.audio.MediaHold.acquire("mark-event")
        }
        when (engine) {
            VoiceSettings.Engine.Android -> {
                if (listening) { recognizer.stop(); MonitorHub.audio.listenCue(false); return }
                listening = true
                micLabel = "Android recognizer"
                MonitorHub.audio.listenCue(true)
                recognizer.start(
                    note,
                    onText = { note = it },
                    onStatus = { status = it },
                    onError = { status = it },
                    onLevel = { level = it },
                    onFinished = { listening = false; level = 0f }
                )
            }
            VoiceSettings.Engine.Realtime -> {
                if (listening) { stopRealtime(); return }
                listening = true
                MonitorHub.audio.listenCue(true)
                liveRaw = note; polished = null
                realtime.start(
                    VoiceSettings.apiKey(context), note,
                    onText = { t -> ui { liveRaw = t; note = shown(t) } },
                    onStatus = { t -> ui { status = t } },
                    onLevel = { l -> ui { level = l } },
                    onError = { t -> ui { status = t; listening = false; level = 0f } },
                    onAutoStop = { ui { stopRealtime() } }
                )
                micLabel = realtime.micLabel
            }
            VoiceSettings.Engine.Whisper -> {
                // Whisper: records, then transcribes when you tap stop (or after the set quiet time).
                if (!whisperRecording) {
                    runCatching { whisper.start() }
                        .onSuccess {
                            whisperRecording = true; listening = true; status = "Recording"
                            micLabel = whisper.micLabel
                            MonitorHub.audio.listenCue(true)
                            val autoStop = (VoiceSettings.autoStopSeconds(context) * 1000).toLong()
                            scope.launch {
                                var spoke = false
                                var quiet = 0L
                                while (whisperRecording) {
                                    kotlinx.coroutines.delay(100)
                                    val l = whisper.level()
                                    level = l
                                    if (l > 0.45f) { spoke = true; quiet = 0 } else quiet += 100
                                    if (autoStop > 0 && spoke && quiet >= autoStop) { stopWhisper(); break }
                                }
                            }
                        }
                        .onFailure { status = "Couldn't start recording (${it.message})" }
                } else stopWhisper()
            }
        }
    }
    // While you dictate, Claude re-punctuates everything said so far once the words pause for a moment, so
    // sentences flow (the transcriber only sees one phrase at a time). Your words are never changed.
    LaunchedEffect(liveRaw, listening) {
        val src = liveRaw
        if (!listening || engine != VoiceSettings.Engine.Realtime || src.isBlank() || polished?.first == src) return@LaunchedEffect
        if (!VoiceSettings.tidyWithClaude(context) || !com.pulsepointlabs.sarahmonitor.core.ClaudeText.available) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        if (tidyBusy) return@LaunchedEffect
        // Runs outside this effect so new words arriving don't cancel it; it applies only if still a prefix.
        tidyBusy = true
        scope.launch {
            try {
                val out = com.pulsepointlabs.sarahmonitor.core.TranscriptTidy.tidy(src)
                if (out != null && liveRaw.startsWith(stem(src))) { polished = src to out; note = shown(liveRaw) }
            } finally { tidyBusy = false }
        }
    }
    // No activity behind an overlay panel to ask for permission from.
    val micPermission = if (inOverlay) null else rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) listen() else status = "Microphone permission needed" }

    EventFrame(
        inOverlay, onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (editing == null) "Mark event" else if (editing.id.isEmpty()) "Add event" else "Edit event", color = MonitorColors.text, fontWeight = FontWeight.Bold)
                    Text(clockLabel(pressedAtMs, seconds = true), color = MonitorColors.label, fontSize = 13.sp)
                }
                last?.let { l ->
                    // One tap repeats the previous event (types and note).
                    Text(
                        "↻ Repeat last", color = MonitorColors.pelvic, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(1.dp, MonitorColors.pelvic.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .clickable { onSave(l.types, l.note, com.pulsepointlabs.sarahmonitor.session.CheckIn()) }.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // What the sensors read at this moment, so you can agree or disagree with them.
                if (editing == null) RightNow(checkIn.stressFeedback) { checkIn = checkIn.copy(stressFeedback = it) }
                if (timing != null) TimingEditor(timing, sessionStartMs, onTiming)
                // Event types in tabs (one per group this session uses); a dot marks tabs with something picked.
                var tab by remember { mutableStateOf(groups.firstOrNull { g -> MarkerTypes.inGroup(g).any { it.id in types } } ?: groups.first()) }
                if (groups.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { g ->
                        // Badge = how many types are picked on that tab, so picks on other tabs aren't forgotten.
                        val n = (MarkerTypes.inGroup(g) + MarkerTypes.inGroup(MarkerTypes.Group.Sensor)).count { it.id in types }
                        val on = g == tab
                        Row(
                            Modifier.weight(1f).heightIn(min = 34.dp).background(if (on) MonitorColors.text else Color.Transparent, RoundedCornerShape(8.dp))
                                .border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(8.dp)).clickable { tab = g },
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (g == MarkerTypes.Group.Procedure) "Procedure" else g.title, color = if (on) MonitorColors.background else MonitorColors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            if (n > 0) androidx.compose.foundation.layout.Box(
                                Modifier.size(18.dp).background(MonitorColors.pelvic, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$n", color = MonitorColors.background, fontWeight = FontWeight.Bold,
                                    style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, lineHeight = 11.sp, platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // "Sensor problem" sits on every tab: it can happen in any kind of session.
                    (MarkerTypes.inGroup(tab) + MarkerTypes.inGroup(MarkerTypes.Group.Sensor)).forEach { type ->
                        val on = type.id in types
                        val color = markerColor(type.id)
                        Text(
                            (if (on) "✓ " else "") + type.label,
                            color = if (on) MonitorColors.background else color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier
                                .heightIn(min = 34.dp)
                                .background(if (on) color else color.copy(alpha = 0.08f), RoundedCornerShape(17.dp))
                                .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(17.dp))
                                .clickable { types = if (on && types.size > 1) types - type.id else types + type.id }
                                .padding(horizontal = 11.dp, vertical = 7.dp)
                        )
                    }
                }
                // Device and check-in as two compact dropdowns.
                val gear = MonitorHub.sessionDetails.collectAsState().value.gear
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editing == null && gear.isNotEmpty()) {
                        DropField("Device", gear.firstOrNull { it.id == checkIn.gearId }?.label ?: "None", Color(0xFF93C5FD), Modifier.weight(1f)) { close ->
                            (listOf<com.pulsepointlabs.sarahmonitor.session.GearItem?>(null) + gear).forEach { g ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(g?.label ?: "None", color = if (g?.id == checkIn.gearId) Color(0xFF93C5FD) else MonitorColors.text, fontSize = 14.sp) },
                                    onClick = { checkIn = checkIn.copy(gearId = g?.id); close() }
                                )
                            }
                        }
                    }
                    DropField("How you are", checkIn.label ?: "Add a check-in", MonitorColors.rmssd, Modifier.weight(1f)) { _ ->
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CheckInPicker(checkIn) { checkIn = it.copy(gearId = checkIn.gearId, stressFeedback = checkIn.stressFeedback) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4
                    )
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                        else micPermission?.launch(Manifest.permission.RECORD_AUDIO) ?: run { status = "Microphone permission needed" }
                    }) {
                        Icon(
                            if (listening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (listening) "Stop listening" else "Voice note",
                            tint = if (listening) MonitorColors.alarm else MonitorColors.pelvic
                        )
                    }
                }
                if (listening) MicLevelMeter(level, (if (engine == VoiceSettings.Engine.Whisper) "Recording" else status.ifBlank { "Listening…" }) + if (micLabel.isNotBlank()) " · $micLabel" else "")
                else if (status.isNotBlank()) Text(status, color = MonitorColors.label, fontSize = 12.sp)
            }
        },
        confirm = {
            androidx.compose.material3.Button(colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MonitorColors.pelvic, contentColor = MonitorColors.background), onClick = {
                if (engine == VoiceSettings.Engine.Realtime && listening) stopRealtime { onSave(types, it, checkIn) }
                else if (engine == VoiceSettings.Engine.Android && listening) { recognizer.stop(); onSave(types, note, checkIn) }
                else if (!whisperRecording) onSave(types, note, checkIn) else {
                    // Saving mid-recording: finish the transcription first so the words aren't lost.
                    whisperRecording = false
                    status = "Transcribing with Whisper…"
                    scope.launch {
                        val text = runCatching { whisper.stopAndTranscribe(VoiceSettings.apiKey(context)) }.getOrNull()
                        val clean = text?.let { VoiceTextNormalizer.normalize(it, fixStrayCaps = false) }.orEmpty()
                        onSave(types, if (clean.isBlank()) note else if (note.isBlank()) clean else note.trimEnd() + " " + clean, checkIn)
                    }
                }
            }) { Text("Save", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
        },
        dismiss = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MonitorColors.alarm) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = MonitorColors.label) }
            }
        }
    )
}


/** An AlertDialog in the app; a matching floating panel when shown as an overlay. */
@Composable
fun EventFrame(
    inOverlay: Boolean,
    onDismiss: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: @Composable () -> Unit
) {
    if (!inOverlay) {
        AlertDialog(onDismissRequest = onDismiss, containerColor = MonitorColors.panel, title = title, text = text, confirmButton = confirm, dismissButton = dismiss)
        return
    }
    Column(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .background(MonitorColors.panel, RoundedCornerShape(22.dp))
            .border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(22.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.MaterialTheme.typography.headlineSmall) { title() }
        Column(Modifier.weight(1f, fill = false)) { text() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { dismiss(); confirm() }
    }
}


/** "How are you?" — feeling 1–5 and stress 0–10. Tap to pick, tap again to clear. Both optional. */
@OptIn(ExperimentalLayoutApi::class)
/** A compact dropdown: label + current value; [content] fills the menu (call close() to shut it). */
@Composable
private fun DropField(label: String, value: String, color: Color, modifier: Modifier, content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).background(color.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = MonitorColors.label, fontSize = 11.sp)
                Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = color)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MonitorColors.panel) { content { open = false } }
    }
}

/**
 * The live read at the moment of the event: stress (with its trend and your verdict), heart, load, HRV,
 * breathing, pelvic and arousal phase. Saved with the event either way; the verdict helps tune the estimate.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RightNow(feedback: String?, onFeedback: (String?) -> Unit) {
    val heart by MonitorHub.heart.collectAsState()
    val stress by MonitorHub.stress.collectAsState()
    val response by MonitorHub.response.collectAsState()
    val pelvic by MonitorHub.pelvic.collectAsState()
    val recent by MonitorHub.recent.collectAsState()
    fun arrow(m: com.pulsepointlabs.sarahmonitor.core.Metric): String { val t = recent.trend(m); return if (t > 0.25) " ↑" else if (t < -0.25) " ↓" else "" }
    @Composable
    fun chip(label: String, value: String, color: Color) = Row(
        Modifier.background(color.copy(alpha = 0.1f), RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label ", color = MonitorColors.label, fontSize = 12.sp)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Column(
        Modifier.fillMaxWidth().background(Color(0x0DFFFFFF), RoundedCornerShape(10.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            stress.score?.let { chip("Stress", "$it ${stress.level?.label?.lowercase() ?: ""}${arrow(com.pulsepointlabs.sarahmonitor.core.Metric.Stress)}", com.pulsepointlabs.sarahmonitor.ui.StressColor) }
            heart.heartRate?.let { chip("HR", "$it${arrow(com.pulsepointlabs.sarahmonitor.core.Metric.Hr)}", MonitorColors.hr) }
            heart.load.score?.let { chip("Load", "$it${arrow(com.pulsepointlabs.sarahmonitor.core.Metric.Load)}", MonitorColors.load) }
            heart.rmssdMs?.let { chip("HRV", "${fmt(it, 0)} ms", MonitorColors.rmssd) }
            response.breathsPerMin?.let { chip("Breathing", "${it.toInt()}/min", MonitorColors.pelvic) }
            pelvic.aboveRestKpa?.let { chip("Pelvic", "${fmt(it, 1)} kPa", MonitorColors.pelvic) }
            if (response.phase != com.pulsepointlabs.sarahmonitor.response.ResponsePhase.Baseline) chip("Phase", response.phase.label, Color(0xFFFF7AB6))
        }
        if (stress.score != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Stress read feels", color = MonitorColors.label, fontSize = 12.sp)
            listOf("right" to "Right", "high" to "Too high", "low" to "Too low").forEach { (k, l) ->
                val on = feedback == k
                Text(
                    l, color = if (on) MonitorColors.background else com.pulsepointlabs.sarahmonitor.ui.StressColor, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(if (on) com.pulsepointlabs.sarahmonitor.ui.StressColor else Color.Transparent, RoundedCornerShape(12.dp))
                        .border(1.dp, com.pulsepointlabs.sarahmonitor.ui.StressColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .clickable { onFeedback(if (on) null else k) }.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CheckInPicker(value: com.pulsepointlabs.sarahmonitor.session.CheckIn, onChange: (com.pulsepointlabs.sarahmonitor.session.CheckIn) -> Unit) {
    val spectrum = listOf(MonitorColors.alarm, MonitorColors.load, MonitorColors.rmssd, MonitorColors.good, Color(0xFF34D399))
    Text("How are you?", color = MonitorColors.label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Awful", "Low", "OK", "Good", "Great").forEachIndexed { i, l ->
            val on = value.wellbeing == i + 1
            val c = spectrum[i]
            Text(
                l, color = if (on) MonitorColors.background else c, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(if (on) c else c.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .border(1.dp, c.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .clickable { onChange(value.copy(wellbeing = if (on) null else i + 1)) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
    Text("Stress" + (value.selfStress?.let { " · $it/10" } ?: ""), color = MonitorColors.label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (0..10).forEach { n ->
            val on = value.selfStress == n
            val c = com.pulsepointlabs.sarahmonitor.ui.percentBandColor(n * 10) ?: MonitorColors.label
            Text(
                "$n", color = if (on) MonitorColors.background else c, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .background(if (on) c else c.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { onChange(value.copy(selfStress = if (on) null else n)) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}
