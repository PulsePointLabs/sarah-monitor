package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.ble.PmdHostClock
import com.pulsepointlabs.sarahmonitor.ble.PmdMeasurement
import com.pulsepointlabs.sarahmonitor.ble.PmdParserState
import com.pulsepointlabs.sarahmonitor.ble.PolarPmdControl
import com.pulsepointlabs.sarahmonitor.ble.PolarPmdParser
import com.pulsepointlabs.sarahmonitor.hrv.MovementAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarPmdParserTest {
    @Test
    fun ecgParsesSigned24MultipleSamples() {
        val packet = pmdPacket(PmdMeasurement.Ecg.id, timestampNs = 1_000_000_000UL, frameType = 0, payload = bytes24(589) + bytes24(-1200))
        val samples = PolarPmdParser.parseEcg(packet, PmdParserState(sampleRateHz = 130))

        assertEquals(2, samples.size)
        assertEquals(589, samples[0].microvolts)
        assertEquals(-1200, samples[1].microvolts)
        assertTrue(samples[1].timestampMs >= samples[0].timestampMs)
    }

    @Test
    fun resettingClockPreventsReconnectionGapFromBeingSpreadAcrossFreshSamples() {
        val state = PmdParserState(sampleRateHz = 130)
        PolarPmdParser.parseEcg(
            pmdPacket(PmdMeasurement.Ecg.id, 1_000_000_000UL, 0, bytes24(100) + bytes24(110)),
            state
        )

        state.resetClock()
        val fresh = PolarPmdParser.parseEcg(
            pmdPacket(PmdMeasurement.Ecg.id, 31_000_000_000UL, 0, bytes24(120) + bytes24(130)),
            state
        )

        assertTrue(fresh[1].timestampMs - fresh[0].timestampMs in 1..20)
    }

    @Test
    fun consecutiveEcgFramesStayEvenlySpaced() {
        val state = PmdParserState(sampleRateHz = 130)
        val first = PolarPmdParser.parseEcg(ecgFrame(frameEndNs(0)), state)
        val second = PolarPmdParser.parseEcg(ecgFrame(frameEndNs(1)), state)

        val spacing = (first + second).zipWithNext { a, b -> b.timestampMs - a.timestampMs }
        assertTrue(spacing.all { it in 7L..8L })
    }

    @Test
    fun lostEcgFrameLeavesAGapInsteadOfStretchingTheNextFrame() {
        val state = PmdParserState(sampleRateHz = 130)
        val first = PolarPmdParser.parseEcg(ecgFrame(frameEndNs(0)), state)
        val afterLoss = PolarPmdParser.parseEcg(ecgFrame(frameEndNs(2)), state)

        val spacing = afterLoss.zipWithNext { a, b -> b.timestampMs - a.timestampMs }
        assertTrue(spacing.all { it in 7L..8L })
        val gapMs = afterLoss.first().timestampMs - first.last().timestampMs
        assertTrue("gap was $gapMs ms", gapMs in 550L..580L)
    }

    @Test
    fun duplicateEcgNotificationIsDroppedInsteadOfDrawnBackwards() {
        val state = PmdParserState(sampleRateHz = 130)
        val first = PolarPmdParser.parseEcg(ecgFrame(frameEndNs(1)), state)
        val copy = PolarPmdParser.parseEcg(ecgFrame(frameEndNs(1)), state)
        val next = PolarPmdParser.parseEcg(ecgFrame(frameEndNs(2)), state)

        assertTrue(first.isNotEmpty())
        assertTrue(copy.isEmpty())
        assertTrue(next.first().timestampMs > first.last().timestampMs)
        val all = first + next
        assertTrue(all.zipWithNext().all { (a, b) -> b.timestampMs >= a.timestampMs })
    }

    @Test
    fun fourByteNegativeSettingIsNotReadAsMinusOne() {
        val factor = (-0.5f).toRawBits()
        val bytes = byteArrayOf(5, 1) + ByteArray(4) { ((factor shr (it * 8)) and 0xFF).toByte() }
        val parsed = com.pulsepointlabs.sarahmonitor.ble.PmdSettings.parse(bytes)
        assertEquals(setOf(factor), parsed.available[com.pulsepointlabs.sarahmonitor.ble.PmdSettingType.Factor])
    }

    @Test
    fun slightlyOlderEcgFrameIsDropped() {
        val state = PmdParserState(sampleRateHz = 130)
        PolarPmdParser.parseEcg(ecgFrame(frameEndNs(3)), state)
        assertTrue(PolarPmdParser.parseEcg(ecgFrame(frameEndNs(2)), state).isEmpty())
    }

    @Test
    fun strapClockRestartIsStillAccepted() {
        val state = PmdParserState(sampleRateHz = 130)
        PolarPmdParser.parseEcg(pmdPacket(PmdMeasurement.Ecg.id, 60_000_000_000UL, 0, bytes24(100) + bytes24(110)), state)
        val restarted = PolarPmdParser.parseEcg(pmdPacket(PmdMeasurement.Ecg.id, 1_000_000_000UL, 0, bytes24(120) + bytes24(130)), state)
        assertEquals(2, restarted.size)
    }

    @Test
    fun duplicateAccelerometerNotificationIsDropped() {
        val state = PmdParserState(sampleRateHz = 50)
        val packet = pmdPacket(PmdMeasurement.Accelerometer.id, 2_000_000_000UL, 1, ByteArray(12))
        assertEquals(2, PolarPmdParser.parseAccelerometer(packet, state).size)
        assertTrue(PolarPmdParser.parseAccelerometer(packet, state).isEmpty())
    }

    @Test
    fun accelerometerParsesSigned16MultipleSamples() {
        val payload = bytes16(1000) + bytes16(-25) + bytes16(42) + bytes16(-1000) + bytes16(500) + bytes16(-500)
        val packet = pmdPacket(PmdMeasurement.Accelerometer.id, timestampNs = 2_000_000_000UL, frameType = 1, payload = payload)
        val samples = PolarPmdParser.parseAccelerometer(packet, PmdParserState(sampleRateHz = 25))

        assertEquals(2, samples.size)
        assertEquals(1000, samples[0].xMilliG)
        assertEquals(-25, samples[0].yMilliG)
        assertEquals(42, samples[0].zMilliG)
        assertEquals(-1000, samples[1].xMilliG)
        assertEquals(500, samples[1].yMilliG)
        assertEquals(-500, samples[1].zMilliG)
    }

    @Test
    fun restingH10AccelerometerReadsAsOneGAndLowMotion() {
        // Polar H10 sends milli-g counts and no Factor setting, so a strap at rest reports ~1000 on the vertical axis.
        val payload = bytes16(8) + bytes16(-12) + bytes16(1003)
        val packet = pmdPacket(PmdMeasurement.Accelerometer.id, timestampNs = 2_000_000_000UL, frameType = 1, payload = payload)
        val sample = PolarPmdParser.parseAccelerometer(packet, PmdParserState(sampleRateHz = 25)).single()

        assertEquals(1003, sample.zMilliG)
        assertEquals("low_motion", MovementAnalyzer.classify(sample.xMilliG, sample.yMilliG, sample.zMilliG).movementClass)
    }

    @Test
    fun accelerometerAppliesFactorSettingWhenPresent() {
        val payload = bytes16(2000) + bytes16(0) + bytes16(-500)
        val packet = pmdPacket(PmdMeasurement.Accelerometer.id, timestampNs = 2_000_000_000UL, frameType = 1, payload = payload)
        val sample = PolarPmdParser.parseAccelerometer(packet, PmdParserState(sampleRateHz = 25, accelerometerFactor = 0.5f)).single()

        assertEquals(1000, sample.xMilliG)
        assertEquals(-250, sample.zMilliG)
    }

    @Test
    fun ecgAndAccelerometerShareOneClockSoMotionLinesUpWithBeats() {
        val clock = PmdHostClock()
        val ecgState = PmdParserState(sampleRateHz = 130, clock = clock)
        val accState = PmdParserState(sampleRateHz = 25, clock = clock)
        val ecg = PolarPmdParser.parseEcg(pmdPacket(PmdMeasurement.Ecg.id, 10_000_000_000UL, 0, bytes24(100)), ecgState)
        Thread.sleep(30)
        val acc = PolarPmdParser.parseAccelerometer(
            pmdPacket(PmdMeasurement.Accelerometer.id, 12_000_000_000UL, 1, bytes16(0) + bytes16(0) + bytes16(1000)),
            accState
        )

        assertEquals(2_000L, acc.single().timestampMs - ecg.single().timestampMs)
    }

    @Test
    fun accelerometerRejectsTruncatedPayload() {
        val packet = pmdPacket(PmdMeasurement.Accelerometer.id, timestampNs = 1_000UL, frameType = 1, payload = byteArrayOf(1, 2, 3, 4))
        assertFails { PolarPmdParser.parseAccelerometer(packet, PmdParserState(sampleRateHz = 25)) }
    }

    @Test
    fun unsupportedFrameTypeRejected() {
        val packet = pmdPacket(PmdMeasurement.Accelerometer.id, timestampNs = 1_000UL, frameType = 9, payload = bytes16(1) + bytes16(2) + bytes16(3))
        assertFails { PolarPmdParser.parseAccelerometer(packet, PmdParserState(sampleRateHz = 25)) }
    }

    @Test
    fun compressedFrameRejectedExplicitly() {
        val packet = pmdPacket(PmdMeasurement.Accelerometer.id, timestampNs = 1_000UL, frameType = 0x81, payload = byteArrayOf(0))
        assertFails { PolarPmdParser.parseAccelerometer(packet, PmdParserState(sampleRateHz = 25)) }
    }

    @Test
    fun truncatedFrameRejected() {
        assertFails { PolarPmdParser.parseFrame(byteArrayOf(0, 1, 2)) }
    }

    @Test
    fun accStartCommandSerializesSelectedSettings() {
        val command = PolarPmdControl.startMeasurement(
            PmdMeasurement.Accelerometer,
            linkedMapOf(
                com.pulsepointlabs.sarahmonitor.ble.PmdSettingType.SampleRate to 25,
                com.pulsepointlabs.sarahmonitor.ble.PmdSettingType.Resolution to 16,
                com.pulsepointlabs.sarahmonitor.ble.PmdSettingType.Range to 2
            )
        )

        assertEquals(0x02, command[0].toInt())
        assertEquals(PmdMeasurement.Accelerometer.id, command[1].toInt())
        assertTrue(command.toList().contains(25.toByte()))
        assertTrue(command.toList().contains(16.toByte()))
        assertTrue(command.toList().contains(2.toByte()))
    }

    private fun frameEndNs(frameIndex: Int): ULong =
        (1_000_000_000_000L + ((frameIndex + 1) * ECG_FRAME_SAMPLES - 1) * 1_000_000_000L / 130L).toULong()

    private fun ecgFrame(timestampNs: ULong): ByteArray =
        pmdPacket(PmdMeasurement.Ecg.id, timestampNs, 0, ByteArray(ECG_FRAME_SAMPLES * 3))

    private fun pmdPacket(type: Int, timestampNs: ULong, frameType: Int, payload: ByteArray): ByteArray =
        byteArrayOf(type.toByte()) + littleU64(timestampNs) + byteArrayOf(frameType.toByte()) + payload

    private fun littleU64(value: ULong): ByteArray =
        ByteArray(8) { index -> ((value shr (index * 8)) and 0xFFu).toByte() }

    private fun bytes16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun bytes24(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(), ((value shr 16) and 0xFF).toByte())

    private fun assertFails(block: () -> Unit) {
        var failed = false
        runCatching(block).onFailure { failed = true }
        assertTrue("Expected parser failure", failed)
    }

    companion object {
        private const val ECG_FRAME_SAMPLES = 73
    }
}
