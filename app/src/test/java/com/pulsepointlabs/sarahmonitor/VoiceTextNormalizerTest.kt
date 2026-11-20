package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.session.VoiceTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTextNormalizerTest {
    @Test
    fun removesSpacesAroundPunctuation() {
        assertEquals(
            "Vibing to music, relaxed. But we're close.",
            VoiceTextNormalizer.normalize("vibing to music , relaxed . But we 're close .")
        )
    }

    @Test
    fun lowersUnexpectedMidSentenceTitleCase() {
        assertEquals(
            "This feature is still not quite working, but we're getting close.",
            VoiceTextNormalizer.normalize("This feature is still not quite working , But we 're getting close .")
        )
    }

    @Test
    fun capitalizesSentenceStartsWithoutChangingAcronyms() {
        assertEquals(
            "THCA influence. HRV and H10 looked steady.",
            VoiceTextNormalizer.normalize("THCA influence . hRV and H10 looked Steady .")
        )
    }

    @Test
    fun keepsSingleIUppercase() {
        assertEquals(
            "I was relaxed, and I felt steady.",
            VoiceTextNormalizer.normalize("i was Relaxed , and i felt Steady .")
        )
    }
}
