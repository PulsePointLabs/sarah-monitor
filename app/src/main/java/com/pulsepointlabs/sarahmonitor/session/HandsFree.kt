package com.pulsepointlabs.sarahmonitor.session

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.pulsepointlabs.sarahmonitor.core.ClaudeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    /** Sarah answers in her own words (and remembers) instead of a plain "Logged: …". */
    val talk = MutableStateFlow(true)

    @Synchronized
    fun load(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("sarahmonitor_handsfree", Context.MODE_PRIVATE)
        prefs = p
        enabled.value = p.getBoolean("enabled", false)
        sayBack.value = p.getBoolean("say_back", true)
        headsetMic.value = p.getBoolean("headset_mic", false)
        talk.value = p.getBoolean("talk", true)
    }
    fun setTalk(v: Boolean) { talk.value = v; prefs?.edit()?.putBoolean("talk", v)?.apply() }
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
    val parsedBy: String,
    /** Numbers he gave: mL in, depth, scores, erection. */
    val measures: Measures = Measures(),
    /** He asked Sarah something (she answers out loud; the question is kept as an event). */
    val question: Boolean = false,
    /** What she answered. */
    val answer: String? = null
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

    val BUCKETS = listOf("procedure_step", "sensation", "arousal_change", "near_climax", "climax", "physiological_change", "body_finding", "comfort", "substance_use", "mood", "activity", "general")

    private val NUMBER_WORDS = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)

    /** "3 out of 10", "three out of ten", "7/10". */
    fun intensity(text: String): Int? {
        val m = Regex("\\b(\\d{1,2}|zero|one|two|three|four|five|six|seven|eight|nine|ten)\\s*(?:out of|/|of)\\s*(?:10|ten)\\b", RegexOption.IGNORE_CASE).find(text) ?: return null
        val v = m.groupValues[1].lowercase()
        return (v.toIntOrNull() ?: NUMBER_WORDS[v])?.takeIf { it in 0..10 }
    }

    // Plain keyword reading, used without an Anthropic key (or if Claude can't be reached).
    private val RULES: List<Pair<Regex, Pair<String, String>>> = listOf(
        // The pressure sensor itself (not a session device): "the probe slipped out", "re-seating the sensor".
        Regex("\\b(probe|sensor|civet)\\b.{0,40}\\b(slip(?:ped|ping|s)?|fell|fall(?:ing|s)?|came|com(?:e|ing) out|moved|dislodg|deflat|re-?seat|re-?inflat|pop(?:ped)?)|\\b(slip(?:ped)?|re-?seat(?:ed|ing)?|re-?inflat(?:ed|ing)?)\\b.{0,30}\\b(probe|sensor|civet)\\b") to ("sensor_issue" to "general"),
        // What he took, how he feels, what he's doing (the all-day log).
        Regex("\\b(indica|sativa|weed|cannabis|marijuana|thc|edible|gumm(?:y|ies)|beers?|wine|liquor|whiske?y|bourbon|vodka|drunk|buzzed|tipsy|coffee|caffeine|espresso|energy drink|cold brew|nicotine|vape|vaping|zyns?|cigarettes?|medication|meds)\\b") to ("substance" to "substance_use"),
        Regex("\\b(relax(?:ed|ing)?|calm|anxious|anxiety|stressed|irritable|tired|exhausted|wired|overstimulated|jittery|focused|foggy|content|happy|sad|down|chill|mellow|intoxicated|stoned|high|sleepy|restless)\\b") to ("state" to "mood"),
        Regex("\\b(chores?|walking|working|cooking|eating|shower(?:ing)?|laundry|dishes|vacuum|going to bed|watching|gaming|driving|resum(?:e|ing)|getting ready)\\b") to ("activity" to "activity"),
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

    private val SENSOR_WORDS = Regex("\\b(probe|sensor|civet|balloon)\\b")

    private val DEVICE_WORDS = mapOf(
        GearType.Foley to Regex("\\b(foley|catheter|cath)\\b"), GearType.Sound to Regex("\\bsound(?:ing)?\\b"),
        GearType.Enema to Regex("\\b(enema|nozzle|bag)\\b"), GearType.Estim to Regex("\\b(e-?stim|howl|pads?)\\b"),
        GearType.Sleeve to Regex("\\b(sleeve|stroker)\\b"), GearType.AnalToy to Regex("\\b(plug|dilator|dildo|prostate)\\b"),
        GearType.Lubricant to Regex("\\b(lube|lubricant)\\b"), GearType.Vibrator to Regex("\\b(vibrator|wand)\\b")
    )

    fun parseLocal(command: String, allowed: Set<String>, gear: List<GearItem>): SpokenEvent {
        val lower = command.lowercase()
        val hits = RULES.filter { (r, _) -> r.containsMatchIn(lower) }.map { it.second }.filter { it.first in allowed }
        // Nothing matched: a plain Event. (Falling back to the first offered type filed everything in a sexual
        // session as a Symptom.)
        val types = hits.map { it.first }.distinct().take(3).toSet().ifEmpty { setOf("event") }
        val bucket = hits.firstOrNull()?.second ?: "general"
        // The CIVET probe/sensor is the measuring instrument, not a session device: when he talks about it (or a
        // balloon without naming the catheter), don't fall back to the only device listed (that filed CIVET notes
        // under the Foley).
        val aboutSensor = SENSOR_WORDS.containsMatchIn(lower) || "sensor_issue" in types
        val device = gear.firstOrNull { g -> DEVICE_WORDS[g.type]?.containsMatchIn(lower) == true }
            ?: gear.singleOrNull { it.type !in setOf(GearType.Lubricant) }.takeIf { bucket == "procedure_step" && !aboutSensor }
        val note = tidy(command)
        val level = intensity(command)
        val what = MarkerTypes.label(types).lowercase()
        val m = ProcedureLog.parseLocal(command)
        return SpokenEvent(types, device?.id, note, bucket, level, null, "Logged: $what" + (m.spoken().takeIf { it.isNotBlank() }?.let { ", $it" } ?: level?.let { ", $it out of 10" } ?: "") + ".", "keywords",
            m, ProcedureLog.isQuestion(command))
    }

    // "End the session", "you can conclude the session", "stop recording", "wrap up the session now please", "we're
    // done, end it". Short and direct only, and never when negated ("don't end the session yet").
    private val END_VERB = "(?:end|stop|finish|conclude|close|wrap(?: it)? up|close out)"
    private val END_SESSION = listOf(
        Regex("\\b$END_VERB\\s+(?:out\\s+|up\\s+)?(?:the|this|my|our)?\\s*(?:session|recording)\\b"),
        Regex("\\b(?:session|recording)\\s+(?:is\\s+)?(?:over|done|finished|ended)\\b"),
        Regex("\\bwe(?:'re| are)\\s+(?:all\\s+)?done\\b.{0,20}\\b(?:end|stop|conclude|wrap)"),
        // "End it" / "wrap it up" on their own — but not a bare "stop" (that's just "be quiet").
        Regex("^(?:you can\\s+)?(?:end|conclude|finish|wrap) (?:it|things)(?: up)?(?: now)?(?: please)?$")
    )
    private val NEGATED = Regex("\\b(?:don't|do not|dont|not|never|yet|later|before|until|almost|about to|going to|gonna|when)\\b")

    private val NEVER_MIND = Regex("^(?:okay |ok )?(?:stop|stop talking|be quiet|quiet|shush|hush|never ?mind|cancel|nothing|that's all|forget it)(?: please)?$")

    /** "Sarah, stop" / "never mind": she's already gone quiet; nothing to log. */
    fun dismisses(command: String): Boolean =
        NEVER_MIND.matches(command.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim())

    /** True when the whole note is an instruction to end the recording. Pure; unit-tested. */
    fun endsSession(command: String): Boolean {
        val c = command.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("okay ").removePrefix("ok ").removePrefix("please ").trim()
        if (c.isBlank() || c.split(' ').size > 12 || NEGATED.containsMatchIn(c)) return false
        return END_SESSION.any { it.containsMatchIn(c) }
    }

    /** His words exactly as heard: only the first letter capitalized and a closing full stop added. Never rewritten. */
    fun verbatim(command: String): String =
        command.trim().replaceFirstChar { it.uppercase() }.let { if (it.isEmpty() || it.last() in ".!?…") it else "$it." }

    private val FILLER = Regex("(?i),?\\s*(?<![\\w'])(?:um+|uh+|uhm+|erm+|er|ah+|hmm+|mm+)(?![\\w'])[,.]?\\s*")
    private val CUT_WORD = Regex("(?<![\\w'])[A-Za-z']{1,8}-\\s+(?=[A-Za-z])")       // "I- I'm", "hold- holding"
    private val REPEAT = Regex("(?i)\\b([a-z']+)(?:[,.]?\\s+\\1\\b)+")              // "the the", "I, I"

    /**
     * Dictation-style clean-up without Claude: drops "um/uh", cut-off words and stammered repeats. Only ever removes
     * words; his raw words are saved separately. Pure; unit-tested.
     */
    fun tidy(command: String): String {
        val t = command.replace(CUT_WORD, "").replace(FILLER, " ").replace(REPEAT, "$1")
            .replace(Regex("\\s+([,.!?])"), "$1").replace(Regex("^[,.\\s]+"), "").replace(Regex("\\s+"), " ").trim()
        return verbatim(t.ifBlank { command })
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }
    private val NUMBER_WORD = Regex("^(?:\\d+(?:\\.\\d+)?|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|half|quarter)$")

    /**
     * Claude's cleaned-up version of the note, accepted only if it just removed stumbles: every word it kept is one he
     * said (in order), and it didn't gut the note. Anything reworded falls back to [tidy]. Pure; unit-tested.
     */
    fun acceptClean(clean: String?, command: String): String? {
        val c = clean?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val kept = words(c)
        val said = words(command)
        if (kept.isEmpty() || kept.size > said.size || kept.size * 2 < said.size) return null
        var i = 0
        for (w in kept) {
            val at = (i until said.size).firstOrNull { said[it] == w }
            if (at != null) i = at + 1 else if (!NUMBER_WORD.matches(w)) return null    // "two" ↔ "2" is fine
        }
        return verbatim(c)
    }

    /** Prompt for Claude: map a spoken note to this session's event types and devices. */
    fun claudePrompt(allowed: List<MarkerTypes.Type>, gear: List<GearItem>, sessionType: String? = null): String = """
You categorize a short spoken note from a paramedic logging his own body during a physiology session.
This session is: ${sessionType ?: "not stated"}. Pick types that fit this kind of session. In a masturbation, sexual or exploration session, notes about stimulation, a sleeve or toy, pleasure, arousal, sensations, getting close, edging or climax use those sexual types (stimulation, arousal, sensation, near, edge, orgasm, finding…), never Symptom. Symptom is only for an unpleasant physical complaint (headache, nausea, dizziness, palpitations, pain that isn't part of the activity). If nothing fits, use event. His raw words are saved separately; you classify them and give a clean written copy.
Allowed event types (id: label — what it means):
${allowed.joinToString("\n") { "- ${it.id}: ${it.label}" + (it.hint.takeIf { h -> h.isNotBlank() }?.let { h -> " — $h" } ?: "") }}
Choosing types: read the whole note for what he means, not single words. Pick the one type that best fits; add a second (at most three) only when the note clearly covers two different things, for example what he is doing and how he feels ("getting ready to resume chores, feeling fairly relaxed" = activity + state). Never use recovery for simply feeling relaxed or calm. Substance use (cannabis such as indica or sativa, alcohol such as beer, caffeine, nicotine, a medication) is substance, including how strongly he feels it ("feeling pretty buzzed from the beer", "overstimulated from the caffeine" = substance + state); note what it is in "measures".
Devices in use (id: label): ${gear.joinToString("; ") { "${it.id}: ${it.label}" }.ifBlank { "none" }}.
The CIVET rectal pressure probe/sensor is the measuring instrument, not one of these devices: if he is talking about the probe, the sensor or a balloon without naming the catheter, set "device" to null. Use "sensor_issue" (if allowed) when the sensor slipped, came out, moved, deflated or was re-seated or re-inflated.
Buckets: ${BUCKETS.joinToString(", ")}.
Procedure notes (enema, catheter, sounding, anal devices) often carry numbers: amount instilled, estimated depth of the tip or device, and 0-10 scores for urge to defecate or void, discomfort (pinch, pop, cramp, burning), arousal and fullness, plus erection state. Pull each number he actually said into "measures"; never guess one. Volume: "total_ml" when he states how much is in altogether, "added_ml" when he states an amount added on top of what was already in (use the running tally he gives you, if any, to tell which). He is American and talks in inches, fluid ounces, quarts, pints, cups and cc: convert to mL (1 oz = 29.57 mL, 1 quart = 946 mL, 1 cc = 1 mL) and cm (1 in = 2.54 cm) for the measures fields only. The running tally gives both.
He may also be asking Sarah a question ("how long have I been holding?", "is that normal?", "what was my heart rate when it popped?"): set "question" true only when he wants an answer from her.
Reply with JSON only: {"types":[1-3 ids from the allowed list],"device":id or null,"bucket":one bucket,"intensity":0-10 or null (only if he gave a number or a clear severity),"site":anatomical site or null,"measures":{"total_ml":n,"added_ml":n,"depth_cm":n,"urge_0_10":n,"discomfort_0_10":n,"arousal_0_10":n,"fullness_0_10":n,"erection":"none|building|partial|full|softening","substance":"cannabis|alcohol|caffeine|nicotine|medication|other","substance_detail":what and how much in a few of his words (e.g. "indica vape, two hits", "second beer", "16 oz cold brew"),"effect_0_10":how strongly he feels it, only if he gave a number } with only the keys he gave (or {}),"question":true or false,"clean":his note as a good dictation app would write it: remove fillers (um, uh, like, you know), stammered repeats, false starts and self-corrections he abandoned, then punctuate. Only delete words: never add, swap, reword or fix his words (not even a misheard one), never summarize,"confirm":a spoken confirmation of at most 12 words that repeats any numbers he gave, in the units he said them (ounces, inches, cc; never converted to metric), so he can catch a mishearing, no names}.
""".trimIndent()

    fun parseClaude(json: String, command: String, allowed: Set<String>, gear: List<GearItem>): SpokenEvent? = runCatching {
        val o = JSONObject(json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1))
        val types = (0 until (o.optJSONArray("types")?.length() ?: 0)).map { o.getJSONArray("types").getString(it) }.filter { it in allowed }.toSet()
        if (types.isEmpty()) return null
        val device = o.optString("device").takeIf { id -> gear.any { it.id == id } }
        SpokenEvent(
            types, device, (acceptClean(o.optString("clean"), command) ?: tidy(command)).ifBlank { return null },
            o.optString("bucket").takeIf { it in BUCKETS } ?: "general",
            o.optInt("intensity", -1).takeIf { !o.isNull("intensity") && it in 0..10 },
            o.optString("site").takeIf { it.isNotBlank() && it != "null" },
            o.optString("confirm").trim().ifBlank { "Logged." }.replace(Regex("(?i)\\bsarah\\b"), "").trim(),
            "claude",
            Measures.fromJson(o.optJSONObject("measures")).orElse(ProcedureLog.parseLocal(command)),
            o.optBoolean("question", false)
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
    /** Says it right away (it's her answer to you, so it doesn't wait behind anything). */
    private val speak: suspend (String) -> Unit,
    private val allowedTypes: () -> List<MarkerTypes.Type>,
    private val gear: () -> List<GearItem>,
    /** She heard "Sarah": stop talking (media is paused here). */
    private val hush: () -> Unit = {},
    /** "Sarah, end the session." */
    private val endSession: () -> Unit = {},
    /** Her spoken answer to a question he asked, from the whole session so far (null = couldn't answer). */
    private val answer: suspend (String) -> String? = { null },
    /** The running procedure tally (JSON), so "another 250" can be told from "750 in". */
    private val tally: () -> String = { "{}" },
    /** Her own reply to a note he just logged (talk mode): (his words, how it was filed, numbers he gave). */
    private val chat: suspend (String, String, String?) -> String? = { _, _, _ -> null }
) {
    private val _status = MutableStateFlow(HandsFreeStatus())
    val status: StateFlow<HandsFreeStatus> = _status
    private var paused = false
    private var wanted = false
    private var preparing: Job? = null
    private var restart: Job? = null
    /** Lets media go again if a note somehow never finishes. */
    private var holdWatchdog: Job? = null
    private val ears = LocalEars(
        context, scope,
        onPhrase = { pcm, at, offline -> phrase(pcm, at, offline) },
        onWake = {
            // One clear signal the moment she hears her name: she goes quiet, music/video pauses, listening tone.
            hush()
            com.pulsepointlabs.sarahmonitor.audio.MediaHold.acquire(HOLD)
            holdWatchdog?.cancel()
            holdWatchdog = scope.launch { delay(150_000L); com.pulsepointlabs.sarahmonitor.audio.MediaHold.release(HOLD) }
            cue(true)
            _status.value = _status.value.copy(message = "Listening… take your time")
        },
        onNothing = {
            cue(false)
            letGo()
            _status.value = _status.value.copy(message = "Listening for \"Sarah…\"")
        },
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
            com.pulsepointlabs.sarahmonitor.audio.OtherAudio.busy.value?.let { wanted && !paused } == true ->
                stop("Paused: ${com.pulsepointlabs.sarahmonitor.audio.OtherAudio.busy.value} going on")
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
        letGo()
        _status.value = HandsFreeStatus(false, message, _status.value.lastLogged)
    }

    /** Media carries on (whatever was playing before "Sarah"). */
    private fun letGo() {
        holdWatchdog?.cancel(); holdWatchdog = null
        com.pulsepointlabs.sarahmonitor.audio.MediaHold.release(HOLD)
    }

    /** A phrase that began with "Sarah": transcribe just this clip, sort it, log it, then let media resume. */
    private fun phrase(pcm: ShortArray, at: Long, offline: String) {
        _status.value = _status.value.copy(message = "Writing it down…")
        scope.launch {
            try { transcribeAndHandle(pcm, at, offline) } finally { letGo() }
        }
    }

    private suspend fun transcribeAndHandle(pcm: ShortArray, at: Long, offline: String) {
        run {
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
            if (command.isBlank()) { cue(false); _status.value = _status.value.copy(message = "Listening for \"Sarah…\""); return }
            handle(command, at, text, if (heard != null) "cloud" else "offline")
        }
    }

    private suspend fun handle(command: String, at: Long, raw: String, heardBy: String) {
        if (VoiceCommand.endsSession(command)) {
            cue(false)
            speak("Okay. Ending the session.")
            _status.value = _status.value.copy(message = "Session ended by voice", lastLogged = VoiceCommand.verbatim(command))
            endSession()
            return
        }
        if (VoiceCommand.dismisses(command)) {
            cue(false)
            _status.value = _status.value.copy(message = "Listening for \"Sarah…\"")
            return
        }
        val allowed = allowedTypes()
        val ids = allowed.map { it.id }.toSet()
        val g = gear()
        val sessionType = com.pulsepointlabs.sarahmonitor.core.MonitorHub.currentCategory()?.label
        // Filing the note (quick model) runs alongside her reply instead of before it, so she starts thinking the
        // moment the words are in. The note is saved once both are done.
        val sorting = scope.async {
            ClaudeText.ask(VoiceCommand.claudePrompt(allowed, g, sessionType), "Running tally so far: ${tally()}\nHis note: $command", maxTokens = 500)
                ?.let { VoiceCommand.parseClaude(it, command, ids, g) }
                ?: VoiceCommand.parseLocal(command, ids, g)
        }
        // A question, plainly worded as one ("how long have I been holding?"): answer it right away.
        if (ProcedureLog.isQuestion(command)) {
            cue(false)
            _status.value = _status.value.copy(message = "Thinking about your question…")
            val reply = answer(command)
            speak(reply ?: "Sorry, I couldn't work that out just now.")
            val sorted = sorting.await()
            mark(at, sorted.copy(types = (sorted.types - "event") + "question", question = true, answer = reply), raw, heardBy)
            _status.value = _status.value.copy(message = "Listening for \"Sarah…\"", lastLogged = sorted.note)
            return
        }
        if (HandsFreeSettings.talk.value) {
            // Talk mode: she answers like herself, repeating any numbers (read on the phone, so she needn't wait).
            _status.value = _status.value.copy(message = "Writing it down…")
            val reply = chat(command, "", ProcedureLog.parseLocal(command).spoken().ifBlank { null })
            cue(false)
            if (reply != null) speak(reply)
            val sorted = sorting.await()
            if (reply == null) speak(sorted.confirm)
            mark(at, sorted.copy(answer = reply), raw, heardBy)
            _status.value = _status.value.copy(message = "Listening for \"Sarah…\"", lastLogged = sorted.note)
            return
        } else {
            val sorted = sorting.await()
            mark(at, sorted, raw, heardBy)
            cue(false)
            if (HandsFreeSettings.sayBack.value) speak(sorted.confirm)
            _status.value = _status.value.copy(message = "Listening for \"Sarah…\"", lastLogged = sorted.note)
        }
    }

    private companion object { const val HOLD = "hands-free" }
}
