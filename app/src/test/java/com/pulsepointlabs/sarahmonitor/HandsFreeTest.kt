package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.TranscriptTidy
import com.pulsepointlabs.sarahmonitor.session.GearItem
import com.pulsepointlabs.sarahmonitor.session.GearType
import com.pulsepointlabs.sarahmonitor.session.MarkerTypes
import com.pulsepointlabs.sarahmonitor.session.SessionCategory
import com.pulsepointlabs.sarahmonitor.session.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeTest {
    private val hybrid = MarkerTypes.all.filter { it.group in SessionCategory.Hybrid.groups }.map { it.id }.toSet()
    private val foley = GearItem("g1", GearType.Foley, mapOf("size_fr" to "22"))
    private val lube = GearItem("g2", GearType.Lubricant, mapOf("type" to "Sterile surgical"))

    @Test fun wakeWord() {
        assertEquals("I'm beginning to prep the glans for the Foley", VoiceCommand.afterWake("Sarah, I'm beginning to prep the glans for the Foley."))
        assertEquals("resistance at the external sphincter", VoiceCommand.afterWake("Okay Sara: resistance at the external sphincter."))
        assertEquals("", VoiceCommand.afterWake("Sarah."))
        assertNull(VoiceCommand.afterWake("I told Sarah earlier about the lube."))   // not addressed to her
        assertNull(VoiceCommand.afterWake("The catheter is in."))
    }

    @Test fun intensityFromSpeech() {
        assertEquals(3, VoiceCommand.intensity("meeting some resistance, about 3 out of 10"))
        assertEquals(7, VoiceCommand.intensity("pinch is seven out of ten"))
        assertEquals(2, VoiceCommand.intensity("2/10 discomfort"))
        assertNull(VoiceCommand.intensity("16 French catheter"))
    }

    @Test fun keywordReadingOfYourExamples() {
        val prep = VoiceCommand.parseLocal("I am beginning to prep the glans penis for the Foley catheter", hybrid, listOf(foley, lube))
        assertTrue(prep.types.contains("prep")); assertEquals("g1", prep.gearId); assertEquals("procedure_step", prep.bucket)
        val resist = VoiceCommand.parseLocal("I'm meeting some resistance at the external sphincter, about 3 out of 10, with a slight pinch sensation", hybrid, listOf(foley))
        assertTrue(resist.types.containsAll(setOf("resistance", "discomfort"))); assertEquals(3, resist.intensity); assertEquals("g1", resist.gearId)
        val soft = VoiceCommand.parseLocal("erection is softer than it was a few minutes ago", hybrid, emptyList())
        assertEquals(setOf("finding"), soft.types); assertEquals("body_finding", soft.bucket)
        val near = VoiceCommand.parseLocal("getting close to orgasm now", hybrid, emptyList())
        assertTrue("near" in near.types)
        val plain = VoiceCommand.parseLocal("relaxing before prepping the lab", setOf("event", "activity"), emptyList())
        assertEquals(setOf("event"), plain.types)
        assertFalse(resist.confirm.contains("Sarah", ignoreCase = true))
    }

    @Test fun yourPorchSentenceIsNotAPrepStep() {
        val e = VoiceCommand.parseLocal("the lab is prepared, relaxing on the porch prior to starting the session", hybrid, listOf(foley))
        assertFalse("types ${e.types}", "prep" in e.types)
        assertEquals(setOf("event"), e.types)
        assertTrue("prep" in VoiceCommand.parseLocal("prepping the glans with betadine", hybrid, listOf(foley)).types)
        assertTrue("prep" in VoiceCommand.parseLocal("applying lube to the catheter tip", hybrid, listOf(foley)).types)
    }

    @Test fun quietClipIsBoosted() {
        val quiet = ShortArray(1600) { (if (it % 2 == 0) 1500 else -1500).toShort() }
        val loud = com.pulsepointlabs.sarahmonitor.session.LocalEars.normalized(quiet)
        assertEquals(12_000, loud.maxOf { it.toInt() })       // capped at ×8
        val ok = ShortArray(10) { 20_000 }
        assertEquals(23_000, com.pulsepointlabs.sarahmonitor.session.LocalEars.normalized(ok).max().toInt())
    }

    @Test fun tidyKeepsYourWords() {
        assertTrue(TranscriptTidy.sameWords("um i was relaxing and then prepping the lab", "I was relaxing, and then prepping the lab."))
        assertFalse(TranscriptTidy.sameWords("I was relaxing and then prepping the lab", "I was relaxing before the procedure started."))
    }
}
