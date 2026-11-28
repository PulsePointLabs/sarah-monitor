package com.pulsepointlabs.sarahmonitor.response

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * How Ben has tuned Sarah's voice: the same knobs he had in Sarah v1 (tone preset, detail level, four style
 * switches and his own standing instructions), with v1's defaults. Read for spirit, written fresh: this only builds a
 * short "how he likes you to talk" block that's added to every prompt where Sarah speaks to him (chat, answers,
 * live feedback). Stays on the phone.
 */
object SarahPersonality {
    enum class Tone(val label: String, val blurb: String, val prompt: String) {
        WarmClinical(
            "Warm clinical", "Plain English, feminine warmth, evidence first",
            "Warm and clinically literate: plain English first, with a gentle feminine warmth; grounded in what was measured or logged."
        ),
        SoftFeminine(
            "Soft feminine", "More intimate and attentive, still grounded",
            "Softer and more intimate: attentive, affectionate and close, like someone who cares about him, while staying grounded in what's real. Intimate in attention, not explicit."
        ),
        ClinicalDirect(
            "Clinical direct", "Cleaner and more technical, less companion",
            "Clean and direct: a sharp clinical colleague, technical where it helps, less companion talk and fewer pleasantries."
        ),
        TechnicalDeep(
            "Technical deep", "Mechanism-heavy physiology",
            "Mechanism-first: when it's useful, explain the physiology underneath (autonomic tone, reflex arcs, smooth muscle, vascular response) as one clinician to another."
        )
    }

    enum class Detail(val label: String, val blurb: String, val prompt: String) {
        Plain("Plain", "Everyday words", "Use everyday words; skip jargon unless he uses it first."),
        Balanced("Balanced", "Plain, with the right terms", "Plain language, with the correct clinical term where it adds precision."),
        Clinical("Clinical", "Full clinical vocabulary", "Use full clinical and anatomical vocabulary; he's a paramedic and educator and doesn't need it softened.")
    }

    /** v1's default standing instructions, in his own words. */
    const val DEFAULT_INSTRUCTIONS =
        "When discussing anatomical terms, keep them clinical but do not break the flow with the fact that we are discussing sexual " +
            "response in session AI generated content. Keep head-to-toe, pelvic, and genital anatomy sections clinical in output. " +
            "I respond strongly to a feminine touch, so keep that in mind. I am also a paramedic and EMS educator, so I have a strong " +
            "knowledge of anatomy, physiology, and pathophysiology."

    private const val PREFS = "sarahmonitor_personality"
    private var prefs: SharedPreferences? = null

    /** Master switch: off = none of these are sent; she uses her built-in voice. */
    val enabled = MutableStateFlow(true)
    val tone = MutableStateFlow(Tone.WarmClinical)
    val detail = MutableStateFlow(Detail.Balanced)
    /** A gentle feminine warmth in how she phrases things. */
    val feminineWarmth = MutableStateFlow(true)
    /** In sexual sessions, name anatomy and responses specifically instead of vaguely. */
    val sexualSpecificity = MutableStateFlow(true)
    /** Connect what's happening now to earlier in the session (the arc, not isolated notes). */
    val sessionStory = MutableStateFlow(true)
    /** Written to be heard: short sentences, numbers and times said the way a person says them. */
    val ttsFriendly = MutableStateFlow(true)
    val instructions = MutableStateFlow(DEFAULT_INSTRUCTIONS)
    /** Pet names ("love", "babe"…). Off by default: he doesn't want them. */
    val petNames = MutableStateFlow(false)
    /** Whether his written instructions are sent (kept either way). */
    val useInstructions = MutableStateFlow(true)

    @Synchronized
    fun load(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabled.value = p.getBoolean("on", true)
        tone.value = runCatching { Tone.valueOf(p.getString("tone", null)!!) }.getOrDefault(Tone.WarmClinical)
        detail.value = runCatching { Detail.valueOf(p.getString("detail", null)!!) }.getOrDefault(Detail.Balanced)
        feminineWarmth.value = p.getBoolean("warmth", true)
        sexualSpecificity.value = p.getBoolean("specific", true)
        sessionStory.value = p.getBoolean("story", true)
        ttsFriendly.value = p.getBoolean("tts", true)
        instructions.value = p.getString("instructions", null) ?: DEFAULT_INSTRUCTIONS
        useInstructions.value = p.getBoolean("use_instructions", true)
        petNames.value = p.getBoolean("pet_names", false)
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) { prefs?.edit()?.apply(block)?.apply() }
    fun setEnabled(v: Boolean) { enabled.value = v; edit { putBoolean("on", v) } }
    fun setTone(v: Tone) { tone.value = v; edit { putString("tone", v.name) } }
    fun setDetail(v: Detail) { detail.value = v; edit { putString("detail", v.name) } }
    fun setWarmth(v: Boolean) { feminineWarmth.value = v; edit { putBoolean("warmth", v) } }
    fun setSpecific(v: Boolean) { sexualSpecificity.value = v; edit { putBoolean("specific", v) } }
    fun setStory(v: Boolean) { sessionStory.value = v; edit { putBoolean("story", v) } }
    fun setTts(v: Boolean) { ttsFriendly.value = v; edit { putBoolean("tts", v) } }
    fun setInstructions(v: String) { instructions.value = v.take(2_000); edit { putString("instructions", instructions.value) } }
    fun resetInstructions() = setInstructions(DEFAULT_INSTRUCTIONS)
    fun setPetNames(v: Boolean) { petNames.value = v; edit { putBoolean("pet_names", v) } }
    fun setUseInstructions(v: Boolean) { useInstructions.value = v; edit { putBoolean("use_instructions", v) } }

    /**
     * How she addresses him. Always sent (even with the personality settings off): no pet names unless he turns
     * them on, and his name only rarely ("love" and "Ben" over and over threw him off mid-session).
     */
    fun addressBlock(pet: Boolean = petNames.value): String = buildString {
        append("How to address him (always follow this):\n")
        if (!pet) append("- Never use pet names or terms of endearment: no love, babe, baby, honey, sweetheart, darling, sweetie, handsome.\n")
        append("- Don't address him by name. If you ever use it, at most once in a long while, never in back-to-back lines.")
    }

    /** Everything about her voice that goes after a system prompt. */
    fun voiceBlock(): String = listOf(promptBlock(), addressBlock()).filter { it.isNotBlank() }.joinToString("\n\n")

    private const val PET = "(?:my\\s+)?(?:love|lover|babe|baby|honey|hon|hun|sweetheart|sweetie|darling|dear|handsome|sweet\\s+thing)"
    private const val NAME = "(?:ben|benjamin)"

    /**
     * Safety net after the prompt: drops a pet name or his name used to address him (", love." / "Babe, " / ", Ben?")
     * from her line before it's spoken. Only removes words; everything else in her line stays. Pure; unit-tested.
     */
    fun cleanAddress(text: String, pet: Boolean = petNames.value): String {
        val who = if (pet) NAME else "(?:$PET|$NAME)"
        var t = text
        t = t.replace(Regex("(?i)^\\s*$who\\s*[,.!]\\s*"), "")                        // "Love, …" at the start
        t = t.replace(Regex("(?i)([.!?]\\s+)$who\\s*,\\s*"), "$1")                     // "… . Babe, …"
        t = t.replace(Regex("(?i),\\s*$who\\s*(?=[.!?,;:]|$)"), "")                     // "…, love." / "…, Ben?"
        t = t.replace(Regex("\\s{2,}"), " ").trim()
        // Capitalize each sentence start that a removal exposed.
        t = t.replace(Regex("(^|[.!?]\\s+)([a-z])")) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return t.ifBlank { text }
    }

    /** The block added to Sarah's prompts. Pure given its inputs; unit-tested. */
    fun promptBlock(
        tone: Tone = this.tone.value, detail: Detail = this.detail.value, warmth: Boolean = feminineWarmth.value,
        specific: Boolean = sexualSpecificity.value, story: Boolean = sessionStory.value, tts: Boolean = ttsFriendly.value,
        instructions: String = this.instructions.value, on: Boolean = enabled.value, useInstr: Boolean = useInstructions.value
    ): String = if (!on) "" else buildString {
        append("How Ben has tuned your voice (follow this):\n")
        append("- Tone: ").append(tone.prompt).append('\n')
        append("- Detail: ").append(detail.prompt).append('\n')
        append(if (warmth) "- Keep a gentle feminine warmth in how you phrase things.\n" else "- Keep warmth light; friendly but not affectionate.\n")
        if (specific) append("- In sexual sessions, be anatomically specific about what's responding (no vague euphemisms), clinical but in the flow of conversation.\n")
        if (story) append("- Track the arc of the session: connect what he just said to what came before when it means something.\n")
        if (tts) append("- It will be spoken aloud: short sentences, numbers and times said the way a person says them, no symbols or abbreviations.\n")
        if (useInstr) instructions.trim().takeIf { it.isNotBlank() }?.let { append("- His standing instructions, in his words: ").append(it).append('\n') }
    }.trimEnd()
}
