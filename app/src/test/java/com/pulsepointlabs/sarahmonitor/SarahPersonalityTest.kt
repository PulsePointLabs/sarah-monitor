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

    @Test fun notesGetSpecificNonRepetitiveReplies() {
        assertTrue(SarahChat.SYSTEM.contains("your_last_replies"))
        assertTrue(SarahChat.SYSTEM.contains("never give generic encouragement"))
    }
}
