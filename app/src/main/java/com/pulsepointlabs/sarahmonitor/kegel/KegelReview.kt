package com.pulsepointlabs.sarahmonitor.kegel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import com.pulsepointlabs.sarahmonitor.ui.TrendArrows
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal val KegelPink = Color(0xFFFF7AB6)

/** Green when it went well, amber in between, red when it didn't (for scores and on-target shares). */
internal fun qualityColor(v: Int?): Color = when {
    v == null -> MonitorColors.label
    v >= 70 -> MonitorColors.good
    v >= 40 -> MonitorColors.warn
    else -> MonitorColors.alarm
}

private fun s1(v: Double?) = v?.let { "%.1f".format(it) } ?: "--"

/**
 * A finished Kegel set in detail: score tiles, a plain read, the whole set as one chart (hover or drag to see the
 * contraction under your finger as a block), a bar per contraction, and every contraction up close.
 * Full screen on phone and DeX.
 */
@Composable
fun KegelSetReview(record: KegelRecord, points: List<KegelPoint>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KegelSetReviewContent(record, points, onDismiss)
    }
}

/** The review itself (also rendered by the screenshot harness, which can't capture dialog windows). */
@Composable
fun KegelSetReviewContent(record: KegelRecord, points: List<KegelPoint>, onDismiss: () -> Unit) {
    val cs = remember(record, points) { KegelAnalysis.contractions(record, points) }
    var selected by remember { mutableStateOf<Contraction?>(cs.getOrNull(cs.size / 2)) }
    val hasRef = record.referenceKpa != null && record.referenceKpa > 0.3
    val unit = if (hasRef) "%" else "kPa"
    run {
        BoxWithConstraints(Modifier.fillMaxSize().background(MonitorColors.background).padding(12.dp)) {
            val wide = maxWidth >= 900.dp
            val header: @Composable () -> Unit = { Header(record, onDismiss) }
            val tiles: @Composable () -> Unit = { SummaryTiles(record, cs, unit) }
            val story: @Composable () -> Unit = { Panel { Text(record.story(), color = MonitorColors.text, fontSize = 15.sp, lineHeight = 22.sp) } }
            val chart: @Composable (Modifier) -> Unit = { m -> SetChart(points, cs, hasRef, selected, { selected = it }, m, overlayBlock = wide) }
            val bars: @Composable (Modifier) -> Unit = { m -> ContractionBars(cs, hasRef, selected, { selected = it }, m) }
            if (wide) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    header()
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            chart(Modifier.weight(1.6f).fillMaxWidth())
                            bars(Modifier.weight(0.8f).fillMaxWidth())
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            tiles(); story(); ContractionGrid(points, cs, hasRef, selected) { selected = it }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Phone: the chart leads; numbers and the read follow.
                    header()
                    chart(Modifier.fillMaxWidth().height(if (selected != null) 430.dp else 300.dp))
                    bars(Modifier.fillMaxWidth().height(160.dp))
                    tiles(); story()
                    ContractionGrid(points, cs, hasRef, selected) { selected = it }
                }
            }
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(MonitorColors.panel, RoundedCornerShape(12.dp)).border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content
    )
}

@Composable
private fun Header(r: KegelRecord, onDismiss: () -> Unit) {
    val when_ = Instant.ofEpochMilli(r.startMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE MMM d · h:mm a"))
    val dur = ((r.endMs - r.startMs) / 1000).toInt()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Kegel set · ${r.routineLabel}", color = KegelPink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("$when_ · ${dur / 60}:${"%02d".format(dur % 60)} · ${r.reps} reps" + if (r.stoppedEarly) " · stopped early" else "", color = MonitorColors.label, fontSize = 13.sp)
        }
        Text(
            "Close", color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

/** A labelled number tile: big value in its color, optional trend arrows (grow with the size of the shift). */
@Composable
internal fun KTile(label: String, value: String, color: Color, modifier: Modifier = Modifier, unit: String = "", trend: Double? = null) {
    Column(
        modifier.heightIn(min = 72.dp).background(color.copy(alpha = 0.10f), RoundedCornerShape(10.dp)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = MonitorColors.label, fontSize = 12.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
            if (unit.isNotEmpty() && value != "--") Text(unit, color = MonitorColors.label, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            trend?.let { TrendArrows(it, Modifier.width(20.dp).height(34.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryTiles(r: KegelRecord, cs: List<Contraction>, unit: String) {
    val onTarget = cs.mapNotNull { it.onTargetPct }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val rise = cs.mapNotNull { it.riseS }.takeIf { it.isNotEmpty() }?.average()
    val release = cs.mapNotNull { it.releaseS }.takeIf { it.isNotEmpty() }?.average()
    val steady = cs.mapNotNull { it.steadiness }.takeIf { it.isNotEmpty() }?.average()
    val peak = cs.mapNotNull { it.peak }.maxOrNull()
    val fatigue = KegelAnalysis.fatigueTrend(cs)
    val tiles = listOf<@Composable (Modifier) -> Unit>(
        { m -> KTile("Score", "${r.score}", qualityColor(r.score), m) },
        { m -> KTile("On target", onTarget?.toString() ?: "--", qualityColor(onTarget), m, "%") },
        { m -> KTile("Strength", r.strengthPct?.toString() ?: "--", KegelPink, m, "% ref", fatigue) },
        { m -> KTile("Best squeeze", peak?.roundToInt()?.toString() ?: "--", KegelPink, m, unit) },
        { m -> KTile("Relaxed", r.relaxPct?.toString() ?: "--", qualityColor(r.relaxPct), m, "%") },
        { m -> KTile("Time to target", s1(rise), if (rise == null) MonitorColors.label else if (rise <= 1.0) MonitorColors.good else if (rise <= 2.0) MonitorColors.warn else MonitorColors.alarm, m, "s") },
        { m -> KTile("Let go in", s1(release), if (release == null) MonitorColors.label else if (release <= 1.0) MonitorColors.good else if (release <= 2.0) MonitorColors.warn else MonitorColors.alarm, m, "s") },
        { m -> KTile("Wobble", steady?.let { "±" + it.roundToInt() } ?: "--", MonitorColors.sdnn, m, unit) }
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Even rows that fill the width: 4 across on wide screens, 2 on a phone.
        val cols = if (maxWidth >= 520.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { t -> t(Modifier.weight(1f)) } }
            }
        }
    }
}

/** Which contraction a moment belongs to: inside its squeeze, or in the relax right after it. */
private fun contractionAt(cs: List<Contraction>, t: Long): Contraction? =
    cs.firstOrNull { t in it.startMs..it.endMs } ?: cs.lastOrNull { it.endMs <= t }?.takeIf { c -> cs.none { it.startMs in c.endMs..t } }

@Composable
private fun SetChart(points: List<KegelPoint>, cs: List<Contraction>, hasRef: Boolean, selected: Contraction?, onSelect: (Contraction?) -> Unit, modifier: Modifier, overlayBlock: Boolean = true) {
    if (points.isEmpty()) {
        Panel(modifier) { Text("No pressure was recorded during this set.", color = MonitorColors.label, fontSize = 14.sp) }
        return
    }
    val from = (cs.firstOrNull()?.startMs ?: points.first().t) - 3_000L
    val to = (cs.lastOrNull()?.endMs ?: points.last().t) + 4_000L
    val shown = points.filter { it.t in from..to }
    val maxV = (shown.maxOfOrNull { it.v } ?: 1.0)
    val top = if (hasRef) maxOf(110.0, maxV * 1.08) else maxOf(1.0, maxV * 1.1)
    val bottom = minOf(0.0, shown.minOfOrNull { it.v } ?: 0.0)
    var widthPx by remember { mutableStateOf(1f) }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Panel(modifier) {
        Text("The whole set", color = MonitorColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text((if (hasRef) "% of your reference hold · dashed = target" else "kPa above rest (capture a reference for % and targets)") + " · drag or hover for a squeeze", color = MonitorColors.label, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().weight(1f, fill = true).heightIn(min = 160.dp).onSizeChanged { widthPx = it.width.toFloat() }) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(cs) {
                    // Touch drag and mouse hover (DeX) both move the readout.
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val p = e.changes.firstOrNull() ?: continue
                            if (e.type == PointerEventType.Move || e.type == PointerEventType.Press || p.pressed) {
                                val t = from + ((p.position.x / size.width) * (to - from)).toLong()
                                onSelect(contractionAt(cs, t))
                            }
                        }
                    }
                }
            ) {
                fun x(t: Long) = (t - from).toFloat() / (to - from) * size.width
                fun y(v: Double) = size.height - ((v - bottom) / (top - bottom)).toFloat() * size.height
                // Grid
                val ticks = if (hasRef) listOf(0.0, 25.0, 50.0, 75.0, 100.0) else listOf(0.0, top / 2, top * 0.95)
                ticks.forEach { v ->
                    drawLine(MonitorColors.trendGrid, Offset(0f, y(v)), Offset(size.width, y(v)), 1f)
                    val label = if (hasRef) "${v.roundToInt()}%" else "%.1f".format(v)
                    drawText(measurer, label, Offset(4f, y(v) - 16.sp.toPx()), androidx.compose.ui.text.TextStyle(color = MonitorColors.label, fontSize = 11.sp))
                }
                // Squeeze windows and targets
                cs.forEach { c ->
                    val on = c == selected
                    drawRect(KegelPink.copy(alpha = if (on) 0.26f else 0.10f), Offset(x(c.startMs), 0f), Size(x(c.endMs) - x(c.startMs), size.height))
                    if (hasRef) drawLine(MonitorColors.warn, Offset(x(c.startMs), y(c.targetPct.toDouble())), Offset(x(c.endMs), y(c.targetPct.toDouble())), 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
                }
                // Trace
                val path = Path(); var started = false; var last = 0L
                shown.forEach { p ->
                    if (!started || p.t - last > 1_500L) path.moveTo(x(p.t), y(p.v)) else path.lineTo(x(p.t), y(p.v))
                    started = true; last = p.t
                }
                drawPath(path, MonitorColors.text.copy(alpha = 0.92f), style = Stroke(2.4f, cap = StrokeCap.Round))
            }
            if (overlayBlock) selected?.let { c ->
                // The block for the contraction under the cursor, kept inside the chart.
                val density = LocalDensity.current
                val cx = (((c.startMs + c.endMs) / 2 - from).toFloat() / (to - from)) * widthPx
                val blockW = with(density) { 260.dp.toPx() }
                val left = (cx - blockW / 2).coerceIn(0f, (widthPx - blockW).coerceAtLeast(0f))
                Box(Modifier.offset { IntOffset(left.toInt(), 0) }.width(260.dp)) { ContractionBlock(c, hasRef) }
            }
        }
        // Narrow screens: the block sits under the chart so it never hides the trace.
        if (!overlayBlock) selected?.let { ContractionBlock(it, hasRef) }
    }
}

/** One contraction as a compact block of labelled values. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContractionBlock(c: Contraction, hasRef: Boolean) {
    val unit = if (hasRef) "%" else " kPa"
    Column(
        Modifier.background(MonitorColors.panel.copy(alpha = 0.96f), RoundedCornerShape(10.dp)).border(1.dp, KegelPink.copy(alpha = 0.8f), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Squeeze ${c.index} · rep ${c.rep}" + if (hasRef) " · target ${c.targetPct}%" else "", color = KegelPink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Peak", c.peak?.let { "${it.roundToInt()}$unit" }, KegelPink)
            Pill("Avg", c.mean?.let { "${it.roundToInt()}$unit" }, KegelPink)
            Pill("On target", c.onTargetPct?.let { "$it%" }, qualityColor(c.onTargetPct))
            Pill("Rise", c.riseS?.let { "${s1(it)} s" }, MonitorColors.sdnn)
            Pill("Wobble", c.steadiness?.let { "±${it.roundToInt()}$unit" }, MonitorColors.sdnn)
            Pill("Let go", c.releaseS?.let { "${s1(it)} s" } ?: "not fully", if (c.releaseS == null) MonitorColors.alarm else MonitorColors.pelvic)
        }
    }
}

@Composable
private fun Pill(label: String, value: String?, color: Color) {
    Row(
        Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MonitorColors.label, fontSize = 12.sp, maxLines = 1)
        Text(value ?: "--", color = if (value == null) MonitorColors.dim else color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A bar per contraction (peak), colored by time on target; tap a bar to see it. */
@Composable
private fun ContractionBars(cs: List<Contraction>, hasRef: Boolean, selected: Contraction?, onSelect: (Contraction) -> Unit, modifier: Modifier) {
    Panel(modifier) {
        Text("Each squeeze", color = MonitorColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("height = peak · color = time on target · tap a bar", color = MonitorColors.label, fontSize = 12.sp)
        if (cs.isEmpty()) { Text("No squeezes in this set.", color = MonitorColors.label, fontSize = 14.sp); return@Panel }
        val top = maxOf(if (hasRef) 110.0 else 1.0, (cs.mapNotNull { it.peak }.maxOrNull() ?: 0.0) * 1.08)
        Canvas(
            Modifier.fillMaxWidth().weight(1f, fill = true).heightIn(min = 90.dp).pointerInput(cs) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val p = e.changes.firstOrNull() ?: continue
                        if (e.type == PointerEventType.Move || p.pressed) {
                            val i = (p.position.x / size.width * cs.size).toInt().coerceIn(0, cs.lastIndex)
                            onSelect(cs[i])
                        }
                    }
                }
            }
        ) {
            val w = size.width / cs.size
            cs.forEachIndexed { i, c ->
                val v = c.peak ?: 0.0
                val h = (v / top).toFloat() * size.height
                val col = if (hasRef) qualityColor(c.onTargetPct) else KegelPink
                drawRoundRect(col.copy(alpha = if (c == selected) 1f else 0.75f), Offset(i * w + w * 0.18f, size.height - h), Size(w * 0.64f, h), androidx.compose.ui.geometry.CornerRadius(4f))
                if (hasRef) {
                    val ty = size.height - (c.targetPct / top).toFloat() * size.height
                    drawLine(MonitorColors.warn, Offset(i * w + w * 0.08f, ty), Offset(i * w + w * 0.92f, ty), 2f, cap = StrokeCap.Round)
                }
            }
        }
    }
}

/** Every contraction up close: its own trace with the target, peak and time on target. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContractionGrid(points: List<KegelPoint>, cs: List<Contraction>, hasRef: Boolean, selected: Contraction?, onSelect: (Contraction) -> Unit) {
    if (cs.isEmpty()) return
    Panel {
        Text("Every squeeze up close", color = MonitorColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = (maxWidth.value / 150f).toInt().coerceIn(2, 6)
            val tileW = (maxWidth - 8.dp * (cols - 1)) / cols
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cs.forEach { c ->
                    val on = c == selected
                    val col = if (hasRef) qualityColor(c.onTargetPct) else KegelPink
                    Column(
                        Modifier.width(tileW).background(if (on) KegelPink.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(8.dp))
                            .border(1.dp, if (on) KegelPink else MonitorColors.panelBorder, RoundedCornerShape(8.dp)).clickable { onSelect(c) }.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${c.index}", color = MonitorColors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(c.onTargetPct?.let { "$it%" } ?: c.peak?.let { s1(it) } ?: "--", color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        val from = c.startMs - 1_000L; val to = c.endMs + 2_500L
                        val pts = points.filter { it.t in from..to }
                        val top = maxOf(if (hasRef) 110.0 else 1.0, (pts.maxOfOrNull { it.v } ?: 0.0) * 1.08)
                        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                            fun x(t: Long) = (t - from).toFloat() / (to - from) * size.width
                            fun y(v: Double) = size.height - (v / top).toFloat().coerceIn(0f, 1f) * size.height
                            drawRect(KegelPink.copy(alpha = 0.12f), Offset(x(c.startMs), 0f), Size(x(c.endMs) - x(c.startMs), size.height))
                            if (hasRef) drawLine(MonitorColors.warn, Offset(x(c.startMs), y(c.targetPct.toDouble())), Offset(x(c.endMs), y(c.targetPct.toDouble())), 1.6f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                            val path = Path(); pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.t), y(p.v)) else path.lineTo(x(p.t), y(p.v)) }
                            drawPath(path, col, style = Stroke(2f, cap = StrokeCap.Round))
                        }
                        Text("peak ${c.peak?.roundToInt() ?: "--"}${if (hasRef) "%" else ""} · rise ${s1(c.riseS)} s", color = MonitorColors.label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
