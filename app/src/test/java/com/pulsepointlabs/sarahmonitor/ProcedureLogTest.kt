package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.session.LoggedEvent
import com.pulsepointlabs.sarahmonitor.session.Measures
import com.pulsepointlabs.sarahmonitor.session.ProcedureLog
import com.pulsepointlabs.sarahmonitor.session.VoiceCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.28: numbers in procedure notes, the running tally, and questions for Sarah. */
class ProcedureLogTest {
    @Test fun readsVolumesDepthsAndScores() {
        ProcedureLog.parseLocal("About 500 mL in so far, urge is a 4 out of 10").let {
            assertEquals(500, it.totalMl); assertNull(it.addedMl); assertEquals(4, it.urge)
        }
        ProcedureLog.parseLocal("Added another 250 milliliters").let { assertEquals(250, it.addedMl); assertNull(it.totalMl) }
        ProcedureLog.parseLocal("that's a liter in total").let { assertEquals(1000, it.totalMl) }
        ProcedureLog.parseLocal("half a liter going in now").let { assertEquals(500, it.totalMl) }
        ProcedureLog.parseLocal("tip is at about 16 centimeters, felt a little pop, discomfort 3").let {
            assertEquals(16.0, it.depthCm!!, 0.01); assertEquals(3, it.discomfort)
        }
        ProcedureLog.parseLocal("nozzle is about 4 inches in").let { assertEquals(10.2, it.depthCm!!, 0.05) }
        ProcedureLog.parseLocal("arousal 6 out of 10, getting hard from the balloon").let { assertEquals(6, it.arousal); assertEquals("building", it.erection) }
        ProcedureLog.parseLocal("partial erection when the device went in").let { assertEquals("partial", it.erection) }
        ProcedureLog.parseLocal("urge to go").let { assertNull(it.urge) }
        assertTrue(ProcedureLog.parseLocal("balloon seated, no issues").empty)
    }

    @Test fun usUnitsInAndOut() {
        ProcedureLog.parseLocal("about 16 ounces in so far, urge 5").let { assertEquals(473, it.totalMl); assertEquals(5, it.urge) }
        ProcedureLog.parseLocal("added another 8 oz").let { assertEquals(237, it.addedMl) }
        ProcedureLog.parseLocal("that's a quart in total").let { assertEquals(946, it.totalMl) }
        ProcedureLog.parseLocal("2 quarts in").let { assertEquals(1893, it.totalMl) }
        ProcedureLog.parseLocal("balloon took 10 cc").let { assertEquals(10, it.totalMl) }
        ProcedureLog.parseLocal("tip about 6 inches in, felt a pop").let { assertEquals(15.2, it.depthCm!!, 0.05) }
        // Shown and said in his units, never metric.
        assertEquals("16 oz in · depth 6 in · urge 5/10", Measures(totalMl = 473, depthCm = 15.24, urge = 5).short())
        assertEquals("8 ounces more, depth 6 inches", Measures(addedMl = 237, depthCm = 15.24).spoken())
        assertEquals("10 cc", com.pulsepointlabs.sarahmonitor.session.UsUnits.volume(10))
        assertEquals("34 oz (1.1 qt)", com.pulsepointlabs.sarahmonitor.session.UsUnits.volume(1000))
    }

    @Test fun claudeVolumeCallIsNeverMixedWithTheKeywordGuess() {
        val claude = Measures(addedMl = 250)
        val local = ProcedureLog.parseLocal("250 mL") // reads as a total on its own
        val merged = claude.orElse(local)
        assertEquals(250, merged.addedMl); assertNull(merged.totalMl)
        assertEquals(Measures(totalMl = 750, urge = 5), Measures.fromJson(JSONObject("""{"total_ml":750,"urge_0_10":5,"depth_cm":null}""")))
    }

    @Test fun runningTallyOfAnEnema() {
        val m = 60_000L
        val events = listOf(
            LoggedEvent(0, setOf("insert"), "Nozzle in", Measures(depthCm = 8.0)),
            LoggedEvent(2 * m, setOf("instill"), "Starting, 500 in", Measures(totalMl = 500)),
            LoggedEvent(5 * m, setOf("instill"), "another 250", Measures(addedMl = 250)),
            LoggedEvent(6 * m, setOf("urge"), "Urge 6", Measures(urge = 6)),
            LoggedEvent(7 * m, setOf("hold"), "Holding now"),
            LoggedEvent(9 * m, setOf("discomfort"), "Cramp, 4", Measures(discomfort = 4)),
            LoggedEvent(10 * m, setOf("finding"), "partial erection", Measures(erection = "partial"))
        )
        val s = ProcedureLog.state(events, 13 * m)
        assertEquals(750, s.getInt("instilled_total_cc"))
        assertEquals(25.5, s.getDouble("instilled_total_oz"), 0.01)
        assertEquals(6.0, s.getDouble("holding_for_minutes"), 0.01)
        assertEquals(13.0, s.getDouble("device_in_place_minutes"), 0.01)
        assertEquals(6, s.getJSONObject("urge_last").getInt("value"))
        assertEquals(7.0, s.getJSONObject("urge_last").getDouble("minutes_ago"), 0.01)
        assertEquals("partial", s.getJSONObject("erection_last").getString("value"))
        // Released: no longer holding.
        val after = ProcedureLog.state(events + LoggedEvent(14 * m, setOf("release"), "Releasing"), 15 * m)
        assertFalse(after.has("holding_for_minutes"))
        assertEquals(1.0, after.getDouble("released_minutes_ago"), 0.01)
    }

    @Test fun questionsAreToldFromNotes() {
        listOf("How long have I been holding?", "what's my heart rate", "is a pop at that depth normal", "Tell me how much is in",
            "Remind me what I said the depth was").forEach { assertTrue(it, ProcedureLog.isQuestion(it)) }
        listOf("Urge is a 6", "Felt a pinch at 15 centimeters", "Holding now", "Erection building").forEach { assertFalse(it, ProcedureLog.isQuestion(it)) }
        val e = VoiceCommand.parseClaude(
            """{"types":["urge"],"device":null,"bucket":"sensation","intensity":6,"site":null,"measures":{"urge_0_10":6,"total_ml":750},"question":false,"confirm":"Urge six, 750 in."}""",
            "urge is six with 750 in", setOf("urge", "event"), emptyList()
        )!!
        assertEquals(6, e.measures.urge); assertEquals(750, e.measures.totalMl); assertFalse(e.question)
    }

    @Test fun allDayLogIsSortedSensibly() {
        val general = com.pulsepointlabs.sarahmonitor.session.MarkerTypes.offered(com.pulsepointlabs.sarahmonitor.session.SessionCategory.General.groups).map { it.id }.toSet()
        assertTrue("substance" in general && "state" in general)
        fun sort(s: String) = VoiceCommand.parseLocal(s, general, emptyList())
        sort("Getting ready to resume doing chores, feeling fairly relaxed").let {
            assertEquals(setOf("activity", "state"), it.types); assertFalse("recovery" in it.types)
        }
        sort("Took a couple hits of indica, feeling pretty high").let { assertTrue("substance" in it.types); assertEquals("cannabis", it.measures.substance) }
        sort("Second beer, feeling a little buzzed").let { assertTrue("substance" in it.types); assertEquals("alcohol", it.measures.substance) }
        sort("Overstimulated from all the caffeine").let { assertEquals(setOf("substance", "state"), it.types); assertEquals("caffeine", it.measures.substance) }
        assertEquals("nicotine", ProcedureLog.substanceIn("just hit the vape"))
        assertEquals("cannabis", ProcedureLog.substanceIn("THC vape"))
        assertNull(ProcedureLog.substanceIn("high heart rate after the stairs"))
        // Substance types are there in a sexual session too.
        assertTrue("substance" in com.pulsepointlabs.sarahmonitor.session.MarkerTypes.offered(com.pulsepointlabs.sarahmonitor.session.SessionCategory.Masturbation.groups).map { it.id })
    }

    @Test fun pressureShowsInMmHg() {
        assertEquals("15", com.pulsepointlabs.sarahmonitor.core.Pressure.fmt(2.0))
        assertEquals("3.8", com.pulsepointlabs.sarahmonitor.core.Pressure.fmt(0.5))
        assertEquals("17 mmHg (2.31 kPa)", com.pulsepointlabs.sarahmonitor.core.Pressure.record(2.31))
    }

    @Test fun hisRealQuestionIsAQuestion() {
        assertTrue(ProcedureLog.isQuestion("Are you currently able to tell me anything about what I have told you recently? I am still building this app."))
        assertFalse(ProcedureLog.isQuestion("Can't feel much from the e-stim right now"))
        assertFalse(ProcedureLog.isQuestion("Was relaxing on the porch, heading back in"))
        assertFalse(ProcedureLog.isQuestion("I'm beginning to feel mild to moderate feelings of being relaxed from Indica THCA."))
    }
}