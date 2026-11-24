package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.BpEntry
import com.pulsepointlabs.sarahmonitor.core.CaptureKind
import com.pulsepointlabs.sarahmonitor.core.PelvicProcessor
import com.pulsepointlabs.sarahmonitor.core.PressureQuality
import com.pulsepointlabs.sarahmonitor.session.FlagKind
import com.pulsepointlabs.sarahmonitor.session.GearItem
import com.pulsepointlabs.sarahmonitor.session.GearType
import com.pulsepointlabs.sarahmonitor.session.HrSampleEntity
import com.pulsepointlabs.sarahmonitor.session.MarkerEntity
import com.pulsepointlabs.sarahmonitor.session.MarkerTypes
import com.pulsepointlabs.sarahmonitor.session.PelvicSampleEntity
import com.pulsepointlabs.sarahmonitor.session.PressureReview
import com.pulsepointlabs.sarahmonitor.session.SessionAnalysis
import com.pulsepointlabs.sarahmonitor.session.SessionCategory
import com.pulsepointlabs.sarahmonitor.session.SessionData
import com.pulsepointlabs.sarahmonitor.session.SessionEntity
import com.pulsepointlabs.sarahmonitor.session.SessionExport
import com.pulsepointlabs.sarahmonitor.session.SessionFlags
import com.pulsepointlabs.sarahmonitor.session.VoiceCommand
import com.pulsepointlabs.sarahmonitor.review.parseElapsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.25.0: calibration-before-logging, sensor integrity, quality-aware analysis and the export fixes. */
class ResearchIntegrityTest {
    private val step = 100L // CIVET sends 10/s

    private fun feed(p: PelvicProcessor, from: Long, seconds: Double, kpa: (Long) -> Double): Long {
        var t = from
        val end = from + (seconds * 1000).toLong()
        while (t < end) { p.ingest(t, kpa(t)); t += step }
        return t
    }

    /** Rest captured at 6 kPa (tiny noise) and logging on; returns the time after. */
    private fun calibrated(p: PelvicProcessor): Long {
        var t = feed(p, 0, 2.0) { 6.0 }
        p.startCapture(CaptureKind.Rest, t)
        t = feed(p, t, 5.2) { 6.0 + if ((it / step) % 2 == 0L) 0.01 else -0.01 }
        assertNull(p.startLogging(t))
        return t
    }

    // --- Calibration before logging -------------------------------------------------------------------------

    @Test fun previewNeverCountsContractions() {
        val p = PelvicProcessor()
        var t = feed(p, 0, 3.0) { 6.0 } // auto rest exists, logging off
        t = feed(p, t, 1.0) { 9.0 }     // a big squeeze while positioning
        val s = p.ingest(feed(p, t, 1.0) { 6.0 }, 6.0)
        assertEquals(PressureQuality.Preview, s.quality)
        assertEquals(0, s.pulseCount)
        assertNull("preview pressure must not feed statistics", s.sessionMax)
        assertTrue("still shown for positioning", s.aboveRestKpa != null)
    }

    @Test fun loggingNeedsARestLevel() {
        val p = PelvicProcessor()
        p.ingest(0, 6.0)
        assertTrue(p.startLogging(100) != null)
    }

    @Test fun startingLoggingRecordsAnEvent() {
        val p = PelvicProcessor()
        calibrated(p)
        val kinds = p.drainEvents().map { it.kind }
        assertTrue(kinds.containsAll(listOf("rest_captured", "logging_on")))
    }

    // --- Displacement --------------------------------------------------------------------------------------

    @Test fun sustainedDropBelowRestIsSuspectedDisplacementAndLatches() {
        val p = PelvicProcessor()
        var t = calibrated(p)
        t = feed(p, t, 1.5) { 1.0 } // sensor slipped: preload gone
        val faulted = p.ingest(t, 1.0)
        assertEquals(PressureQuality.SuspectedDisplacement, faulted.quality)
        assertFalse(faulted.holding)
        assertTrue(p.drainEvents().any { it.kind == "suspected_displacement" })
        // Pressure comes back: the fault stays until the user acts (never recalibrated away silently).
        t = feed(p, t, 5.0) { 6.0 }
        t = feed(p, t, 1.0) { 8.0 } // a squeeze while faulted
        val still = p.ingest(t, 6.0)
        assertEquals(PressureQuality.SuspectedDisplacement, still.quality)
        assertEquals(0, still.pulseCount)
        // Recapturing rest is the user recalibrating: logging resumes.
        p.startCapture(CaptureKind.Rest, t)
        t = feed(p, t, 5.2) { 6.0 }
        assertEquals(PressureQuality.Valid, p.ingest(t, 6.0).quality)
    }

    @Test fun briefDipIsIgnored() {
        val p = PelvicProcessor()
        var t = calibrated(p)
        t = feed(p, t, 0.5) { 1.0 }
        assertEquals(PressureQuality.Valid, p.ingest(feed(p, t, 1.0) { 6.0 }, 6.0).quality)
    }

    @Test fun resumeKeepsRestAndStabilizesFirst() {
        val p = PelvicProcessor()
        var t = calibrated(p)
        t = feed(p, t, 1.5) { 1.0 }
        assertNull(p.resume(t))
        assertEquals(PressureQuality.Stabilizing, p.ingest(t + 100, 6.0).quality)
        t = feed(p, t + 200, 6.0) { 6.0 }
        assertEquals(PressureQuality.Valid, p.ingest(t, 6.0).quality)
        assertEquals(6.0, p.ingest(t + 100, 6.0).restKpa!!, 0.05)
    }

    @Test fun negativeBaselineShiftIsFlagged() {
        val p = PelvicProcessor()
        var t = calibrated(p)
        t = feed(p, t, 60.0) { 4.5 } // re-seated lower: 1.5 kPa under rest, not enough for displacement
        assertEquals(PressureQuality.BaselineShift, p.ingest(t, 4.5).quality)
    }

    @Test fun reportedProblemStopsAnalysis() {
        val p = PelvicProcessor()
        val t = calibrated(p)
        p.reportProblem(t, "probe slipped out")
        assertEquals(PressureQuality.ReportedProblem, p.ingest(t + 100, 6.0).quality)
    }

    @Test fun reconnectGapStabilizesWithoutTouchingRest() {
        val p = PelvicProcessor()
        var t = calibrated(p)
        t += 8_000L // link dropped for 8 s
        assertEquals(PressureQuality.Stabilizing, p.ingest(t, 6.0).quality)
        t = feed(p, t + 100, 5.5) { 6.0 }
        assertEquals(PressureQuality.Valid, p.ingest(t, 6.0).quality)
        assertTrue(p.drainEvents().any { it.kind == "signal_gap" })
    }

    // --- Sustained elevation -------------------------------------------------------------------------------

    @Test fun holdLongerThanAContractionIsNotCounted() {
        val p = PelvicProcessor()
        var t = calibrated(p)
        t = feed(p, t, 45.0) { 7.5 } // 45 s "hold": sustained elevation
        assertTrue(p.ingest(t, 7.5).sustained)
        t = feed(p, t, 2.0) { 6.0 }
        val after = p.ingest(t, 6.0)
        assertEquals(0, after.pulseCount)
        assertNull(after.longestHoldS)
    }

    // --- Review: quality-aware analysis and legacy replay --------------------------------------------------

    private fun samples(seconds: Int, quality: String?, value: (Long) -> Pair<Double, Boolean>): List<PelvicSampleEntity> =
        (0 until seconds * 10).map { i ->
            val t = 1_000_000L + i * 100L
            val (kpa, hold) = value(t - 1_000_000L)
            PelvicSampleEntity(sessionId = "s", timestampMs = t, pressureKpa = kpa, aboveRestKpa = kpa - 6.0, holding = hold, quality = quality)
        }

    @Test fun legacyReplayFindsADisplacement() {
        // An older recording (no stored quality), captured rest 6 kPa, sensor out from 60 s to 70 s.
        val s = samples(120, null) { ms -> (if (ms in 60_000L..70_000L) 0.5 else 6.0) to false }
        val q = PressureReview.qualities(s)
        val faults = PressureReview.faultSpans(s, q)
        assertEquals(1, faults.size)
        assertEquals(PressureQuality.SuspectedDisplacement, faults[0].quality)
        assertTrue(faults[0].replayed)
        assertEquals(PressureQuality.Unknown, q[100]) // before: usable as before
    }

    @Test fun sensorProblemEventExcludesItsRange() {
        val s = samples(60, PressureQuality.Valid.name) { ms -> (if (ms in 20_000L..25_000L) 8.0 else 6.0) to (ms in 20_000L..25_000L) }
        val marker = MarkerEntity("m", "s", 1_015_000L, "sensor_issue", "Sensor problem", endTimeMs = 1_030_000L, source = "Recollection")
        val summary = SessionAnalysis.pelvic(s, listOf(marker))
        assertEquals(0, summary.pulses.size)
        assertEquals(PressureQuality.ReportedProblem, summary.faults.single().quality)
        assertNull("excluded pressure must not set the maximum", summary.maxKpa?.takeIf { it > 0.5 })
    }

    @Test fun longStoredHoldBecomesSustainedElevation() {
        val s = samples(120, PressureQuality.Valid.name) { ms -> (if (ms in 10_000L..90_000L) 7.5 else 6.0) to (ms in 10_000L..90_000L) }
        val summary = SessionAnalysis.pelvic(s)
        assertEquals(0, summary.pulses.size)
        assertEquals(1, summary.sustained.size)
    }

    @Test fun previewSamplesAreNotLogged() {
        val s = samples(30, PressureQuality.Preview.name) { 6.0 to false }
        val summary = SessionAnalysis.pelvic(s)
        assertEquals(0, summary.loggedCount)
        assertNull(summary.usableFraction)
    }

    // --- Export and flags ----------------------------------------------------------------------------------

    private val start = 1_700_000_000_000L

    private fun data(pelvic: List<PelvicSampleEntity> = emptyList(), bp: List<BpEntry> = emptyList(), markers: List<MarkerEntity> = emptyList()) =
        SessionData(
            SessionEntity("s", "t", start, start + 10 * 60_000L),
            (0 until 600).map { HrSampleEntity(sessionId = "s", timestampMs = start + it * 1000L, bpm = 70, rmssdMs = 40.0, sdnnMs = 50.0, pnn50Percent = 10.0, load = 20, contact = true) },
            emptyList(), pelvic, markers, bp, emptyList(), false
        )

    @Test fun csvNeverRepeatsAFlagTitleInOneRow() {
        // Two long holds back to back: their flags overlap in one second.
        val p = (0 until 300).map { i ->
            val t = start + 60_000L + i * 100L
            val hold = i in 0..69 || i in 72..141
            PelvicSampleEntity(sessionId = "s", timestampMs = t, pressureKpa = if (hold) 8.0 else 6.0, aboveRestKpa = if (hold) 2.0 else 0.0, holding = hold, quality = "Valid")
        }
        val csv = SessionExport.csv(data(p))
        assertTrue(csv.lines().none { it.contains("Long pelvic hold | Long pelvic hold") })
        assertTrue(csv.contains("Long pelvic hold"))
    }

    @Test fun preSessionBpIsLabelledNotContemporaneous() {
        val d = data(bp = listOf(BpEntry(start - 12 * 60_000L, 148, 104, 90, false, "test")))
        assertEquals("12 min before start", SessionAnalysis.bpTiming(start - 12 * 60_000L, d))
        val flag = SessionFlags.detect(d).single { it.kind == FlagKind.HighBp }
        assertTrue(flag.detail.contains("before start"))
        val md = SessionExport.markdown(d)
        assertTrue(md.contains("12 min before start"))
        assertTrue(md.contains("-12:00"))
    }

    // --- Hands-free: the CIVET is not the Foley ------------------------------------------------------------

    private val exploration = MarkerTypes.offered(SessionCategory.Exploration.groups).map { it.id }.toSet()
    private val foley = GearItem("g1", GearType.Foley, mapOf("size_fr" to "22"))

    @Test fun probeSlippingIsASensorProblemNotTheFoley() {
        val e = VoiceCommand.parseLocal("the probe slipped out again", exploration, listOf(foley))
        assertTrue("sensor_issue" in e.types)
        assertNull(e.gearId)
    }

    @Test fun adjustingTheSensorDoesNotFileUnderTheFoley() {
        val e = VoiceCommand.parseLocal("adjusting the sensor", exploration, listOf(foley))
        assertNull(e.gearId)
    }

    @Test fun namingTheCatheterStillFilesUnderIt() {
        val e = VoiceCommand.parseLocal("adjusting the catheter", exploration, listOf(foley))
        assertEquals("g1", e.gearId)
    }

    @Test fun sensorProblemIsOfferedInEveryLane() {
        SessionCategory.entries.forEach { c -> assertTrue(c.name, MarkerTypes.offered(c.groups).any { it.id == "sensor_issue" }) }
    }

    // --- Retrospective timing ------------------------------------------------------------------------------

    @Test fun elapsedTimesParse() {
        assertEquals(33 * 60_000L + 15_000L, parseElapsed("33:15"))
        assertEquals(3_725_000L, parseElapsed("1:02:05"))
        assertNull(parseElapsed("33:75"))
        assertNull(parseElapsed("abc"))
    }
}
