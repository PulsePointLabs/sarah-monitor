package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Card layout rules, shared by every card so they behave the same:
 *  - nothing ever draws over anything else: each part gets its own slice of the height;
 *  - when a card is short, the least important parts are left out whole (status line first, then extras),
 *    never squashed or cut off;
 *  - the reading takes whatever height is left.
 */
class FitScope {
    internal class Part(val flex: Float, val minDp: Float, val drop: Int, val content: @Composable () -> Unit)
    internal val parts = mutableListOf<Part>()

    /** Always shown at its natural height unless [drop] > 0, in which case it goes first when space runs out (higher goes first). */
    fun fixed(drop: Int = 0, content: @Composable () -> Unit) { parts += Part(0f, 0f, drop, content) }

    /** Takes a share of the height left over (by [weight]); needs at least [minDp] or lower-priority parts are dropped. */
    fun flex(weight: Float = 1f, minDp: Float = 30f, drop: Int = 0, content: @Composable () -> Unit) { parts += Part(weight, minDp, drop, content) }
}

@Composable
fun FitColumn(modifier: Modifier = Modifier, gap: Dp = 5.dp, build: FitScope.() -> Unit) {
    val parts = FitScope().apply(build).parts
    Layout(
        content = { parts.forEach { p -> Box(propagateMinConstraints = p.flex > 0f) { p.content() } } },
        modifier = modifier
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE / 2
        val gapPx = gap.roundToPx()
        val fixedPlaceables = HashMap<Int, androidx.compose.ui.layout.Placeable>()
        parts.forEachIndexed { i, p ->
            if (p.flex == 0f) fixedPlaceables[i] = measurables[i].measure(Constraints(maxWidth = w))
        }
        val included = parts.indices.toMutableSet()
        fun need(): Int {
            var sum = 0
            included.forEach { i -> sum += if (parts[i].flex > 0f) parts[i].minDp.dp.roundToPx() else fixedPlaceables[i]!!.height }
            return sum + gapPx * (included.size - 1).coerceAtLeast(0)
        }
        while (need() > h) {
            val victim = included.filter { parts[it].drop > 0 }.maxByOrNull { parts[it].drop } ?: break
            included -= victim
        }
        val fixedSum = included.filter { parts[it].flex == 0f }.sumOf { fixedPlaceables[it]!!.height }
        val flexIdx = included.filter { parts[it].flex > 0f }
        val left = (h - fixedSum - gapPx * (included.size - 1).coerceAtLeast(0)).coerceAtLeast(0)
        val totalWeight = flexIdx.sumOf { parts[it].flex.toDouble() }.toFloat()
        val flexPlaceables = flexIdx.associateWith { i ->
            val share = if (totalWeight > 0f) (left * parts[i].flex / totalWeight).roundToInt() else 0
            measurables[i].measure(Constraints.fixed(w, share))
        }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else fixedSum + flexPlaceables.values.sumOf { it.height } + gapPx * (included.size - 1)
        layout(w, height) {
            var y = 0
            parts.indices.filter { it in included }.forEach { i ->
                val pl = fixedPlaceables[i] ?: flexPlaceables[i]!!
                pl.place(0, y)
                y += pl.height + gapPx
            }
        }
    }
}

/** True when chips should put the label above the value (narrow tiles) instead of beside it. */
val LocalChipStacked = compositionLocalOf { false }

/**
 * Stat chips in an even grid: every row is filled edge to edge with equal-width chips and rows hold the same
 * number of chips (so one never hangs on its own). If side-by-side "label value" chips can't do that, the
 * chips switch to label-over-value tiles, which are narrower.
 */
@Composable
fun ChipGrid(modifier: Modifier = Modifier, hGap: Dp = 4.dp, vGap: Dp = 4.dp, content: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val w = constraints.maxWidth
        val gx = hGap.roundToPx()
        val gy = vGap.roundToPx()

        // cols, rows, and whether it's a clean grid (fits, and no single chip stranded on the last row).
        fun plan(widths: List<Int>): Triple<Int, Int, Boolean> {
            val n = widths.size
            if (n == 0) return Triple(1, 0, true)
            val widest = widths.max()
            var maxCols = 1
            for (c in n downTo 1) if ((w - gx * (c - 1)) / c >= widest) { maxCols = c; break }
            val rows = ceil(n / maxCols.toDouble()).toInt()
            val cols = ceil(n / rows.toDouble()).toInt()
            val last = n - (rows - 1) * cols
            val fits = (w - gx * (cols - 1)) / cols >= widest
            // A row with a single chip under fuller rows reads as a leftover; so does one chip per row.
            val clean = fits && !(rows > 1 && cols > 1 && last == 1) && !(n > 1 && cols == 1)
            return Triple(cols, rows, clean)
        }

        val inlineW = subcompose("inline") { CompositionLocalProvider(LocalChipStacked provides false) { content() } }
            .map { it.measure(Constraints()).width }
        val tileW = subcompose("stacked-probe") { CompositionLocalProvider(LocalChipStacked provides true) { content() } }
            .map { it.measure(Constraints()).width }
        // Chips come in order of importance. Use the most that make a clean grid (side-by-side chips if they're
        // at least as compact as tiles, else tiles). If that would mean leaving out more than two, show them
        // all as tiles anyway.
        var keep = inlineW.size
        var stacked = true
        var cols = 1
        run {
            for (k in inlineW.size downTo maxOf(1, inlineW.size - 2)) {
                val a = plan(inlineW.take(k)); val b = plan(tileW.take(k))
                if (a.third && (!b.third || a.second <= b.second)) { keep = k; stacked = false; cols = a.first; return@run }
                if (b.third) { keep = k; stacked = true; cols = b.first; return@run }
            }
            keep = tileW.size; stacked = true; cols = plan(tileW).first
        }
        val items = subcompose("real") { CompositionLocalProvider(LocalChipStacked provides stacked) { content() } }.take(keep)
        val n = items.size
        if (n == 0) return@SubcomposeLayout layout(w, 0) {}
        val rows = ceil(n / cols.toDouble()).toInt()
        val placed = mutableListOf<Triple<androidx.compose.ui.layout.Placeable, Int, Int>>()
        var y = 0
        for (r in 0 until rows) {
            val rowItems = items.subList(r * cols, minOf(n, (r + 1) * cols))
            val each = ((w - gx * (rowItems.size - 1)) / rowItems.size).coerceAtLeast(0)
            val ps = rowItems.map { it.measure(Constraints(minWidth = each, maxWidth = each)) }
            val rowH = ps.maxOf { it.height }
            var x = 0
            ps.forEach { p -> placed += Triple(p, x, y + (rowH - p.height) / 2); x += each + gx }
            y += rowH + gy
        }
        layout(w, (y - gy).coerceAtLeast(0)) { placed.forEach { (p, x, py) -> p.place(x, py) } }
    }
}

/** Shows [content] only if it fits the width it's given in full; otherwise shows nothing (no "Conn…" stubs). */
@Composable
fun FitOrHide(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val ps = measurables.map { it.measure(Constraints()) }
        val width = ps.maxOfOrNull { it.width } ?: 0
        val height = ps.maxOfOrNull { it.height } ?: 0
        if (width > constraints.maxWidth) layout(0, 0) {}
        else layout(width.coerceAtLeast(constraints.minWidth), height) { ps.forEach { it.place(constraints.minWidth.coerceAtLeast(width) - it.width, (height - it.height) / 2) } }
    }
}
