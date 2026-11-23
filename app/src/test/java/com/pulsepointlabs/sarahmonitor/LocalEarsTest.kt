package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.session.LocalEars
import com.pulsepointlabs.sarahmonitor.session.PcmRing
import com.pulsepointlabs.sarahmonitor.session.VoiceCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEarsTest {
    @Test fun ringKeepsTheLastSecondsByAbsoluteIndex() {
        val r = PcmRing(1, rate = 10)                     // holds 10 samples
        r.write(ShortArray(25) { it.toShort() }, 25)       // samples 0..24; only 15..24 remain
        assertEquals(25L, r.written)
        assertArrayEquals(ShortArray(5) { (18 + it).toShort() }, r.slice(18, 23))
        assertArrayEquals(ShortArray(5) { (15 + it).toShort() }, r.slice(0, 20))   // older part already gone
        assertEquals(0, r.slice(30, 40).size)
    }

    @Test fun offlineWakeWord() {
        assertTrue(LocalEars.startsWithWake("sarah i'm meeting some resistance"))
        assertTrue(LocalEars.startsWithWake("hey sara prep the glans"))
        assertFalse(LocalEars.startsWithWake("i told sarah about it"))
        assertTrue(LocalEars.wakeOnly("sarah"))
        assertFalse(LocalEars.wakeOnly("sarah balloon inflated"))
    }

    @Test fun commandPrefersTheCloudTranscript() {
        assertEquals("meeting some resistance, about 3 out of 10", VoiceCommand.commandFrom("Sarah, meeting some resistance, about 3 out of 10.", "sarah meeting some resistance about three out of ten"))
        // The phone heard "Sarah"; the cloud misspelled it: the stray first word is dropped.
        assertEquals("balloon inflated with ten mils", VoiceCommand.commandFrom("Zahra, balloon inflated with ten mils.", "sarah balloon inflated with ten mils"))
        // No network: the offline words are used.
        assertEquals("balloon inflated", VoiceCommand.commandFrom(null, "sarah balloon inflated"))
    }
}
