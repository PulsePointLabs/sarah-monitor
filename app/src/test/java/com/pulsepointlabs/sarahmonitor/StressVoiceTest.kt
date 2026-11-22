package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.StressAnnouncer
import com.pulsepointlabs.sarahmonitor.core.StressComponent
import com.pulsepointlabs.sarahmonitor.core.StressEstimator
import com.pulsepointlabs.sarahmonitor.core.StressEvent
import com.pulsepointlabs.sarahmonitor.core.StressLevel
import com.pulsepointlabs.sarahmonitor.core.StressReading
import com.pulsepointlabs.sarahmonitor.core.StressShift
import com.pulsepointlabs.sarahmonitor.core.StressVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StressVoiceTest {
    private fun r(score: Int, moving: Boolean = false) = StressReading(score = score, level = StressEstimator.level(score), moving = moving)
    private val gap = 180_000L

    /** Feeds one reading per second from [from] to [to] (seconds), returning every event. */
    private fun StressAnnouncer.run(from: Int, to: Int, score: Int, moving: Boolean = false, reminders: Boolean = false) =
        (from until to).mapNotNull { s -> update(s * 1000L, r(score, moving), 10, gap, reminders) }

    @Test fun firstReadingIsASilentReference() {
        val a = StressAnnouncer()
        assertTrue(a.run(0, 300, 30).isEmpty())
    }

    @Test fun sustainedRiseIsAnnouncedOnce() {
        val a = StressAnnouncer()
        a.run(0, 200, 30)
        val e = a.run(200, 400, 45)
        assertEquals(1, e.size)
        assertEquals(StressEvent(StressShift.Rising, 30, 45, StressLevel.Mild), e.single())
    }

    @Test fun briefBlipIsIgnored() {
        val a = StressAnnouncer()
        a.run(0, 200, 30)
        assertTrue(a.run(200, 210, 50).isEmpty()) // 10 s, less than the 20 s hold
        assertTrue(a.run(210, 400, 31).isEmpty())
    }

    @Test fun respectsTheGapThenEases() {
        val a = StressAnnouncer()
        a.run(0, 200, 30)
        assertEquals(StressShift.Rising, a.run(200, 260, 60).single().shift) // at ~220 s
        // Drops right away, but the next line waits for the 3-minute gap.
        val easing = (260 until 600).mapNotNull { s -> a.update(s * 1000L, r(35), 10, gap, false)?.let { s to it } }
        assertEquals(1, easing.size)
        assertTrue("spoke at ${easing.single().first}", easing.single().first >= 220 + 180)
        assertEquals(StressShift.Easing, easing.single().second.shift)
        assertEquals(35, easing.single().second.to)
    }

    @Test fun movementIsNotStress() {
        val a = StressAnnouncer()
        a.run(0, 200, 30)
        assertTrue(a.run(200, 400, 70, moving = true).isEmpty())
        // A minute after stopping it re-references silently at the new level, not announcing the exercise.
        assertTrue(a.run(400, 700, 65).isEmpty())
    }

    @Test fun stillHighRemindersOnlyWhenCoaching() {
        val quiet = StressAnnouncer()
        quiet.run(0, 30, 60)
        assertTrue(quiet.run(30, 1200, 62).isEmpty())
        val coach = StressAnnouncer()
        coach.run(0, 30, 60, reminders = true)
        val e = coach.run(30, 1200, 62, reminders = true)
        assertTrue(e.isNotEmpty())
        assertTrue(e.all { it.shift == StressShift.StillHigh && it.level == StressLevel.Elevated })
        assertTrue("reminders ${e.size}", e.size <= 4) // at most every 6 minutes over ~20 minutes
    }

    @Test fun wording() {
        val rising = StressEvent(StressShift.Rising, 30, 54, StressLevel.Elevated)
        val fastBreathing = StressReading(score = 54, breathsPerMin = 19.0)
        val tense = StressReading(score = 54, breathsPerMin = 13.0, components = listOf(StressComponent("tension", "Tension", 70, "")))
        assertEquals("Stress is rising, now 54, elevated.", StressVoice.localLine(rising, fastBreathing, announce = true, relax = false))
        assertEquals("Stress is rising, now 54, elevated. Let your breathing slow down. Breathe in for four, and out for six.",
            StressVoice.localLine(rising, fastBreathing, announce = true, relax = true))
        assertTrue(StressVoice.localLine(rising, tense, announce = false, relax = true)!!.startsWith("Let your shoulders drop"))
        val settled = StressEvent(StressShift.Easing, 48, 20, StressLevel.Calm)
        assertEquals("Stress is easing, down to 20. Nicely settled. Keep doing what you're doing.", StressVoice.localLine(settled, r(20), true, true))
        assertNull(StressVoice.localLine(rising, fastBreathing, announce = false, relax = false))
    }
}
