package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.overlay.OverlaySnap
import com.pulsepointlabs.sarahmonitor.overlay.OverlaySnap.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Overlays click together: snap to neighbors and edges, never end up stacked, resize stops at neighbors. */
class OverlaySnapTest {
    private val f = OverlaySnap.Feel(threshold = 18, gap = 4, grid = 8, screenW = 1080, screenH = 2340)
    private val hr = Box(20, 200, 300, 200)

    @Test fun dropsFlushBesideANeighborAndLinesUpItsTop() {
        // Dragged to roughly the right of the HR overlay, a bit low and a bit far.
        val b = OverlaySnap.move(Box(333, 206, 250, 180), listOf(hr), f)
        assertEquals(hr.right + f.gap, b.x)
        assertEquals(hr.y, b.y)
    }

    @Test fun stacksUnderAndLinesUpItsLeft() {
        val b = OverlaySnap.move(Box(30, 410, 300, 150), listOf(hr), f)
        assertEquals(hr.bottom + f.gap, b.y)
        assertEquals(hr.x, b.x)
    }

    @Test fun snapsToTheScreenEdgeAndOtherwiseToTheGrid() {
        assertEquals(0, OverlaySnap.move(Box(12, 900, 200, 120), emptyList(), f).x)
        val free = OverlaySnap.move(Box(301, 903, 200, 120), emptyList(), f)
        assertEquals(0, free.x % 8); assertEquals(0, free.y % 8)
    }

    @Test fun neverLeftOnTopOfAnother() {
        val dropped = OverlaySnap.settle(Box(100, 260, 250, 150), listOf(hr), f)
        assertFalse(dropped.overlaps(hr))
        // The nearest way out (right of it: 220 px; below it: 144 px → below).
        assertEquals(hr.bottom + f.gap, dropped.y)
    }

    @Test fun resizeStopsShortOfTheNeighborAndMatchesItsHeight() {
        val left = Box(20, 200, 250, 150)
        val right = Box(400, 200, 300, 200)
        val grown = OverlaySnap.resize(left, 500, 195, 76, 44, listOf(right), f)
        assertTrue(!grown.overlaps(right))
        assertEquals(right.x - f.gap, grown.right)
        assertEquals(right.bottom, grown.bottom)  // bottoms line up
    }

    @Test fun staysOnScreen() {
        val b = OverlaySnap.move(Box(1000, 2300, 300, 200), emptyList(), f)
        assertEquals(1080 - 300, b.x); assertEquals(2340 - 200, b.y)
    }
}
