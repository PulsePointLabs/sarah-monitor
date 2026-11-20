package com.pulsepointlabs.sarahmonitor.review

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.ble.EcgSample
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.session.EcgCodec
import com.pulsepointlabs.sarahmonitor.session.MarkerEntity
import com.pulsepointlabs.sarahmonitor.session.MarkerTypes
import com.pulsepointlabs.sarahmonitor.session.SessionAnalysis
import com.pulsepointlabs.sarahmonitor.session.SessionData
import com.pulsepointlabs.sarahmonitor.session.SessionExport
import com.pulsepointlabs.sarahmonitor.session.SessionRepository
import com.pulsepointlabs.sarahmonitor.ui.EcgDisplayFilter
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import com.pulsepointlabs.sarahmonitor.ui.bpColor
import com.pulsepointlabs.sarahmonitor.ui.fmt
import com.pulsepointlabs.sarahmonitor.ui.loadColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max

private class ReviewSeriesSet(data: SessionData) {
    val hrTs = data.hr.map { it.timestampMs }.toLongArray()
    val hr = ReviewSeries("HR", MonitorColors.hr, hrTs, data.hr.map { it.bpm.toDouble() }.toDoubleArray())
    val rmssd = ReviewSeries("RMSSD", MonitorColors.rmssd, hrTs, data.hr.map { it.rmssdMs ?: Double.NaN }.toDoubleArray())
    val sdnn = ReviewSeries("SDNN", MonitorColors.sdnn, hrTs, data.hr.map { it.sdnnMs ?: Double.NaN }.toDoubleArray())
    val load = ReviewSeries("Load", MonitorColors.load, hrTs, data.hr.map { it.load?.toDouble() ?: Double.NaN }.toDoubleArray())
    val pelvic = ReviewSeries(
        "Above rest", MonitorColors.pelvic,
        data.pelvic.map { it.timestampMs }.toLongArray(),
        data.pelvic.map { it.aboveRestKpa ?: Double.NaN }.toDoubleArray(),
        SeriesStyle.Area, gapMs = 1_000L
    )
    val sys = ReviewSeries("SYS", MonitorColors.bp, data.bp.map { it.timestampMs }.toLongArray(), data.bp.map { it.systolic.toDouble() }.toDoubleArray(), SeriesStyle.Dots)
    val dia = ReviewSeries("DIA", MonitorColors.bpDia, data.bp.map { it.timestampMs }.toLongArray(), data.bp.map { it.diastolic.toDouble() }.toDoubleArray(), SeriesStyle.Dots)
    val glucose = ReviewSeries("Glucose", MonitorColors.glucose, data.glucose.map { it.timestampMs }.toLongArray(), data.glucose.map { it.mgDl }.toDoubleArray(), SeriesStyle.Dots)
    val hasHrv = data.hr.any { it.rmssdMs != null }
    val hasLoad = data.hr.any { it.load != null }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d yyyy")

@Composable
fun ReviewScreen(
    sessionId: String,
    onBack: () -> Unit,
    onExportCsv: (fileName: String, text: String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dao = MonitorHub.recorder.dao
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<SessionData?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId, reload) {
        runCatching { withContext(Dispatchers.IO) { SessionRepository.load(dao, sessionId) } }
            .onSuccess { if (it == null) failed = "This session no longer exists." else { data = it; failed = null } }
            .onFailure { failed = "Couldn't open this session (${it.message ?: it.javaClass.simpleName})." }
    }
    // A session that's still recording refreshes itself.
    LaunchedEffect(data?.session?.active) {
        while (data?.session?.active == true) {
            delay(5_000L)
            reload++
        }
    }
    val d = data ?: run {
        Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(failed ?: "Loading session…", color = if (failed != null) MonitorColors.warn else MonitorColors.label)
            if (failed != null) TextButton(onClick = onBack) { Text("Back to sessions") }
        }
        return
    }
    val series = remember(d) { ReviewSeriesSet(d) }
    val fullStart = d.session.startTimeMs
    val fullEnd = max(d.endMs, fullStart + 60_000L)
    var viewport by remember(sessionId) { mutableStateOf(Viewport(fullStart, fullEnd)) }
    if (viewport.endMs > fullEnd || viewport.startMs < fullStart) viewport = viewport.clampTo(fullStart, fullEnd)
    var cursor by remember(sessionId) { mutableStateOf<Long?>(null) }
    var selectedMarker by remember(sessionId) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<MarkerEntity?>(null) }
    var addingAt by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val chartMarkers = d.markers.map { ChartMarker(it.timestampMs, markerColor(it.types), it.id == selectedMarker) }
    val zoom: (Double, Long) -> Unit = { f, anchor -> viewport = viewport.zoomed(f, anchor, fullStart, fullEnd) }
    val setCursor: (Long) -> Unit = { cursor = it; selectedMarker = null }
    fun focus(marker: MarkerEntity) {
        selectedMarker = marker.id
        cursor = marker.timestampMs
        viewport = viewport.centeredOn(marker.timestampMs, minOf(viewport.spanMs, 4 * 60_000L), fullStart, fullEnd)
    }
    fun saveMarker(marker: MarkerEntity) = scope.launch {
        withContext(Dispatchers.IO) {
            dao.upsertMarker(marker)
            dao.getSession(sessionId)?.let { dao.upsertSession(it.copy(markerCount = dao.getMarkers(sessionId).size)) }
        }
        reload++
    }

    val charts: @Composable ColumnScope.() -> Unit = {
        RangeBar(viewport, fullStart, fullEnd, cursor, series, chartMarkers) { viewport = it }
        CursorReadout(cursor, d, series)
        if (d.hr.isNotEmpty()) {
            val avg = d.session.averageHr ?: d.hr.map { it.bpm }.average()
            ReviewChart("Heart rate", "bpm", listOf(series.hr), viewport, chartMarkers, cursor, setCursor, zoom, 170.dp, minSpan = 20.0,
                refLines = listOf(avg to "avg ${"%.0f".format(avg)}"))
        }
        if (series.hasHrv) ReviewChart("HRV (rolling 2 min)", "ms", listOf(series.rmssd, series.sdnn), viewport, chartMarkers, cursor, setCursor, zoom, 140.dp, minSpan = 10.0, decimals = 1)
        if (series.hasLoad) ReviewChart("Load", "0–100", listOf(series.load), viewport, chartMarkers, cursor, setCursor, zoom, 100.dp, fixedBounds = 0.0 to 100.0)
        if (d.pelvic.isNotEmpty()) ReviewChart("Pelvic pressure", "kPa above rest", listOf(series.pelvic), viewport, chartMarkers, cursor, setCursor, zoom, 170.dp, minSpan = 2.0, decimals = 1)
        if (d.bp.isNotEmpty()) ReviewChart("Blood pressure", "mmHg", listOf(series.sys, series.dia), viewport, chartMarkers, cursor, setCursor, zoom, 120.dp, minSpan = 40.0,
            emptyText = "Readings are outside the zoomed range")
        if (d.glucose.any { it.timestampMs in fullStart..fullEnd }) ReviewChart("Glucose", "mg/dL", listOf(series.glucose), viewport, chartMarkers, cursor, setCursor, zoom, 110.dp, minSpan = 40.0,
            refLines = listOf(70.0 to "70", 180.0 to "180"), emptyText = "Readings are outside the zoomed range")
        if (d.hasEcg && cursor != null) EcgAtCursor(sessionId, cursor!!)
        if (d.hr.isEmpty() && d.pelvic.isEmpty()) Text("No sensor data was recorded in this session.", color = MonitorColors.label, fontSize = 14.sp)
    }
    val details: @Composable ColumnScope.() -> Unit = {
        SummaryCard(d)
        EventsCard(
            d, selectedMarker,
            onFocus = ::focus,
            onEdit = { editing = it },
            onAdd = { addingAt = cursor ?: d.endMs }
        )
        NotesCard(d.session.notes) { notes ->
            scope.launch { withContext(Dispatchers.IO) { dao.upsertSession(d.session.copy(notes = notes)) }; reload++ }
        }
    }

    Column(modifier) {
        ReviewHeader(
            d,
            onBack = onBack,
            onRename = { renaming = true },
            onAskAi = { SessionExport.askAi(context, d) },
            onCsv = { onExportCsv(SessionExport.fileStem(d) + ".csv", SessionExport.csv(d)) },
            onDelete = { confirmDelete = true }
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 900.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1.7f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { charts() }
                    Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { details() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(d)
                    charts()
                    EventsCard(d, selectedMarker, onFocus = ::focus, onEdit = { editing = it }, onAdd = { addingAt = cursor ?: d.endMs })
                    NotesCard(d.session.notes) { notes ->
                        scope.launch { withContext(Dispatchers.IO) { dao.upsertSession(d.session.copy(notes = notes)) }; reload++ }
                    }
                }
            }
        }
    }

    editing?.let { m ->
        MarkEventDialog(
            pressedAtMs = m.timestampMs,
            editing = m,
            onSave = { types, note -> saveMarker(m.copy(types = MarkerTypes.join(types), label = MarkerTypes.label(types), note = note.trim())); editing = null },
            onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        dao.deleteMarker(m.id)
                        dao.getSession(sessionId)?.let { dao.upsertSession(it.copy(markerCount = dao.getMarkers(sessionId).size)) }
                    }
                    reload++
                }
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
    addingAt?.let { t ->
        MarkEventDialog(
            pressedAtMs = t,
            editing = MarkerEntity("", sessionId, t, "event", "Event"),
            onSave = { types, note ->
                saveMarker(MarkerEntity(UUID.randomUUID().toString(), sessionId, t, MarkerTypes.join(types), MarkerTypes.label(types), note.trim()))
                addingAt = null
            },
            onDismiss = { addingAt = null }
        )
    }
    if (renaming) {
        var title by remember { mutableStateOf(d.session.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = MonitorColors.panel,
            title = { Text("Rename session", color = MonitorColors.text) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { dao.upsertSession(d.session.copy(title = title.ifBlank { d.session.title })) }; reload++ }
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MonitorColors.panel,
            title = { Text("Delete this session?", color = MonitorColors.text) },
            text = { Text("All recorded data and events for \"${d.session.title}\" will be removed. BP and glucose readings are kept.", color = MonitorColors.label) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (d.session.active) {
                        onMessage("Stop the recording first.")
                    } else {
                        scope.launch { withContext(Dispatchers.IO) { dao.deleteEverything(sessionId) }; onBack() }
                    }
                }) { Text("Delete", color = MonitorColors.alarm) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ReviewHeader(d: SessionData, onBack: () -> Unit, onRename: () -> Unit, onAskAi: () -> Unit, onCsv: () -> Unit, onDelete: () -> Unit) {
    val start = Instant.ofEpochMilli(d.session.startTimeMs).atZone(ZoneId.systemDefault())
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        val narrow = maxWidth < 600.dp
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MonitorColors.text) }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.session.title, color = MonitorColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, contentDescription = "Rename", tint = MonitorColors.label, modifier = Modifier.size(16.dp)) }
                    }
                    Text(
                        "${start.format(dayFmt)} · ${clockLabel(d.session.startTimeMs)}–${clockLabel(d.endMs)} · ${SessionAnalysis.durationWords(d.endMs - d.session.startTimeMs)}" +
                            if (d.session.active) " · recording" else "",
                        color = if (d.session.active) MonitorColors.alarm else MonitorColors.label,
                        fontSize = 12.sp
                    )
                }
                if (!narrow) Actions(onAskAi, onCsv, onDelete)
            }
            if (narrow) Row(Modifier.padding(start = 8.dp)) { Actions(onAskAi, onCsv, onDelete) }
        }
    }
}

@Composable
private fun Actions(onAskAi: () -> Unit, onCsv: () -> Unit, onDelete: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onAskAi) { Text("✦ Ask AI", fontSize = 13.sp) }
        OutlinedButton(onClick = onCsv) { Text("Export CSV", fontSize = 13.sp) }
        TextButton(onClick = onDelete) { Text("Delete", color = MonitorColors.alarm, fontSize = 13.sp) }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.fillMaxWidth().background(MonitorColors.panel, shape).border(1.dp, MonitorColors.panelBorder, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(d: SessionData) {
    val s = d.session
    val pelvic = remember(d) { SessionAnalysis.pelvic(d.pelvic) }
    val loads = d.hr.mapNotNull { it.load }
    Section("Summary") {
        SessionAnalysis.plainSummary(d).forEach { Text(it, color = MonitorColors.text, fontSize = 14.sp) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Avg HR", fmt(s.averageHr), "bpm", MonitorColors.hr)
            Tile("HR range", if (s.minHr != null) "${s.minHr}–${s.maxHr}" else "--", "bpm", MonitorColors.hr)
            Tile("RMSSD", fmt(s.rmssdMs, 1), "ms", MonitorColors.rmssd)
            Tile("SDNN", fmt(s.sdnnMs, 1), "ms", MonitorColors.sdnn)
            Tile("pNN50", fmt(s.pnn50Percent, 0), "%", MonitorColors.pnn50)
            if (loads.isNotEmpty()) Tile("Load avg / max", "${"%.0f".format(loads.average())} / ${loads.max()}", "", loadColor(loads.max()))
            if (d.pelvic.isNotEmpty()) {
                Tile("Pressure peak", fmt(pelvic.maxKpa, 1), "kPa", MonitorColors.pelvic)
                Tile("Contractions", pelvic.pulses.size.toString(), "", MonitorColors.pelvic)
                Tile("Longest hold", fmt(pelvic.longestHoldS, 1), "s", MonitorColors.pelvic)
            }
            d.bp.lastOrNull()?.let { Tile("Last BP", "${it.systolic}/${it.diastolic}", "mmHg", bpColor(it.systolic, it.diastolic)) }
            d.glucose.lastOrNull()?.let { Tile("Last glucose", fmt(it.mgDl), "mg/dL", MonitorColors.glucose) }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, color: Color) {
    Column(
        Modifier.widthIn(min = 96.dp).background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = MonitorColors.label, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            if (unit.isNotBlank()) Text(" $unit", color = MonitorColors.label, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

private enum class Range(val label: String, val ms: Long?) {
    All("All", null), M30("30 min", 30 * 60_000L), M10("10 min", 10 * 60_000L), M2("2 min", 2 * 60_000L), S30("30 s", 30_000L)
}

@Composable
private fun RangeBar(viewport: Viewport, fullStart: Long, fullEnd: Long, cursor: Long?, series: ReviewSeriesSet, markers: List<ChartMarker>, onViewport: (Viewport) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Zoom", color = MonitorColors.label, fontSize = 12.sp)
            Range.entries.forEach { r ->
                val full = fullEnd - fullStart
                if (r.ms != null && r.ms >= full) return@forEach
                val selected = if (r.ms == null) viewport.spanMs >= full - 1_000L else kotlin.math.abs(viewport.spanMs - r.ms) < 1_000L
                Text(
                    r.label,
                    color = if (selected) MonitorColors.background else MonitorColors.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(if (selected) MonitorColors.label else Color.Transparent, RoundedCornerShape(6.dp))
                        .border(1.dp, MonitorColors.label.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .clickable {
                            onViewport(if (r.ms == null) Viewport(fullStart, fullEnd) else viewport.centeredOn(cursor ?: (viewport.startMs + viewport.spanMs / 2), r.ms, fullStart, fullEnd))
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        OverviewStrip(series.hr.takeIf { it.timestamps.isNotEmpty() } ?: series.pelvic, fullStart, fullEnd, viewport, markers, onViewport)
        Text("Tap or drag on a graph to inspect a moment · drag the strip to move · mouse wheel zooms", color = MonitorColors.dim, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CursorReadout(cursor: Long?, d: SessionData, series: ReviewSeriesSet) {
    if (cursor == null) return
    val near = d.markers.minByOrNull { kotlin.math.abs(it.timestampMs - cursor) }?.takeIf { kotlin.math.abs(it.timestampMs - cursor) < 15_000L }
    Row(
        Modifier.fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(clockLabel(cursor, seconds = true), color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("+" + SessionAnalysis.elapsed(cursor - d.session.startTimeMs), color = MonitorColors.label, fontSize = 12.sp)
        }
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Value("HR", series.hr.valueAt(cursor, 5_000L)?.let { "%.0f".format(it) }, MonitorColors.hr)
            Value("RMSSD", series.rmssd.valueAt(cursor, 5_000L)?.let { "%.1f".format(it) }, MonitorColors.rmssd)
            Value("SDNN", series.sdnn.valueAt(cursor, 5_000L)?.let { "%.1f".format(it) }, MonitorColors.sdnn)
            Value("Load", series.load.valueAt(cursor, 5_000L)?.let { "%.0f".format(it) }, MonitorColors.load)
            Value("Pressure", series.pelvic.valueAt(cursor, 1_000L)?.let { SessionAnalysis.num(it) }, MonitorColors.pelvic)
            near?.let { Value("Event", it.label, markerColor(it.types)) }
        }
    }
}

@Composable
private fun Value(label: String, value: String?, color: Color) {
    if (value == null) return
    Column {
        Text(label, color = MonitorColors.label, fontSize = 11.sp)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EventsCard(d: SessionData, selected: String?, onFocus: (MarkerEntity) -> Unit, onEdit: (MarkerEntity) -> Unit, onAdd: () -> Unit) {
    Section("Events (${d.markers.size})", trailing = { TextButton(onClick = onAdd) { Text("+ Add at cursor", fontSize = 13.sp) } }) {
        if (d.markers.isEmpty()) Text("No events marked. Use + to add one at the cursor position.", color = MonitorColors.label, fontSize = 13.sp)
        d.markers.forEach { m ->
            val ctx = remember(m, d) { SessionAnalysis.markerContext(m, d) }
            val color = markerColor(m.types)
            val isSelected = m.id == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (isSelected) color.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .border(1.dp, if (isSelected) color.copy(alpha = 0.6f) else MonitorColors.panelBorder, RoundedCornerShape(8.dp))
                    .clickable { onFocus(m) }
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.padding(top = 5.dp).size(10.dp).background(color, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${clockLabel(m.timestampMs, seconds = true)} · +${SessionAnalysis.elapsed(m.timestampMs - d.session.startTimeMs)}", color = MonitorColors.label, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (m.note.isNotBlank()) Text(m.note, color = MonitorColors.text, fontSize = 13.sp)
                    val facts = listOfNotNull(
                        ctx.hrAt?.let { "HR $it" },
                        ctx.rmssdAt?.let { "RMSSD ${SessionAnalysis.num(it)}" },
                        ctx.pressureAt?.let { "pressure ${SessionAnalysis.num(it)} kPa" },
                        ctx.peakPressureNext30s?.let { "peak next 30 s ${SessionAnalysis.num(it)} kPa" },
                        listOfNotNull(
                            ctx.delta1Min?.let { "${SessionAnalysis.signed(it)} at 1 min" },
                            ctx.delta5Min?.let { "${SessionAnalysis.signed(it)} at 5 min" }
                        ).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "HR change ")
                    )
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = MonitorColors.label, fontSize = 12.sp)
                }
                IconButton(onClick = { onEdit(m) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, contentDescription = "Edit event", tint = MonitorColors.label, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
private fun NotesCard(notes: String, onSave: (String) -> Unit) {
    var text by remember(notes) { mutableStateOf(notes) }
    Section("Notes", trailing = { if (text != notes) TextButton(onClick = { onSave(text) }) { Text("Save", fontSize = 13.sp) } }) {
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("How did this session go?") }, minLines = 2)
    }
}

/** 6 seconds of recorded ECG centred on the cursor. */
@Composable
private fun EcgAtCursor(sessionId: String, cursor: Long) {
    var samples by remember { mutableStateOf<List<EcgSample>>(emptyList()) }
    LaunchedEffect(sessionId, cursor / 250) {
        samples = withContext(Dispatchers.IO) {
            MonitorHub.recorder.dao.getEcg(sessionId, cursor - 3_000L, cursor + 3_000L).flatMap { chunk ->
                val (ts, uv) = EcgCodec.decode(chunk)
                ts.indices.filter { ts[it] in (cursor - 3_000L)..(cursor + 3_000L) }.map { EcgSample(ts[it], uv[it]) }
            }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().background(MonitorColors.panel, shape).border(1.dp, MonitorColors.ecg.copy(alpha = 0.3f), shape).padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(width = 4.dp, height = 14.dp).background(MonitorColors.ecg, RoundedCornerShape(2.dp)))
            Text("ECG at ${clockLabel(cursor, seconds = true)}", color = MonitorColors.ecg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("6 s · H10", color = MonitorColors.label, fontSize = 12.sp)
        }
        val trace = remember(samples) { if (samples.size > 10) EcgDisplayFilter.prepare(samples, smooth = true) else null }
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val mm = (160f / 25.4f) * density
                var x = 0f; var i = 0
                while (x <= size.width) { drawLine(if (i % 5 == 0) MonitorColors.gridMajor else MonitorColors.gridMinor, Offset(x, 0f), Offset(x, size.height), strokeWidth = if (i % 5 == 0) 1.2f else 0.6f); x += mm; i++ }
                var y = 0f; i = 0
                while (y <= size.height) { drawLine(if (i % 5 == 0) MonitorColors.gridMajor else MonitorColors.gridMinor, Offset(0f, y), Offset(size.width, y), strokeWidth = if (i % 5 == 0) 1.2f else 0.6f); y += mm; i++ }
                val t = trace ?: return@Canvas
                val start = cursor - 3_000L
                val path = Path()
                var started = false
                for (k in 0 until t.size) {
                    val px = (t.timestampsMs[k] - start).toFloat() / 6_000f * size.width
                    val py = size.height * 0.1f + size.height * 0.8f * (1f - t.normalized(k))
                    if (!started || t.breakBefore[k]) path.moveTo(px, py) else path.lineTo(px, py)
                    started = true
                }
                drawPath(path, MonitorColors.ecg, style = Stroke(width = 2f * density / 2f + 1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(Color.White.copy(alpha = 0.6f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1.2f)
            }
            if (trace == null) Text("No ECG recorded at this moment", color = MonitorColors.dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
