package com.pulsepointlabs.sarahmonitor.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import com.pulsepointlabs.sarahmonitor.ui.drawLabel
import com.pulsepointlabs.sarahmonitor.ui.niceTicks
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class SeriesStyle { Line, Area, Dots }

/** A series as parallel arrays (fast for 10 Hz pressure); NaN = no value. */
class ReviewSeries(
    val label: String,
    val color: Color,
    val timestamps: LongArray,
    val values: DoubleArray,
    val style: SeriesStyle = SeriesStyle.Line,
    val gapMs: Long = 5_000L
) {
    fun valueAt(t: Long, maxDistanceMs: Long): Double? {
        if (timestamps.isEmpty()) return null
        var i = timestamps.binarySearch(t)
        if (i < 0) i = -i - 1
        val candidates = listOf(i - 1, i).filter { it in timestamps.indices && !values[it].isNaN() }
        val best = candidates.minByOrNull { abs(timestamps[it] - t) } ?: return null
        return if (abs(timestamps[best] - t) <= maxDistanceMs) values[best] else null
    }
}

data class ChartMarker(val timestampMs: Long, val color: Color, val selected: Boolean)

/** A shaded time range on a graph (automatic flags). */
data class ChartBand(val startMs: Long, val endMs: Long, val color: Color, val selected: Boolean = false)

/** Shared view of the session timeline: what's visible and where the cursor is. */
data class Viewport(val startMs: Long, val endMs: Long) {
    val spanMs: Long get() = (endMs - startMs).coerceAtLeast(1L)

    fun zoomed(factor: Double, anchorMs: Long, fullStart: Long, fullEnd: Long): Viewport {
        val fullSpan = (fullEnd - fullStart).coerceAtLeast(1L)
        val span = (spanMs * factor).toLong().coerceIn(10_000L.coerceAtMost(fullSpan), fullSpan)
        val ratio = (anchorMs - startMs).toDouble() / spanMs
        val start = (anchorMs - span * ratio).toLong()
        return Viewport(start, start + span).clampTo(fullStart, fullEnd)
    }

    fun centeredOn(t: Long, spanMs: Long, fullStart: Long, fullEnd: Long): Viewport {
        val span = spanMs.coerceAtMost((fullEnd - fullStart).coerceAtLeast(1L))
        return Viewport(t - span / 2, t + span / 2).clampTo(fullStart, fullEnd)
    }

    fun clampTo(fullStart: Long, fullEnd: Long): Viewport {
        val span = spanMs.coerceAtMost((fullEnd - fullStart).coerceAtLeast(1L))
        val start = startMs.coerceIn(fullStart, (fullEnd - span).coerceAtLeast(fullStart))
        return Viewport(start, start + span)
    }
}

private val clockFmt = DateTimeFormatter.ofPattern("h:mm a")
private val clockSecFmt = DateTimeFormatter.ofPattern("h:mm:ss")

fun clockLabel(ms: Long, seconds: Boolean = false): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(if (seconds) clockSecFmt else clockFmt)

/**
 * One review graph. Every graph uses the same left gutter, time scale, marker lines and cursor, so stacked
 * graphs line up exactly. Tap or drag sideways to move the cursor; mouse wheel zooms (DeX).
 */
@Composable
fun ReviewChart(
    title: String,
    unit: String,
    series: List<ReviewSeries>,
    viewport: Viewport,
    markers: List<ChartMarker>,
    cursorMs: Long?,
    onCursor: (Long) -> Unit,
    onZoom: (factor: Double, anchorMs: Long) -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    minSpan: Double = 10.0,
    decimals: Int = 0,
    fixedBounds: Pair<Double, Double>? = null,
    refLines: List<Pair<Double, String>> = emptyList(),
    showTimeAxis: Boolean = true,
    emptyText: String = "No data in this range",
    bands: List<ChartBand> = emptyList(),
    /** Fill the height the caller gives (a weighted slot) instead of [height]. */
    fillHeight: Boolean = false,
    /** Extra tooltip lines for a moment (the event / flag / episode under it). */
    describe: ((Long) -> List<Pair<String, Color>>)? = null
) {
    // Hover (mouse / DeX) shows a tooltip at the pointer; on touch it follows the tapped cursor.
    var hoverX by remember { mutableStateOf<Float?>(null) }
    val vp by rememberUpdatedState(viewport)
    val cursorCb by rememberUpdatedState(onCursor)
    val zoomCb by rememberUpdatedState(onZoom)
    val identity = series.firstOrNull()?.color ?: MonitorColors.label
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(identity.copy(alpha = 0.08f), MonitorColors.panel)), shape)
            .border(1.dp, identity.copy(alpha = 0.3f), shape)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(width = 4.dp, height = 14.dp).background(identity, RoundedCornerShape(2.dp)))
            Text(title, color = identity, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = MonitorColors.label, fontSize = 12.sp)
            Box(Modifier.weight(1f))
            if (series.size > 1) series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 12.dp, height = 3.dp).background(s.color, RoundedCornerShape(2.dp)))
                    Text(" ${s.label}", color = s.color, fontSize = 12.sp)
                }
            }
        }
        val visibleValues = series.flatMap { s ->
            val from = lowerBound(s.timestamps, viewport.startMs)
            val to = lowerBound(s.timestamps, viewport.endMs + 1)
            (from until to).map { s.values[it] }.filter { !it.isNaN() }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(if (fillHeight) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(height)) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                when (e.type) {
                                    PointerEventType.Move, PointerEventType.Enter -> if (e.changes.none { it.pressed }) hoverX = e.changes.first().position.x
                                    PointerEventType.Exit -> hoverX = null
                                    else -> Unit
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { pos -> cursorCb(timeAt(pos.x, size.width.toFloat(), vp)) }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            cursorCb(timeAt(change.position.x, size.width.toFloat(), vp))
                        }
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll) {
                                    val change = event.changes.first()
                                    val dy = change.scrollDelta.y
                                    if (dy != 0f) {
                                        zoomCb(if (dy > 0) 1.25 else 0.8, timeAt(change.position.x, size.width.toFloat(), vp))
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
            ) {
                drawChart(series, viewport, markers, cursorMs, visibleValues, minSpan, decimals, fixedBounds, refLines, showTimeAxis, bands)
            }
            if (visibleValues.isEmpty()) {
                Text(emptyText, color = MonitorColors.dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
            }
            // Tooltip: at the hovered point, else at the cursor.
            val tipT = hoverX?.let { timeAt(it, widthPx, viewport) } ?: cursorMs?.takeIf { it in viewport.startMs..viewport.endMs }
            if (tipT != null) {
                val lines = buildList {
                    add(clockLabel(tipT, seconds = true) to MonitorColors.text)
                    series.forEach { s -> s.valueAt(tipT, maxOf(s.gapMs, 5_000L))?.let { v -> add("${s.label} ${if (decimals == 0) "%.0f".format(v) else "%.${decimals}f".format(v)}" to s.color) } }
                    describe?.invoke(tipT)?.let { addAll(it) }
                }
                val density = androidx.compose.ui.platform.LocalDensity.current
                val gutter = with(density) { GUTTER_DP.dp.toPx() }
                val x = hoverX ?: (gutter + (tipT - viewport.startMs).toFloat() / viewport.spanMs.toFloat() * (widthPx - gutter))
                // Fixed width so it can be placed exactly: right of the point, or left of it near the right edge.
                val tipW = with(density) { 210.dp.toPx() }
                val gap = with(density) { 10.dp.toPx() }
                val left = (if (x + gap + tipW <= widthPx) x + gap else x - gap - tipW).coerceIn(0f, (widthPx - tipW).coerceAtLeast(0f))
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset { androidx.compose.ui.unit.IntOffset(left.toInt(), 4) }
                        .width(210.dp)
                        .background(Color(0xF00B1520), RoundedCornerShape(6.dp))
                        .border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    lines.forEach { (text, color) -> Text(text, color = color, fontSize = 11.sp, fontWeight = if (color == MonitorColors.text) FontWeight.Bold else FontWeight.Normal, lineHeight = 13.sp) }
                }
            }
        }
    }
}

const val GUTTER_DP = 40f

private fun timeAt(x: Float, width: Float, vp: Viewport): Long {
    // Pointer coordinates are in px; the gutter is in dp but a small mismatch only shifts the cursor slightly.
    val frac = (x / width).coerceIn(0f, 1f)
    return vp.startMs + (vp.spanMs * frac).toLong()
}

fun lowerBound(a: LongArray, t: Long): Int {
    var i = a.binarySearch(t)
    if (i < 0) i = -i - 1
    return i
}

private fun DrawScope.drawChart(
    series: List<ReviewSeries>,
    vp: Viewport,
    markers: List<ChartMarker>,
    cursorMs: Long?,
    visibleValues: List<Double>,
    minSpan: Double,
    decimals: Int,
    fixedBounds: Pair<Double, Double>?,
    refLines: List<Pair<Double, String>>,
    showTimeAxis: Boolean,
    bands: List<ChartBand> = emptyList()
) {
    val labelSp = 11f
    val labelPx = labelSp * density * fontScale
    val left = 0f
    val right = size.width
    val top = labelPx * 0.6f
    val bottom = size.height - if (showTimeAxis) labelPx * 1.5f else 2f
    if (bottom <= top) return
    val (lo, hi) = fixedBounds ?: run {
        if (visibleValues.isEmpty()) 0.0 to minSpan else {
            var a = visibleValues.min()
            var b = visibleValues.max()
            refLines.forEach { (v, _) -> a = min(a, v); b = max(b, v) }
            if (b - a < minSpan) { val mid = (a + b) / 2; a = mid - minSpan / 2; b = mid + minSpan / 2 }
            val pad = (b - a) * 0.08
            (a - pad) to (b + pad)
        }
    }
    val span = max(1e-9, hi - lo)
    fun xFor(t: Long) = left + (t - vp.startMs).toFloat() / vp.spanMs.toFloat() * (right - left)
    fun yFor(v: Double) = bottom - ((v - lo) / span).toFloat() * (bottom - top)

    // Automatic flags: a soft band over the time range plus a stronger strip along the top.
    bands.forEach { b ->
        if (b.endMs < vp.startMs || b.startMs > vp.endMs) return@forEach
        val x0 = xFor(b.startMs)
        val x1 = max(xFor(b.endMs), x0 + 5f * density)
        drawRect(b.color.copy(alpha = if (b.selected) 0.22f else 0.11f), Offset(x0, top), Size(x1 - x0, bottom - top))
        drawRect(b.color.copy(alpha = if (b.selected) 0.95f else 0.65f), Offset(x0, top), Size(x1 - x0, 3f * density))
    }
    // Grid + y labels (drawn inside the plot so stacked charts share the same x scale exactly).
    // As many ticks as fit without labels touching (about one per 1.8 label heights).
    val tickCount = ((bottom - top) / (labelPx * 1.8f)).toInt().coerceIn(1, 4)
    niceTicks(lo, hi, tickCount).forEach { v ->
        val y = yFor(v)
        drawLine(MonitorColors.trendGrid, Offset(left, y), Offset(right, y), strokeWidth = 1f)
        drawLabel(com.pulsepointlabs.sarahmonitor.session.SessionAnalysis.num(v, if (span < 4 && decimals > 0) 1 else 0), left + 3f * density, y - 3f * density, MonitorColors.dim.toArgb(), labelSp)
    }
    if (showTimeAxis) {
        timeTicks(vp.startMs, vp.endMs).forEach { t ->
            val x = xFor(t)
            drawLine(MonitorColors.trendGrid, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
            // Labels too close to an edge would be clipped; the grid line still shows.
            if (x > 34f * density && x < size.width - 34f * density) {
                drawLabel(clockLabel(t, seconds = vp.spanMs < 5 * 60_000L), x, size.height - 2f * density, MonitorColors.label.toArgb(), labelSp, center = true)
            }
        }
    }
    refLines.forEach { (v, label) ->
        if (v !in lo..hi) return@forEach
        val y = yFor(v)
        drawLine(MonitorColors.label.copy(alpha = 0.6f), Offset(left, y), Offset(right, y), strokeWidth = 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        drawLabel(label, right - 4f * density, y - 3f * density, MonitorColors.label.toArgb(), labelSp, alignRight = true)
    }
    markers.forEach { m ->
        if (m.timestampMs !in vp.startMs..vp.endMs) return@forEach
        val x = xFor(m.timestampMs)
        drawLine(m.color.copy(alpha = if (m.selected) 0.95f else 0.55f), Offset(x, top), Offset(x, bottom), strokeWidth = if (m.selected) 2.5f else 1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
        drawCircle(m.color, radius = 3.5f * density, center = Offset(x, top + 2f * density))
    }
    series.forEach { s -> drawSeries(s, vp, ::xFor, ::yFor, bottom, right - left) }
    cursorMs?.takeIf { it in vp.startMs..vp.endMs }?.let { t ->
        val x = xFor(t)
        drawLine(Color.White.copy(alpha = 0.75f), Offset(x, top), Offset(x, bottom), strokeWidth = 1.4f)
        series.forEach { s ->
            s.valueAt(t, max(s.gapMs, vp.spanMs / 200))?.let { v -> drawCircle(s.color, radius = 4f * density, center = Offset(x, yFor(v))) }
        }
    }
}

/** Draws a series, reducing dense data to a min/max envelope per pixel column so long sessions stay fast. */
private fun DrawScope.drawSeries(s: ReviewSeries, vp: Viewport, xFor: (Long) -> Float, yFor: (Double) -> Float, bottom: Float, width: Float) {
    val from = (lowerBound(s.timestamps, vp.startMs) - 1).coerceAtLeast(0)
    val to = (lowerBound(s.timestamps, vp.endMs + 1) + 1).coerceAtMost(s.timestamps.size)
    if (to - from <= 0) return
    if (s.style == SeriesStyle.Dots) {
        for (i in from until to) {
            if (s.values[i].isNaN()) continue
            val c = Offset(xFor(s.timestamps[i]), yFor(s.values[i]))
            drawCircle(s.color, radius = 4.5f * density / 2f + 2f, center = c)
            drawCircle(MonitorColors.panel, radius = 1.6f * density / 2f + 0.5f, center = c)
        }
        return
    }
    val path = Path()
    val fill = Path()
    val dense = (to - from) > width * 2
    var drawing = false
    var lastT = Long.MIN_VALUE
    var segmentStartX = 0f
    var lastX = 0f
    fun closeFill() {
        if (s.style == SeriesStyle.Area && drawing) {
            fill.lineTo(lastX, bottom); fill.lineTo(segmentStartX, bottom); fill.close()
        }
    }
    if (!dense) {
        for (i in from until to) {
            val v = s.values[i]
            val t = s.timestamps[i]
            if (v.isNaN() || (drawing && t - lastT > s.gapMs)) { closeFill(); drawing = false }
            if (v.isNaN()) continue
            val x = xFor(t); val y = yFor(v)
            if (!drawing) { path.moveTo(x, y); if (s.style == SeriesStyle.Area) { fill.moveTo(x, y); segmentStartX = x }; drawing = true }
            else { path.lineTo(x, y); if (s.style == SeriesStyle.Area) fill.lineTo(x, y) }
            lastT = t; lastX = x
        }
    } else {
        // Envelope: per pixel column, line through min and max.
        var col = Int.MIN_VALUE
        var cMin = 0.0; var cMax = 0.0; var cT = 0L
        fun emit() {
            if (col == Int.MIN_VALUE) return
            val x = col.toFloat()
            if (!drawing) { path.moveTo(x, yFor(cMin)); if (s.style == SeriesStyle.Area) { fill.moveTo(x, yFor(cMax)); segmentStartX = x }; drawing = true }
            else path.lineTo(x, yFor(cMin))
            path.lineTo(x, yFor(cMax))
            if (s.style == SeriesStyle.Area) fill.lineTo(x, yFor(cMax))
            lastX = x
        }
        for (i in from until to) {
            val v = s.values[i]
            val t = s.timestamps[i]
            if (v.isNaN() || (lastT != Long.MIN_VALUE && t - lastT > s.gapMs)) { emit(); col = Int.MIN_VALUE; closeFill(); drawing = false }
            if (v.isNaN()) continue
            val c = xFor(t).toInt()
            if (c != col) { emit(); col = c; cMin = v; cMax = v } else { cMin = min(cMin, v); cMax = max(cMax, v) }
            cT = t; lastT = t
        }
        emit()
    }
    closeFill()
    if (s.style == SeriesStyle.Area) drawPath(fill, s.color.copy(alpha = 0.14f))
    drawPath(path, s.color, style = Stroke(width = 2f * density / 2f + 1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun timeTicks(start: Long, end: Long, maxLines: Int = 6): List<Long> {
    val span = (end - start).coerceAtLeast(1L)
    val steps = longArrayOf(5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000, 7_200_000, 14_400_000)
    val step = steps.firstOrNull { span / it <= maxLines } ?: steps.last()
    val offset = java.util.TimeZone.getDefault().getOffset(start).toLong()
    val first = ((start + offset + step - 1) / step) * step - offset
    return generateSequence(first) { it + step }.takeWhile { it <= end }.toList()
}

/** Whole-session overview with the visible window highlighted; drag it to pan. */
@Composable
fun OverviewStrip(
    series: ReviewSeries?,
    fullStart: Long,
    fullEnd: Long,
    viewport: Viewport,
    markers: List<ChartMarker>,
    onViewport: (Viewport) -> Unit,
    modifier: Modifier = Modifier
) {
    val vp by rememberUpdatedState(viewport)
    val cb by rememberUpdatedState(onViewport)
    val full = Viewport(fullStart, fullEnd)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(MonitorColors.panel, RoundedCornerShape(8.dp))
            .border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(8.dp))
            .pointerInput(fullStart, fullEnd) {
                detectTapGestures { pos ->
                    val t = fullStart + ((fullEnd - fullStart) * (pos.x / size.width)).toLong()
                    cb(vp.centeredOn(t, vp.spanMs, fullStart, fullEnd))
                }
            }
            .pointerInput(fullStart, fullEnd) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    val shift = ((fullEnd - fullStart) * (dx / size.width)).toLong()
                    cb(Viewport(vp.startMs + shift, vp.endMs + shift).clampTo(fullStart, fullEnd))
                }
            }
    ) {
        val w = size.width
        val h = size.height
        fun x(t: Long) = (t - fullStart).toFloat() / (fullEnd - fullStart).coerceAtLeast(1L) * w
        series?.let { s ->
            val vals = s.values.filter { !it.isNaN() }
            if (vals.isNotEmpty()) {
                val lo = vals.min(); val hi = max(vals.max(), lo + 1)
                drawSeries(s, full, { x(it) }, { v -> h - 4f - ((v - lo) / (hi - lo)).toFloat() * (h - 8f) }, h, w)
            }
        }
        markers.forEach { m -> drawLine(m.color.copy(alpha = 0.8f), Offset(x(m.timestampMs), 0f), Offset(x(m.timestampMs), h), strokeWidth = 1.5f) }
        val vx0 = x(viewport.startMs)
        val vx1 = max(x(viewport.endMs), vx0 + 4f)
        drawRect(Color.Black.copy(alpha = 0.45f), Offset(0f, 0f), Size(vx0, h))
        drawRect(Color.Black.copy(alpha = 0.45f), Offset(vx1, 0f), Size(w - vx1, h))
        drawRect(Color.White.copy(alpha = 0.8f), Offset(vx0, 1f), Size(vx1 - vx0, h - 2f), style = Stroke(width = 2f))
    }
}
