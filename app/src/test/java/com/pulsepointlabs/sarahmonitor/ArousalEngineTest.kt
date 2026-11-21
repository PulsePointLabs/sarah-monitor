package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.PelvicPulse
import com.pulsepointlabs.sarahmonitor.response.ArousalEngine
import com.pulsepointlabs.sarahmonitor.response.ClimaxSample
import com.pulsepointlabs.sarahmonitor.response.PersonalProfile
import com.pulsepointlabs.sarahmonitor.response.ResponsePhase
import com.pulsepointlabs.sarahmonitor.response.ResponseState
import com.pulsepointlabs.sarahmonitor.ui.partition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArousalEngineTest {
    private fun run(e: ArousalEngine, from: Int, to: Int, hr: (Int) -> Int, rmssd: (Int) -> Double = { 45.0 }): List<ResponseState> =
        (from until to).map { s -> e.onHeart(s * 1000L, hr(s), rmssd(s), 30) }

    @Test
    fun arousalSessionMovesThroughThePhases() {
        val e = ArousalEngine()
        run(e, 0, 240, { 66 })
        assertEquals(ResponsePhase.Baseline, e.current().phase)
        // Build: +40 bpm over 6 minutes, HRV compressing.
        val build = run(e, 240, 600, { s -> 66 + ((s - 240) / 9).coerceAtMost(40) }, { s -> (45.0 - (s - 240) / 12.0).coerceAtLeast(14.0) })
        assertTrue(build.any { it.phase == ResponsePhase.Building })
        // Hold high and steady with pelvic contractions.
        var count = 0
        val high = (600 until 780).map { s ->
            if (s % 6 == 0) { count++; e.onPelvic(s * 1000L, 2.5, false, count, PelvicPulse(s * 1000L - 800, s * 1000L - 200, 3.0)) }
            e.onHeart(s * 1000L, 106, 14.0, 70)
        }
        assertTrue(high.any { it.phase == ResponsePhase.Plateau || it.phase == ResponsePhase.Approaching })
        assertTrue(high.last().approach >= 60)
        // Climax signature: 6 contractions 0.8 s apart.
        for (i in 0 until 6) {
            count++
            val onset = 780_000L + i * 800L
            e.onPelvic(onset + 500, 4.0, false, count, PelvicPulse(onset, onset + 400, 6.0))
        }
        val at = e.onHeart(785_000L, 110, 13.0, 75)
        assertNotNull(at.candidate)
        assertEquals(ResponsePhase.Climax, at.phase)
        // Confirmed by marking → recovery as HR falls.
        e.onMarker(786_000L, setOf("orgasm"))
        assertEquals(1, e.current().climaxes)
        val rec = run(e, 790, 900, { s -> (110 - (s - 790) / 3).coerceAtLeast(80) }, { 30.0 })
        assertTrue(rec.any { it.phase == ResponsePhase.Recovery })
    }

    @Test
    fun edgeIsCountedWhenApproachBacksOff() {
        val e = ArousalEngine()
        run(e, 0, 400, { 64 })
        run(e, 400, 700, { s -> 64 + ((s - 400) / 8).coerceAtMost(36) }, { s -> (45.0 - (s - 400) / 10.0).coerceAtLeast(12.0) })
        var count = 0
        val near = (700 until 820).map { s ->
            if (s % 5 == 0) { count++; e.onPelvic(s * 1000L, 2.0, true, count, PelvicPulse(s * 1000L - 900, s * 1000L - 300, 2.5)) }
            e.onHeart(s * 1000L, 101 + (s - 700) / 30, 12.0, 70)
        }
        assertTrue(near.any { it.phase == ResponsePhase.Approaching })
        val off = run(e, 820, 900, { s -> (104 - (s - 820) / 2).coerceAtLeast(78) }, { 30.0 })
        assertTrue(off.any { it.phase == ResponsePhase.BackingOff })
        assertEquals(1, off.last().edges)
    }

    @Test
    fun profileCalibratesClimaxRise() {
        assertEquals(30.0, PersonalProfile().climaxHrDelta, 0.0)
        val p = PersonalProfile(listOf(ClimaxSample(44.0, null, null), ClimaxSample(48.0, null, null), ClimaxSample(40.0, null, null)))
        assertEquals(44.0, p.climaxHrDelta, 0.0)
        assertTrue(p.learned)
    }

    @Test
    fun sectionsSplitIntoBalancedColumns() {
        val w = listOf(10, 10, 5, 8, 14)
        val p = partition(w, 3)
        assertEquals(listOf(0..1, 2..3, 4..4), p.map { it.first..it.last })
        assertEquals(20, p.maxOf { r -> r.sumOf { w[it] } })
        assertEquals(listOf(0..4), partition(w, 1).map { it.first..it.last })
    }
}
