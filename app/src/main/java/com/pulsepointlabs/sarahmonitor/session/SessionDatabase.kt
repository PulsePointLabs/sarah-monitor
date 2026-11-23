package com.pulsepointlabs.sarahmonitor.session

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One recording. Summary fields are filled in when it ends (and refreshed when it's edited). */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val notes: String = "",
    val averageHr: Double? = null,
    val minHr: Int? = null,
    val maxHr: Int? = null,
    val rmssdMs: Double? = null,
    val sdnnMs: Double? = null,
    val pnn50Percent: Double? = null,
    val hrvQuality: String? = null,
    val pelvicMaxKpa: Double? = null,
    val pelvicAverageKpa: Double? = null,
    val pelvicPulseCount: Int? = null,
    val pelvicLongestHoldS: Double? = null,
    val markerCount: Int = 0,
    /** Up to 48 comma-separated HR values for the list sparkline. */
    val hrSparkline: String = "",
    /** [SessionCategory] name; null for sessions recorded before categories existed. */
    val category: String? = null,
    /** JSON [SessionDetails]: check-ins, devices used, wrap-up and the linked video. */
    val details: String? = null
) {
    val durationMs: Long get() = (endTimeMs ?: System.currentTimeMillis()) - startTimeMs
    val active: Boolean get() = endTimeMs == null
}

/** One H10 heart-rate packet (about 1/s) with the rolling HRV and Load at that moment. */
@Entity(tableName = "hr_samples", indices = [Index("sessionId", "timestampMs")])
data class HrSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestampMs: Long,
    val bpm: Int,
    val rmssdMs: Double?,
    val sdnnMs: Double?,
    val pnn50Percent: Double?,
    val load: Int?,
    val contact: Boolean?,
    /** Estimated stress 0–100 (see StressEstimator); null before v4 or when unavailable. */
    val stress: Int? = null,
    /** Breathing rate from the RR intervals (breaths/min). */
    val breathsPerMin: Double? = null
)

@Entity(tableName = "rr_samples", indices = [Index("sessionId", "timestampMs")])
data class RrEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestampMs: Long,
    val rawMs: Int,
    val cleanMs: Int?,
    val accepted: Boolean,
    val artifact: Boolean,
    val rejectionReason: String?
)

/** CIVET pressure (about 10/s). [aboveRestKpa] is null until a rest level exists. */
@Entity(tableName = "pelvic_samples", indices = [Index("sessionId", "timestampMs")])
data class PelvicSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestampMs: Long,
    val pressureKpa: Double,
    val aboveRestKpa: Double?,
    val holding: Boolean
)

/** EMG envelope from the Arduino/MyoWare (25 Hz averages). pct = % of the calibrated hold. */
@Entity(tableName = "emg_samples", indices = [Index("sessionId", "timestampMs")])
data class EmgSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestampMs: Long,
    val ch1: Double,
    val ch2: Double?,
    val pct1: Double?,
    val pct2: Double?
)

/** A block of H10 ECG: [timestamps] as int32 offsets from [startMs], [microvolts] as int32, little-endian. */
@Entity(tableName = "ecg_chunks", indices = [Index("sessionId", "startMs")])
data class EcgChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    val sampleCount: Int,
    val data: ByteArray
)

@Entity(tableName = "markers", indices = [Index("sessionId")])
data class MarkerEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val timestampMs: Long,
    /** Comma-separated types, e.g. "event,symptom". */
    val types: String,
    val label: String,
    val note: String = "",
    /** Self-reported check-in: how you feel, 1 (awful) – 5 (great). */
    val wellbeing: Int? = null,
    /** Self-reported stress, 0 (none) – 10 (extreme). */
    val selfStress: Int? = null,
    /** JSON: everything live at the moment it was marked (see EventSnapshot). Null for events added in review. */
    val snapshot: String? = null
)

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSession(session: SessionEntity)
    @Query("SELECT * FROM sessions ORDER BY startTimeMs DESC") fun observeSessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id = :id") fun observeSession(id: String): Flow<SessionEntity?>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun getSession(id: String): SessionEntity?
    // Targeted updates, so editing one thing never writes back a stale copy of the rest of the row.
    @Query("UPDATE sessions SET details = :details WHERE id = :id") suspend fun setDetails(id: String, details: String?)
    @Query("UPDATE sessions SET notes = :notes WHERE id = :id") suspend fun setNotes(id: String, notes: String)
    @Query("UPDATE sessions SET title = :title WHERE id = :id") suspend fun setTitle(id: String, title: String)
    @Query("UPDATE sessions SET markerCount = :count WHERE id = :id") suspend fun setMarkerCount(id: String, count: Int)
    @Query("SELECT * FROM sessions WHERE endTimeMs IS NULL ORDER BY startTimeMs DESC LIMIT 1") suspend fun getActiveSession(): SessionEntity?
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: String)

    @Insert suspend fun insertHr(rows: List<HrSampleEntity>)
    @Insert suspend fun insertRr(rows: List<RrEntity>)
    @Insert suspend fun insertPelvic(rows: List<PelvicSampleEntity>)
    @Insert suspend fun insertEcg(rows: List<EcgChunkEntity>)
    @Insert suspend fun insertEmg(rows: List<EmgSampleEntity>)
    @Query("SELECT * FROM emg_samples WHERE sessionId = :id ORDER BY timestampMs") suspend fun getEmg(id: String): List<EmgSampleEntity>
    @Query("DELETE FROM emg_samples WHERE sessionId = :id") suspend fun deleteEmg(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMarker(marker: MarkerEntity)
    @Query("DELETE FROM markers WHERE id = :id") suspend fun deleteMarker(id: String)

    @Query("SELECT * FROM hr_samples WHERE sessionId = :id ORDER BY timestampMs") suspend fun getHr(id: String): List<HrSampleEntity>
    @Query("SELECT * FROM rr_samples WHERE sessionId = :id ORDER BY timestampMs, id") suspend fun getRr(id: String): List<RrEntity>
    @Query("SELECT * FROM pelvic_samples WHERE sessionId = :id ORDER BY timestampMs") suspend fun getPelvic(id: String): List<PelvicSampleEntity>
    @Query("SELECT * FROM markers WHERE sessionId = :id ORDER BY timestampMs") suspend fun getMarkers(id: String): List<MarkerEntity>
    @Query("SELECT * FROM markers WHERE sessionId = :id ORDER BY timestampMs") fun observeMarkers(id: String): Flow<List<MarkerEntity>>
    @Query("SELECT * FROM ecg_chunks WHERE sessionId = :id AND endMs >= :fromMs AND startMs <= :toMs ORDER BY startMs")
    suspend fun getEcg(id: String, fromMs: Long, toMs: Long): List<EcgChunkEntity>
    @Query("SELECT COUNT(*) FROM ecg_chunks WHERE sessionId = :id") suspend fun ecgChunkCount(id: String): Int

    @Query("DELETE FROM hr_samples WHERE sessionId = :id") suspend fun deleteHr(id: String)
    @Query("DELETE FROM rr_samples WHERE sessionId = :id") suspend fun deleteRr(id: String)
    @Query("DELETE FROM pelvic_samples WHERE sessionId = :id") suspend fun deletePelvic(id: String)
    @Query("DELETE FROM ecg_chunks WHERE sessionId = :id") suspend fun deleteEcg(id: String)
    @Query("DELETE FROM markers WHERE sessionId = :id") suspend fun deleteMarkers(id: String)

    suspend fun deleteEverything(id: String) {
        deleteHr(id); deleteRr(id); deletePelvic(id); deleteEcg(id); deleteEmg(id); deleteMarkers(id); deleteSession(id)
    }
}

@Database(
    entities = [SessionEntity::class, HrSampleEntity::class, RrEntity::class, PelvicSampleEntity::class, EcgChunkEntity::class, MarkerEntity::class, EmgSampleEntity::class],
    version = 6,
    exportSchema = false
)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun dao(): SessionDao

    companion object {
        @Volatile private var instance: SessionDatabase? = null

        /** 0.20.0: structured session details (check-ins, devices, wrap-up, video link). */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `details` TEXT")
            }
        }

        /** 0.15.0: snapshot of everything live when an event is marked. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `markers` ADD COLUMN `snapshot` TEXT")
            }
        }

        /** 0.14.0: estimated stress + breathing per sample; check-in ratings on events. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `hr_samples` ADD COLUMN `stress` INTEGER")
                db.execSQL("ALTER TABLE `hr_samples` ADD COLUMN `breathsPerMin` REAL")
                db.execSQL("ALTER TABLE `markers` ADD COLUMN `wellbeing` INTEGER")
                db.execSQL("ALTER TABLE `markers` ADD COLUMN `selfStress` INTEGER")
            }
        }

        /** 0.13.0: session category (general / sexual types / hybrid). */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `category` TEXT")
            }
        }

        /** 0.10.0: EMG samples. Existing sessions are kept. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `emg_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `ch1` REAL NOT NULL, `ch2` REAL, `pct1` REAL, `pct2` REAL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_emg_samples_sessionId_timestampMs` ON `emg_samples` (`sessionId`, `timestampMs`)")
            }
        }
        fun get(context: Context): SessionDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SessionDatabase::class.java, "sarah_monitor_sessions.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build().also { instance = it }
        }
    }
}

/** Packs ECG samples into [EcgChunkEntity.data] and back. */
object EcgCodec {
    fun encode(startMs: Long, timestamps: LongArray, microvolts: IntArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(timestamps.size * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in timestamps.indices) {
            buffer.putInt((timestamps[i] - startMs).toInt())
            buffer.putInt(microvolts[i])
        }
        return buffer.array()
    }

    /** Returns (timestamps, microvolts). */
    fun decode(chunk: EcgChunkEntity): Pair<LongArray, IntArray> {
        val buffer = java.nio.ByteBuffer.wrap(chunk.data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val n = chunk.data.size / 8
        val ts = LongArray(n)
        val uv = IntArray(n)
        for (i in 0 until n) {
            ts[i] = chunk.startMs + buffer.getInt()
            uv[i] = buffer.getInt()
        }
        return ts to uv
    }
}
