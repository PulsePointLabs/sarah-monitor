package com.pulsepointlabs.sarahmonitor.session

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.pulsepointlabs.sarahmonitor.core.ClaudeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Hands-free settings: say "Sarah, …" during a recording to log an event without touching anything. */
object HandsFreeSettings {
    private var prefs: SharedPreferences? = null
    val enabled = MutableStateFlow(false)
    /** Sarah says back what she logged ("Logged: resistance, 3 out of 10"). */
    val sayBack = MutableStateFlow(true)

    @Synchronized
    fun load(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("sarahmonitor_handsfree", Context.MODE_PRIVATE)
        prefs = p
        enabled.value = p.getBoolean("enabled", false)
        sayBack.value = p.getBoolean("say_back", true)
    }
    fun setEnabled(v: Boolean) { enabled.value = v; prefs?.edit()?.putBoolean("enabled", v)?.apply() }
    fun setSayBack(v: Boolean) { sayBack.value = v; prefs?.edit()?.putBoolean("say_back", v)?.apply() }
}

/** What a spoken note turned into. [bucket] is the kind of observation (sensation, procedure step, …). */
data class SpokenEvent(
    val types: Set<String>,
    val gearId: String?,
    val note: String,
    val bucket: String,
    val intensity: Int? = null,
    val site: String? = null,
    val confirm: String,
    val parsedBy: String
)

/** Pure parsing for hands-free commands (unit-tested). */
object VoiceCommand {
    private val WAKE = Regex("^(?:(?:ok|okay|hey|hi|so|and)\\s+)?(sarah|sara|sera|sarrah|serah)\\b[\\s,.:!?-]*", RegexOption.IGNORE_CASE)

    /** The command after the wake word; "" when the wake word was said on its own; null when it wasn't said. */
    fun afterWake(utterance: String): String? {
        val t = utterance.trim()
        val m = WAKE.find(t) ?: return null
        return t.substring(m.range.last + 1).trim().trimEnd('.').trim()
    }

    val BUCKETS = listOf("procedure_step", "sensation", "arousal_change", "near_climax", "climax", "physiological_change", "body_finding", "comfort", "general")

    private val NUMBER_WORDS = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)

    /** "3 out of 10", "three out of ten", "7/10". */
    fun intensity(text: String): Int? {
        val m = Regex("\\b(\\d{1,2}|zero|one|two|three|four|five|six|seven|eight|nine|ten)\\s*(?:out of|/|of)\\s*(?:10|ten)\\b", RegexOption.IGNORE_CASE).find(text) ?: return null
        val v = m.groupValues[1].lowercase()
        return (v.toIntOrNull() ?: NUMBER_WORDS[v])?.takeIf { it in 0..10 }
    }

    // Plain keyword reading, used without an Anthropic key (or if Claude can't be reached).
    private val RULES: List<Pair<Regex, Pair<String, String>>> = listOf(
        Regex("\\b(lube|lubric|prep(?:ping|ped)?|clean(?:ing)?|betadine|swab)") to ("prep" to "procedure_step"),
        Regex("\\b(resistance|resisting|tight(?:ness)?|stuck)") to ("resistance" to "procedure_step"),
        Regex("\\b(fully (?:in|inserted|seated)|all the way in|in the bladder|seated)") to ("seated" to "procedure_step"),
        Regex("\\b(insert(?:ing|ed|ion)?|advanc(?:e|ing|ed)|going in)") to ("insert" to "procedure_step"),
        Regex("\\bballoon") to ("balloon" to "procedure_step"),
        Regex("\\b(urine|flashback|drainage|draining)") to ("urine" to "procedure_step"),
        Regex("\\b(instil+(?:ing|ed|ation)?|filling|fluid (?:going|flowing))") to ("instill" to "procedure_step"),
        Regex("\\b(retain(?:ing)?|retention|holding it)") to ("hold" to "procedure_step"),
        Regex("\\b(urge|cramp(?:ing)?)") to ("urge" to "sensation"),
        Regex("\\b(release|expel(?:ling)?|evacuat)") to ("release" to "procedure_step"),
        Regex("\\b(remov(?:e|ing|ed|al)|withdraw(?:ing|n)?|pull(?:ing)? (?:it )?out)") to ("remove" to "procedure_step"),
        Regex("\\b(reposition|adjust(?:ing|ed)?)") to ("adjust" to "procedure_step"),
        Regex("\\b(e-?stim|howl|channel [ab])") to ("estim_change" to "procedure_step"),
        Regex("\\b(pinch|pain|burn(?:ing)?|sting(?:ing)?|hurt|discomfort|sore)") to ("discomfort" to "sensation"),
        Regex("\\b(edg(?:e|ing)|backing off|backed off)") to ("edge" to "near_climax"),
        Regex("\\b(close to|near(?:ly)?|almost) (?:orgasm|climax|cumming|coming)|\\bpoint of no return") to ("near" to "near_climax"),
        Regex("\\b(orgasm(?:ing)?|cumming|climax(?:ing)?)") to ("orgasm" to "climax"),
        Regex("\\bejaculat") to ("ejaculation" to "climax"),
        Regex("\\b(softer|soft|losing (?:my )?erection|flaccid|harder|firmer|erection|swollen|flush(?:ed|ing)?|scrot|testicle|retract)") to ("finding" to "body_finding"),
        Regex("\\b(arous(?:al|ed)|turned on|building|plateau)") to ("arousal" to "arousal_change"),
        Regex("\\b(tingl|warm(?:th)?|pressure|fullness|sensitiv|feels?)") to ("sensation" to "sensation"),
        Regex("\\b(heart (?:is )?racing|breathing|sweat|shak(?:y|ing)|dizzy|lightheaded)") to ("symptom" to "physiological_change")
    )

    private val DEVICE_WORDS = mapOf(
        GearType.Foley to Regex("\\b(foley|catheter|cath)\\b"), GearType.Sound to Regex("\\bsound(?:ing)?\\b"),
        GearType.Enema to Regex("\\b(enema|nozzle|bag)\\b"), GearType.Estim to Regex("\\b(e-?stim|howl|pads?)\\b"),
        GearType.Sleeve to Regex("\\b(sleeve|stroker)\\b"), GearType.AnalToy to Regex("\\b(plug|dilator|dildo|prostate)\\b"),
        GearType.Lubricant to Regex("\\b(lube|lubricant)\\b"), GearType.Vibrator to Regex("\\b(vibrator|wand)\\b")
    )

    fun parseLocal(command: String, allowed: Set<String>, gear: List<GearItem>): SpokenEvent {
        val lower = command.lowercase()
        val hits = RULES.filter { (r, _) -> r.containsMatchIn(lower) }.map { it.second }.filter { it.first in allowed }
        val types = hits.map { it.first }.distinct().take(3).toSet().ifEmpty { setOf(if ("event" in allowed) "event" else allowed.first()) }
        val bucket = hits.firstOrNull()?.second ?: "general"
        val device = gear.firstOrNull { g -> DEVICE_WORDS[g.type]?.containsMatchIn(lower) == true }
            ?: gear.singleOrNull { it.type !in setOf(GearType.Lubricant) }.takeIf { bucket == "procedure_step" }
        val note = command.trim().replaceFirstChar { it.uppercase() }.let { if (it.last() in ".!?") it else "$it." }
        val level = intensity(command)
        val what = MarkerTypes.label(types).lowercase()
        return SpokenEvent(types, device?.id, note, bucket, level, null, "Logged: $what" + (level?.let { ", $it out of 10" } ?: "") + ".", "keywords")
    }

    /** Prompt for Claude: map a spoken note to this session's event types and devices. */
    fun claudePrompt(allowed: List<MarkerTypes.Type>, gear: List<GearItem>): String = """
You turn a short spoken note from a paramedic logging his own body during a physiology session into one structured event.
Allowed event types (id: label): ${allowed.joinToString("; ") { "${it.id}: ${it.label}" }}.
Devices in use (id: label): ${gear.joinToString("; ") { "${it.id}: ${it.label}" }.ifBlank { "none" }}.
Buckets: ${BUCKETS.joinToString(", ")}.
Reply with JSON only: {"types":[1-3 ids from the allowed list],"device":id or null,"bucket":one bucket,"intensity":0-10 or null (only if he gave a number or a clear severity),"site":anatomical site or null,"note":his note as one clean first-person sentence (keep his meaning and words; fix grammar only),"confirm":a spoken confirmation of at most 10 words, no names}.
""".trimIndent()

    fun parseClaude(json: String, allowed: Set<String>, gear: List<GearItem>): SpokenEvent? = runCatching {
        val o = JSONObject(json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1))
        val types = (0 until (o.optJSONArray("types")?.length() ?: 0)).map { o.getJSONArray("types").getString(it) }.filter { it in allowed }.toSet()
        if (types.isEmpty()) return null
        val device = o.optString("device").takeIf { id -> gear.any { it.id == id } }
        SpokenEvent(
            types, device, o.optString("note").trim().ifBlank { return null },
            o.optString("bucket").takeIf { it in BUCKETS } ?: "general",
            o.optInt("intensity", -1).takeIf { !o.isNull("intensity") && it in 0..10 },
            o.optString("site").takeIf { it.isNotBlank() && it != "null" },
            o.optString("confirm").trim().ifBlank { "Logged." }.replace(Regex("(?i)\\bsarah\\b"), "").trim(),
            "claude"
        )
    }.getOrNull()
}

data class HandsFreeStatus(val listening: Boolean = false, val message: String = "Off", val lastLogged: String? = null)

/**
 * Hands-free event logging. While on and a session is recording, the mic streams to live transcription; any
 * phrase that starts with "Sarah" becomes an event at the moment you started saying it. "Sarah" on its own gets a
 * tone and the next phrase is taken as the note. Claude sorts it into the session's event types and devices
 * (plain keyword rules without a key). Never logs anything that wasn't addressed to Sarah.
 */
class HandsFree(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mark: (atMs: Long, event: SpokenEvent, raw: String) -> Unit,
    /** true = "listening" tone, false = "logged" tone. */
    private val cue: (Boolean) -> Unit,
    private val speak: (String) -> Unit,
    private val allowedTypes: () -> List<MarkerTypes.Type>,
    private val gear: () -> List<GearItem>
) {
    private val transcriber = com.pulsepointlabs.sarahmonitor.session.RealtimeTranscriber(context)
    private val _status = MutableStateFlow(HandsFreeStatus())
    val status: StateFlow<HandsFreeStatus> = _status
    private var running = false
    private var paused = false
    private var wanted = false
    private var restart: Job? = null
    /** After a bare "Sarah": the next phrase (within 10 s) is the note, timed from when "Sarah" was said. */
    private var awaitingSince: Long? = null

    /** Start/stop to match: enabled, recording, not paused for dictation, key + mic available. */
    @Synchronized
    fun sync(recording: Boolean) {
        wanted = HandsFreeSettings.enabled.value && recording
        val key = VoiceSettings.apiKey(context)
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when {
            !wanted || paused -> stop(if (!HandsFreeSettings.enabled.value) "Off" else if (paused) "Paused while you dictate" else "Waiting for a recording")
            key.isBlank() -> stop("Needs the OpenAI key (Settings → API keys)")
            !mic -> stop("Needs microphone permission")
            !running -> start(key)
        }
    }

    /** Dictation in the Mark event panel needs the mic: step aside while it runs. */
    @Synchronized
    fun pause(on: Boolean, recording: Boolean) { paused = on; sync(recording) }

    private fun start(key: String) {
        running = true
        _status.value = HandsFreeStatus(true, "Listening for \"Sarah…\"", _status.value.lastLogged)
        transcriber.start(
            key, "",
            onText = {}, onStatus = {}, onLevel = {},
            onError = { msg ->
                // Live sessions end now and then (time limits, network): reconnect while still wanted.
                synchronized(this) { running = false }
                _status.value = _status.value.copy(listening = false, message = "Reconnecting… ($msg)")
                restart?.cancel()
                restart = scope.launch { delay(3_000L); sync(wanted) }
            },
            onUtterance = { text, at -> heard(text, at) },
            continuous = true
        )
    }

    private fun stop(message: String) {
        restart?.cancel()
        if (running) { running = false; transcriber.stop() }
        awaitingSince = null
        _status.value = HandsFreeStatus(false, message, _status.value.lastLogged)
    }

    private fun heard(text: String, at: Long) {
        val command = VoiceCommand.afterWake(text)
        val waitingFrom = awaitingSince?.takeIf { at - it < 10_000L }
        when {
            command == "" -> { awaitingSince = at; cue(true); _status.value = _status.value.copy(message = "Go ahead…") }
            command != null -> handle(command, at, text)
            waitingFrom != null -> { awaitingSince = null; handle(text, waitingFrom, text) }
            else -> Unit // not for Sarah
        }
    }

    private fun handle(command: String, at: Long, raw: String) {
        if (command.isBlank()) return
        val allowed = allowedTypes()
        val ids = allowed.map { it.id }.toSet()
        val g = gear()
        _status.value = _status.value.copy(message = "Logging…")
        scope.launch {
            val event = ClaudeText.ask(VoiceCommand.claudePrompt(allowed, g), command, maxTokens = 400)?.let { VoiceCommand.parseClaude(it, ids, g) }
                ?: VoiceCommand.parseLocal(command, ids, g)
            mark(at, event, raw)
            cue(false)
            if (HandsFreeSettings.sayBack.value) speak(event.confirm)
            _status.value = _status.value.copy(message = "Listening for \"Sarah…\"", lastLogged = event.note)
        }
    }
}
