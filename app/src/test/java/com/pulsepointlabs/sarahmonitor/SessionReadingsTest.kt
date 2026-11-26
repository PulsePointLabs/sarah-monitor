package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.BpEntry
import com.pulsepointlabs.sarahmonitor.core.StressComponent
import com.pulsepointlabs.sarahmonitor.core.duringSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.32.1: BP only from the current session; stress drivers never pop in and out. */
class SessionReadingsTest {
    private fun bp(t: Long) = BpEntry(timestampMs = t, systolic = 120, diastolic = 80, pulse = 70, irregular = false, source = "cuff")

    @Test fun bpOnlyFromThisSession() {
        val readings = listOf(bp(1_000), bp(5_000), bp(9_000))
        assertTrue(readings.duringSession(null).isEmpty())                 // nothing recording: blank
        assertEquals(listOf(9_000L), readings.duringSession(8_000).map { it.timestampMs })
        assertTrue(readings.duringSession(10_000).isEmpty())               // session with no reading yet: blank
    }

    @Test fun stressDriversKeepTheirSlots() {
        val withBreath = StressComponent.slots(listOf(StressComponent("hrv", "HRV", 40, ""), StressComponent("breath", "Breathing", 55, "")))
        val withoutBreath = StressComponent.slots(listOf(StressComponent("hrv", "HRV", 42, "")))
        assertEquals(withBreath.map { it.key }, withoutBreath.map { it.key })
        assertEquals(5, withoutBreath.size)
        assertNull(withoutBreath.first { it.key == "breath" }.score)
        assertEquals(listOf("hrv", "hr", "breath"), StressComponent.slots(emptyList()).take(3).map { it.key })
        // No CIVET or EMG in use: Tension isn't shown at all.
        assertEquals(listOf("hrv", "hr", "breath", "restless"), StressComponent.slots(emptyList(), withTension = false).map { it.key })
    }
}
