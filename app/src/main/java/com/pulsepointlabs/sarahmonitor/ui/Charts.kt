package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import com.pulsepointlabs.sarahmonitor.ble.EcgSample
import com.pulsepointlabs.sarahmonitor.core.PelvicPoint
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private class SweepState {
    var low = Float.NaN
    var high = Float.NaN
    var lagMs = Float.NaN
    var lastLatestTs = Long.MIN_VALUE
}

/** 25 mm/s sweeping ECG with monitor filter and 1 mV cal pulse (from the SarahVS monitor). */
@Composable
fun EcgSweep(samples: List<EcgSample>, modifier: Modifier = Modifier) {
    val s = LocalCardScale.current
    val state = remember { SweepState() }
    val trace = remember(samples) {
        EcgDisplayFilter.prepare(samples.takeLast(900), smooth = true)?.also { t ->
            if (state.low.isNaN()) { state.low = t.low; state.high = t.high } else {
                state.low += (t.low - state.low) * 0.2f
                state.high += (t.high - state.high) * 0.2f
            }
            val latest = t.timestampsMs.last()
            if (latest != state.lastLatestTs) {
                state.lastLatestTs = latest
                val lag = (System.currentTimeMillis() - latest).toFloat()
                state.lagMs = when {
                    state.lagMs.isNaN() -> lag
                    lag > state.lagMs -> lag
                    else -> state.lagMs * 0.97f + lag * 0.03f
                }
            }
        }
    }
    var frameNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(trace != null) {
        while (isActive && trace != null) withFrameMillis { frameNow = System.currentTimeMillis() }
    }
    val labelSize = s.sp(11f).value
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawEcgPaper()
            val t = trace ?: return@Canvas
            if (t.size < 2) return@Canvas
            val pxPerMs = (25f * (160f / 25.4f) * density) / 1000f
            val sweepMs = (size.width / pxPerMs).toLong().coerceIn(2_000L, 8_000L)
            val pxPerSweepMs = size.width / sweepMs.toFloat()
            val latest = t.timestampsMs.last()
            val lag = if (state.lagMs.isNaN()) 0f else state.lagMs
            val head = min(latest, frameNow - lag.toLong() - 60L).coerceAtLeast(t.timestampsMs.first())
            val eraseMs = (sweepMs * 0.04f).toLong().coerceAtLeast(120L)
            val low = if (state.low.isNaN()) t.low else state.low
            val high = if (state.high.isNaN()) t.high else max(state.high, low + 1f)
            val top = size.height * 0.08f
            val usable = size.height * 0.80f
            fun yFor(v: Float): Float = top + usable * (1f - ((v - low) / (high - low)).coerceIn(0f, 1f))
            val path = Path()
            var previousX = -1f
            var started = false
            val from = head - sweepMs + eraseMs
            for (i in 0 until t.size) {
                val ts = t.timestampsMs[i]
                if (ts < from) continue
                if (ts > head) break
                val x = Math.floorMod(ts, sweepMs).toFloat() * pxPerSweepMs
                val y = yFor(t.values[i])
                if (!started || t.breakBefore[i] || x < previousX) path.moveTo(x, y) else path.lineTo(x, y)
                started = true
                previousX = x
            }
            drawPath(path, MonitorColors.ecg, style = Stroke(width = 2.2f * density / 2f + 1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val headIndex = t.timestampsMs.indexOfLast { it <= head }
            if (headIndex >= 0) {
                val hx = Math.floorMod(t.timestampsMs[headIndex], sweepMs).toFloat() * pxPerSweepMs
                drawCircle(MonitorColors.ecg, radius = 3.5f * density / 2f + 1f, center = Offset(hx, yFor(t.values[headIndex])))
            }
            val oneMvPx = usable / (high - low) * 1000f
            if (oneMvPx in 8f..usable) {
                val baseY = yFor(0f)
                val x0 = 4f * density
                val w = 8f * density
                val cal = Path().apply {
                    moveTo(x0, baseY); lineTo(x0 + w * 0.25f, baseY); lineTo(x0 + w * 0.25f, baseY - oneMvPx)
                    lineTo(x0 + w * 0.75f, baseY - oneMvPx); lineTo(x0 + w * 0.75f, baseY); lineTo(x0 + w, baseY)
                }
                drawPath(cal, MonitorColors.ecg.copy(alpha = 0.6f), style = Stroke(width = 1.5f * density / 2f + 0.5f))
            }
            drawLabel("ECG · 25 mm/s · 1 mV cal", 10f * density, size.height - 4f * density, MonitorColors.label.toArgb(), labelSize)
        }
        if (trace == null) {
            Text("Waiting for H10 ECG…", color = MonitorColors.warn, fontSize = s.sp(15f), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
    }
}

private fun DrawScope.drawEcgPaper() {
    val mm = (160f / 25.4f) * density
    if (mm < 2f) return
    var x = 0f
    var i = 0
    while (x <= size.width) {
        val major = i % 5 == 0
        drawLine(if (major) MonitorColors.gridMajor else MonitorColors.gridMinor, Offset(x, 0f), Offset(x, size.height), strokeWidth = if (major) 1.2f else 0.6f)
        x += mm; i++
    }
    var y = 0f
    i = 0
    while (y <= size.height) {
        val major = i % 5 == 0
        drawLine(if (major) MonitorColors.gridMajor else MonitorColors.gridMinor, Offset(0f, y), Offset(size.width, y), strokeWidth = if (major) 1.2f else 0.6f)
        y += mm; i++
    }
}

fun DrawScope.drawLabel(text: String, x: Float, y: Float, color: Int, sizeSp: Float, alignRight: Boolean = false, center: Boolean = false) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = color
        textSize = sizeSp * density * fontScale
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = when {
            center -> android.graphics.Paint.Align.CENTER
            alignRight -> android.graphics.Paint.Align.RIGHT
            else -> android.graphics.Paint.Align.LEFT
        }
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

data class Series(val label: String, val color: Color, val points: List<Pair<Long, Double?>>)

data class RefLine(val value: Double, val label: String, val color: Color)

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayClock: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")

/**
 * Time-series line chart in the monitor style. Gaps longer than [gapMs] are left open. [dots] draws each
 * point (for sparse readings such as BP and glucose).
 */
@Composable
fun TrendChart(
    series: List<Series>,
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier,
    minSpan: Double = 10.0,
    gapMs: Long = 30_000L,
    decimals: Int = 0,
    refLines: List<RefLine> = emptyList(),
    fixedBounds: Pair<Double, Double>? = null,
    dots: Boolean = false,
    emptyText: String = "Collecting…"
) {
    val s = LocalCardScale.current
    val labelSp = s.sp(11f).value
    val values = series.flatMap { it.points.filter { p -> p.first in startMs..endMs }.mapNotNull { p -> p.second } } +
        refLines.map { it.value }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val labelPx = labelSp * density * fontScale
            val left = labelPx * 3.2f
            val right = size.width - 6f * density
            val top = labelPx * 0.8f
            val bottom = size.height - labelPx * 1.6f
            if (right <= left || bottom <= top) return@Canvas
            val (lo, hi) = fixedBounds ?: run {
                if (values.isEmpty()) return@run 0.0 to minSpan
                var a = values.min()
                var b = values.max()
                if (b - a < minSpan) { val mid = (a + b) / 2; a = mid - minSpan / 2; b = mid + minSpan / 2 }
                val pad = (b - a) * 0.1
                (a - pad) to (b + pad)
            }
            val span = max(1e-6, hi - lo)
            fun xFor(t: Long) = left + (t - startMs).toFloat() / max(1L, endMs - startMs).toFloat() * (right - left)
            fun yFor(v: Double) = bottom - ((v - lo) / span).toFloat() * (bottom - top)
            val ticks = niceTicks(lo, hi, 4)
            ticks.forEach { v ->
                val y = yFor(v)
                drawLine(MonitorColors.trendGrid, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                drawLabel("%.${if (span < 3) 1 else 0}f".format(v), left - 4f * density, y + labelPx * 0.35f, MonitorColors.label.toArgb(), labelSp, alignRight = true)
            }
            val fmt = if (endMs - startMs > 20 * 60 * 60 * 1000L) dayClock else clock
            listOf(startMs, (startMs + endMs) / 2, endMs).forEachIndexed { i, t ->
                val text = if (i == 2 && System.currentTimeMillis() - endMs < 60_000L) "now" else Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(fmt)
                drawLabel(text, xFor(t), size.height - 2f * density, MonitorColors.label.toArgb(), labelSp, alignRight = i == 2, center = i == 1)
            }
            refLines.forEach { ref ->
                if (ref.value !in lo..hi) return@forEach
                val y = yFor(ref.value)
                drawLine(ref.color.copy(alpha = 0.7f), Offset(left, y), Offset(right, y), strokeWidth = 1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                drawLabel(ref.label, right - 4f * density, y - 3f * density, ref.color.toArgb(), labelSp, alignRight = true)
            }
            series.forEach { line ->
                val path = Path()
                var previous: Long? = null
                line.points.forEach { (t, v) ->
                    if (t < startMs || t > endMs) return@forEach
                    if (v == null) { previous = null; return@forEach }
                    val x = xFor(t)
                    val y = yFor(v)
                    val p = previous
                    if (p == null || t - p > gapMs) path.moveTo(x, y) else path.lineTo(x, y)
                    previous = t
                    if (dots) drawCircle(line.color, radius = 3.2f * density / 2f + 1.5f, center = Offset(x, y))
                }
                drawPath(path, line.color, style = Stroke(width = 2.2f * density / 2f + 1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (values.isEmpty()) {
            Text(emptyText, color = MonitorColors.dim, fontSize = s.sp(13f), modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** Live pressure-above-rest waveform with the hold threshold and the rest line. */
@Composable
fun PelvicWaveform(
    points: List<PelvicPoint>,
    thresholdKpa: Double,
    referenceKpa: Double?,
    windowMs: Long,
    modifier: Modifier = Modifier
) {
    val s = LocalCardScale.current
    val labelSp = s.sp(11f).value
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val labelPx = labelSp * density * fontScale
            val left = labelPx * 2.8f
            val right = size.width - 4f * density
            val top = labelPx * 0.6f
            val bottom = size.height - labelPx * 1.4f
            if (right <= left || bottom <= top) return@Canvas
            val end = points.lastOrNull()?.timestampMs ?: return@Canvas
            val start = end - windowMs
            val visible = points.filter { it.timestampMs >= start && it.aboveRestKpa != null }
            val maxV = max(max(1.0, (referenceKpa ?: 0.0) * 1.1), visible.maxOfOrNull { it.aboveRestKpa!! } ?: 1.0)
            val minV = min(-0.3, visible.minOfOrNull { it.aboveRestKpa!! } ?: 0.0)
            val hi = ceil(maxV * 2) / 2
            val lo = floor(minV * 2) / 2
            fun xFor(t: Long) = left + (t - start).toFloat() / windowMs.toFloat() * (right - left)
            fun yFor(v: Double) = bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top)
            niceTicks(lo, hi, 4).forEach { v ->
                val y = yFor(v)
                drawLine(MonitorColors.trendGrid, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                drawLabel("%.1f".format(v), left - 4f * density, y + labelPx * 0.35f, MonitorColors.label.toArgb(), labelSp, alignRight = true)
            }
            drawLine(MonitorColors.label.copy(alpha = 0.6f), Offset(left, yFor(0.0)), Offset(right, yFor(0.0)), strokeWidth = 1.2f)
            val ty = yFor(thresholdKpa)
            drawLine(MonitorColors.warn.copy(alpha = 0.6f), Offset(left, ty), Offset(right, ty), strokeWidth = 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            referenceKpa?.let {
                val ry = yFor(it)
                drawLine(MonitorColors.pelvic.copy(alpha = 0.55f), Offset(left, ry), Offset(right, ry), strokeWidth = 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
                drawLabel("100%", right - 2f * density, ry - 3f * density, MonitorColors.pelvic.toArgb(), labelSp, alignRight = true)
            }
            val path = Path()
            val fill = Path()
            var started = false
            var firstX = 0f
            var lastX = 0f
            points.forEach { p ->
                if (p.timestampMs < start) return@forEach
                val v = p.aboveRestKpa ?: run { started = false; return@forEach }
                val x = xFor(p.timestampMs)
                val y = yFor(v)
                if (!started || p.gapBefore) {
                    path.moveTo(x, y)
                    if (!started) { fill.moveTo(x, yFor(0.0)); firstX = x }
                    fill.lineTo(x, y)
                } else {
                    path.lineTo(x, y); fill.lineTo(x, y)
                }
                started = true
                lastX = x
            }
            if (lastX > firstX) {
                fill.lineTo(lastX, yFor(0.0)); fill.close()
                drawPath(fill, MonitorColors.pelvic.copy(alpha = 0.12f))
            }
            drawPath(path, MonitorColors.pelvic, style = Stroke(width = 2.4f * density / 2f + 1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLabel("−${windowMs / 1000}s", left, size.height - 2f * density, MonitorColors.label.toArgb(), labelSp)
            drawLabel("now", right, size.height - 2f * density, MonitorColors.label.toArgb(), labelSp, alignRight = true)
        }
        if (points.none { it.aboveRestKpa != null }) {
            Text("Waiting for pressure…", color = MonitorColors.warn, fontSize = s.sp(14f), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
    }
}

fun niceTicks(min: Double, max: Double, maxTicks: Int): List<Double> {
    val span = max - min
    if (span <= 0) return listOf(min)
    val raw = span / maxTicks
    val mag = Math.pow(10.0, floor(Math.log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { span / it <= maxTicks }
    val first = ceil(min / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= max + 1e-9 }.toList()
}
