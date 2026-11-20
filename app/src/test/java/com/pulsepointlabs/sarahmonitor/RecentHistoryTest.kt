package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.Metric
import com.pulsepointlabs.sarahmonitor.core.RecentHistoryRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentHistoryTest {
    @Test
    fun risingHrIsPositiveAndCapped() {
        val r = RecentHistoryRecorder()
        for (i in 0..60) r.add(Metric.Hr, i * 1000L, 70.0 + i * 0.5) // +30 bpm over a minute
        assertEquals(1.0, r.snapshot().trend(Metric.Hr), 1e-9)
    }

    @Test
    fun steadyIsZeroAndFallingIsNegative() {
        val r = RecentHistoryRecorder()
        for (i in 0..60) { r.add(Metric.Rmssd, i * 1000L, 40.0); r.add(Metric.Load, i * 1000L, 60.0 - i * 0.2) }
        val h = r.snapshot()
        assertEquals(0.0, h.trend(Metric.Rmssd), 1e-9)
        assertTrue(h.trend(Metric.Load) < -0.4)
    }

    @Test
    fun notEnoughHistoryIsSteady() {
        val r = RecentHistoryRecorder()
        for (i in 0..10) r.add(Metric.Pelvic, i * 1000L, i.toDouble())
        assertEquals(0.0, r.snapshot().trend(Metric.Pelvic), 1e-9)
    }

    @Test
    fun keepsTwoMinutesAndThrottles() {
        val r = RecentHistoryRecorder()
        assertTrue(r.add(Metric.Hr, 0, 70.0))
        assertFalse(r.add(Metric.Hr, 100, 71.0)) // under 500 ms apart
        for (i in 1..300) r.add(Metric.Hr, i * 1000L, 70.0)
        val values = r.snapshot().series[Metric.Hr]!!
        assertTrue(values.first().timestampMs >= 300_000L - 120_000L)
    }
}
