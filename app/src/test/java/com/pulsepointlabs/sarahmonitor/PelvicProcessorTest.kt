package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.CaptureKind
import com.pulsepointlabs.sarahmonitor.core.PelvicProcessor
import com.pulsepointlabs.sarahmonitor.core.RestSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PelvicProcessorTest {
    private val stepMs = 50L // 20 Hz

    private fun feed(p: PelvicProcessor, startMs: Long, seconds: Double, kpa: (Long) -> Double): Long {
        var t = startMs
        val end = startMs + (seconds * 1000).toLong()
        while (t < end) { p.ingest(t, kpa(t)); t += stepMs }
        return t
    }

    @Test
    fun capturedRestGivesPressureAboveRest() {
        val p = PelvicProcessor()
        var t = feed(p, 0, 2.0) { 5.0 }
        assertNull(p.startCapture(CaptureKind.Rest, t))
        t = feed(p, t, 5.2) { 5.0 + if ((it / stepMs) % 2 == 0L) 0.01 else -0.01 }
        val s = p.ingest(t, 6.5)
        assertEquals(RestSource.Captured, s.restSource)
        assertEquals(1.5, s.aboveRestKpa!!, 0.02)
    }

    @Test
    fun countsAPulseAboveThreshold() {
        val p = PelvicProcessor()
        var t = feed(p, 0, 2.0) { 5.0 }
        p.startCapture(CaptureKind.Rest, t)
        t = feed(p, t, 5.2) { 5.0 }
        t = feed(p, t, 1.0) { 5.0 }
        t = feed(p, t, 1.0) { 7.0 } // 2 kPa squeeze for 1 s
        val holding = p.ingest(t, 7.0)
        assertTrue(holding.holding)
        t = feed(p, t + stepMs, 1.0) { 5.0 }
        val after = p.ingest(t, 5.0)
        assertFalse(after.holding)
        assertEquals(1, after.pulseCount)
        assertEquals(1.0, after.lastPulse!!.durationS, 0.15)
        assertEquals(2.0, after.lastPulse!!.peakKpa, 0.01)
    }

    @Test
    fun noisyRestIsRejected() {
        val p = PelvicProcessor()
        var t = feed(p, 0, 1.0) { 5.0 }
        p.startCapture(CaptureKind.Rest, t)
        t = feed(p, t, 5.2) { if ((it / stepMs) % 2 == 0L) 6.0 else 4.0 }
        val s = p.ingest(t, 5.0)
        assertTrue(s.restSource != RestSource.Captured)
        assertNotNull(s.message)
    }

    @Test
    fun referenceGivesPercent() {
        val p = PelvicProcessor()
        var t = feed(p, 0, 1.0) { 5.0 }
        p.startCapture(CaptureKind.Rest, t)
        t = feed(p, t, 5.2) { 5.0 }
        p.startCapture(CaptureKind.Reference, t)
        t = feed(p, t, 5.2) { 9.0 }
        val s = p.ingest(t, 7.0)
        assertEquals(4.0, s.referenceKpa!!, 0.01)
        assertEquals(50.0, s.percentOfReference!!, 0.5)
    }

    @Test
    fun autoRestFollowsTheLowLevel() {
        val p = PelvicProcessor()
        val t = feed(p, 0, 3.0) { 3.0 }
        val s = p.ingest(t, 4.0)
        assertEquals(RestSource.Auto, s.restSource)
        assertEquals(1.0, s.aboveRestKpa!!, 0.01)
    }

    @Test
    fun referenceNeedsRestFirst() {
        assertNotNull(PelvicProcessor().startCapture(CaptureKind.Reference, 0))
    }
}
