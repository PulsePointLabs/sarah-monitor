package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.ui.FitLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.35.1: the phone board never squashes a card; it scrolls instead. */
class DashboardResizeTest {
    @Test fun fitsWhenEveryRowIsReadable() {
        // 4 rows sharing 700 dp: shortest is well above the floor, no scrolling.
        assertEquals(1f, FitLayout.scrollStretch(listOf(200f, 200f, 200f, 200f), 700f), 0.001f)
    }

    @Test fun scrollsInsteadOfSquashing() {
        // One card made 2.5x tall on a full board: rows would drop under the floor, so everything stretches.
        val stretch = FitLayout.scrollStretch(listOf(200f, 200f, 600f, 200f, 200f, 200f), 600f)
        assertTrue(stretch > 1f)
        val shortestAfter = 600f * 200f / 1600f * stretch
        assertEquals(FitLayout.MIN_ROW_DP, shortestAfter, 0.5f)
    }

    @Test fun emptyBoardIsFine() {
        assertEquals(1f, FitLayout.scrollStretch(emptyList(), 700f), 0f)
    }
}
