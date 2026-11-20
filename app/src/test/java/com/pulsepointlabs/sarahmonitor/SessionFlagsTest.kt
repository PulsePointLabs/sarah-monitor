package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.BpEntry
import com.pulsepointlabs.sarahmonitor.core.GlucoseEntry
import com.pulsepointlabs.sarahmonitor.session.FlagKind
import com.pulsepointlabs.sarahmonitor.session.FlagSeverity
import com.pulsepointlabs.sarahmonitor.session.HrSampleEntity
import com.pulsepointlabs.sarahmonitor.session.PelvicSampleEntity
import com.pulsepointlabs.sarahmonitor.session.SessionData
import com.pulsepointlabs.sarahmonitor.session.SessionEntity
import com.pulsepointlabs.sarahmonitor.session.SessionFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFlagsTest {
    private val start = 1_700_000_000_000L
    private val minutes = 30

    private fun hr(bpm: (Int) -> Int, rmssd: (Int) -> Double? = { 40.0 }, load: (Int) -> Int? = { 20 }) =
        (0 until minutes * 60).map { s -> HrSampleEntity(sessionId = "s", timestampMs = start + s * 1000L, bpm = bpm(s), rmssdMs = rmssd(s), sdnnMs = 50.0, pnn50Percent = 10.0, load = load(s), contact = true) }

    private fun data(
        hr: List<HrSampleEntity> = hr({ 70 }),
        pelvic: List<PelvicSampleEntity> = emptyList(),
        bp: List<BpEntry> = emptyList(),
        glucose: List<GlucoseEntry> = emptyList()
    ) = SessionData(SessionEntity("s", "t", start, start + minutes * 60_000L), hr, emptyList(), pelvic, emptyList(), bp, glucose, false)

    @Test fun calmSessionFlagsNothing() = assertTrue(SessionFlags.detect(data()).isEmpty())

    @Test fun highHrPeriodAndSurge() {
        // 70 bpm, then minutes 10–13 at 125 bpm (reached within ~20 s).
        val flags = SessionFlags.detect(data(hr({ s -> if (s in 600..780) 125 else 70 })))
        val high = flags.single { it.kind == FlagKind.HighHr }
        assertTrue(high.durationMs in 150_000L..200_000L)
        assertTrue(flags.any { it.kind == FlagKind.HrSurge })
        assertTrue("surge and high HR should be linked", high.alongside.contains(FlagKind.HrSurge))
    }

    @Test fun shortSpikeIsNotAHighHrPeriod() {
        val flags = SessionFlags.detect(data(hr({ s -> if (s in 600..610) 130 else 70 })))
        assertTrue(flags.none { it.kind == FlagKind.HighHr })
    }

    @Test fun highLoadNeedsAMinute() {
        val flags = SessionFlags.detect(data(hr({ 70 }, load = { s -> if (s in 300..420) 85 else if (s in 900..930) 70 else 20 })))
        val load = flags.filter { it.kind == FlagKind.HighLoad }
        assertEquals(1, load.size)
        assertEquals(FlagSeverity.High, load.single().severity)
    }

    @Test fun hrvDropAgainstSessionMedian() {
        val flags = SessionFlags.detect(data(hr({ 70 }, rmssd = { s -> if (s in 1200..1400) 15.0 else 45.0 })))
        assertTrue(flags.any { it.kind == FlagKind.HrvDrop && it.severity == FlagSeverity.High })
    }

    @Test fun signalGapIsInfoOnly() {
        val samples = hr({ 70 }).filter { it.timestampMs - start !in 300_000L..330_000L }
        val gap = SessionFlags.detect(data(samples)).single { it.kind == FlagKind.HrSignalGap }
        assertEquals(FlagSeverity.Info, gap.severity)
    }

    @Test fun pelvicBurstLongHoldAndStrongContraction() {
        val pelvic = mutableListOf<PelvicSampleEntity>()
        fun pulse(atS: Double, lengthS: Double, kpa: Double) {
            val from = start + (atS * 1000).toLong()
            for (k in 0 until (lengthS * 10).toInt()) pelvic += PelvicSampleEntity(sessionId = "s", timestampMs = from + k * 100L, pressureKpa = 5 + kpa, aboveRestKpa = kpa, holding = true)
            pelvic += PelvicSampleEntity(sessionId = "s", timestampMs = from + (lengthS * 1000).toLong() + 100, pressureKpa = 5.0, aboveRestKpa = 0.0, holding = false)
        }
        // background samples so gaps don't split things
        for (t in 0 until 600) pelvic += PelvicSampleEntity(sessionId = "s", timestampMs = start + t * 100L, pressureKpa = 5.0, aboveRestKpa = 0.0, holding = false)
        listOf(70.0, 90.0, 110.0).forEach { pulse(it, 1.0, 1.2) }          // ordinary
        listOf(200.0, 201.0, 202.0, 203.0, 204.0).forEach { pulse(it, 0.5, 1.5) } // burst of 5
        pulse(300.0, 7.0, 1.4)                                            // long hold
        pulse(400.0, 1.0, 4.5)                                            // strong
        val sorted = pelvic.sortedBy { it.timestampMs }
        val flags = SessionFlags.detect(data(pelvic = sorted))
        assertTrue(flags.any { it.kind == FlagKind.ContractionBurst && it.detail.startsWith("5 contractions") })
        assertTrue(flags.any { it.kind == FlagKind.LongHold })
        assertTrue(flags.any { it.kind == FlagKind.StrongContraction })
    }

    @Test fun bpAndGlucose() {
        val flags = SessionFlags.detect(data(
            bp = listOf(BpEntry(start + 60_000, 128, 82, 66, false, "x"), BpEntry(start + 120_000, 186, 101, 90, false, "x")),
            glucose = listOf(GlucoseEntry(start + 200_000, 62.0, "x"), GlucoseEntry(start + 300_000, 120.0, "x"))
        ))
        assertEquals(FlagSeverity.High, flags.single { it.kind == FlagKind.HighBp }.severity)
        assertEquals(1, flags.count { it.kind == FlagKind.LowGlucose })
        assertTrue(flags.none { it.kind == FlagKind.HighGlucose })
    }

    @Test fun countsReadNicely() {
        val text = SessionFlags.describeCounts(SessionFlags.detect(data(hr({ s -> if (s in 600..780) 125 else 70 }))))!!
        assertTrue(text, text.contains("high heart rate"))
    }
}
