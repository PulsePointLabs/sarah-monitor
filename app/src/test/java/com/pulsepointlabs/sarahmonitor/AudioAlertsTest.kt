package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.audio.AlertKind
import com.pulsepointlabs.sarahmonitor.audio.AlertLimits
import com.pulsepointlabs.sarahmonitor.audio.AlertTone
import com.pulsepointlabs.sarahmonitor.audio.BeatScheduler
import com.pulsepointlabs.sarahmonitor.audio.BeatSound
import com.pulsepointlabs.sarahmonitor.audio.BeatSynth
import com.pulsepointlabs.sarahmonitor.audio.LiveAlert
import com.pulsepointlabs.sarahmonitor.audio.LiveAlertEngine
import com.pulsepointlabs.sarahmonitor.core.PelvicPulse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioAlertsTest {
    @Test
    fun synthesizedSoundsStartAndEndSilentWithNoClipping() {
        val all = BeatSound.entries.mapNotNull { BeatSynth.render(it) } + listOfNotNull(BeatSynth.render(AlertTone.Chime))
        assertEquals(6, all.size)
        all.forEach { pcm ->
            assertTrue(abs(pcm.first().toInt()) < 200)
            assertTrue(abs(pcm.last().toInt()) < 200)
            assertTrue(pcm.maxOf { abs(it.toInt()) } < Short.MAX_VALUE)
            assertTrue(pcm.maxOf { abs(it.toInt()) } > 10_000)
        }
    }

    @Test
    fun wavHeaderIs48kMono16Bit() {
        val wav = BeatSynth.wav(ShortArray(480))
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(44 + 960, wav.size)
        val rate = (wav[24].toInt() and 0xff) or (wav[25].toInt() and 0xff shl 8) or (wav[26].toInt() and 0xff shl 16)
        assertEquals(48_000, rate)
    }

    @Test
    fun schedulerPlaysRealRrThenFallsBackToHr() {
        val s = BeatScheduler(maxQueue = 4)
        s.onHeart(60, listOf(800, 1200, 100), followRhythm = true) // 100 ms is noise, dropped
        assertEquals(800L, s.nextIntervalMs())
        assertEquals(1200L, s.nextIntervalMs())
        assertEquals(1000L, s.nextIntervalMs())
        s.onHeart(120, (1..10).map { 500 }, followRhythm = true)
        assertEquals(4, s.queued()) // never lags more than 4 beats
        s.onHeart(120, listOf(700), followRhythm = false)
        assertEquals(500L, s.nextIntervalMs())
    }

    private fun feed(engine: LiveAlertEngine, from: Long, seconds: Int, bpm: (Int) -> Int): List<LiveAlert> =
        (0 until seconds).flatMap { i -> engine.onHeart(from + i * 1000L, bpm(i), null, null, true, false) }

    @Test
    fun highHrNeedsThirtySecondsAndDoesNotRepeat() {
        val e = LiveAlertEngine { AlertLimits(highHr = 130) }
        assertTrue(feed(e, 0, 60) { 80 }.none { it.kind == AlertKind.HighHr })
        val high = feed(e, 60_000, 90) { 145 }.filter { it.kind == AlertKind.HighHr }
        assertEquals(1, high.size)
        assertTrue(high[0].spoken.startsWith("Heart rate 145"))
    }

    @Test
    fun surgeIsSpokenWithTheRise() {
        val e = LiveAlertEngine { AlertLimits(highHr = 200) }
        feed(e, 0, 60) { 70 }
        val surge = feed(e, 60_000, 40) { 105 }.filter { it.kind == AlertKind.HrSurge }
        assertEquals(1, surge.size)
        assertTrue(surge[0].spoken.contains("up"))
    }

    @Test
    fun contractionBurstAfterFourQuickPulses() {
        val e = LiveAlertEngine()
        var count = 0
        val alerts = (0 until 5).flatMap { i ->
            count++
            val onset = i * 900L
            e.onPelvic(onset + 500, false, null, count, PelvicPulse(onset, onset + 400, 2.0))
        }
        assertEquals(1, alerts.count { it.kind == AlertKind.ContractionBurst })
    }

    @Test
    fun longHoldOncePerHold() {
        val e = LiveAlertEngine { AlertLimits(longHoldS = 10) }
        val a = (0..15).flatMap { e.onPelvic(it * 1000L, true, it.toDouble(), 0, null) }
        assertEquals(1, a.count { it.kind == AlertKind.LongHold })
    }

    @Test
    fun bpAndGlucoseRanges() {
        val e = LiveAlertEngine()
        assertTrue(e.onBp(0, 128, 82).isEmpty())
        assertEquals("Blood pressure 152 over 94.", e.onBp(1, 152, 94).single().spoken)
        assertEquals("Glucose is low, 62.", e.onGlucose(0, 62.0).single().spoken)
    }

    @Test
    fun contactLostAfterTenSeconds() {
        val e = LiveAlertEngine()
        val a = (0..12).flatMap { e.onHeart(it * 1000L, 70, null, null, false, false) }
        assertEquals(1, a.count { it.kind == AlertKind.SignalLost })
    }
}
