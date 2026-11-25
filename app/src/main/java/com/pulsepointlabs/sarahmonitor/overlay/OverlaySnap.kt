package com.pulsepointlabs.sarahmonitor.overlay

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where a floating overlay lands (pure, unit-tested). While you drag, it's pulled onto nearby edges: flush against a
 * neighbor (with a small gap), lined up with a neighbor's top/bottom/left/right, or onto the screen edge; otherwise it
 * sits on an 8 dp grid. When you let go it never stays on top of another overlay: it moves the shortest distance to
 * a free spot. Resizing snaps the moving edges the same way, matches a neighbor's width/height, and stops short of
 * overlapping.
 */
object OverlaySnap {
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right get() = x + w
        val bottom get() = y + h
        fun overlaps(o: Box) = x < o.right && o.x < right && y < o.bottom && o.y < bottom
    }

    /** Pixel sizes for one display. */
    data class Feel(val threshold: Int, val gap: Int, val grid: Int, val screenW: Int, val screenH: Int)

    fun feel(density: Float, screenW: Int, screenH: Int) =
        Feel((18 * density).roundToInt(), (4 * density).roundToInt(), (8 * density).roundToInt().coerceAtLeast(1), screenW, screenH)

    private fun nearest(v: Int, candidates: List<Int>, threshold: Int): Int? =
        candidates.minByOrNull { abs(it - v) }?.takeIf { abs(it - v) <= threshold }

    private fun grid(v: Int, g: Int) = ((v.toFloat() / g).roundToInt() * g)

    /** Side by side (their rows overlap) or stacked (their columns overlap), allowing [slack]. */
    private fun sideBySide(a: Box, b: Box, slack: Int) = a.y < b.bottom + slack && b.y < a.bottom + slack
    private fun stacked(a: Box, b: Box, slack: Int) = a.x < b.right + slack && b.x < a.right + slack

    /** Top-left for a window being dragged to [m] (its raw, unsnapped position). */
    fun move(m: Box, others: List<Box>, f: Feel): Box {
        val xs = mutableListOf(0, f.screenW - m.w)
        val ys = mutableListOf(0, f.screenH - m.h)
        others.forEach { o ->
            if (sideBySide(m, o, f.threshold)) {
                xs += o.right + f.gap; xs += o.x - f.gap - m.w
                ys += o.y; ys += o.bottom - m.h
            }
            if (stacked(m, o, f.threshold)) {
                ys += o.bottom + f.gap; ys += o.y - f.gap - m.h
                xs += o.x; xs += o.right - m.w
            }
        }
        val x = nearest(m.x, xs, f.threshold) ?: grid(m.x, f.grid)
        val y = nearest(m.y, ys, f.threshold) ?: grid(m.y, f.grid)
        return clamp(m.copy(x = x, y = y), f)
    }

    /** Let go: if it sits on another overlay, the nearest free spot (or the least-bad one if the screen is full). */
    fun settle(m: Box, others: List<Box>, f: Feel): Box {
        var cur = clamp(m, f)
        repeat(4) {
            val hit = others.firstOrNull { cur.overlaps(it) } ?: return cur
            val options = listOf(
                cur.copy(x = hit.x - f.gap - cur.w), cur.copy(x = hit.right + f.gap),
                cur.copy(y = hit.y - f.gap - cur.h), cur.copy(y = hit.bottom + f.gap)
            ).map { clamp(it, f) }
            fun cost(b: Box) = abs(b.x - cur.x) + abs(b.y - cur.y)
            cur = options.filter { o -> others.none { o.overlaps(it) } }.minByOrNull(::cost)
                ?: options.filter { !it.overlaps(hit) }.minByOrNull(::cost)
                ?: return cur
        }
        return cur
    }

    /** New size while dragging the corner grip to raw size [w]×[h]. */
    fun resize(m: Box, w: Int, h: Int, minW: Int, minH: Int, others: List<Box>, f: Feel): Box {
        val raw = m.copy(w = w.coerceAtLeast(minW), h = h.coerceAtLeast(minH))
        val rights = mutableListOf(f.screenW)
        val bottoms = mutableListOf(f.screenH)
        others.forEach { o ->
            if (sideBySide(raw, o, f.threshold)) { rights += o.x - f.gap; rights += o.right; bottoms += o.bottom }
            if (stacked(raw, o, f.threshold)) { bottoms += o.y - f.gap; bottoms += o.bottom; rights += o.right; rights += raw.x + o.w }
            if (sideBySide(raw, o, f.threshold)) bottoms += raw.y + o.h
        }
        val right = nearest(raw.right, rights, f.threshold) ?: grid(raw.right, f.grid)
        val bottom = nearest(raw.bottom, bottoms, f.threshold) ?: grid(raw.bottom, f.grid)
        var out = raw.copy(w = (right - raw.x).coerceIn(minW, f.screenW - raw.x), h = (bottom - raw.y).coerceIn(minH, f.screenH - raw.y))
        // Don't grow into a neighbor: stop just short of it (never below the minimum size).
        others.filter { out.overlaps(it) }.forEach { o ->
            out = if (o.x >= m.right - f.gap) out.copy(w = (o.x - f.gap - out.x).coerceAtLeast(minW))
            else if (o.y >= m.bottom - f.gap) out.copy(h = (o.y - f.gap - out.y).coerceAtLeast(minH))
            else out
        }
        return out
    }

    fun clamp(b: Box, f: Feel) = b.copy(
        x = b.x.coerceIn(0, (f.screenW - b.w).coerceAtLeast(0)),
        y = b.y.coerceIn(0, (f.screenH - b.h).coerceAtLeast(0))
    )
}
