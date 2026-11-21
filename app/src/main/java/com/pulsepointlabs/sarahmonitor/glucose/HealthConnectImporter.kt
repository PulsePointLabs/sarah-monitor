package com.pulsepointlabs.sarahmonitor.glucose

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.WeightRecord
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

    /** Optional: SpO2 and body composition (scale apps such as VeSync). Glucose/BP work without them. */
    val optionalPermissions = setOf(
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class)
    )

    /** Lets the background service keep pulling new readings while the app isn't on screen. */
    val requestPermissions: Set<String> = permissions + optionalPermissions + BACKGROUND_READ

    private suspend fun granted(): Set<String> =
        if (!available()) emptySet() else HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    suspend fun readSpO2(days: Long = 30): List<com.pulsepointlabs.sarahmonitor.vitals.SpO2Entry> {
        if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) !in granted()) return emptyList()
        val end = Instant.now()
        return HealthConnectClient.getOrCreate(context).readRecords(
            ReadRecordsRequest(OxygenSaturationRecord::class, TimeRangeFilter.between(end.minusSeconds(days * 86_400L), end), pageSize = 5000)
        ).records.map {
            com.pulsepointlabs.sarahmonitor.vitals.SpO2Entry(it.time.toEpochMilli(), Math.round(it.percentage.value).toInt(), null, null, "Health Connect · ${it.metadata.dataOrigin.packageName}")
        }
    }

    /** Weigh-ins from Health Connect, with whatever composition the scale app wrote at (nearly) the same time. */
    suspend fun readBody(days: Long = 365): List<com.pulsepointlabs.sarahmonitor.vitals.BodyEntry> {
        val g = granted()
        if (HealthPermission.getReadPermission(WeightRecord::class) !in g) return emptyList()
        val client = HealthConnectClient.getOrCreate(context)
        val end = Instant.now()
        val range = TimeRangeFilter.between(end.minusSeconds(days * 86_400L), end)
        suspend fun <T : androidx.health.connect.client.records.Record> read(type: kotlin.reflect.KClass<T>): List<T> =
            if (HealthPermission.getReadPermission(type) in g) runCatching { client.readRecords(ReadRecordsRequest(type, range, pageSize = 2000)).records }.getOrDefault(emptyList()) else emptyList()
        val fat = read(BodyFatRecord::class)
        val lean = read(LeanBodyMassRecord::class)
        val bone = read(BoneMassRecord::class)
        val water = read(BodyWaterMassRecord::class)
        val bmr = read(BasalMetabolicRateRecord::class)
        fun <T> near(list: List<T>, t: Instant, time: (T) -> Instant): T? =
            list.minByOrNull { kotlin.math.abs(time(it).toEpochMilli() - t.toEpochMilli()) }?.takeIf { kotlin.math.abs(time(it).toEpochMilli() - t.toEpochMilli()) <= 120_000L }
        return read(WeightRecord::class).map { w ->
            val kg = w.weight.inKilograms
            val waterKg = near(water, w.time) { it.time }?.mass?.inKilograms
            com.pulsepointlabs.sarahmonitor.vitals.BodyEntry(
                timestampMs = w.time.toEpochMilli(),
                weightKg = Math.round(kg * 100) / 100.0,
                bodyFatPct = near(fat, w.time) { it.time }?.percentage?.value,
                leanMassKg = near(lean, w.time) { it.time }?.mass?.inKilograms,
                boneMassKg = near(bone, w.time) { it.time }?.mass?.inKilograms,
                bodyWaterPct = waterKg?.let { Math.round(it / kg * 1000) / 10.0 },
                bmrKcal = near(bmr, w.time) { it.time }?.basalMetabolicRate?.inKilocaloriesPerDay?.toInt(),
                source = "Health Connect · ${w.metadata.dataOrigin.packageName}"
            )
        }
    }

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
                source = "Health Connect · ${it.metadata.dataOrigin.packageName}",
                site = when (it.measurementLocation) {
                    BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM, BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM -> "arm"
                    BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_WRIST, BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST -> "wrist"
                    else -> null
                },
                posture = when (it.bodyPosition) {
                    BloodPressureRecord.BODY_POSITION_SITTING_DOWN -> "sitting"
                    BloodPressureRecord.BODY_POSITION_STANDING_UP -> "standing"
                    BloodPressureRecord.BODY_POSITION_LYING_DOWN -> "supine"
                    BloodPressureRecord.BODY_POSITION_RECLINING -> "reclined"
                    else -> null
                }
            )
        }
    }

    companion object {
        const val BACKGROUND_READ = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    }
}
