package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.ble.CivetClient
import com.pulsepointlabs.sarahmonitor.ble.CivetPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CivetPacketTest {
    @Test
    fun decodesSignedLittleEndianHundredthsOfKpa() {
        // Bytes 8..9 = 0x04D2 = 1234 -> 12.34 kPa (same rule as Sarah's CivetPacket).
        val packet = CivetPacket.fromHex("d0" + "00".repeat(7) + "d204")
        assertEquals(10, packet.size)
        assertEquals(12.34, CivetPacket.pressureKpa(packet)!!, 1e-9)
    }

    @Test
    fun negativePressureIsSigned() {
        // 0xFF9C = -100 -> -1.00 kPa
        assertEquals(-1.0, CivetPacket.pressureKpa(CivetPacket.fromHex("d000000000000000" + "9cff"))!!, 1e-9)
    }

    @Test
    fun rejectsOtherPackets() {
        assertNull(CivetPacket.pressureKpa(CivetPacket.fromHex("a000000000000000d204")))
        assertNull(CivetPacket.pressureKpa(CivetPacket.fromHex("d0000000")))
    }

    @Test
    fun commandsMatchSarah() {
        assertEquals("5001d0" + "00".repeat(14), CivetPacket.hex(CivetClient.START_COMMAND))
        assertEquals("66000000000000000000000002", CivetPacket.hex(CivetClient.ZERO_COMMAND))
    }

    @Test
    fun recognisesSensorNames() {
        assertTrue(CivetClient.isCivetName("47L124-ABC"))
        assertTrue(CivetClient.isCivetName("My CIVET"))
        assertFalse(CivetClient.isCivetName("Polar H10 1234"))
    }

    @Test
    fun matchesDgLabExamples() {
        // DG-LAB protocol doc: bytes 1603 -> 7.9 kPa, A806 -> 17.04 kPa (17-byte D0 message).
        val base = "d001" + "00".repeat(6)
        assertEquals(7.9, CivetPacket.pressureKpa(CivetPacket.fromHex(base + "1603" + "00".repeat(7)))!!, 1e-9)
        assertEquals(17.04, CivetPacket.pressureKpa(CivetPacket.fromHex(base + "a806" + "00".repeat(7)))!!, 1e-9)
    }

    @Test
    fun stopAndRotateCommandsMatchDgLab() {
        assertEquals("500100" + "00".repeat(14), CivetPacket.hex(CivetClient.STOP_COMMAND))
        assertEquals("66" + "00".repeat(9) + "01" + "0000", CivetPacket.hex(CivetClient.rotateCommand(1)))
        assertEquals("66" + "00".repeat(9) + "03" + "0000", CivetPacket.hex(CivetClient.rotateCommand(3)))
        assertTrue(CivetClient.isCivetName("47L124000"))
    }
}
