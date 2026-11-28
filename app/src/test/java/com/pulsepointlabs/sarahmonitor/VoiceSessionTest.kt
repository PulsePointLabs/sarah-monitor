package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.PelvicPulse
import com.pulsepointlabs.sarahmonitor.response.ArousalEngine
import com.pulsepointlabs.sarahmonitor.response.ResponsePhase
import com.pulsepointlabs.sarahmonitor.session.LocalEars
import com.pulsepointlabs.sarahmonitor.session.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.27: wake word patience, voice "end the session", and one climax then a refractory period. */
class VoiceSessionTest {
    @Test fun sheWaitsLongerWhenTheThoughtSoundsUnfinished() {
        assertEquals(LocalEars.WAIT_FOR_START_MS, LocalEars.quietNeededMs("sarah"))
        assertEquals(LocalEars.UNFINISHED_QUIET_MS, LocalEars.quietNeededMs("sarah the probe"))           // two words
        assertEquals(LocalEars.UNFINISHED_QUIET_MS, LocalEars.quietNeededMs("sarah i'm feeling a lot of pressure and"))
        assertEquals(LocalEars.UNFINISHED_QUIET_MS, LocalEars.quietNeededMs("sarah the e-stim is getting more intense because"))
        assertEquals(LocalEars.END_QUIET_MS, LocalEars.quietNeededMs("sarah i'm feeling a lot of pressure building"))
        assertEquals(LocalEars.FINISHED_QUIET_MS, LocalEars.quietNeededMs("sarah i'm feeling a lot of pressure now"))
        assertEquals(LocalEars.FINISHED_QUIET_MS, LocalEars.quietNeededMs("sarah urge is about a seven"))
        assertTrue(LocalEars.END_QUIET_MS >= 2_000)   // the old 1.2 s cut him off mid-thought
    }

    @Test fun aLongNoteEndsOnSilenceNotTheClock() {
        val long = "sarah okay so the enema is in and I'm holding about a quart now the cramping is easing off " +
            "and the plug feels snug but comfortable and I'm noticing my heart rate settle down a bit now"
        assertEquals(LocalEars.END_QUIET_MS, LocalEars.quietNeededMs(long))   // long, but ends finished ("…now")
        // Two minutes in, still talking: not over.
        assertFalse(LocalEars.phraseOver(quietMs = 400, wordlessMs = 400, heard = long, lengthMs = 120_000))
        // Paused 2 s mid-note: not over yet.
        assertFalse(LocalEars.phraseOver(quietMs = 2_000, wordlessMs = 2_000, heard = long, lengthMs = 60_000))
        // Done: quiet past the long-note window.
        assertTrue(LocalEars.phraseOver(quietMs = 2_600, wordlessMs = 2_600, heard = long, lengthMs = 60_000))
        // A fan keeps the mic "busy" but no new words for a long while: over.
        assertTrue(LocalEars.phraseOver(quietMs = 0, wordlessMs = 10_000, heard = long, lengthMs = 60_000))
        assertTrue(LocalEars.MAX_PHRASE_MS >= 300_000)
    }

    @Test fun endTheSessionByVoice() {
        listOf(
            "End the session", "you can conclude the session", "Okay, end the session now please", "stop recording",
            "wrap up the session", "finish the recording", "the session is over", "We're done, you can end it", "end it"
        ).forEach { assertTrue(it, VoiceCommand.endsSession(it)) }
        listOf(
            "don't end the session yet", "I'm close to the end", "stop", "we're almost at the end of the session",
            "I'm going to end the session in five minutes", "end of the stimulation", "stop the e-stim",
            "the probe slipped near the end of the session and I had to reseat it twice while the balloon deflated"
        ).forEach { assertFalse(it, VoiceCommand.endsSession(it)) }
    }

    @Test fun neverMindLogsNothing() {
        assertTrue(VoiceCommand.dismisses("Stop."))
        assertTrue(VoiceCommand.dismisses("never mind"))
        assertTrue(VoiceCommand.dismisses("be quiet"))
        assertFalse(VoiceCommand.dismisses("stop the e-stim"))
    }

    private fun build(e: ArousalEngine): Int {
        (0 until 300).forEach { s -> e.onHeart(s * 1000L, 66, 45.0, 30) }
        (300 until 700).forEach { s -> e.onHeart(s * 1000L, 66 + ((s - 300) / 9).coerceAtMost(40), (45.0 - (s - 300) / 12.0).coerceAtLeast(14.0), 60) }
        var count = 0
        (700 until 800).forEach { s ->
            if (s % 6 == 0) { count++; e.onPelvic(s * 1000L, 2.5, false, count, PelvicPulse(s * 1000L - 800, s * 1000L - 200, 3.0)) }
            e.onHeart(s * 1000L, 106, 14.0, 70)
        }
        return count
    }

    @Test fun oneClimaxThenRefractoryEvenWhileWalkingAround() {
        val e = ArousalEngine()
        build(e)
        e.onMarker(800_000L, setOf("orgasm"))
        // Describing the same climax again a couple of minutes later isn't a second one.
        e.onMarker(920_000L, setOf("ejaculation"))
        e.onMarker(1_000_000L, setOf("orgasm"))
        assertEquals(1, e.current().climaxes)
        assertFalse(e.isNewClimax(1_100_000L))
        // Up and cleaning up: HR climbs back to 105 and swings with movement for 15 minutes.
        val after = (801 until 1_800).map { s ->
            e.onMotion(s * 1000L, 260.0)
            e.onHeart(s * 1000L, if (s < 900) 100 - (s - 801) / 5 else 95 + (s % 40) / 4, 30.0, 50)
        }
        assertTrue(after.none { it.phase == ResponsePhase.Approaching || it.phase == ResponsePhase.Building || it.phase == ResponsePhase.Climax && it.timestampAfter(900) })
        assertTrue(after.all { it.approach <= 40 })
        assertTrue(after.last().refractory)
        assertNull(after.last().candidate)
        assertEquals(1, after.last().climaxes)
    }

    @Test fun heSaysWhenHesGoingAgain() {
        val e = ArousalEngine()
        build(e)
        e.onMarker(800_000L, setOf("orgasm"))
        (801 until 1_200).forEach { s -> e.onHeart(s * 1000L, 80, 35.0, 40) }
        assertTrue(e.current().refractory || e.current().phase == ResponsePhase.Recovery)
        e.onMarker(1_200_000L, setOf("arousal"))
        val next = e.onHeart(1_201_000L, 90, 30.0, 50)
        assertFalse(next.refractory)
        assertTrue(e.isNewClimax(1_300_000L))
    }

    private fun com.pulsepointlabs.sarahmonitor.response.ResponseState.timestampAfter(s: Int) = (sinceClimaxS ?: 0) > s - 800
}
