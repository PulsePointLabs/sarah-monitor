package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.glucose.GlucoseCsv
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** The OneTouch / meter export cases from Sarah's parseVitalCsv.test.js, so shares behave the same in both apps. */
class GlucoseCsvSarahParityTest {
    private val zone = ZoneId.of("America/New_York")
    private fun parse(vararg lines: String) = GlucoseCsv.parse(lines.joinToString("\n"), "test.csv", zone)
    private fun hour(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).hour
    private fun mg(vararg lines: String) = parse(*lines).rows.map { it.mgDl }

    @Test fun mmolConverted() = assertEquals(listOf(109.9), mg("Timestamp,Glucose,Unit", "2026-08-01 08:00,6.1,mmol/L"))

    @Test fun oneTouchHeaderAfterReportMetadata() {
        val r = parse(
            "OneTouch Reveal Data Report", "Patient Name,Ben", "Report Generated,08/01/2026",
            "Meter,Serial Number,Device Timestamp,Record Type,Glucose Value,Glucose Units",
            "OneTouch Verio,ABC123,\"08/01/2026 06:46 PM\",BG,108,mg/dL"
        )
        assertEquals(listOf(108.0), r.rows.map { it.mgDl })
        assertEquals(Instant.parse("2026-08-01T22:46:00Z").toEpochMilli(), r.rows[0].timestampMs)
    }

    @Test fun separateDateAndTimeAfterPreamble() =
        assertEquals(listOf(109.9), mg("OneTouch Reveal", "Generated for personal use", "Date,Time,Result,Units", "2026-08-01,07:30,6.1,mmol/L"))

    @Test fun readingDateAndReadingTime() {
        val r = parse("Reading Date,Reading Time,Result,Units,Meal Tag,Notes", "07/29/2026,8:15 PM,112,mg/dL,After Meal,session context", "07/29/2026,8:45 PM,6.2,mmol/L,Before Meal,")
        assertEquals(listOf(112.0, 111.7), r.rows.map { it.mgDl })
        assertEquals(20, hour(r.rows[0].timestampMs))
    }

    @Test fun mixedEventTableFiltered() = assertEquals(
        listOf(112.0, 111.7),
        mg(
            "OneTouch Reveal Data Export", "Generated,07/29/2026",
            "Date and Time,Event Type,Value,Unit,Manually Entered,Meal Tag,Notes",
            "07/29/2026 8:15 PM,Blood Glucose,112,mg/dL,No,After Meal,session context",
            "07/29/2026 8:20 PM,Carbs,30,g,Yes,,dinner",
            "07/29/2026 8:45 PM,Glucose,6.2,mmol/L,No,Before Meal,"
        )
    )

    @Test fun monthNamesAndZoneLabels() {
        val r = parse("Date and Time,Glucose Reading (mg/dL)", "\"Jul 29, 2026 8:15 PM EDT\",112", "\"29-Jul-2026 8:45 PM\",104")
        assertEquals(listOf(112.0, 104.0), r.rows.map { it.mgDl })
        assertEquals(20, hour(r.rows[0].timestampMs))
    }

    @Test fun compactEpochAndExcelSerial() {
        val epoch = LocalDateTime.of(2026, 7, 29, 20, 15).atZone(zone).toEpochSecond()
        val r = parse("Timestamp,Glucose", "20260729201500,112", "$epoch,104", "46232.84375,99")
        assertEquals(listOf(112.0, 104.0, 99.0), r.rows.map { it.mgDl })
        assertEquals(LocalDateTime.of(2026, 7, 29, 20, 15).atZone(zone).toInstant().toEpochMilli(), r.rows[2].timestampMs)
    }

    @Test fun unquotedCommaInMonthNameDate() {
        val r = parse("Date,Time,Result,Units", "Jul 29, 2026,8:15 PM,112,mg/dL")
        assertEquals(listOf(112.0), r.rows.map { it.mgDl })
        assertEquals(20, hour(r.rows[0].timestampMs))
    }

    @Test fun timeZoneColumnIsNotTheTimestamp() {
        val r = parse("Time Zone,Device Timestamp,Glucose Value,Glucose Units", "America/New_York,07/29/2026 8:15 PM,112,mg/dL", "EDT,07/29/2026 8:45 PM,104,mg/dL")
        assertEquals(listOf(112.0, 104.0), r.rows.map { it.mgDl })
        assertEquals(20, hour(r.rows[0].timestampMs))
    }

    @Test fun timezoneIsNotReadingTime() {
        val r = parse("Timezone,Reading Date,Reading Time,Result,Units", "-04:00,07/29/2026,8:15 PM,112,mg/dL")
        assertEquals(listOf(112.0), r.rows.map { it.mgDl })
        assertEquals(20, hour(r.rows[0].timestampMs))
    }

    @Test fun photographedItemTypeExport() {
        val r = parse(
            "Item Type,Date and Time,Value,Recommended Range,Unit,Manual,Additional Notes,Data Source",
            "Blood Glucose,\"Aug 1, 2026, 9:04 PM\",90,,mg/dL,No,Before Meal,",
            "Blood Glucose,\"Aug 1, 2026 at 8:41 PM\",89,,mg/dL,No,Before Meal,",
            "Blood Glucose,\"Jul 29, 2026, 6:46 PM\",158,,mg/dL,No,Before Meal,"
        )
        assertEquals(listOf(90.0, 89.0, 158.0), r.rows.map { it.mgDl })
        assertEquals(21, hour(r.rows[0].timestampMs))
    }
}
