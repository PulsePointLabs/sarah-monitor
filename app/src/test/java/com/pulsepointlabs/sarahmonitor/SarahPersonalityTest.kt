package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.response.SarahChat
import com.pulsepointlabs.sarahmonitor.response.SarahPersonality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.32: v1's personality knobs, with v1's defaults, reach her prompts. */
class SarahPersonalityTest {
    @Test fun v1DefaultsAreTheDefaults() {
        assertTrue(SarahPersonality.tone.value == SarahPersonality.Tone.WarmClinical)
        assertTrue(SarahPersonality.detail.value == SarahPersonality.Detail.Balanced)
        assertTrue(SarahPersonality.feminineWarmth.value && SarahPersonality.sexualSpecificity.value && SarahPersonality.sessionStory.value && SarahPersonality.ttsFriendly.value)
        assertTrue(SarahPersonality.instructions.value.contains("I respond strongly to a feminine touch"))
    }

    @Test fun theBlockFollowsTheKnobs() {
        val all = SarahPersonality.promptBlock()
        assertTrue(all.contains(SarahPersonality.Tone.WarmClinical.prompt))
        assertTrue(all.contains("arc of the session"))
        assertTrue(all.contains("paramedic and EMS educator"))
        val lean = SarahPersonality.promptBlock(
            tone = SarahPersonality.Tone.ClinicalDirect, detail = SarahPersonality.Detail.Clinical, warmth = false,
            specific = false, story = false, tts = false, instructions = ""
        )
        assertTrue(lean.contains(SarahPersonality.Tone.ClinicalDirect.prompt))
        assertFalse(lean.contains("arc of the session"))
        assertFalse(lean.contains("standing instructions"))
    }

    /** Opus 5.5 rejects "thinking: disabled" with a 400; only Haiku gets it. */
    @Test fun onlyHaikuGetsThinkingDisabled() {
        assertTrue(com.pulsepointlabs.sarahmonitor.core.ClaudeText.allowsDisabledThinking("claude-haiku-5-5"))
        assertFalse(com.pulsepointlabs.sarahmonitor.core.ClaudeText.allowsDisabledThinking("claude-opus-5-5"))
        assertFalse(com.pulsepointlabs.sarahmonitor.core.ClaudeText.allowsDisabledThinking("claude-sonnet-5-5"))
    }

    @Test fun instructionsHaveTheirOwnSwitch() {
        assertTrue(SarahPersonality.promptBlock(instructions = "be brief", useInstr = true).contains("be brief"))
        assertFalse(SarahPersonality.promptBlock(instructions = "be brief", useInstr = false).contains("be brief"))
    }

    @Test fun offMeansNothingIsSent() {
        assertTrue(SarahPersonality.promptBlock(on = false).isEmpty())
        assertTrue(SarahPersonality.promptBlock(on = true).isNotEmpty())
    }

    /** A long reply used to be thrown away (and the sample blamed the API key); now it's cut at a sentence end. */
    @Test fun longRepliesAreTrimmedNotDropped() {
        val long = "That sounds really nice. " + (1..70).joinToString(" ") { "word" } + "."
        assertTrue(SarahChat.fitWords(long, 60) == "That sounds really nice.")
        val short = "Settling in sounds good. Take your time."
        assertTrue(SarahChat.fitWords(short, 60) == short)
        assertTrue(SarahChat.fitWords((1..80).joinToString(" ") { "w" }, 60).split(' ').size == 60)
    }

    @Test fun notesGetSpecificNonRepetitiveReplies() {
        assertTrue(SarahChat.SYSTEM.contains("your_last_replies"))
        assertTrue(SarahChat.SYSTEM.contains("never give generic encouragement"))
    }
}
