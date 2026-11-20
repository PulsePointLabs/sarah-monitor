package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.hrv.HrvCalculator
import com.pulsepointlabs.sarahmonitor.hrv.RrSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class HrvCalculatorTest {
    @Test
    fun rejectsImplausibleRr() {
        assertEquals("below_absolute_minimum", HrvCalculator.filter(200).rejectionReason)
        assertEquals("above_absolute_maximum", HrvCalculator.filter(2200).rejectionReason)
        assertTrue(HrvCalculator.filter(800).valid)
    }

    @Test
    fun calculatesRmssdSdnnPnn50AndQuality() {
        val rr = rrEntities(listOf(800, 860, 810, 900))
        // 3.37 s of intervals over three one-second heart-rate packets: fully covered.
        val metrics = HrvCalculator.calculate(rr, 3)
        assertTrue(metrics.rmssdMs!! > 60.0)
        assertTrue(metrics.sdnnMs!! > 35.0)
        assertEquals(100.0, metrics.rrCoveragePercent, 0.1)
        assertEquals("Good", metrics.quality)
    }

    @Test
    fun stableNormalRhythmIsAccepted() {
        val cleaned = HrvCalculator.cleanSeries(listOf(500, 505, 498, 502, 501, 504))
        assertTrue(cleaned.all { it.accepted })
    }

    @Test
    fun probableMissedBeatIsRejectedContextually() {
        val cleaned = HrvCalculator.cleanSeries(listOf(507, 508, 506, 509, 1013, 507, 508))
        assertEquals("probable_missed_beat", cleaned[4].rejectionReason)
        assertTrue(cleaned[4].artifact)
        assertTrue(!cleaned[4].accepted)
    }

    @Test
    fun probableFalseExtraBeatIsRejectedContextually() {
        // A spurious detection splits one normal interval into two short halves. The first half alone looks like an
        // early beat; the second half completes the interval and reveals the split.
        val raw = listOf(800, 805, 798, 802, 401, 399, 803)
        val cleaned = HrvCalculator.cleanSeries(raw)
        assertTrue(!cleaned[4].accepted)
        assertEquals("probable_false_beat", cleaned[5].rejectionReason)
        assertTrue(cleaned[5].artifact)
        assertTrue(!cleaned[5].accepted)
        assertTrue(cleaned[6].accepted)
        val metrics = HrvCalculator.calculate(rrEntitiesFromCleaner(raw), raw.size)
        assertEquals(0, metrics.ectopicCount)
    }

    @Test
    fun gradualPhysiologicChangeIsNotRejected() {
        val cleaned = HrvCalculator.cleanSeries(listOf(900, 880, 860, 840, 820, 800, 780))
        assertTrue(cleaned.all { it.accepted })
    }

    @Test
    fun suddenSustainedRateChangeIsReacquiredInsteadOfLockingOut() {
        val beforeStep = List(20) { 1000 }
        val afterStep = List(200) { 720 + (it % 3) * 5 }
        val cleaned = HrvCalculator.cleanSeries(beforeStep + afterStep).drop(beforeStep.size)

        assertTrue(cleaned.take(5).none { it.accepted })
        assertTrue(cleaned.drop(5).all { it.accepted })
    }

    @Test
    fun isolatedEarlyBeatIsExcludedAsEctopicNotArtifact() {
        val cleaned = HrvCalculator.cleanSeries(List(10) { 800 } + 520 + 1080 + List(6) { 800 })

        assertEquals(HrvCalculator.REASON_ECTOPIC_PREMATURE, cleaned[10].rejectionReason)
        assertEquals(HrvCalculator.REASON_ECTOPIC_COMPENSATORY, cleaned[11].rejectionReason)
        assertTrue(cleaned.subList(10, 12).all { !it.accepted && !it.artifact && it.ectopic })
        assertTrue(cleaned.drop(12).all { it.accepted })
    }

    @Test
    fun alternatingBigeminyDoesNotReseedTheBaseline() {
        val cleaned = HrvCalculator.cleanSeries(List(10) { 800 } + List(20) { if (it % 2 == 0) 520 else 1080 })

        assertTrue(cleaned.drop(10).none { it.accepted })
        assertTrue(cleaned.drop(10).all { it.ectopic && !it.artifact })
    }

    @Test
    fun absoluteLowerRejection() {
        val cleaned = HrvCalculator.cleanSeries(listOf(250))
        assertEquals("below_absolute_minimum", cleaned.single().rejectionReason)
        assertTrue(!cleaned.single().valid)
        assertTrue(!cleaned.single().accepted)
    }

    @Test
    fun absoluteUpperRejection() {
        val cleaned = HrvCalculator.cleanSeries(listOf(2200))
        assertEquals("above_absolute_maximum", cleaned.single().rejectionReason)
        assertTrue(!cleaned.single().valid)
        assertTrue(!cleaned.single().accepted)
    }

    @Test
    fun insufficientContextIsAcceptedWhenAbsoluteValid() {
        val cleaned = HrvCalculator.cleanSeries(listOf(500, 650, 480))
        assertTrue(cleaned.all { it.accepted })
        assertTrue(cleaned.all { it.rejectionReason == null })
    }

    @Test
    fun cleanedRmssdIsProtectedFromOneDoubledArtifact() {
        val clean = listOf(507, 508, 506, 509, 507, 508)
        val withArtifact = listOf(507, 508, 506, 509, 1013, 507, 508)
        val cleanRmssd = HrvCalculator.calculate(rrEntities(clean), clean.size).rmssdMs!!
        val artifactRmssd = HrvCalculator.calculate(rrEntitiesFromCleaner(withArtifact), withArtifact.size).rmssdMs!!
        assertTrue(kotlin.math.abs(cleanRmssd - artifactRmssd) < 2.0)
    }

    @Test
    fun coverageAndAcceptanceCanUseFullCountsWithBoundedLiveWindow() {
        val recentWindow = rrEntities(listOf(800, 810, 790, 805))
        val metrics = HrvCalculator.calculate(
            rr = recentWindow,
            hrSamples = 1000,
            totalRrCount = 980,
            acceptedCount = 960,
            artifactCount = 12
        )
        assertEquals(980, metrics.totalCount)
        assertEquals(960, metrics.acceptedCount)
        assertEquals(20, metrics.rejectedCount)
        assertEquals(12, metrics.contextualArtifactCount)
        // 980 intervals averaging 801.25 ms span 785.2 s of the 1000 s the heart-rate packets cover.
        assertEquals(78.5225, metrics.rrCoveragePercent, 0.0001)
        assertEquals(97.959183673, metrics.rrAcceptancePercent, 0.0001)
    }

    @Test
    fun rmssdDoesNotPairBeatsAcrossARejectedBeatOrAGap() {
        fun rr(offset: Long, value: Int, accepted: Boolean = true) = RrSampleEntity(
            id = "$offset", sessionId = "s", timestampMs = offset, offsetMs = offset, rawIntervalMs = value,
            cleanIntervalMs = value.takeIf { accepted }, valid = accepted, rejectionReason = null, accepted = accepted
        )
        val series = listOf(
            rr(1_000, 800), rr(1_900, 900),
            rr(2_350, 450, accepted = false),
            rr(3_050, 700), rr(3_840, 790),
            rr(63_840, 600), rr(64_440, 610)
        )

        assertEquals(listOf(100, 90, 10), HrvCalculator.successiveDifferences(series))
        val expected = kotlin.math.sqrt((100.0 * 100.0 + 90.0 * 90.0 + 10.0 * 10.0) / 3.0)
        assertEquals(expected, HrvCalculator.calculate(series, series.size).rmssdMs!!, 0.0001)
    }

    @Test
    fun pvcEveryTwentyBeatsKeepsQualityGoodAndRmssdClean() {
        val clean = sinusRhythm(400)
        val withPvcs = clean.toMutableList()
        var pvcs = 0
        for (index in 20 until clean.size - 1 step 20) {
            val normal = clean[index]
            withPvcs[index] = (normal * 0.62).toInt()
            withPvcs[index + 1] = 2 * normal - withPvcs[index]
            pvcs += 1
        }

        val cleanMetrics = HrvCalculator.calculate(timedEntitiesFromCleaner(clean), secondsSpanned(clean))
        val metrics = HrvCalculator.calculate(timedEntitiesFromCleaner(withPvcs), secondsSpanned(withPvcs))

        assertEquals(2 * pvcs, metrics.ectopicCount)
        assertEquals(0, metrics.contextualArtifactCount)
        assertEquals(100.0, metrics.rrAcceptancePercent, 0.0001)
        assertEquals("Good", metrics.quality)
        assertEquals(cleanMetrics.rmssdMs!!, metrics.rmssdMs!!, cleanMetrics.rmssdMs!! * 0.05)
        assertEquals(cleanMetrics.sdnnMs!!, metrics.sdnnMs!!, cleanMetrics.sdnnMs!! * 0.05)
    }

    @Test
    fun bigeminyIsEctopyAndDoesNotMakeQualityPoor() {
        val lead = sinusRhythm(12)
        val bigeminy = List(60) { if (it % 2 == 0) 600 else 1400 }
        val rr = timedEntitiesFromCleaner(lead + bigeminy)
        val metrics = HrvCalculator.calculate(rr, secondsSpanned(lead + bigeminy))

        assertEquals(bigeminy.size, metrics.ectopicCount)
        assertEquals(0, metrics.contextualArtifactCount)
        assertTrue(metrics.quality != "Poor")
        // RMSSD comes only from the normal-to-normal pairs before the bigeminy started.
        assertEquals(lead.size - 1, HrvCalculator.successiveDifferences(rr).size)
        assertNotNull(metrics.rmssdMs)
        assertTrue(metrics.rmssdMs!! < 60.0)
    }

    @Test
    fun bigeminyWithNoNormalPairsIsHonestlyUnavailable() {
        val entities = timedEntitiesFromCleaner(List(3) { 1000 } + List(40) { if (it % 2 == 0) 600 else 1400 })
            .drop(3)
        val metrics = HrvCalculator.calculate(entities, 40)

        assertEquals("Unavailable", metrics.quality)
        assertNull(metrics.rmssdMs)
        assertEquals(40, metrics.ectopicCount)
    }

    @Test
    fun mildPrematureAtrialBeatIsExcludedFromRmssdAndPnn50() {
        val clean = sinusRhythm(300)
        val withPacs = clean.toMutableList()
        for (index in 15 until clean.size - 1 step 15) {
            val normal = clean[index]
            withPacs[index] = (normal * 0.80).toInt()
            withPacs[index + 1] = (normal * 1.15).toInt()
        }
        val cleaned = HrvCalculator.cleanSeries(withPacs)
        assertEquals(HrvCalculator.REASON_ECTOPIC_PREMATURE, cleaned[15].rejectionReason)
        assertEquals(HrvCalculator.REASON_ECTOPIC_COMPENSATORY, cleaned[16].rejectionReason)

        val cleanMetrics = HrvCalculator.calculate(timedEntitiesFromCleaner(clean), secondsSpanned(clean))
        val metrics = HrvCalculator.calculate(timedEntitiesFromCleaner(withPacs), secondsSpanned(withPacs))
        assertEquals(cleanMetrics.rmssdMs!!, metrics.rmssdMs!!, cleanMetrics.rmssdMs!! * 0.05)
        assertEquals(cleanMetrics.pnn50Percent!!, metrics.pnn50Percent!!, 2.0)
        assertEquals("Good", metrics.quality)
    }

    @Test
    fun breathingVariationIsNotMistakenForEctopy() {
        // Strong respiratory sinus arrhythmia: intervals swing 800-1100 ms but change gradually beat to beat.
        val rsa = List(200) { (950 + 150 * kotlin.math.sin(it * 2 * Math.PI / 6)).toInt() }
        val metrics = HrvCalculator.calculate(timedEntitiesFromCleaner(rsa), secondsSpanned(rsa))
        assertEquals(0, metrics.ectopicCount)
        assertEquals(0, metrics.contextualArtifactCount)
    }

    @Test
    fun coverageDoesNotDependOnHeartRate() {
        // 40 bpm, every beat received over a minute of one-second packets.
        val slow = timedEntities(List(40) { 1500 })
        assertEquals(100.0, HrvCalculator.calculate(slow, 60).rrCoveragePercent, 0.5)
        // 120 bpm with half the beats missing.
        val fastHalf = timedEntities(List(60) { 500 })
        assertEquals(50.0, HrvCalculator.calculate(fastHalf, 60).rrCoveragePercent, 0.5)
    }

    @Test
    fun realNoiseArtifactsStillLowerQuality() {
        val noisy = sinusRhythm(300).toMutableList()
        for (index in 10 until noisy.size - 1 step 10) {
            if ((index / 10) % 2 == 0) {
                noisy[index] = noisy[index] * 2 // missed detection
            } else {
                noisy[index] = 420 // spurious extra detection splitting the interval
                noisy[index + 1] = 560
            }
        }
        val metrics = HrvCalculator.calculate(timedEntitiesFromCleaner(noisy), secondsSpanned(noisy))
        assertTrue(metrics.contextualArtifactCount >= 30)
        assertEquals(0, metrics.ectopicCount)
        assertTrue(metrics.rrAcceptancePercent < 90.0)
        assertEquals("Poor", metrics.quality)
    }

    @Test
    fun liveWindowEstimatesSessionEctopyFromItsRejections() {
        val window = timedEntitiesFromCleaner(sinusRhythm(19) + 620 + 1380 + sinusRhythm(19))
        val metrics = HrvCalculator.calculate(
            rr = window,
            hrSamples = 1000,
            totalRrCount = 1000,
            acceptedCount = 940,
            artifactCount = 10
        )
        // All of the window's non-artifact rejections are ectopic, so the session's 50 are treated the same way.
        assertEquals(50, metrics.ectopicCount)
        assertEquals(940.0 / 950.0 * 100.0, metrics.rrAcceptancePercent, 0.0001)
    }

    /** Sinus rhythm around 1000 ms with gentle breathing variation. */
    private fun sinusRhythm(count: Int): List<Int> =
        List(count) { (1000 + 30 * kotlin.math.sin(it * 2 * Math.PI / 5)).toInt() }

    private fun secondsSpanned(raw: List<Int>): Int = (raw.sum() / 1000.0).roundToInt()

    private fun timedEntities(raw: List<Int>): List<RrSampleEntity> {
        var offset = 0L
        return raw.mapIndexed { index, value ->
            offset += value
            val filtered = HrvCalculator.filter(value)
            RrSampleEntity(
                id = "$index", sessionId = "s", timestampMs = offset, offsetMs = offset, rawIntervalMs = value,
                cleanIntervalMs = filtered.cleanMs, valid = filtered.valid, rejectionReason = filtered.rejectionReason,
                artifact = filtered.artifact, accepted = filtered.accepted
            )
        }
    }

    private fun timedEntitiesFromCleaner(raw: List<Int>): List<RrSampleEntity> {
        var offset = 0L
        return HrvCalculator.cleanSeries(raw).mapIndexed { index, filtered ->
            offset += filtered.rawMs
            RrSampleEntity(
                id = "$index", sessionId = "s", timestampMs = offset, offsetMs = offset, rawIntervalMs = filtered.rawMs,
                cleanIntervalMs = filtered.cleanMs, valid = filtered.valid, rejectionReason = filtered.rejectionReason,
                artifact = filtered.artifact, accepted = filtered.accepted
            )
        }
    }

    private fun rrEntities(raw: List<Int>): List<RrSampleEntity> =
        raw.mapIndexed { index, value ->
            val filtered = HrvCalculator.filter(value)
            RrSampleEntity(
                id = "$index",
                sessionId = "s",
                timestampMs = index * 1000L,
                offsetMs = index * 1000L,
                rawIntervalMs = value,
                cleanIntervalMs = filtered.cleanMs,
                valid = filtered.valid,
                rejectionReason = filtered.rejectionReason,
                artifact = filtered.artifact,
                accepted = filtered.accepted
            )
        }

    private fun rrEntitiesFromCleaner(raw: List<Int>): List<RrSampleEntity> =
        HrvCalculator.cleanSeries(raw).mapIndexed { index, filtered ->
            RrSampleEntity(
                id = "$index",
                sessionId = "s",
                timestampMs = index * 1000L,
                offsetMs = index * 1000L,
                rawIntervalMs = filtered.rawMs,
                cleanIntervalMs = filtered.cleanMs,
                valid = filtered.valid,
                rejectionReason = filtered.rejectionReason,
                artifact = filtered.artifact,
                accepted = filtered.accepted
            )
        }
}
