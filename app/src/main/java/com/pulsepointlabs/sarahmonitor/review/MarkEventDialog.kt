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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
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

/** Color per event type, used for chips, chart marker lines and the event list. */
fun markerColor(types: String): Color = when (MarkerTypes.split(types).firstOrNull()) {
    "symptom" -> MonitorColors.warn
    "sensor_issue" -> MonitorColors.alarm
    "activity" -> MonitorColors.sdnn
    "position" -> Color(0xFF7DD3FC)
    "intake" -> MonitorColors.glucose
    "substance" -> Color(0xFFF0ABFC)
    "state" -> Color(0xFF5EEAD4)
    "question" -> Color(0xFF93C5FD)
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
            // Saved or canceled: whatever was playing when you started dictating carries on.
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

    fun mic() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else micPermission?.launch(Manifest.permission.RECORD_AUDIO) ?: run { status = "Microphone permission needed" }
    }
    fun save() {
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
    }
    val tabs = remember(groups) { EventChoices.tabs(groups) }
    val civet by MonitorHub.civet.state.collectAsState()
    val sensorOn = civet == com.pulsepointlabs.sarahmonitor.ble.ConnectionState.Connected || civet == com.pulsepointlabs.sarahmonitor.ble.ConnectionState.Stale

    EventFrame(
        inOverlay, onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (editing == null) "Mark event" else if (editing.id.isEmpty()) "Add event" else "Edit event", color = MonitorColors.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(clockLabel(pressedAtMs, seconds = true), color = MonitorColors.label, fontSize = 13.sp)
                }
                last?.let { l ->
                    // One tap repeats the previous event (types and note).
                    Text(
                        "↻ Repeat last", color = MonitorColors.pelvic, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.heightIn(min = 40.dp).border(1.dp, MonitorColors.pelvic.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .clickable { onSave(l.types, l.note, com.pulsepointlabs.sarahmonitor.session.CheckIn()) }.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 1. What happened: your words first, typed or spoken.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = { Text("What happened? Type, or tap the mic", color = MonitorColors.label) },
                        modifier = Modifier.weight(1f),
                        minLines = 2,
                        maxLines = 6
                    )
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(56.dp)
                            .background((if (listening) MonitorColors.alarm else MonitorColors.pelvic).copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape)
                            .border(2.dp, if (listening) MonitorColors.alarm else MonitorColors.pelvic, androidx.compose.foundation.shape.CircleShape)
                            .clickable { mic() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (listening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (listening) "Stop listening" else "Speak the note",
                            tint = if (listening) MonitorColors.alarm else MonitorColors.pelvic,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                if (listening) MicLevelMeter(level, (if (engine == VoiceSettings.Engine.Whisper) "Recording" else status.ifBlank { "Listening…" }) + if (micLabel.isNotBlank()) " · $micLabel" else "")
                else if (status.isNotBlank()) Text(status, color = MonitorColors.label, fontSize = 12.sp)

                // 2. What kind: only what fits this session (a general session has no arousal steps).
                Section("What kind") {
                    var tab by remember { mutableStateOf(tabs.firstOrNull { t -> t.types.any { it.id in types } } ?: tabs.first()) }
                    if (tabs.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tabs.forEach { t ->
                            val n = t.types.count { it.id in types }
                            val on = t == tab
                            Row(
                                Modifier.weight(1f).heightIn(min = 40.dp).background(if (on) MonitorColors.text else Color.Transparent, RoundedCornerShape(10.dp))
                                    .border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(10.dp)).clickable { tab = t },
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(t.title, color = if (on) MonitorColors.background else MonitorColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                if (n > 0) androidx.compose.foundation.layout.Box(
                                    Modifier.size(18.dp).background(MonitorColors.pelvic, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center
                                ) {
                                    Text("$n", color = MonitorColors.background, fontWeight = FontWeight.Bold,
                                        style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, lineHeight = 11.sp, platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val shownTypes = tab.types.filter { it.id !in EventChoices.HIDDEN || it.id in types } +
                            // The pressure sensor only matters when it's in use.
                            MarkerTypes.inGroup(MarkerTypes.Group.Sensor).filter { sensorOn || it.id in types }
                        shownTypes.forEach { type ->
                            val on = type.id in types
                            ChoicePill(EventChoices.label(type), on, markerColor(type.id)) {
                                types = if (on && types.size > 1) types - type.id else if (on) types else (types - "event") + type.id
                            }
                        }
                    }
                }

                // 3. How you are (optional): one tap each, nothing to open or close.
                Section("How you feel" + (checkIn.label?.let { " · $it" } ?: ""), hint = "optional") {
                    val spectrum = listOf(MonitorColors.alarm, MonitorColors.load, MonitorColors.rmssd, MonitorColors.good, Color(0xFF34D399))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.pulsepointlabs.sarahmonitor.session.FEELING.forEachIndexed { i, l ->
                            val on = checkIn.wellbeing == i + 1
                            ChoicePill(l, on, spectrum[i], Modifier.weight(1f), center = true) { checkIn = checkIn.copy(wellbeing = if (on) null else i + 1) }
                        }
                    }
                    Text("Stress" + (checkIn.selfStress?.let { " · $it/10" } ?: ""), color = MonitorColors.label, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        (0..10).forEach { n ->
                            val on = checkIn.selfStress == n
                            val c = com.pulsepointlabs.sarahmonitor.ui.percentBandColor(n * 10) ?: MonitorColors.label
                            ChoicePill("$n", on, c, Modifier.weight(1f), center = true, tight = true) { checkIn = checkIn.copy(selfStress = if (on) null else n) }
                        }
                    }
                }

                // 4. Which device it's about (only when the session has devices).
                val gear = MonitorHub.sessionDetails.collectAsState().value.gear
                if (editing == null && gear.isNotEmpty()) Section("Device", hint = "optional") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoicePill("None", checkIn.gearId == null, Color(0xFF93C5FD)) { checkIn = checkIn.copy(gearId = null) }
                        gear.forEach { g -> ChoicePill(g.type.label, checkIn.gearId == g.id, Color(0xFF93C5FD)) { checkIn = checkIn.copy(gearId = if (checkIn.gearId == g.id) null else g.id) } }
                    }
                }

                if (timing != null) TimingEditor(timing, sessionStartMs, onTiming)
                // What the sensors read at this moment, so you can agree or disagree with them.
                if (editing == null) RightNow(checkIn.stressFeedback) { checkIn = checkIn.copy(stressFeedback = it) }
            }
        },
        confirm = {
            androidx.compose.material3.Button(
                onClick = { save() },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MonitorColors.pelvic, contentColor = MonitorColors.background)
            ) { Text("Save", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        },
        dismiss = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MonitorColors.alarm, fontSize = 15.sp) }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", color = MonitorColors.label, fontSize = 15.sp) }
            }
        }
    )
}

/** Which event types the Mark event panel offers, by session, in a sensible order. */
object EventChoices {
    data class Tab(val title: String, val types: List<MarkerTypes.Type>)

    /** Kept for voice and for editing old events, but not offered as a chip (it confused general sessions). */
    val HIDDEN = setOf("recovery")

    private val ORDER = listOf(
        "activity", "state", "substance", "intake", "symptom", "position",
        "stimulation", "arousal", "plateau", "edge", "near", "orgasm", "ejaculation", "resolution", "sensation", "finding", "catheter", "enema",
        "prep", "insert", "resistance", "seated", "balloon", "instill", "instill_end", "hold", "urge", "release", "urine", "discomfort", "adjust", "estim_change", "remove",
        "event", "recovery"
    )

    fun label(t: MarkerTypes.Type) = if (t.id == "event") "Other" else t.label

    /**
     * One tab per kind of event the session uses: a general session gets General only; sexual sessions get Sexual
     * plus a General tab with what he took, how he feels and symptoms; exploration gets Procedure, Sexual and General.
     */
    fun tabs(groups: List<MarkerTypes.Group>): List<Tab> {
        val offered = MarkerTypes.offered(groups).filter { it.group != MarkerTypes.Group.Sensor && it.group != MarkerTypes.Group.Voice }
        val order = groups + MarkerTypes.Group.General
        return order.distinct().mapNotNull { g ->
            val ts = offered.filter { it.group == g }.sortedBy { ORDER.indexOf(it.id).let { i -> if (i < 0) 99 else i } }
            if (ts.isEmpty()) null else Tab(if (g == MarkerTypes.Group.Procedure) "Procedure" else g.title, ts)
        }
    }
}

/** A labeled block in the event panel. */
@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (hint != null) Text("  $hint", color = MonitorColors.label, fontSize = 12.sp)
        }
        content()
    }
}

/** A tap target that's easy to hit and plainly on or off (filled when chosen). */
@Composable
private fun ChoicePill(label: String, on: Boolean, color: Color, modifier: Modifier = Modifier, center: Boolean = false, tight: Boolean = false, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier
            .heightIn(min = 42.dp)
            .background(if (on) color else color.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = if (on) 1f else 0.55f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (tight) 0.dp else 14.dp, vertical = 8.dp),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart
    ) {
        Text(
            label, color = if (on) MonitorColors.background else color, fontSize = if (tight) 13.sp else 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip
        )
    }
}

/**
 * The event panel's frame: in the app, a window that moves up with the keyboard and scrolls (the stock dialog
 * let the keyboard cover the fields); as an overlay, the same panel floating over other apps.
 */
@Composable
fun EventFrame(
    inOverlay: Boolean,
    onDismiss: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: @Composable () -> Unit
) {
    val panel: @Composable (Modifier) -> Unit = { m ->
        Column(
            m
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .background(MonitorColors.panel, RoundedCornerShape(22.dp))
                .border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(22.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            title()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { text() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { dismiss(); confirm() }
        }
    }
    if (inOverlay) { panel(Modifier.heightIn(max = 760.dp)); return }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                // Status bar, navigation bar and the keyboard: the panel always sits in what's left.
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) { panel(Modifier) }
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
    // Nothing connected: no empty box.
    if (stress.score == null && heart.heartRate == null && heart.load.score == null && heart.rmssdMs == null && response.breathsPerMin == null && pelvic.aboveRestKpa == null) return
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
            pelvic.aboveRestKpa?.let { chip("Pelvic", com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(it), MonitorColors.pelvic) }
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

