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
                Text("Tap ● Record on the monitor to start one. Mark events while it runs, then review it here.", color = MonitorColors.label, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(s: SessionEntity, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val start = Instant.ofEpochMilli(s.startTimeMs).atZone(ZoneId.systemDefault())
    Column(
        Modifier
            .fillMaxWidth()
            .background(MonitorColors.panel, shape)
            .border(1.dp, if (s.active) MonitorColors.alarm else MonitorColors.panelBorder, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.active) Box(Modifier.size(9.dp).background(MonitorColors.alarm, CircleShape))
            Text(s.title, color = MonitorColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(SessionAnalysis.durationWords(s.durationMs), color = MonitorColors.label, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Text(
            "${start.format(dayFmt)} · ${clockLabel(s.startTimeMs)}" + if (s.active) " · recording now" else "",
            color = if (s.active) MonitorColors.alarm else MonitorColors.label,
            fontSize = 12.sp
        )
        Sparkline(s.hrSparkline, Modifier.fillMaxWidth().height(34.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Stat("Avg HR", s.averageHr?.let { "%.0f".format(it) }, MonitorColors.hr)
            Stat("Min", s.minHr?.toString(), MonitorColors.sdnn)
            Stat("Max", s.maxHr?.toString(), MonitorColors.alarm)
            Stat("RMSSD", s.rmssdMs?.let { "%.1f".format(it) }, MonitorColors.rmssd)
            Stat("Pressure max", s.pelvicMaxKpa?.let { "%.1f kPa".format(it) }, MonitorColors.pelvic)
            Stat("Contractions", s.pelvicPulseCount?.toString(), MonitorColors.pelvic)
            Stat("Events", s.markerCount.takeIf { it > 0 }?.toString(), MonitorColors.text)
        }
    }
}

@Composable
private fun Stat(label: String, value: String?, color: androidx.compose.ui.graphics.Color) {
    if (value == null) return
    Row(
        Modifier.background(color.copy(alpha = 0.1f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = MonitorColors.label, fontSize = 12.sp)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
