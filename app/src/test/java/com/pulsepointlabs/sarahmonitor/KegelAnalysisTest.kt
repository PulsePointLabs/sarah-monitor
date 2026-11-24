package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.kegel.KegelAnalysis
import com.pulsepointlabs.sarahmonitor.kegel.KegelPoint
import com.pulsepointlabs.sarahmonitor.kegel.KegelRecord
import com.pulsepointlabs.sarahmonitor.kegel.KegelRoutine
import com.pulsepointlabs.sarahmonitor.kegel.KegelSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KegelAnalysisTest {
    private val start = 1_000_000L
    private val session = KegelSession(KegelRoutine.Holds, 4, 5.0, 5.0, start)
    private val windows = session.stepWindows().filter { it.first.targetPct != null }

    private fun record(): KegelRecord = KegelRecord.fromJson("t", session.toJson(session.result(start + 60_000L), false, 4.0, 0.3), start + 60_000L)!!

    @Test fun measuresEachSqueeze() {
        // Squeeze to 80 % one second into each window, let go 0.5 s after it ends; last rep only reaches 40 %.
        val pts = (0 until 500).map { i ->
            val t = start + i * 100L
            val w = windows.indexOfFirst { t in (it.second + 1_000L)..(it.third + 500L) }
            KegelPoint(t, if (w < 0) 2.0 else if (w == 3) 40.0 else 80.0)
        }
        val cs = KegelAnalysis.contractions(record(), pts)
        assertEquals(4, cs.size)
        assertEquals(80.0, cs[0].peak!!, 0.01)
        assertEquals(1.0, cs[0].riseS!!, 0.11)
        assertEquals(0.6, cs[0].releaseS!!, 0.11)
        assertTrue(cs[0].onTargetPct!! >= 75)
        assertEquals(0, cs[3].onTargetPct)
        assertNotNull(KegelAnalysis.fatigueTrend(cs))
        assertTrue("strength faded", KegelAnalysis.fatigueTrend(cs)!! < 0)
    }

    @Test fun referenceAndThresholdRoundTrip() {
        val r = record()
        assertEquals(4.0, r.referenceKpa!!, 0.001)
        assertEquals(0.3, r.thresholdKpa!!, 0.001)
        assertEquals(4, r.squeezes.size)
    }
}
