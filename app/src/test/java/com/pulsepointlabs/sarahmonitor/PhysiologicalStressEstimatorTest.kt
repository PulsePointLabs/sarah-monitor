package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.ble.AccelerometerSample
import com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressBaseline
import com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressEstimator
import com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressLevel
import com.pulsepointlabs.sarahmonitor.hrv.StressAlertGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysiologicalStressEstimatorTest {
    @Test
    fun cleanLowMotionDataBuildsBaselineAndDetectsHighLoad() {
        var savedBaseline: PhysiologicalStressBaseline? = null
        val estimator = PhysiologicalStressEstimator(
            onBaselineUpdated = { savedBaseline = it },
            baselineWindowCount = 3
        )
        // Three non-overlapping one-minute windows are needed before the first baseline exists.
        var reading = feed(estimator, 0, 200, 65, 950, 1050, moving = false)
        assertNotNull(savedBaseline)
        assertTrue(reading.level == PhysiologicalStressLevel.Settled || reading.level == PhysiologicalStressLevel.EstablishingBaseline)

        reading = feed(estimator, 201, 370, 100, 700, 706, moving = false)
        assertEquals(PhysiologicalStressLevel.HighLoad, reading.level)
        assertTrue((reading.score ?: 0) >= 75)
        assertTrue((reading.score ?: 0) < 100)
        assertTrue((reading.confidencePercent ?: 0) >= 70)
    }

    @Test
    fun motionSuppressesStressScore() {
        val estimator = PhysiologicalStressEstimator(
            initialBaseline = PhysiologicalStressBaseline(65.0, 60.0),
            baselineWindowCount = 1
        )
        val reading = feed(estimator, 0, 130, 105, 700, 706, moving = true)
        assertEquals(PhysiologicalStressLevel.Moving, reading.level)
        assertEquals(null, reading.score)
    }

    @Test
    fun isolatedHrvSuppressionDoesNotImmediatelyPegScore() {
        val estimator = PhysiologicalStressEstimator(
            initialBaseline = PhysiologicalStressBaseline(80.0, 60.0),
            baselineWindowCount = 1
        )
        val reading = feed(estimator, 0, 130, 84, 1100, 1110, moving = false)
        assertTrue((reading.score ?: 0) in 28..74)
        assertTrue(reading.level == PhysiologicalStressLevel.MildLoad || reading.level == PhysiologicalStressLevel.ElevatedLoad)
        assertTrue((reading.hrvSuppressionPercent ?: 0) > (reading.hrLoadPercent ?: 0))
    }

    @Test
    fun baselineProgressIsReportedWhileLearning() {
        val estimator = PhysiologicalStressEstimator(baselineWindowCount = 4)
        val reading = feed(estimator, 0, 70, 70, 950, 1050, moving = false)
        assertEquals(PhysiologicalStressLevel.EstablishingBaseline, reading.level)
        assertNotNull(reading.baselineCoveragePercent)
        assertTrue((reading.baselineCoveragePercent ?: 0.0) > 0.0)
        assertTrue((reading.baselineCoveragePercent ?: 0.0) < 100.0)
        assertTrue(reading.motionSamplesObserved >= reading.motionSamplesRequired)
        assertTrue(reading.rrSamplesObserved >= reading.rrSamplesRequired)
        assertTrue(reading.windowSpanMs >= reading.windowSpanRequiredMs)
        assertTrue(reading.baselineWindowsObserved in 1 until reading.baselineWindowsRequired)
    }

    @Test
    fun breathingLikeChestMotionStillAllowsStressScoring() {
        val estimator = PhysiologicalStressEstimator(
            initialBaseline = PhysiologicalStressBaseline(82.0, 52.0),
            baselineWindowCount = 1
        )
        var reading = estimator.evaluate(0L)
        for (second in 0..160) {
            val timestamp = second * 1_000L
            estimator.addHeartRate(timestamp, 96)
            estimator.addRr(timestamp, if (second % 2 == 0) 860 else 900, accepted = true)
            val z = if (second % 4 < 2) 1115 else 895
            estimator.addAccelerometer(
                listOf(
                    AccelerometerSample(timestamp, 0, 0, z),
                    AccelerometerSample(timestamp + 20, 0, 0, z)
                )
            )
            reading = estimator.evaluate(timestamp)
        }
        assertTrue(
            reading.level == PhysiologicalStressLevel.MildLoad ||
                reading.level == PhysiologicalStressLevel.ElevatedLoad ||
                reading.level == PhysiologicalStressLevel.HighLoad
        )
        assertNotNull(reading.score)
    }

    @Test
    fun insufficientMotionWindowReportsObservedProgress() {
        val estimator = PhysiologicalStressEstimator()
        var reading = estimator.evaluate(0L)
        for (second in 0..10) {
            val timestamp = second * 1_000L
            estimator.addHeartRate(timestamp, 82)
            estimator.addRr(timestamp, 900, accepted = true)
            estimator.addAccelerometer(
                listOf(
                    AccelerometerSample(timestamp, 0, 0, 1000),
                    AccelerometerSample(timestamp + 20, 0, 0, 1000)
                )
            )
            reading = estimator.evaluate(timestamp)
        }
        assertEquals(PhysiologicalStressLevel.Unavailable, reading.level)
        assertTrue(reading.motionSamplesObserved > 0)
        assertTrue(reading.motionSamplesObserved < reading.motionSamplesRequired)
    }

    @Test
    fun firstBaselineNeedsFiveNonOverlappingMinutes() {
        var savedBaseline: PhysiologicalStressBaseline? = null
        var savedAtSecond: Int? = null
        val estimator = PhysiologicalStressEstimator(onBaselineUpdated = { savedBaseline = it })
        var reading = estimator.evaluate(0L)
        for (second in 0..420) {
            val timestamp = second * 1_000L
            estimator.addHeartRate(timestamp, 78)
            estimator.addRr(timestamp, if (second % 2 == 0) 960 else 1010, accepted = true)
            estimator.addAccelerometer(
                listOf(
                    AccelerometerSample(timestamp, 0, 0, if (second % 4 < 2) 1040 else 960),
                    AccelerometerSample(timestamp + 20, 0, 0, if (second % 4 < 2) 1040 else 960)
                )
            )
            reading = estimator.evaluate(timestamp)
            if (savedBaseline != null && savedAtSecond == null) savedAtSecond = second
            if (reading.level == PhysiologicalStressLevel.Settled && savedBaseline != null) break
        }
        assertNotNull(savedBaseline)
        assertTrue("baseline after ${savedAtSecond}s", savedAtSecond!! >= 280)
        assertTrue(
            reading.level == PhysiologicalStressLevel.Settled ||
                reading.level == PhysiologicalStressLevel.MildLoad
        )
    }

    @Test
    fun highStressAlertRequiresSustainedPeriodCooldownAndRearm() {
        val gate = StressAlertGate(sustainedDurationMs = 300_000L, cooldownMs = 2_700_000L)
        assertFalse(gate.update(0L, 80, 75))
        assertFalse(gate.update(299_999L, 80, 75))
        assertTrue(gate.update(300_000L, 80, 75))
        assertFalse(gate.update(900_000L, 90, 75))
        assertFalse(gate.update(1_000_000L, 30, 75))
        assertFalse(gate.update(3_000_000L, 80, 75))
        assertTrue(gate.update(3_300_000L, 80, 75))
    }

    @Test
    fun calibratesAndScoresWithFivePercentEctopy() {
        var savedBaseline: PhysiologicalStressBaseline? = null
        val estimator = PhysiologicalStressEstimator(onBaselineUpdated = { savedBaseline = it })
        var reading = estimator.evaluate(0L)
        for (second in 0..420) {
            val timestamp = second * 1_000L
            estimator.addHeartRate(timestamp, 62)
            when (second % 20) {
                // A premature beat and its compensatory pause: real intervals, excluded as not normal-to-normal.
                10 -> estimator.addRr(timestamp, 620, accepted = false)
                11 -> estimator.addRr(timestamp, 1380, accepted = false)
                else -> estimator.addRr(timestamp, if (second % 2 == 0) 950 else 1010, accepted = true)
            }
            quietChest(estimator, timestamp)
            reading = estimator.evaluate(timestamp)
            assertTrue("PoorSignal at ${second}s", reading.level != PhysiologicalStressLevel.PoorSignal)
        }
        assertNotNull(savedBaseline)
        assertEquals(100.0, reading.rrAcceptancePercent!!, 0.0001)
        assertTrue(reading.level == PhysiologicalStressLevel.Settled || reading.level == PhysiologicalStressLevel.MildLoad)
    }

    @Test
    fun tenPercentNoiseArtifactsStillBlockScoring() {
        val estimator = PhysiologicalStressEstimator(initialBaseline = PhysiologicalStressBaseline(62.0, 40.0))
        var reading = estimator.evaluate(0L)
        for (second in 0..180) {
            val timestamp = second * 1_000L
            estimator.addHeartRate(timestamp, 62)
            if (second % 10 == 5) {
                estimator.addRr(timestamp, null, accepted = false)
            } else {
                estimator.addRr(timestamp, if (second % 2 == 0) 950 else 1010, accepted = true)
            }
            quietChest(estimator, timestamp)
            reading = estimator.evaluate(timestamp)
        }
        assertEquals(PhysiologicalStressLevel.PoorSignal, reading.level)
    }

    @Test
    fun rmssdNeedsEnoughSuccessiveDifferences() {
        val estimator = PhysiologicalStressEstimator(initialBaseline = PhysiologicalStressBaseline(62.0, 40.0))
        var reading = estimator.evaluate(0L)
        for (second in 0..120) {
            val timestamp = second * 1_000L
            estimator.addHeartRate(timestamp, 62)
            // Every third interval is ectopic, so no more than one difference survives between them.
            estimator.addRr(timestamp, 980, accepted = second % 3 != 0)
            quietChest(estimator, timestamp)
            reading = estimator.evaluate(timestamp)
        }
        assertEquals(null, reading.score)
        assertEquals(null, reading.currentRmssdMs)
    }

    @Test
    fun baselineFollowsAPostureChange() {
        // Baseline learned lying down: slow heart rate and high HRV.
        var savedBaseline: PhysiologicalStressBaseline? = null
        val estimator = PhysiologicalStressEstimator(
            initialBaseline = PhysiologicalStressBaseline(60.0, 60.0),
            onBaselineUpdated = { savedBaseline = it }
        )
        // Sitting: about 12 bpm faster with RMSSD around 35 ms, calm and still.
        val early = feed(estimator, 0, 180, 72, 815, 850, moving = false)
        assertTrue(
            early.level == PhysiologicalStressLevel.MildLoad || early.level == PhysiologicalStressLevel.ElevatedLoad
        )
        val late = feed(estimator, 181, 3_600, 72, 815, 850, moving = false)
        assertEquals(PhysiologicalStressLevel.Settled, late.level)
        assertTrue(savedBaseline!!.hr > 70.0)
        assertTrue(savedBaseline!!.rmssdMs < 40.0)
    }

    @Test
    fun briefStressEpisodeBarelyMovesTheBaseline() {
        var savedBaseline: PhysiologicalStressBaseline? = null
        val estimator = PhysiologicalStressEstimator(
            initialBaseline = PhysiologicalStressBaseline(65.0, 50.0),
            onBaselineUpdated = { savedBaseline = it }
        )
        feed(estimator, 0, 300, 95, 700, 712, moving = false)
        assertTrue(savedBaseline!!.hr < 70.0)
        assertTrue(savedBaseline!!.rmssdMs > 38.0)
    }

    private fun quietChest(estimator: PhysiologicalStressEstimator, timestamp: Long) {
        estimator.addAccelerometer(
            listOf(
                AccelerometerSample(timestamp, 0, 0, 1000),
                AccelerometerSample(timestamp + 20, 0, 0, 1000)
            )
        )
    }

    private fun feed(
        estimator: PhysiologicalStressEstimator,
        startSecond: Int,
        endSecond: Int,
        hr: Int,
        rrA: Int,
        rrB: Int,
        moving: Boolean
    ): com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressReading {
        var reading = estimator.evaluate(startSecond * 1_000L)
        for (second in startSecond..endSecond) {
            val timestamp = second * 1_000L
            estimator.addHeartRate(timestamp, hr)
            estimator.addRr(timestamp, if (second % 2 == 0) rrA else rrB, accepted = true)
            estimator.addAccelerometer(
                listOf(
                    AccelerometerSample(timestamp, 0, 0, if (moving) 1500 else 1000),
                    AccelerometerSample(timestamp + 20, 0, 0, if (moving) 1500 else 1000)
                )
            )
            reading = estimator.evaluate(timestamp)
        }
        return reading
    }
}
