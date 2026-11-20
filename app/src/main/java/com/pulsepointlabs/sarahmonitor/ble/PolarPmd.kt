package com.pulsepointlabs.sarahmonitor.ble

import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong

enum class PmdMeasurement(val id: Int) {
    Ecg(0),
    Accelerometer(2);
}

enum class PmdSettingType(val id: Int, val byteSize: Int) {
    SampleRate(0, 2),
    Resolution(1, 2),
    Range(2, 2),
    RangeMilliUnit(3, 4),
    Channels(4, 1),
    Factor(5, 4)
}

data class PmdSettings(
    val available: Map<PmdSettingType, Set<Int>>
) {
    fun select(preferredSampleRate: Int, preferredRange: Int? = null): Map<PmdSettingType, Int> {
        val selected = linkedMapOf<PmdSettingType, Int>()
        available[PmdSettingType.SampleRate]?.let { rates ->
            selected[PmdSettingType.SampleRate] = rates.minByOrNull { kotlin.math.abs(it - preferredSampleRate) } ?: preferredSampleRate
        }
        available[PmdSettingType.Resolution]?.let { resolutions ->
            selected[PmdSettingType.Resolution] = resolutions.minOrNull() ?: return@let
        }
        available[PmdSettingType.Range]?.let { ranges ->
            selected[PmdSettingType.Range] = if (preferredRange != null && preferredRange in ranges) preferredRange else ranges.minOrNull() ?: return@let
        }
        available[PmdSettingType.Channels]?.let { channels ->
            selected[PmdSettingType.Channels] = channels.maxOrNull() ?: return@let
        }
        return selected
    }

    companion object {
        fun parse(bytes: ByteArray): PmdSettings {
            val settings = linkedMapOf<PmdSettingType, MutableSet<Int>>()
            var index = 0
            while (index < bytes.size) {
                require(index + 2 <= bytes.size) { "Truncated PMD setting header" }
                val typeId = bytes[index++].u8()
                val count = bytes[index++].u8()
                val type = PmdSettingType.entries.firstOrNull { it.id == typeId }
                    ?: error("Unknown PMD setting type $typeId")
                repeat(count) {
                    require(index + type.byteSize <= bytes.size) { "Truncated PMD setting value" }
                    settings.getOrPut(type) { linkedSetOf() } += littleSigned(bytes, index, type.byteSize)
                    index += type.byteSize
                }
            }
            return PmdSettings(settings)
        }
    }
}

data class PmdControlResponse(
    val command: Int,
    val measurement: Int,
    val status: Int,
    val more: Boolean,
    val parameters: ByteArray
) {
    val success: Boolean get() = status == 0

    companion object {
        fun parse(bytes: ByteArray): PmdControlResponse {
            require(bytes.size >= 4) { "Truncated PMD control response" }
            require(bytes[0] == 0xF0.toByte()) { "Not a PMD control response" }
            val status = bytes[3].u8()
            val more = status == 0 && bytes.size > 4 && bytes[4] != 0.toByte()
            val params = if (status == 0 && bytes.size > 5) bytes.copyOfRange(5, bytes.size) else byteArrayOf()
            return PmdControlResponse(bytes[1].u8(), bytes[2].u8(), status, more, params)
        }
    }
}

object PolarPmdControl {
    private const val GET_MEASUREMENT_SETTINGS = 0x01
    private const val REQUEST_MEASUREMENT_START = 0x02

    fun querySettings(measurement: PmdMeasurement): ByteArray =
        byteArrayOf(GET_MEASUREMENT_SETTINGS.toByte(), measurement.id.toByte())

    fun startMeasurement(measurement: PmdMeasurement, selected: Map<PmdSettingType, Int>): ByteArray {
        val settings = ByteArrayOutputStream()
        selected.forEach { (type, value) ->
            if (type == PmdSettingType.Factor || type == PmdSettingType.RangeMilliUnit) return@forEach
            settings.write(type.id)
            settings.write(1)
            repeat(type.byteSize) { shift -> settings.write((value shr (shift * 8)) and 0xFF) }
        }
        return byteArrayOf(REQUEST_MEASUREMENT_START.toByte(), measurement.id.toByte()) + settings.toByteArray()
    }
}

data class PmdParsedFrame(
    val measurement: PmdMeasurement,
    val deviceTimestampNs: ULong,
    val frameType: Int,
    val compressed: Boolean,
    val content: ByteArray
)

/**
 * Maps the strap's clock to the phone's clock. ECG and accelerometer frames share one strap clock, so
 * their parser states share one of these to keep motion lined up with the beats it may veto.
 */
class PmdHostClock {
    private var hostBaseMs: Long? = null
    private var deviceBaseNs: ULong? = null

    @Synchronized
    fun reset() {
        hostBaseMs = null
        deviceBaseNs = null
    }

    @Synchronized
    fun hostTimestampFor(deviceTimestampNs: ULong): Long {
        val hostBase = hostBaseMs
        val deviceBase = deviceBaseNs
        if (hostBase == null || deviceBase == null || deviceTimestampNs + MAX_BACKWARD_NS < deviceBase) {
            hostBaseMs = System.currentTimeMillis()
            deviceBaseNs = deviceTimestampNs
            return hostBaseMs!!
        }
        return hostBase + (deviceTimestampNs.toLong() - deviceBase.toLong()) / 1_000_000L
    }

    private companion object {
        // A sample this far before the anchor means the strap clock restarted, not a slightly earlier stream.
        const val MAX_BACKWARD_NS = 10_000_000_000UL
    }
}

data class PmdParserState(
    var sampleRateHz: Int,
    /** Scale from raw counts to milli-g, from the PMD Factor setting. Polar H10 omits it: counts are already milli-g. */
    var accelerometerFactor: Float? = null,
    var previousDeviceTimestampNs: ULong = 0UL,
    val clock: PmdHostClock = PmdHostClock()
) {
    fun resetClock() {
        previousDeviceTimestampNs = 0UL
        clock.reset()
    }

    fun hostTimestampFor(deviceTimestampNs: ULong): Long = clock.hostTimestampFor(deviceTimestampNs)
}

object PolarPmdParser {
    fun parseFrame(bytes: ByteArray): PmdParsedFrame {
        require(bytes.size >= 10) { "Truncated PMD frame" }
        val measurementId = bytes[0].u8() and 0x3F
        val measurement = PmdMeasurement.entries.firstOrNull { it.id == measurementId }
            ?: error("Unsupported PMD measurement $measurementId")
        val timestamp = littleULong(bytes, 1, 8)
        val frameByte = bytes[9].u8()
        return PmdParsedFrame(
            measurement = measurement,
            deviceTimestampNs = timestamp,
            frameType = frameByte and 0x7F,
            compressed = frameByte and 0x80 != 0,
            content = bytes.copyOfRange(10, bytes.size)
        )
    }

    fun parseEcg(bytes: ByteArray, state: PmdParserState): List<EcgSample> {
        val frame = parseFrame(bytes)
        require(frame.measurement == PmdMeasurement.Ecg) { "Not an ECG frame" }
        require(!frame.compressed) { "Compressed ECG frames are not supported" }
        require(frame.frameType == 0) { "Unsupported ECG frame type ${frame.frameType}" }
        require(frame.content.isNotEmpty() && frame.content.size % 3 == 0) { "Malformed ECG payload" }
        if (isRepeatedFrame(state, frame.deviceTimestampNs)) return emptyList()
        val count = frame.content.size / 3
        val timestamps = reconstructDeviceTimestamps(state, frame.deviceTimestampNs, count)
        state.previousDeviceTimestampNs = frame.deviceTimestampNs
        return (0 until count).map { index ->
            val offset = index * 3
            EcgSample(
                timestampMs = state.hostTimestampFor(timestamps[index]),
                microvolts = littleSigned(frame.content, offset, 3)
            )
        }
    }

    fun parseAccelerometer(bytes: ByteArray, state: PmdParserState): List<AccelerometerSample> {
        val frame = parseFrame(bytes)
        require(frame.measurement == PmdMeasurement.Accelerometer) { "Not an accelerometer frame" }
        require(!frame.compressed) { "Compressed ACC frames are not supported" }
        val bytesPerAxis = when (frame.frameType) {
            0 -> 1
            1 -> 2
            2 -> 3
            else -> error("Unsupported ACC frame type ${frame.frameType}")
        }
        val sampleSize = bytesPerAxis * 3
        require(frame.content.isNotEmpty() && frame.content.size % sampleSize == 0) { "Malformed ACC payload" }
        if (isRepeatedFrame(state, frame.deviceTimestampNs)) return emptyList()
        val count = frame.content.size / sampleSize
        val timestamps = reconstructDeviceTimestamps(state, frame.deviceTimestampNs, count)
        state.previousDeviceTimestampNs = frame.deviceTimestampNs
        val factor = state.accelerometerFactor ?: 1f
        return (0 until count).map { index ->
            val offset = index * sampleSize
            AccelerometerSample(
                timestampMs = state.hostTimestampFor(timestamps[index]),
                xMilliG = accelerometerCountsToMilliG(littleSigned(frame.content, offset, bytesPerAxis), factor),
                yMilliG = accelerometerCountsToMilliG(littleSigned(frame.content, offset + bytesPerAxis, bytesPerAxis), factor),
                zMilliG = accelerometerCountsToMilliG(littleSigned(frame.content, offset + bytesPerAxis * 2, bytesPerAxis), factor)
            )
        }
    }

    /**
     * A frame stamped at or slightly before the last one is a copy, not new data: some phones deliver each
     * notification twice. Placing a copy's samples again sends the waveform back in time and doubles every
     * beat, so it is dropped. A jump back of more than a few seconds is the strap clock restarting.
     */
    private fun isRepeatedFrame(state: PmdParserState, frameTimestampNs: ULong): Boolean {
        val previous = state.previousDeviceTimestampNs
        if (previous == 0UL || frameTimestampNs > previous) return false
        if (previous - frameTimestampNs < STRAP_CLOCK_RESTART_NS) return true
        state.previousDeviceTimestampNs = 0UL
        return false
    }

    /**
     * Spreads a frame's samples between the previous frame and this one. When the device timestamps
     * jump by much more than a frame's worth of samples, frames were lost in transit: the samples are
     * placed at the nominal rate just before this frame's timestamp so the loss shows up as a gap
     * instead of silently stretching the waveform.
     */
    private fun reconstructDeviceTimestamps(state: PmdParserState, frameTimestampNs: ULong, count: Int): List<ULong> {
        require(count > 0) { "No samples" }
        val nominalDelta = 1_000_000_000.0 / state.sampleRateHz.toDouble()
        val previous = state.previousDeviceTimestampNs
        val anchorToFrameEnd = previous == 0UL || frameTimestampNs <= previous ||
            (frameTimestampNs - previous).toDouble() > nominalDelta * count * LOST_FRAME_TOLERANCE
        val delta = if (anchorToFrameEnd) {
            nominalDelta
        } else {
            ((frameTimestampNs - previous).toDouble() / count.toDouble())
        }
        return (0 until count).map { index ->
            val ns = if (anchorToFrameEnd) {
                frameTimestampNs.toDouble() - delta * (count - 1 - index)
            } else {
                previous.toDouble() + delta * (index + 1)
            }
            ns.roundToLong().coerceAtLeast(0L).toULong()
        }
    }

    private fun accelerometerCountsToMilliG(raw: Int, factor: Float): Int = (raw * factor.toDouble()).roundToLong().toInt()

    private const val LOST_FRAME_TOLERANCE = 1.5
    private const val STRAP_CLOCK_RESTART_NS = 10_000_000_000UL
}

private fun Byte.u8(): Int = toInt() and 0xFF

private fun littleULong(bytes: ByteArray, offset: Int, size: Int): ULong {
    var result = 0UL
    repeat(size) { index ->
        result = result or ((bytes[offset + index].u8().toULong()) shl (index * 8))
    }
    return result
}

private fun littleSigned(bytes: ByteArray, offset: Int, size: Int): Int {
    require(size in 1..4)
    var result = 0
    repeat(size) { index ->
        result = result or (bytes[offset + index].u8() shl (index * 8))
    }
    val signBit = 1 shl (size * 8 - 1)
    // A 4-byte value already fills the Int; shifting by 32 would wrap to a shift of 0 and turn it into -1.
    if (size < 4 && result and signBit != 0) result = result or (-1 shl (size * 8))
    return result
}
