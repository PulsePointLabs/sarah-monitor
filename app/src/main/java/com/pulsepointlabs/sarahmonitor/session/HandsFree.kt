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
    /** Listen through a Bluetooth headset's mic. Off by default: it switches the headset to call-quality audio. */
    val headsetMic = MutableStateFlow(false)

    @Synchronized
    fun load(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("sarahmonitor_handsfree", Context.MODE_PRIVATE)
        prefs = p
        enabled.value = p.getBoolean("enabled", false)
        sayBack.value = p.getBoolean("say_back", true)
        headsetMic.value = p.getBoolean("headset_mic", false)
    }
    fun setEnabled(v: Boolean) { enabled.value = v; prefs?.edit()?.putBoolean("enabled", v)?.apply() }
    fun setSayBack(v: Boolean) { sayBack.value = v; prefs?.edit()?.putBoolean("say_back", v)?.apply() }
    fun setHeadsetMic(v: Boolean) { headsetMic.value = v; prefs?.edit()?.putBoolean("headset_mic", v)?.apply() }
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

    /**
     * The command text: from the cloud transcript when there is one (dropping the wake word, or whatever it was
     * misheard as, since the phone already heard "Sarah"), otherwise from the offline transcript.
     */
    fun commandFrom(cloud: String?, offline: String): String {
        if (cloud != null) return afterWake(cloud) ?: cloud.replaceFirst(Regex("^\\W*[\\p{L}']+[,.:!?]\\s*"), "").trim().trimEnd('.')
        return afterWake(offline) ?: offline.trim()
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
        // Word endings matter: "prepped" is a prep step, "the lab is prepared" isn't.
        Regex("\\b(lube\\b|lubric|prep(?:ping|ped|aring|are|s)?\\b|clean(?:ing|ed)?\\b|betadine|swab)") to ("prep" to "procedure_step"),
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
        val note = verbatim(command)
        val level = intensity(command)
        val what = MarkerTypes.label(types).lowercase()
        return SpokenEvent(types, device?.id, note, bucket, level, null, "Logged: $what" + (level?.let { ", $it out of 10" } ?: "") + ".", "keywords")
    }

    /** His words exactly as heard: only the first letter capitalised and a closing full stop added. Never rewritten. */
    fun verbatim(command: String): String =
        command.trim().replaceFirstChar { it.uppercase() }.let { if (it.isEmpty() || it.last() in ".!?…") it else "$it." }

    /** Prompt for Claude: map a spoken note to this session's event types and devices. */
    fun claudePrompt(allowed: List<MarkerTypes.Type>, gear: List<GearItem>): String = """
You categorise a short spoken note from a paramedic logging his own body during a physiology session. His words are saved verbatim; you only classify them.
Allowed event types (id: label): ${allowed.joinToString("; ") { "${it.id}: ${it.label}" }}.
Devices in use (id: label): ${gear.joinToString("; ") { "${it.id}: ${it.label}" }.ifBlank { "none" }}.
Buckets: ${BUCKETS.joinToString(", ")}.
Reply with JSON only: {"types":[1-3 ids from the allowed list],"device":id or null,"bucket":one bucket,"intensity":0-10 or null (only if he gave a number or a clear severity),"site":anatomical site or null,"confirm":a spoken confirmation of at most 10 words, no names}.
""".trimIndent()

    fun parseClaude(json: String, command: String, allowed: Set<String>, gear: List<GearItem>): SpokenEvent? = runCatching {
        val o = JSONObject(json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1))
        val types = (0 until (o.optJSONArray("types")?.length() ?: 0)).map { o.getJSONArray("types").getString(it) }.filter { it in allowed }.toSet()
        if (types.isEmpty()) return null
        val device = o.optString("device").takeIf { id -> gear.any { it.id == id } }
        SpokenEvent(
            types, device, verbatim(command).ifBlank { return null },
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
 * Hands-free event logging. The phone listens on its own (offline) for "Sarah"; only the phrase that follows is
 * sent to OpenAI for an accurate, punctuated transcript, and Claude sorts it into the session's event types and
 * devices (keyword rules without a key). The event is timed to when you started speaking. Without a network the
 * offline transcript is used, so nothing is lost. Never logs anything that wasn't addressed to Sarah.
 */
class HandsFree(
    private val context: Context,
    private val scope: CoroutineScope,
    /** [heardBy] = "cloud" (OpenAI transcript) or "offline" (the phone's own). */
    private val mark: (atMs: Long, event: SpokenEvent, raw: String, heardBy: String) -> Unit,
    /** true = "listening" tone, false = "logged" tone. */
    private val cue: (Boolean) -> Unit,
    private val speak: (String) -> Unit,
    private val allowedTypes: () -> List<MarkerTypes.Type>,
    private val gear: () -> List<GearItem>
) {
    private val _status = MutableStateFlow(HandsFreeStatus())
    val status: StateFlow<HandsFreeStatus> = _status
    private var paused = false
    private var wanted = false
    private var preparing: Job? = null
    private var restart: Job? = null
    private val ears = LocalEars(
        context, scope,
        onPhrase = { pcm, at, offline -> phrase(pcm, at, offline) },
        onWakeOnly = { cue(true); _status.value = _status.value.copy(message = "Go ahead…") },
        onProblem = { msg ->
            _status.value = _status.value.copy(listening = false, message = "Reconnecting… ($msg)")
            restart?.cancel()
            restart = scope.launch { delay(3_000L); sync(wanted) }
        }
    )

    /** Start/stop to match: enabled, recording, not paused for dictation, mic allowed, model ready. */
    @Synchronized
    fun sync(recording: Boolean) {
        wanted = HandsFreeSettings.enabled.value && recording
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when {
            !wanted || paused -> stop(if (!HandsFreeSettings.enabled.value) "Off" else if (paused) "Paused while you dictate" else "On · listens when you record")
            !mic -> stop("Needs microphone permission")
            ears.active || preparing?.isActive == true -> Unit
            else -> prepareAndStart()
        }
    }

    /** Re-open the mic (after changing which mic to use). */
    @Synchronized
    fun restart() { val w = wanted; stop("Switching microphone…"); sync(w) }

    /** Dictation in the Mark event panel needs the mic: step aside while it runs. */
    @Synchronized
    fun pause(on: Boolean, recording: Boolean) { paused = on; sync(recording) }

    private fun prepareAndStart() {
        preparing = scope.launch {
            try {
                if (!VoskModel.ready(context)) {
                    _status.value = _status.value.copy(listening = false, message = "Getting Sarah's offline ears (one time, 40 MB)…")
                    VoskModel.ensure(context) { p -> _status.value = _status.value.copy(message = "Getting Sarah's offline ears… $p%") }
                }
                val model = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VoskModel.load(context) }
                synchronized(this@HandsFree) {
                    if (!wanted || paused) return@launch
                    ears.start(model)
                }
                _status.value = _status.value.copy(listening = true, message = "Listening for \"Sarah…\"")
            } catch (e: Exception) {
                _status.value = _status.value.copy(listening = false, message = "Couldn't get the offline ears: ${e.message ?: "network"}. Retrying…")
                restart?.cancel()
                restart = scope.launch { delay(30_000L); sync(wanted) }
            }
        }
    }

    private fun stop(message: String) {
        restart?.cancel()
        preparing?.cancel(); preparing = null
        ears.stop()
        _status.value = HandsFreeStatus(false, message, _status.value.lastLogged)
    }

    /** A phrase that began with "Sarah": transcribe just this clip, sort it, log it. */
    private fun phrase(pcm: ShortArray, at: Long, offline: String) {
        _status.value = _status.value.copy(message = "Writing it down…")
        scope.launch {
            val key = VoiceSettings.apiKey(context)
            val heard = if (key.isNotBlank() && pcm.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val wav = com.pulsepointlabs.sarahmonitor.audio.BeatSynth.wav(LocalEars.normalized(pcm), LocalEars.RATE)
                runCatching { WhisperTranscriber.transcribe(wav, key, "phrase.wav", "audio/wav", "gpt-4o-transcribe") }
                    .recoverCatching { WhisperTranscriber.transcribe(wav, key, "phrase.wav", "audio/wav", "whisper-1") }
                    .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            } else null
            // The cloud transcript is better; the offline one keeps it working without a network.
            val text = heard ?: offline
            val command = VoiceCommand.commandFrom(heard, offline)
            if (command.isBlank()) { _status.value = _status.value.copy(message = "Listening for \"Sarah…\""); return@launch }
            handle(command, at, text, if (heard != null) "cloud" else "offline")
        }
    }

    private suspend fun handle(command: String, at: Long, raw: String, heardBy: String) {
        val allowed = allowedTypes()
        val ids = allowed.map { it.id }.toSet()
        val g = gear()
        val event = ClaudeText.ask(VoiceCommand.claudePrompt(allowed, g), command, maxTokens = 400)?.let { VoiceCommand.parseClaude(it, command, ids, g) }
            ?: VoiceCommand.parseLocal(command, ids, g)
        mark(at, event, raw, heardBy)
        cue(false)
        if (HandsFreeSettings.sayBack.value) speak(event.confirm)
        _status.value = _status.value.copy(message = "Listening for \"Sarah…\"", lastLogged = event.note)
    }
}
