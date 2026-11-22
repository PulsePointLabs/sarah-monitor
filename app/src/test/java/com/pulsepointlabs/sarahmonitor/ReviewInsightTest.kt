package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.response.EpisodeKind
import com.pulsepointlabs.sarahmonitor.response.ResponseEpisodes
import com.pulsepointlabs.sarahmonitor.session.FlagInsight
import com.pulsepointlabs.sarahmonitor.session.FlagKind
import com.pulsepointlabs.sarahmonitor.session.HrSampleEntity
import com.pulsepointlabs.sarahmonitor.session.MarkerEntity
import com.pulsepointlabs.sarahmonitor.session.SessionData
import com.pulsepointlabs.sarahmonitor.session.SessionEntity
import com.pulsepointlabs.sarahmonitor.session.SessionFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class ReviewInsightTest {
    private val t0 = 1_700_000_000_000L

    /** 8 min at 72 bpm, then three build → peak → drop cycles; the third ends with a marked orgasm. */
    private fun session(): SessionData {
        val hr = mutableListOf<HrSampleEntity>()
        var t = t0
        fun add(bpm: Double, rmssd: Double, load: Int) { hr += HrSampleEntity(sessionId = "s", timestampMs = t, bpm = bpm.toInt(), rmssdMs = rmssd, sdnnMs = rmssd * 1.4, pnn50Percent = null, load = load, contact = true, stress = 20, breathsPerMin = 14.0); t += 1_000L }
        repeat(480) { add(72.0 + (it % 3), 42.0, 15) }
        val climaxAt = mutableListOf<Long>()
        repeat(3) { cycle ->
            repeat(150) { i -> val f = i / 150.0; add(72 + 45 * f, 42 - 30 * f, (15 + 70 * f).toInt()) }      // build 2.5 min
            repeat(40) { add(117.0 + (it % 2), 11.0, 86) }                                                       // peak 40 s
            if (cycle == 2) climaxAt += t
            repeat(90) { i -> val f = i / 90.0; add(117 - 40 * f, 11 + 25 * f, max(15.0, 86 - 70 * f).toInt()) } // drop 1.5 min
            repeat(150) { add(76.0 + (it % 2), 38.0, 18) }                                                       // rest 2.5 min
        }
        val markers = listOf(MarkerEntity("m1", "s", climaxAt.single() + 5_000L, "orgasm", "Orgasm"))
        return SessionData(SessionEntity("s", "Test", t0, t, category = "Masturbation"), hr, emptyList(), emptyList(), markers, emptyList(), emptyList(), false)
    }

    @Test fun findsEachCycleAndTheClimax() {
        val eps = ResponseEpisodes.detect(session())
        assertEquals("episodes: ${eps.map { it.kind to it.peakApproach }}", 3, eps.size)
        assertTrue(eps.take(2).all { it.kind == EpisodeKind.NearClimax || it.kind == EpisodeKind.Close })
        assertEquals(EpisodeKind.Climax, eps[2].kind)
        eps.forEach { e ->
            assertTrue("rise ${e.riseBpm}", (e.riseBpm ?: 0.0) in 35.0..50.0)
            assertTrue("half recovery ${e.halfRecoveryS}", (e.halfRecoveryS ?: 0) in 20..90)
            assertTrue("hrv ${e.rmssdAtPeak} vs ${e.rmssdBefore}", e.rmssdAtPeak!! < e.rmssdBefore!! * 0.5)
        }
        println("STORY " + eps[0].story())
    }

    @Test fun loadFlagGetsABreakdown() {
        val d = session()
        val flag = SessionFlags.detect(d).first { it.kind == FlagKind.HighLoad }
        val b = FlagInsight.explain(flag, d)
        println("FLAG " + b.story)
        println("FACTS " + b.facts)
        assertTrue(b.story.contains("Load rose from"))
        assertTrue(b.story.contains("sympathetic"))
        assertTrue(b.facts.any { it.label == "Peak" && it.value == "86" })
        assertTrue(b.traces.first().label == "Load")
    }
}
