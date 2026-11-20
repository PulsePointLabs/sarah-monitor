package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.BpEntry
import com.pulsepointlabs.sarahmonitor.review.Viewport
import com.pulsepointlabs.sarahmonitor.session.HrSampleEntity
import com.pulsepointlabs.sarahmonitor.session.MarkerEntity
import com.pulsepointlabs.sarahmonitor.session.MarkerTypes
import com.pulsepointlabs.sarahmonitor.session.PelvicSampleEntity
import com.pulsepointlabs.sarahmonitor.session.RrEntity
import com.pulsepointlabs.sarahmonitor.session.SessionAnalysis
import com.pulsepointlabs.sarahmonitor.session.SessionData
import com.pulsepointlabs.sarahmonitor.session.SessionEntity
import com.pulsepointlabs.sarahmonitor.session.SessionExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAnalysisTest {
    private val start = 1_700_000_000_000L
    private val session = SessionEntity("s1", "Test", start, start + 10 * 60_000L)

    private fun hr(): List<HrSampleEntity> = (0 until 600).map { i ->
        HrSampleEntity(sessionId = "s1", timestampMs = start + i * 1000L, bpm = if (i < 300) 70 else 90, rmssdMs = 40.0, sdnnMs = 50.0, pnn50Percent = 10.0, load = 20, contact = true)
    }

    private fun pelvic(): List<PelvicSampleEntity> = (0 until 600).map { i ->
        val t = start + i * 100L
        val holding = i in 100..129 || i in 300..359 // 3 s and 6 s holds
        PelvicSampleEntity(sessionId = "s1", timestampMs = t, pressureKpa = 5.0, aboveRestKpa = if (holding) 2.0 + (i % 3) * 0.1 else 0.05, holding = holding)
    }

    private fun data(markers: List<MarkerEntity> = emptyList()) = SessionData(session, hr(), emptyList(), pelvic(), markers, listOf(BpEntry(start + 60_000L, 128, 82, 66, false, "Omron")), emptyList(), false)

    @Test
    fun pelvicPulsesAndLongestHold() {
        val p = SessionAnalysis.pelvic(pelvic())
        assertEquals(2, p.pulses.size)
        assertEquals(6.0, p.longestHoldS!!, 0.11)
        assertEquals(2.2, p.maxKpa!!, 1e-9)
    }

    @Test
    fun summaryFillsSessionFields() {
        val rr = (0 until 50).map { RrEntity(sessionId = "s1", timestampMs = start + it * 900L, rawMs = 850 + (it % 2) * 20, cleanMs = 850 + (it % 2) * 20, accepted = true, artifact = false, rejectionReason = null) }
        val s = SessionAnalysis.summarize(session, hr(), rr, pelvic(), 3)
        assertEquals(80.0, s.averageHr!!, 0.01)
        assertEquals(70, s.minHr)
        assertEquals(90, s.maxHr)
        assertEquals(20.0, s.rmssdMs!!, 0.01)
        assertEquals(2, s.pelvicPulseCount)
        assertEquals(3, s.markerCount)
        assertTrue(s.hrSparkline.split(',').size in 40..48)
    }

    @Test
    fun markerContextShowsHrResponse() {
        val m = MarkerEntity("m1", "s1", start + 290_000L, "event", "Event")
        val c = SessionAnalysis.markerContext(m, data(listOf(m)))
        assertEquals(70, c.hrAt)
        assertEquals(20.0, c.delta1Min!!, 0.01)
    }

    @Test
    fun plainSummaryReadsNaturally() {
        val lines = SessionAnalysis.plainSummary(data())
        assertTrue(lines.first().startsWith("10 min session"))
        assertTrue(lines.any { it.contains("Pelvic pressure peaked") })
        assertTrue(lines.any { it.contains("128/82") })
    }

    @Test
    fun csvHasOneRowPerSecondWithEvents() {
        val m = MarkerEntity("m1", "s1", start + 5_000L, "edge,symptom", "Symptom + Edge", "felt warm, then tense")
        val csv = SessionExport.csv(data(listOf(m))).lines().filter { it.isNotBlank() }
        assertEquals(601, csv.size) // header + 600 seconds
        assertTrue(csv[6].contains("Symptom + Edge"))
        assertTrue(csv[6].contains("\"felt warm, then tense\""))
    }

    @Test
    fun markdownIncludesAllSections() {
        val md = SessionExport.markdown(data(listOf(MarkerEntity("m1", "s1", start + 290_000L, "event", "Event", "note"))))
        listOf("## Prompt", "## Heart", "## Pelvic pressure", "## Events", "## Blood pressure", "## Glucose", "## Minute-by-minute").forEach { assertTrue(it, md.contains(it)) }
    }

    @Test
    fun markerTypesKeepOrderAndDefault() {
        assertEquals("event,edge", MarkerTypes.join(setOf("edge", "event")))
        assertEquals("event", MarkerTypes.join(emptySet()))
        assertEquals("Event + Edge", MarkerTypes.label(setOf("edge", "event")))
    }

    @Test
    fun viewportZoomStaysInsideSession() {
        val full = Viewport(0, 600_000)
        val z = full.zoomed(0.1, 590_000, 0, 600_000)
        assertEquals(60_000, z.spanMs)
        assertEquals(591_000, z.endMs) // the point under the pointer stays put
        val c = full.centeredOn(10_000, 120_000, 0, 600_000)
        assertEquals(0, c.startMs)
        assertNotNull(c)
        assertEquals(600_000, full.zoomed(10.0, 0, 0, 600_000).spanMs)
    }
}
