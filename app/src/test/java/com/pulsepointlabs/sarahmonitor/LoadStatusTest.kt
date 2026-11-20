package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressBaseline
import com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressEstimator
import com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressLevel
import com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressReading
import com.pulsepointlabs.sarahmonitor.session.HrSampleEntity
import com.pulsepointlabs.sarahmonitor.session.SessionAnalysis
import com.pulsepointlabs.sarahmonitor.session.SessionData
import com.pulsepointlabs.sarahmonitor.session.SessionEntity
import com.pulsepointlabs.sarahmonitor.ui.loadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadStatusTest {
    @Test
    fun saysWhatIsHappening() {
        assertEquals("Learning baseline 2/5 min", loadStatus(PhysiologicalStressReading(level = PhysiologicalStressLevel.EstablishingBaseline, baselineWindowsObserved = 2, baselineWindowsRequired = 5)))
        assertEquals("Moving · paused", loadStatus(PhysiologicalStressReading(level = PhysiologicalStressLevel.Moving)))
        assertEquals("Waiting for motion data", loadStatus(PhysiologicalStressReading(level = PhysiologicalStressLevel.Unavailable, hrSamplesObserved = 40, motionSamplesObserved = 0)))
        assertEquals("Elevated", loadStatus(PhysiologicalStressReading(score = 60, level = PhysiologicalStressLevel.ElevatedLoad)))
    }

    @Test
    fun savedBaselineMeansNoRelearning() {
        val estimator = PhysiologicalStressEstimator(PhysiologicalStressBaseline(65.0, 40.0))
        val first = estimator.evaluate(0)
        assertTrue(first.level != PhysiologicalStressLevel.EstablishingBaseline)
        assertEquals(5, first.baselineWindowsObserved)
    }

    @Test
    fun liveSessionSummaryUsesDataSoFar() {
        val start = 1_700_000_000_000L
        val hr = (0 until 120).map { HrSampleEntity(sessionId = "s", timestampMs = start + it * 1000L, bpm = 60 + it % 20, rmssdMs = null, sdnnMs = null, pnn50Percent = null, load = null, contact = true) }
        val live = SessionAnalysis.summarize(SessionEntity("s", "Live", start), hr, emptyList(), emptyList(), 0)
        assertEquals(60, live.minHr)
        assertEquals(79, live.maxHr)
        val lines = SessionAnalysis.plainSummary(SessionData(live, hr, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false))
        assertTrue(lines.first(), lines.first().startsWith("Recording for"))
        assertTrue(lines.any { it.startsWith("Heart rate averaged") })
    }
}
