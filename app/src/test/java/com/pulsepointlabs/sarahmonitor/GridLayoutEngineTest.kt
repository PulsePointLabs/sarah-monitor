package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.ui.CardSlot
import com.pulsepointlabs.sarahmonitor.ui.CardType
import com.pulsepointlabs.sarahmonitor.ui.GridLayoutEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridLayoutEngineTest {
    private fun noOverlaps(slots: List<CardSlot>) {
        val visible = slots.filter { it.visible }
        visible.forEachIndexed { i, a -> visible.drop(i + 1).forEach { b -> assertFalse("$a overlaps $b", a.overlaps(b)) } }
    }

    @Test
    fun defaultLayoutFillsOneScreenWithoutOverlaps() {
        noOverlaps(GridLayoutEngine.defaultLayout)
        assertEquals(GridLayoutEngine.SCREEN_ROWS, GridLayoutEngine.defaultLayout.maxOf { it.bottom })
        assertEquals(CardType.entries.size, GridLayoutEngine.defaultLayout.size)
    }

    @Test
    fun movingOntoAnotherCardPushesItAside() {
        val start = GridLayoutEngine.defaultLayout
        val hr = start.first { it.type == CardType.HeartRate }
        val placed = GridLayoutEngine.place(start, hr.copy(x = 6, y = 0))
        noOverlaps(placed)
        val moved = placed.first { it.type == CardType.HeartRate }
        assertEquals(6, moved.x)
        assertEquals(0, moved.y)
    }

    @Test
    fun resizeIsClampedToMinimumAndGrid() {
        val start = GridLayoutEngine.defaultLayout
        val pelvic = start.first { it.type == CardType.Pelvic }
        val placed = GridLayoutEngine.place(start, pelvic.copy(w = 1, h = 1))
        val result = placed.first { it.type == CardType.Pelvic }
        assertEquals(CardType.Pelvic.minW, result.w)
        assertEquals(CardType.Pelvic.minH, result.h)
        val wide = GridLayoutEngine.place(start, pelvic.copy(w = 99))
        assertEquals(GridLayoutEngine.COLUMNS, wide.first { it.type == CardType.Pelvic }.w)
        noOverlaps(wide)
    }

    @Test
    fun gapsCloseUpWhenACardIsHidden() {
        val hidden = GridLayoutEngine.setVisible(GridLayoutEngine.defaultLayout, CardType.HeartRate, false)
        assertEquals(0, hidden.first { it.type == CardType.Hrv }.y)
        val shown = GridLayoutEngine.setVisible(hidden, CardType.HeartRate, true)
        assertTrue(shown.first { it.type == CardType.HeartRate }.visible)
        noOverlaps(shown)
    }

    @Test
    fun jsonRoundTrip() {
        val layout = GridLayoutEngine.place(GridLayoutEngine.defaultLayout, GridLayoutEngine.defaultLayout[0].copy(w = 8))
        assertEquals(layout, GridLayoutEngine.fromJson(GridLayoutEngine.toJson(layout)))
    }
}
