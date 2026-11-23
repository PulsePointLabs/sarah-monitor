package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.session.TranscriptAssembler
import com.pulsepointlabs.sarahmonitor.session.VoiceTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class PauseJoinTest {
    private fun say(vararg pieces: Pair<String, Long?>) = VoiceTextNormalizer.normalize(TranscriptAssembler.join(pieces.toList()), fixStrayCaps = false)

    @Test fun shortPauseMidThoughtIsAComma() =
        assertEquals("I was going to say I'm relaxing, and then prepping the lab.",
            say("I was going to say I'm relaxing." to 0L, "And then prepping the lab." to 1_000L))

    @Test fun longThinkingPauseIsAnEllipsis() =
        assertEquals("Resistance at the external sphincter… maybe three out of ten.",
            say("Resistance at the external sphincter." to 0L, "Maybe three out of ten." to 2_400L))

    @Test fun newThoughtGetsAFullStop() =
        assertEquals("Lube is helping. Erection is softer than a few minutes ago.",
            say("Lube is helping." to 0L, "Erection is softer than a few minutes ago." to 3_000L))

    @Test fun namesKeepTheirCapitals() =
        assertEquals("Inserting the Foley now, and the Howl is on channel A.",
            say("Inserting the Foley now." to 0L, "and the Howl is on channel A." to 1_100L))

    @Test fun withoutTimingTheModelPunctuationStands() =
        assertEquals("This first. Then this.", say("This first." to null, "Then this." to null))
}
