package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.kegel.KegelRoutine
import com.pulsepointlabs.sarahmonitor.kegel.KegelSession
import com.pulsepointlabs.sarahmonitor.kegel.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KegelTest {
    @Test
    fun holdsRoutineTimingAndSteps() {
        val s = KegelSession(KegelRoutine.Holds, 3, 5.0, 4.0, 0)
        // ready 3 + 3×hold 5 + 2×rest 4 + final relax 3
        assertEquals(3 + 15 + 8 + 3.0, s.totalS, 0.0)
        assertEquals(StepKind.Ready, s.at(1_000).step.kind)
        assertEquals(StepKind.Hold, s.at(4_000).step.kind)
        assertEquals(1, s.at(4_000).step.rep)
        assertEquals(StepKind.Relax, s.at(8_500).step.kind)
        assertTrue(s.at(30_000).done)
    }

    @Test
    fun scoringRewardsOnTargetSqueezesAndCleanRelax() {
        val good = KegelSession(KegelRoutine.Holds, 2, 5.0, 5.0, 0)
        val weak = KegelSession(KegelRoutine.Holds, 2, 5.0, 5.0, 0)
        var t = 0L
        while (t < good.totalS * 1000) {
            val squeezing = good.at(t).step.kind == StepKind.Hold
            good.sample(t, if (squeezing) 3.6 else 0.1, 4.0, 1.0)
            weak.sample(t, if (squeezing) 1.2 else 0.9, 4.0, 1.0)
            t += 100
        }
        val g = good.result(t)
        val w = weak.result(t)
        assertTrue("good ${g.score}", g.score >= 90)
        assertTrue("weak ${w.score}", w.score <= 30)
        assertEquals(90, g.avgPctOfRef)
    }

    @Test
    fun elevatorHasFloorsUpAndDown() {
        val steps = KegelRoutine.Elevator.steps(1, 3.0, 5.0)
        assertEquals(listOf(25, 50, 75, 100, 75, 50, 25), steps.mapNotNull { it.targetPct })
    }
}
