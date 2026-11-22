package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.ui.CardType
import com.pulsepointlabs.sarahmonitor.ui.CardType.*
import com.pulsepointlabs.sarahmonitor.ui.FitLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class FitLayoutTest {
    private fun visible(slots: List<com.pulsepointlabs.sarahmonitor.ui.CardSlot>) = slots.filter { it.visible }.sortedBy { it.y }.map { it.type }

    @Test fun portraitPairsNumbersAndGivesWaveformsAFullRow() {
        val rows = FitLayout.rows(visible(FitLayout.monitorDefaults), 2)
        assertEquals(listOf(listOf(HeartRate, Stress), listOf(Ecg), listOf(Hrv, Load), listOf(Pelvic, BloodPressure), listOf(HrTrend)), rows)
    }

    @Test fun landscapeUsesFourSlotRows() {
        val rows = FitLayout.rows(visible(FitLayout.monitorDefaults), 4)
        assertEquals(listOf(listOf(HeartRate, Stress, Ecg), listOf(Hrv, Load, Pelvic, BloodPressure), listOf(HrTrend)), rows)
    }

    @Test fun smallCardIsPulledForwardToFillAGap() {
        // HR alone, then ECG (wide) doesn't fit beside it in portrait, so Load moves up next to HR.
        assertEquals(listOf(listOf(HeartRate, Load), listOf(Ecg)), FitLayout.rows(listOf(HeartRate, Ecg, Load), 2))
    }

    @Test fun picksColumnsForTheScreenShape() {
        val cards = visible(FitLayout.monitorDefaults)
        assertEquals(2, FitLayout.bestUnits(cards, 376f, 690f))   // phone portrait
        assertEquals(5, FitLayout.bestUnits(cards, 784f, 290f))   // phone landscape: two tall rows
        assertEquals(2, FitLayout.rows(cards, 5).size)
    }

    @Test fun everyCardAppearsExactlyOnce() {
        val all = CardType.entries.toList()
        listOf(2, 4).forEach { u -> assertEquals(all.toSet(), FitLayout.rows(all, u).flatten().toSet()); assertEquals(all.size, FitLayout.rows(all, u).flatten().size) }
    }
}
