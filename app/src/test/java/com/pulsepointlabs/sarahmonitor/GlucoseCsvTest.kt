package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.glucose.GlucoseCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GlucoseCsvTest {
    private val zone = ZoneId.of("America/New_York")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun parsesOneTouchStyleExport() {
        val csv = """
            OneTouch Reveal export
            Date,Time,Event Type,Event Value,Unit,Note
            11/18/2026,7:42 AM,Blood Glucose,104,mg/dL,fasting
            11/18/2026,12:15 PM,Blood Glucose,142,mg/dL,
            11/18/2026,12:15 PM,Insulin,4,units,
        """.trimIndent()
        val result = GlucoseCsv.parse(csv, "reveal.csv", zone)
        assertNull(result.error)
        assertEquals(2, result.rows.size)
        assertEquals(104.0, result.rows[0].mgDl, 0.01)
        assertEquals(ms(2026, 11, 18, 7, 42), result.rows[0].timestampMs)
        assertEquals(ms(2026, 11, 18, 12, 15), result.rows[1].timestampMs)
    }

    @Test
    fun convertsMmol() {
        val csv = "Timestamp,Glucose (mmol/L)\n2026-11-18 08:00,5.5\n"
        val result = GlucoseCsv.parse(csv, "x.csv", zone)
        assertEquals(99.1, result.rows.single().mgDl, 0.1)
    }

    @Test
    fun reportsUnrecognisedFiles() {
        assertNotNull(GlucoseCsv.parse("a,b\n1,2\n", "x.csv", zone).error)
    }

    @Test
    fun parsesNamedMonths() {
        assertEquals(ms(2026, 11, 18, 21, 5), GlucoseCsv.parseLocalTimestamp("Nov 18, 2026 9:05 PM", zone))
    }
}
