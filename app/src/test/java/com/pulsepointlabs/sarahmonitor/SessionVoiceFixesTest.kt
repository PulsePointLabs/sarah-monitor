package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.CrashLog
import com.pulsepointlabs.sarahmonitor.response.SarahPersonality
import com.pulsepointlabs.sarahmonitor.session.LocalEars
import com.pulsepointlabs.sarahmonitor.session.MarkerTypes
import com.pulsepointlabs.sarahmonitor.session.SessionCategory
import com.pulsepointlabs.sarahmonitor.session.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.34: no pet names, notes not filed as Symptom, the name-only wake recogniser, crash notes. */
class SessionVoiceFixesTest {
    @Test fun noPetNamesOrHisName() {
        assertEquals("You're right there. Breathe with it.", SarahPersonality.cleanAddress("You're right there, love. Breathe with it.", pet = false))
        assertEquals("Keep that pace.", SarahPersonality.cleanAddress("Babe, keep that pace.", pet = false))
        assertEquals("That's it. So close now.", SarahPersonality.cleanAddress("That's it, Ben. So close now, my love.", pet = false))
        // Ordinary uses of the words stay.
        assertEquals("I love how steady your breathing is.", SarahPersonality.cleanAddress("I love how steady your breathing is.", pet = false))
        // With pet names allowed, only his name is dropped.
        assertEquals("Nice and slow, love.", SarahPersonality.cleanAddress("Nice and slow, love, Ben.", pet = true))
        assertTrue(SarahPersonality.addressBlock(pet = false).contains("Never use pet names"))
        assertFalse(SarahPersonality.addressBlock(pet = true).contains("Never use pet names"))
    }

    @Test fun sexualSessionNotesAreNotSymptoms() {
        val allowed = MarkerTypes.offered(SessionCategory.Masturbation.groups)
        val ids = allowed.map { it.id }.toSet()
        assertTrue("event" in ids)
        // Nothing matched locally: a plain Event, never "the first type on the list" (that was Symptom).
        val e = VoiceCommand.parseLocal("just switched to the other hand", ids, emptyList())
        assertEquals(setOf("event"), e.types)
        assertTrue(VoiceCommand.claudePrompt(allowed, emptyList(), "Masturbation").contains("This session is: Masturbation"))
    }

    @Test fun nameOnlyRecogniserWakes() {
        assertTrue(LocalEars.grammarWake("sarah"))
        assertTrue(LocalEars.grammarWake("hey sarah"))
        assertFalse(LocalEars.grammarWake("[unk] [unk] sarah"))
        assertFalse(LocalEars.grammarWake("[unk]"))
        assertTrue(LocalEars.startsWithWake("sarai the sleeve feels great"))
    }

    @Test fun crashNoteReadsPlainly() {
        val note = "Sarah Monitor 0.34.0 crashed 2026-11-27T01:02:03 on thread main\njava.lang.IllegalStateException: Unsupported concurrent change\n\tat x.y"
        val s = CrashLog.summary(note)
        assertTrue(s.startsWith("0.34.0 crashed"))
        assertTrue(s.contains("IllegalStateException: Unsupported concurrent change"))
    }
}
