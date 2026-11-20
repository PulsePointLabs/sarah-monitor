package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.ble.BloodPressurePacketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BloodPressurePacketParserTest {
    @Test
    fun parsesStandardBpMeasurementWithPulseAndStatus() {
        val packet = BloodPressurePacketParser.parse(
            byteArrayOf(
                0x0C,
                120, 0,
                80, 0,
                93, 0,
                72, 0,
                0x04, 0x00
            )
        )
        assertEquals(120, packet.systolic)
        assertEquals(80, packet.diastolic)
        assertEquals(72, packet.pulse)
        assertTrue(packet.irregularPulseDetected == true)
    }
}
