package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.vitals.Esf551
import com.pulsepointlabs.sarahmonitor.vitals.VitalCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class VitalsTest {
    @Test
    fun parsesEsf551Packet() {
        val b = ByteArray(22)
        b[0] = 0xa5.toByte(); b[1] = 0x02; b[3] = 0x10; b[4] = 0x00
        b[6] = 0x01; b[7] = 0x61; b[8] = 0xa1.toByte(); b[9] = 0x00
        val g = 82691
        b[10] = (g and 0xff).toByte(); b[11] = (g shr 8 and 0xff).toByte(); b[12] = (g shr 16 and 0xff).toByte()
        b[13] = 0xf4.toByte(); b[14] = 0x01; b[19] = 1; b[20] = 1; b[21] = 1
        val m = Esf551.parse(b)!!
        assertEquals(82.69, m.weightKg, 0.0)
        assertEquals(500, m.impedanceOhms)
        b[19] = 0
        assertNull(Esf551.parse(b))
    }

    /** Expected values are Sarah's etekcityEsf551Metrics.js output for the same inputs. */
    @Test
    fun compositionMatchesSarah() {
        val m = Esf551.composition(82.69, 500, 175.0, 44, true, "t", 0)
        assertEquals(27.0, m.bmi!!, 1e-9); assertEquals(22.6, m.bodyFatPct!!, 1e-9); assertEquals(64.0, m.leanMassKg!!, 1e-9)
        assertEquals(19.8, m.subcutaneousFatPct!!, 1e-9); assertEquals(9, m.visceralFat); assertEquals(55.9, m.bodyWaterPct!!, 1e-9)
        assertEquals(1752, m.bmrKcal); assertEquals(50.0, m.skeletalMusclePct!!, 1e-9); assertEquals(60.8, m.muscleMassKg!!, 1e-9)
        assertEquals(3.2, m.boneMassKg!!, 1e-9); assertEquals(17.6, m.proteinPct!!, 1e-9); assertEquals(45, m.metabolicAge)
        val f = Esf551.composition(61.2, 620, 163.0, 37, false, "t", 0)
        assertEquals(23.03, f.bmi!!, 1e-9); assertEquals(25.6, f.bodyFatPct!!, 1e-9); assertEquals(45.53, f.leanMassKg!!, 1e-9)
        assertEquals(23.6, f.subcutaneousFatPct!!, 1e-9); assertEquals(5, f.visceralFat); assertEquals(51.1, f.bodyWaterPct!!, 1e-9)
        assertEquals(1353, f.bmrKcal); assertEquals(43.4, f.skeletalMusclePct!!, 1e-9); assertEquals(42.8, f.muscleMassKg!!, 1e-9)
        assertEquals(2.73, f.boneMassKg!!, 1e-9); assertEquals(17.6, f.proteinPct!!, 1e-9); assertEquals(36, f.metabolicAge)
    }

    @Test
    fun parsesEmaySpO2Csv() {
        val csv = "Date,Time,SpO2(%),PR(bpm),PI(%)\n11/20/2026,23:10:00,97,62,4.1\n11/20/2026,23:10:04,96,61,3.9\n11/20/2026,23:10:08,49,60,3.9\n"
        assertEquals("spo2", VitalCsv.classify(csv, "export.csv"))
        val r = VitalCsv.parseSpO2(csv, "EMAY", ZoneId.of("UTC"))
        assertEquals(2, r.rows.size)
        assertEquals(1, r.skipped)
        assertEquals(97, r.rows[0].spo2); assertEquals(62, r.rows[0].pulse); assertEquals(4.1, r.rows[0].perfusionIndex!!, 1e-9)
        assertEquals("glucose", VitalCsv.classify("Date,Time,Glucose (mg/dL)\n", "onetouch.csv"))
    }
}
