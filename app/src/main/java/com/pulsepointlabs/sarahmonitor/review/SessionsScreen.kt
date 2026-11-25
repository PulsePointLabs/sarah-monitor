package com.pulsepointlabs.sarahmonitor.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.session.SessionAnalysis
import com.pulsepointlabs.sarahmonitor.session.SessionEntity
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayFmt = DateTimeFormatter.ofPattern("EEE MMM d, yyyy")

@Composable
fun SessionsScreen(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val sessions by remember { MonitorHub.recorder.dao.observeSessions() }.collectAsState(initial = null)
    BoxWithConstraints(modifier) {
        val columns = when {
            maxWidth >= 1200.dp -> 3
            maxWidth >= 720.dp -> 2
            else -> 1
        }
        val list = sessions
        when {
            list == null -> Unit
            list.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("No sessions yet", color = MonitorColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("No sessions yet.", color = MonitorColors.label, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(list, key = { it.id }) { SessionCard(it) { onOpen(it.id) } }
            }
        }
    }
}

/** Every session card has the same shape — the live recording included (its numbers refresh as it runs). */
@Composable
private fun SessionCard(s: SessionEntity, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val start = Instant.ofEpochMilli(s.startTimeMs).atZone(ZoneId.systemDefault())
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    if (s.active) androidx.compose.runtime.LaunchedEffect(s.id) { while (true) { kotlinx.coroutines.delay(1_000L); now = System.currentTimeMillis() } }
    val category = com.pulsepointlabs.sarahmonitor.session.SessionCategory.of(s.category)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MonitorColors.panel, shape)
            .border(1.dp, if (s.active) MonitorColors.alarm.copy(alpha = 0.8f) else MonitorColors.panelBorder, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.title, color = MonitorColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (s.active) SessionAnalysis.elapsed(now - s.startTimeMs) else SessionAnalysis.durationWords(s.durationMs),
                color = if (s.active) MonitorColors.alarm else MonitorColors.label, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.active) Tag("● REC", MonitorColors.alarm)
            category?.let { Tag(it.label, if (it.sexual && it != com.pulsepointlabs.sarahmonitor.session.SessionCategory.Hybrid) androidx.compose.ui.graphics.Color(0xFFFF7AB6) else MonitorColors.sdnn) }
            Text("${start.format(dayFmt)} · ${clockLabel(s.startTimeMs)}", color = MonitorColors.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Same height either way so cards line up; says why it's empty instead of a blank gap.
        if (s.hrSparkline.isBlank()) Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.CenterStart) {
            Text(if (s.active) "Heart rate appears here once the H10 is sending" else "No heart rate in this session", color = MonitorColors.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Sparkline(s.hrSparkline, Modifier.fillMaxWidth().height(34.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("Avg HR", s.averageHr?.let { "%.0f".format(it) }, MonitorColors.hr, Modifier.weight(1f))
            Stat("Min", s.minHr?.toString(), MonitorColors.sdnn, Modifier.weight(1f))
            Stat("Max", s.maxHr?.toString(), MonitorColors.alarm, Modifier.weight(1f))
            Stat("RMSSD", s.rmssdMs?.let { "%.1f".format(it) }, MonitorColors.rmssd, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("Events", s.markerCount.toString(), MonitorColors.text, Modifier.weight(1f))
            Stat("Max ${com.pulsepointlabs.sarahmonitor.core.Pressure.label}", s.pelvicMaxKpa?.let { com.pulsepointlabs.sarahmonitor.core.Pressure.fmt(it) }, MonitorColors.pelvic, Modifier.weight(1f))
            Stat("Contract.", s.pelvicPulseCount?.toString(), MonitorColors.pelvic, Modifier.weight(1f))
            Stat("Hold", s.pelvicLongestHoldS?.let { "%.0f s".format(it) }, MonitorColors.pelvic, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(5.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** Fixed-size stat cell: label over value; "--" when there's nothing, so every card lines up. */
@Composable
private fun Stat(label: String, value: String?, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(
        modifier.background(color.copy(alpha = if (value == null) 0.04f else 0.1f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(label, color = MonitorColors.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value ?: "--", color = if (value == null) MonitorColors.dim else color, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Sparkline(csv: String, modifier: Modifier) {
    val values = remember(csv) { csv.split(',').mapNotNull { it.toIntOrNull() } }
    if (values.size < 2) {
        Box(modifier)
        return
    }
    Canvas(modifier) {
        val lo = values.min().toFloat() - 2
        val hi = values.max().toFloat() + 2
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i.toFloat() / (values.size - 1) * size.width
            val y = size.height - (v - lo) / (hi - lo) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, MonitorColors.hr, style = Stroke(width = 2f * density / 2f + 0.5f, cap = StrokeCap.Round))
        drawLine(MonitorColors.trendGrid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
    }
}
