package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.StressEstimator
import com.pulsepointlabs.sarahmonitor.core.StressInputs
import com.pulsepointlabs.sarahmonitor.core.StressLevel
import com.pulsepointlabs.sarahmonitor.core.StressTracker
import com.pulsepointlabs.sarahmonitor.session.CheckIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StressTest {
    private fun inputs(hr: Int, rmssd: Double, breaths: Double? = 12.0, motion: Double? = 5.0, pelvic: Double? = null) =
        StressInputs(hr, rmssd, restingHr = 62.0, restingRmssd = 45.0, breathsPerMin = breaths, motionMg = motion, pelvicKpa = pelvic, emgPct = null)

    @Test
    fun restingIsCalm() {
        val r = StressEstimator.estimate(inputs(63, 46.0))
        assertTrue("score ${r.score}", r.score!! < 15)
        assertEquals(StressLevel.Calm, r.level)
    }

    @Test
    fun suppressedHrvFastBreathingAndUnexplainedHrIsHigh() {
        val r = StressEstimator.estimate(inputs(92, 14.0, breaths = 20.0, motion = 40.0, pelvic = 1.2))
        assertTrue("score ${r.score}", r.score!! >= 70)
        assertEquals(StressLevel.High, r.level)
    }

    @Test
    fun exerciseHeartRateIsNotStress() {
        // Same HR, but you're walking briskly: the HR is explained by movement.
        val still = StressEstimator.estimate(inputs(100, 40.0, motion = 5.0))
        val moving = StressEstimator.estimate(inputs(100, 40.0, motion = 200.0))
        assertTrue("still ${still.score} moving ${moving.score}", moving.score!! < still.score!!)
        assertTrue(moving.moving)
    }

    @Test
    fun needsHeartDataAndSmoothes() {
        assertNull(StressEstimator.estimate(StressInputs(null, null, null, null, 14.0, 5.0, null, null)).score)
        val t = StressTracker()
        t.update(0, inputs(63, 46.0))
        val after = t.update(1_000, inputs(92, 14.0, breaths = 20.0))
        assertTrue("smoothed ${after.score}", after.score!! < 30) // one bad second doesn't jump it
    }

    @Test
    fun checkInLabel() {
        assertEquals("feeling good · stress 3/10", CheckIn(4, 3).label)
        assertNull(CheckIn().label)
    }
}
