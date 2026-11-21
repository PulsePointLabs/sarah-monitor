package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.emg.EmgLineParser
import com.pulsepointlabs.sarahmonitor.emg.EmgProcessor
import com.pulsepointlabs.sarahmonitor.emg.EmgStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmgTest {
    @Test
    fun parserKeepsPartialLinesAndRejectsJunk() {
        val p = EmgLineParser()
        assertEquals(0, p.feed("51".toByteArray()).size)
        val a = p.feed("2,300\r\n4".toByteArray())
        assertEquals(1, a.size); assertEquals(512, a[0][0]); assertEquals(300, a[0][1])
        val b = p.feed("10,20\nabc,1\n2000,1\n5\n".toByteArray())
        assertEquals(listOf(410, 5), b.map { it[0] })
        assertEquals(1, b[1].size)
    }

    private fun feed(e: EmgProcessor, from: Long, ms: Long, v: (Long) -> IntArray): Long {
        var t = from
        while (t < from + ms) { e.add(t, v(t)); t += 10 }
        return t
    }

    @Test
    fun guidedCalibrationGivesPercentOfHold() {
        val e = EmgProcessor(listOf("Perineum", "Glute"))
        var t = feed(e, 0, 1_000) { intArrayOf(100, 90) }
        e.startCapture(EmgStep.Rest, t)
        t = feed(e, t, 8_200) { intArrayOf(100 + (it / 10 % 3).toInt(), 90) }
        assertEquals(100.0, e.snapshot(t).channels[0].rest!!, 2.0)
        e.startCapture(EmgStep.Hold1, t)
        t = feed(e, t, 3_000) { intArrayOf(100, 90) }
        t = feed(e, t, 5_200) { intArrayOf(500, 90) }
        val c = e.snapshot(t).channels[0]
        assertTrue(c.calibrated)
        assertEquals(100.0, c.pct!!, 3.0)
        t = feed(e, t, 3_000) { intArrayOf(300, 90) }
        assertEquals(50.0, e.snapshot(t).channels[0].pct!!, 3.0)
        assertNull(e.snapshot(t).channels[1].pct)
    }

    @Test
    fun weakHoldIsRejected() {
        val e = EmgProcessor()
        var t = feed(e, 0, 500) { intArrayOf(100) }
        e.startCapture(EmgStep.Rest, t); t = feed(e, t, 8_200) { intArrayOf(100) }
        e.startCapture(EmgStep.Hold1, t); t = feed(e, t, 8_200) { intArrayOf(112) }
        val s = e.snapshot(t)
        assertNull(s.channels[0].hold)
        assertNotNull(s.message)
        assertTrue(s.message!!.contains("Not enough rise"))
    }
}
