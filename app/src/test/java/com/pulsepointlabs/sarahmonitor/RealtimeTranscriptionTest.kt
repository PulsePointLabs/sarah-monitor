package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.session.RealtimeTranscriber
import com.pulsepointlabs.sarahmonitor.session.TranscriptAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTranscriptionTest {
    @Test
    fun deltasStreamThenCompletionReplaces() {
        val a = TranscriptAssembler("Earlier note.")
        a.touch("i1")
        a.delta("i1", "Heart rate")
        a.delta("i1", " climbing")
        assertEquals("Earlier note. Heart rate climbing", a.text())
        assertTrue(a.pending())
        a.completed("i1", "Heart rate climbing fast.")
        a.delta("i1", " stray") // late delta after completion is ignored
        assertEquals("Earlier note. Heart rate climbing fast.", a.text())
        assertFalse(a.pending())
    }

    @Test
    fun turnsStayInTheOrderTheyStarted() {
        val a = TranscriptAssembler()
        a.touch("first"); a.touch("second")
        a.completed("second", "Then this.")
        a.completed("first", "This first.")
        assertEquals("This first. Then this.", a.text())
    }

    @Test
    fun sessionUpdateUsesGaTranscriptionShape() {
        val live = RealtimeTranscriber.sessionUpdate(RealtimeTranscriber.LIVE_MODEL)
        assertEquals("session.update", live.getString("type"))
        val session = live.getJSONObject("session")
        assertEquals("transcription", session.getString("type"))
        val input = session.getJSONObject("audio").getJSONObject("input")
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals(24_000, input.getJSONObject("format").getInt("rate"))
        assertTrue(input.isNull("turn_detection"))
        assertEquals("gpt-live-transcribe", input.getJSONObject("transcription").getString("model"))
        assertEquals("en", input.getJSONObject("transcription").getJSONArray("languages").getString(0))
        val fallback = RealtimeTranscriber.sessionUpdate(RealtimeTranscriber.FALLBACK_MODEL)
            .getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        assertEquals("server_vad", fallback.getJSONObject("turn_detection").getString("type"))
        assertEquals("en", fallback.getJSONObject("transcription").getString("language"))
    }

    @Test
    fun levelOfFullScaleAndSilence() {
        val loud = ByteArray(200) { if (it % 2 == 0) 0xff.toByte() else 0x7f }
        assertTrue(RealtimeTranscriber.levelDb(loud, loud.size) > -1.0)
        assertTrue(RealtimeTranscriber.levelDb(ByteArray(200), 200) < -90.0 + 1e-6 || RealtimeTranscriber.levelDb(ByteArray(200), 200) <= -100.0)
    }
}
