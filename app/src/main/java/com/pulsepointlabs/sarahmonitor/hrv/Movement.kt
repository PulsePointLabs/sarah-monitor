package com.pulsepointlabs.sarahmonitor.hrv

import kotlin.math.abs
import kotlin.math.sqrt

data class MovementSampleMetrics(
    val vectorMagnitudeMilliG: Double,
    val dynamicMagnitudeMilliG: Double,
    val movementClass: String
)

/** Same movement classes as SarahVS (H10 accelerometer, milli-g). */
object MovementAnalyzer {
    const val LOW_MOTION_DYNAMIC_MG = 90.0
    const val MILD_MOTION_DYNAMIC_MG = 180.0
    const val MODERATE_MOTION_DYNAMIC_MG = 360.0

    fun classify(xMilliG: Int, yMilliG: Int, zMilliG: Int): MovementSampleMetrics {
        val vector = sqrt(
            xMilliG.toDouble() * xMilliG.toDouble() +
                yMilliG.toDouble() * yMilliG.toDouble() +
                zMilliG.toDouble() * zMilliG.toDouble()
        )
        val dynamic = abs(vector - 1000.0)
        val klass = when {
            dynamic < LOW_MOTION_DYNAMIC_MG -> "low_motion"
            dynamic < MILD_MOTION_DYNAMIC_MG -> "mild_motion"
            dynamic < MODERATE_MOTION_DYNAMIC_MG -> "moderate_motion"
            else -> "high_motion"
        }
        return MovementSampleMetrics(vector, dynamic, klass)
    }

    fun displayLabel(movementClass: String?): String = when (movementClass) {
        "low_motion" -> "Still"
        "mild_motion" -> "Mild motion"
        "moderate_motion" -> "Active"
        "high_motion" -> "Heavy motion"
        else -> "Unavailable"
    }
}
