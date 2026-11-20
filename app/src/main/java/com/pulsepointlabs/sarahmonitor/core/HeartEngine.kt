package com.pulsepointlabs.sarahmonitor.core

import com.pulsepointlabs.sarahmonitor.ble.AccelerometerSample
import com.pulsepointlabs.sarahmonitor.ble.HrReading
import com.pulsepointlabs.sarahmonitor.hrv.HrvCalculator
import com.pulsepointlabs.sarahmonitor.hrv.MovementAnalyzer
import com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressEstimator
import com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressReading
import com.pulsepointlabs.sarahmonitor.hrv.RrSampleEntity

data class HeartSnapshot(
    val heartRate: Int? = null,
    val smoothedHr: Double? = null,
    val averageHr: Double? = null,
    val minHr: Int? = null,
    val maxHr: Int? = null,
    val recentHr: List<Int> = emptyList(),
    val rmssdMs: Double? = null,
    val sdnnMs: Double? = null,
    val pnn50Percent: Double? = null,
    val hrvQuality: String = "Unavailable",
    val rrCoveragePercent: Double = 0.0,
    val latestRrMs: Int? = null,
    val load: PhysiologicalStressReading = PhysiologicalStressReading(),
    val contact: Boolean? = null,
    val movement: String? = null,
    val startedAtMs: Long? = null,
    val updatedAtMs: Long? = null
)

/** One trend bucket (5 s). Nulls are gaps. */
data class TrendPoint(
    val timestampMs: Long,
    val hr: Double?,
    val rmssd: Double?,
    val sdnn: Double?,
    val pnn50: Double?,
    val load: Double?,
    val pelvic: Double?
)

/**
 * Live H10 processing: the same contextual RR filter, HRV maths and Load (physiological stress) estimator as
 * SarahVS, kept in memory for the monitor.
 */
class HeartEngine {
    private val estimator = PhysiologicalStressEstimator()
    private val hrWindow = ArrayDeque<Pair<Long, Int>>()
    private val rrWindow = ArrayDeque<RrSampleEntity>()
    private val acceptedSeed = ArrayDeque<Int>()
    private val rawSeed = ArrayDeque<Int>()
    private var smoothed = 0.0
    private var hrSum = 0L
    private var hrCount = 0
    private var minHr: Int? = null
    private var maxHr: Int? = null
    private var startedAt: Long? = null
    private var counter = 0L
    private var lastMovement: String? = null
    private var snapshot = HeartSnapshot()

    /** RR intervals (with filter results) from the most recent [add] call, for session recording. */
    var lastRr: List<RrSampleEntity> = emptyList()
        private set

    @Synchronized
    fun reset() {
        hrWindow.clear(); rrWindow.clear(); acceptedSeed.clear(); rawSeed.clear()
        smoothed = 0.0; hrSum = 0; hrCount = 0; minHr = null; maxHr = null; startedAt = null
        estimator.resetSession()
        snapshot = HeartSnapshot()
    }

    @Synchronized
    fun addAccelerometer(samples: List<AccelerometerSample>) {
        estimator.addAccelerometer(samples)
        samples.lastOrNull()?.let { lastMovement = MovementAnalyzer.classify(it.xMilliG, it.yMilliG, it.zMilliG).movementClass }
    }

    @Synchronized
    fun add(reading: HrReading): HeartSnapshot {
        val now = reading.timestampMs
        if (startedAt == null) startedAt = now
        val bpm = reading.heartRateBpm
        hrWindow.addLast(now to bpm)
        while (hrWindow.isNotEmpty() && now - hrWindow.first().first > HRV_WINDOW_MS) hrWindow.removeFirst()
        if (bpm in 25..250) {
            hrSum += bpm; hrCount++
            minHr = minOf(minHr ?: bpm, bpm)
            maxHr = maxOf(maxHr ?: bpm, bpm)
            smoothed = if (smoothed == 0.0) bpm.toDouble() else smoothed * 0.75 + bpm * 0.25
        }
        val batch = mutableListOf<RrSampleEntity>()
        reading.rrIntervalsMs.forEachIndexed { index, raw ->
            val filtered = HrvCalculator.filterContextual(raw, acceptedSeed.toList(), rawSeed.toList())
            if (filtered.accepted && filtered.cleanMs != null) {
                acceptedSeed.addLast(filtered.cleanMs); while (acceptedSeed.size > SEED_SIZE) acceptedSeed.removeFirst()
            }
            if (filtered.valid) {
                rawSeed.addLast(raw); while (rawSeed.size > SEED_SIZE) rawSeed.removeFirst()
            }
            val offset = (now - (startedAt ?: now)) - reading.rrIntervalsMs.drop(index + 1).sum()
            val entity = RrSampleEntity(
                id = "rr-${counter++}",
                sessionId = "live",
                timestampMs = now,
                offsetMs = offset.coerceAtLeast(0),
                rawIntervalMs = raw,
                cleanIntervalMs = filtered.cleanMs,
                valid = filtered.valid,
                rejectionReason = filtered.rejectionReason,
                artifact = filtered.artifact,
                accepted = filtered.accepted
            )
            rrWindow.addLast(entity)
            batch += entity
            estimator.addRr(now, entity.cleanIntervalMs, entity.accepted, HrvCalculator.isEctopic(entity))
        }
        lastRr = batch
        while (rrWindow.isNotEmpty() && now - rrWindow.first().timestampMs > HRV_WINDOW_MS) rrWindow.removeFirst()
        estimator.addHeartRate(now, bpm)
        val windowSpan = hrWindow.let { if (it.size < 2) null else it.last().first - it.first().first + 1_000L }
        val hrv = HrvCalculator.calculate(rrWindow.toList(), hrWindow.size, elapsedMs = windowSpan)
        snapshot = HeartSnapshot(
            heartRate = bpm,
            smoothedHr = smoothed.takeIf { it > 0 },
            averageHr = if (hrCount > 0) hrSum.toDouble() / hrCount else null,
            minHr = minHr,
            maxHr = maxHr,
            recentHr = hrWindow.takeLast(30).map { it.second },
            rmssdMs = hrv.rmssdMs,
            sdnnMs = hrv.sdnnMs,
            pnn50Percent = hrv.pnn50Percent,
            hrvQuality = hrv.quality,
            rrCoveragePercent = hrv.rrCoveragePercent,
            latestRrMs = reading.rrIntervalsMs.lastOrNull(),
            load = estimator.evaluate(now),
            contact = reading.contactDetected,
            movement = lastMovement,
            startedAtMs = startedAt,
            updatedAtMs = now
        )
        return snapshot
    }

    @Synchronized
    fun current(): HeartSnapshot = snapshot

    companion object {
        /** Rolling HRV window, matching the "live" HRV on SarahVS's monitor. */
        const val HRV_WINDOW_MS = 120_000L
        private const val SEED_SIZE = 24
    }
}

/** Buckets live values into 5 s points for the trend graphs; keeps up to 24 h in memory. */
class TrendRecorder(private val bucketMs: Long = 5_000L, private val keepMs: Long = 24L * 60 * 60 * 1000) {
    private data class Acc(var sum: Double = 0.0, var n: Int = 0) {
        fun add(v: Double?) { if (v != null && v.isFinite()) { sum += v; n++ } }
        fun mean(): Double? = if (n > 0) sum / n else null
    }

    private val points = ArrayDeque<TrendPoint>()
    private var bucketStart = -1L
    private val hr = Acc(); private val rmssd = Acc(); private val sdnn = Acc()
    private val pnn = Acc(); private val load = Acc(); private val pelvic = Acc()

    @Synchronized
    fun addHeart(timestampMs: Long, s: HeartSnapshot) {
        roll(timestampMs)
        hr.add(s.heartRate?.toDouble()); rmssd.add(s.rmssdMs); sdnn.add(s.sdnnMs)
        pnn.add(s.pnn50Percent); load.add(s.load.score?.toDouble())
    }

    @Synchronized
    fun addPelvic(timestampMs: Long, aboveRest: Double?) {
        roll(timestampMs)
        pelvic.add(aboveRest)
    }

    private fun roll(timestampMs: Long) {
        val start = timestampMs - Math.floorMod(timestampMs, bucketMs)
        if (bucketStart < 0) bucketStart = start
        if (start != bucketStart) {
            flush()
            bucketStart = start
        }
    }

    private fun flush() {
        if (hr.n + pelvic.n == 0) return
        points.addLast(TrendPoint(bucketStart, hr.mean(), rmssd.mean(), sdnn.mean(), pnn.mean(), load.mean(), pelvic.mean()))
        listOf(hr, rmssd, sdnn, pnn, load, pelvic).forEach { it.sum = 0.0; it.n = 0 }
        while (points.isNotEmpty() && bucketStart - points.first().timestampMs > keepMs) points.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<TrendPoint> = points.toList()

    @Synchronized
    fun clear() {
        points.clear(); bucketStart = -1
        listOf(hr, rmssd, sdnn, pnn, load, pelvic).forEach { it.sum = 0.0; it.n = 0 }
    }
}
