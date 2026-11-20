package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.ble.HeartRatePacketParser
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRatePacketParserTest {
    @Test
    fun rrIntervalsAreRoundedToTheNearestMillisecond() {
        // 1000/1024 s is 976.56 ms; truncating gave 976 and biased every interval low.
        val packet = HeartRatePacketParser.parse(byteArrayOf(0x10, 60, 0xE8.toByte(), 0x03))
        assertEquals(listOf(977), packet.rrIntervalsMs)
    }

    @Test
    fun parsesEightBitHrWithoutRr() {
        val packet = HeartRatePacketParser.parse(byteArrayOf(0x00, 72))
        assertEquals(72, packet.heartRateBpm)
        assertEquals(emptyList<Int>(), packet.rrIntervalsMs)
    }

    @Test
    fun parsesMultipleRrIntervals() {
        val packet = HeartRatePacketParser.parse(
            byteArrayOf(
                0x10,
                80,
                0x00, 0x04,
                0x20, 0x03
            )
        )
        assertEquals(80, packet.heartRateBpm)
        assertEquals(listOf(1000, 781), packet.rrIntervalsMs)
    }
}
