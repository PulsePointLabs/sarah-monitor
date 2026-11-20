package com.pulsepointlabs.sarahmonitor.ble

import kotlin.math.roundToInt

data class HeartRatePacket(
    val heartRateBpm: Int,
    val rrIntervalsMs: List<Int>,
    val contactDetected: Boolean?
)

object HeartRatePacketParser {
    fun parse(bytes: ByteArray): HeartRatePacket {
        require(bytes.isNotEmpty()) { "Empty Heart Rate Measurement packet" }
        val flags = bytes[0].toInt() and 0xFF
        val hr16Bit = flags and 0x01 != 0
        val contactSupported = flags and 0x04 != 0
        val contactDetected = if (contactSupported) flags and 0x02 != 0 else null
        val energyPresent = flags and 0x08 != 0
        val rrPresent = flags and 0x10 != 0
        var index = 1
        val hr = if (hr16Bit) {
            require(bytes.size >= index + 2) { "Malformed 16-bit HR packet" }
            littleUInt16(bytes, index).also { index += 2 }
        } else {
            require(bytes.size >= index + 1) { "Malformed 8-bit HR packet" }
            (bytes[index++].toInt() and 0xFF)
        }
        if (energyPresent) {
            require(bytes.size >= index + 2) { "Malformed energy field" }
            index += 2
        }
        val rr = mutableListOf<Int>()
        if (rrPresent) {
            while (index + 1 < bytes.size) {
                val rr1024 = littleUInt16(bytes, index)
                rr += ((rr1024.toDouble() / 1024.0) * 1000.0).roundToInt()
                index += 2
            }
        }
        return HeartRatePacket(hr, rr, contactDetected)
    }

    private fun littleUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

object PolarEcgPacketParser {
    fun parse(bytes: ByteArray, receivedAtMs: Long = System.currentTimeMillis()): List<EcgSample> {
        if (bytes.size < 13) return emptyList()
        val type = bytes[0].toInt() and 0xFF
        if (type != 0x00) return emptyList()
        val payloadOffset = 10
        val sampleCount = (bytes.size - payloadOffset) / 3
        if (sampleCount <= 0) return emptyList()
        val intervalMs = 1000.0 / 130.0
        val firstTs = receivedAtMs - ((sampleCount - 1) * intervalMs).toLong()
        return (0 until sampleCount).map { index ->
            val offset = payloadOffset + index * 3
            val raw = signed24(bytes, offset)
            EcgSample(timestampMs = firstTs + (index * intervalMs).toLong(), microvolts = raw)
        }
    }

    private fun signed24(bytes: ByteArray, offset: Int): Int {
        var value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
        if (value and 0x800000 != 0) value = value or -0x1000000
        return value
    }
}
