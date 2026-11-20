package com.pulsepointlabs.sarahmonitor.ble

import java.time.LocalDateTime
import java.time.ZoneId

data class BloodPressurePacket(
    val systolic: Int,
    val diastolic: Int,
    val meanArterialPressure: Int?,
    val pulse: Int?,
    val timestampMs: Long?,
    val bodyMovementDetected: Boolean?,
    val cuffFitProblem: Boolean?,
    val irregularPulseDetected: Boolean?,
    val measurementPositionProblem: Boolean?
)

object BloodPressurePacketParser {
    fun parse(bytes: ByteArray): BloodPressurePacket {
        require(bytes.size >= 7) { "Malformed Blood Pressure Measurement packet" }
        val flags = bytes[0].toInt() and 0xFF
        val kpa = flags and 0x01 != 0
        val timestampPresent = flags and 0x02 != 0
        val pulsePresent = flags and 0x04 != 0
        val statusPresent = flags and 0x08 != 0
        var index = 1
        val systolic = sfloat(bytes, index).also { index += 2 }
        val diastolic = sfloat(bytes, index).also { index += 2 }
        val map = sfloat(bytes, index).also { index += 2 }
        val scale = if (kpa) 7.50062 else 1.0
        var timestamp: Long? = null
        if (timestampPresent) {
            require(bytes.size >= index + 7) { "Malformed BP timestamp" }
            val year = littleUInt16(bytes, index)
            val month = bytes[index + 2].toInt() and 0xFF
            val day = bytes[index + 3].toInt() and 0xFF
            val hour = bytes[index + 4].toInt() and 0xFF
            val minute = bytes[index + 5].toInt() and 0xFF
            val second = bytes[index + 6].toInt() and 0xFF
            index += 7
            if (year > 0 && month in 1..12 && day in 1..31) {
                timestamp = LocalDateTime.of(year, month, day, hour, minute, second)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
        }
        var pulse: Int? = null
        if (pulsePresent) {
            require(bytes.size >= index + 2) { "Malformed BP pulse" }
            pulse = sfloat(bytes, index).toInt()
            index += 2
        }
        var bodyMove: Boolean? = null
        var cuffFit: Boolean? = null
        var irregular: Boolean? = null
        var position: Boolean? = null
        if (statusPresent && bytes.size >= index + 2) {
            val status = littleUInt16(bytes, index)
            bodyMove = status and 0x01 != 0
            cuffFit = status and 0x02 != 0
            irregular = status and 0x04 != 0
            position = status and 0x20 != 0
        }
        return BloodPressurePacket(
            systolic = (systolic * scale).toInt(),
            diastolic = (diastolic * scale).toInt(),
            meanArterialPressure = (map * scale).toInt(),
            pulse = pulse,
            timestampMs = timestamp,
            bodyMovementDetected = bodyMove,
            cuffFitProblem = cuffFit,
            irregularPulseDetected = irregular,
            measurementPositionProblem = position
        )
    }

    private fun littleUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun sfloat(bytes: ByteArray, offset: Int): Double {
        val raw = littleUInt16(bytes, offset)
        var mantissa = raw and 0x0FFF
        var exponent = raw shr 12
        if (mantissa >= 0x0800) mantissa -= 0x1000
        if (exponent >= 0x0008) exponent -= 0x0010
        return mantissa * Math.pow(10.0, exponent.toDouble())
    }
}
