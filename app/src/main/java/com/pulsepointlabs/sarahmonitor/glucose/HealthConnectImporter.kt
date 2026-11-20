package com.pulsepointlabs.sarahmonitor.glucose

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pulsepointlabs.sarahmonitor.core.BpEntry
import com.pulsepointlabs.sarahmonitor.core.GlucoseEntry
import java.time.Instant

/** Reads glucose and blood pressure that other apps (meter apps, Omron Connect) wrote to Health Connect. */
class HealthConnectImporter(private val context: Context) {
    val permissions = setOf(
        HealthPermission.getReadPermission(BloodGlucoseRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class)
    )

    fun available(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(): Boolean {
        if (!available()) return false
        return HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(permissions)
    }

    suspend fun readGlucose(days: Long = 90): List<GlucoseEntry> {
        if (!hasPermissions()) return emptyList()
        val end = Instant.now()
        val response = HealthConnectClient.getOrCreate(context).readRecords(
            ReadRecordsRequest(
                recordType = BloodGlucoseRecord::class,
                timeRangeFilter = TimeRangeFilter.between(end.minusSeconds(days * 86_400L), end),
                pageSize = 1000
            )
        )
        return response.records.map {
            GlucoseEntry(
                timestampMs = it.time.toEpochMilli(),
                mgDl = Math.round(it.level.inMilligramsPerDeciliter * 10) / 10.0,
                source = "Health Connect · ${it.metadata.dataOrigin.packageName}"
            )
        }
    }

    suspend fun readBloodPressure(days: Long = 30): List<BpEntry> {
        if (!hasPermissions()) return emptyList()
        val end = Instant.now()
        val response = HealthConnectClient.getOrCreate(context).readRecords(
            ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(end.minusSeconds(days * 86_400L), end),
                pageSize = 1000
            )
        )
        return response.records.map {
            BpEntry(
                timestampMs = it.time.toEpochMilli(),
                systolic = it.systolic.inMillimetersOfMercury.toInt(),
                diastolic = it.diastolic.inMillimetersOfMercury.toInt(),
                pulse = null,
                irregular = null,
                source = "Health Connect · ${it.metadata.dataOrigin.packageName}"
            )
        }
    }
}
