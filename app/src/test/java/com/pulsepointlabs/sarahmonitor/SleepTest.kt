package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.sleep.BodyPosition
import com.pulsepointlabs.sarahmonitor.sleep.SleepEpoch
import com.pulsepointlabs.sarahmonitor.sleep.SleepMath
import com.pulsepointlabs.sarahmonitor.sleep.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SleepTest {
    @Test
    fun breathingRateFromRespiratorySinusArrhythmia() {
        // 60 bpm with ±40 ms RSA at 14 breaths/min.
        val rr = mutableListOf<Int>()
        var t = 0.0
        while (t < 120) {
            val r = 1000 + 40 * sin(2 * PI * (14 / 60.0) * t)
            rr += r.toInt(); t += r / 1000
        }
        val b = SleepMath.breathsFromRr(rr)
        assertNotNull(b)
        assertEquals(14.0, b!!, 0.8)
    }

    @Test
    fun positionFromCalibration() {
        val back = doubleArrayOf(0.0, 0.0, 1000.0)
        val right = doubleArrayOf(1000.0, 0.0, 0.0)
        assertEquals(BodyPosition.Back, SleepMath.position(doubleArrayOf(80.0, 40.0, 990.0), back, right))
        assertEquals(BodyPosition.Front, SleepMath.position(doubleArrayOf(0.0, 50.0, -990.0), back, right))
        assertEquals(BodyPosition.Left, SleepMath.position(doubleArrayOf(-980.0, 0.0, 100.0), back, right))
        assertEquals(BodyPosition.Upright, SleepMath.position(doubleArrayOf(0.0, 1000.0, 0.0), back, right))
        assertEquals(BodyPosition.Unknown, SleepMath.position(doubleArrayOf(0.0, 1000.0, 0.0), null, right))
    }

    @Test
    fun syntheticNightIsStagedAndScored() {
        val start = 0L
        val epochs = (0 until 960).map { i -> // 8 h
            val min = i / 2
            val awakeStart = min < 15
            val deep = min in 40..100 || min in 160..200
            val rem = min in 120..140 || min in 300..330 || min in 420..445
            val hr = when { awakeStart -> 72.0; deep -> 50.0; rem -> 60.0 + (i % 5) * 2.5; else -> 56.0 }
            SleepEpoch(start + i * 30_000L, hr, if (deep) 60.0 else if (rem) 30.0 else 40.0, if (awakeStart) 120.0 else 8.0, BodyPosition.Back, if (rem) 14.0 + (i % 3) * 3 else 13.0)
        }
        val staged = SleepMath.stage(epochs)
        assertEquals(SleepStage.Wake, staged[5].stage)
        assertEquals(SleepStage.Deep, staged[2 * 60].stage)
        assertEquals(SleepStage.Rem, staged[2 * 310].stage)
        val s = SleepMath.summarize(staged, 960 * 30_000L, emptyList())
        assertTrue("asleep ${s.asleepMin}", s.asleepMin in 440..470)
        assertTrue("score ${s.score}", s.score >= 80)
        assertEquals(15, s.latencyMin)
    }
}
