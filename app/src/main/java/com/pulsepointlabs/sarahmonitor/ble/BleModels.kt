package com.pulsepointlabs.sarahmonitor.ble

data class BleDevice(
    val name: String,
    val address: String
)

enum class ConnectionState {
    NotConfigured,
    Scanning,
    Connecting,
    Connected,
    Reconnecting,
    Stale,
    Disconnected,
    WaitingForCuff,
    Receiving,
    ReadingSaved,
    Error
}

data class HrReading(
    val deviceName: String,
    val deviceAddress: String,
    val timestampMs: Long,
    val heartRateBpm: Int,
    val rrIntervalsMs: List<Int>,
    val contactDetected: Boolean?,
    val connectionState: ConnectionState
)

data class BpReading(
    val deviceName: String,
    val deviceAddress: String,
    val timestampMs: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    val irregular: Boolean?,
    val movementStatus: String?
)

data class EcgSample(
    val timestampMs: Long,
    val microvolts: Int
)

data class AccelerometerSample(
    val timestampMs: Long,
    val xMilliG: Int,
    val yMilliG: Int,
    val zMilliG: Int
)

enum class PmdStreamState {
    Unavailable,
    Configuring,
    Streaming,
    Unsupported,
    Failed
}

data class PmdStreamStatus(
    val ecg: PmdStreamState = PmdStreamState.Unavailable,
    val accelerometer: PmdStreamState = PmdStreamState.Unavailable,
    val ecgMessage: String? = null,
    val accelerometerMessage: String? = null
)
