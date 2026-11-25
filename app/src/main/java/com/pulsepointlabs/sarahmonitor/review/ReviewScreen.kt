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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.collectAsState
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
import com.pulsepointlabs.sarahmonitor.session.FlagChart
import com.pulsepointlabs.sarahmonitor.session.FlagSeverity
import com.pulsepointlabs.sarahmonitor.session.SessionFlag
import com.pulsepointlabs.sarahmonitor.session.SessionFlags
import com.pulsepointlabs.sarahmonitor.session.lowerTitle
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
    private val emgTs = data.emg.map { it.timestampMs }.toLongArray()
    /** % of calibrated hold when available, else raw ENV (0–1023). */
    val emgCalibrated = data.emg.any { it.pct1 != null }
    val emg1 = ReviewSeries("EMG 1", com.pulsepointlabs.sarahmonitor.emg.EmgColor, emgTs, data.emg.map { (if (emgCalibrated) it.pct1 else it.ch1) ?: Double.NaN }.toDoubleArray(), SeriesStyle.Area, gapMs = 1_000L)
    val emg2 = ReviewSeries("EMG 2", com.pulsepointlabs.sarahmonitor.emg.Emg2Color, emgTs, data.emg.map { (if (emgCalibrated) it.pct2 else it.ch2) ?: Double.NaN }.toDoubleArray(), gapMs = 1_000L)
    val hasEmg2 = data.emg.any { it.ch2 != null }
    val stress = ReviewSeries("Stress", com.pulsepointlabs.sarahmonitor.ui.StressColor, hrTs, data.hr.map { it.stress?.toDouble() ?: Double.NaN }.toDoubleArray())
    val hasStress = data.hr.any { it.stress != null }
    val breaths = ReviewSeries("Breathing", MonitorColors.pelvic, hrTs, data.hr.map { it.breathsPerMin ?: Double.NaN }.toDoubleArray())
    val hasBreaths = data.hr.any { it.breathsPerMin != null }
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
    val liveSession by MonitorHub.session.collectAsState()
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<SessionData?>(null) }
    // Research export: every native sample in a ZIP, written straight to the file the user picks.
    var pendingZip by remember { mutableStateOf<SessionData?>(null) }
    val zipLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val zd = pendingZip
        pendingZip = null
        if (uri != null && zd != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val episodes = if (com.pulsepointlabs.sarahmonitor.session.SessionCategory.of(zd.session.category)?.sexual == true)
                        runCatching { com.pulsepointlabs.sarahmonitor.response.ResponseEpisodes.detect(zd, com.pulsepointlabs.sarahmonitor.response.ResponseSettings.profile.value) }.getOrDefault(emptyList())
                    else emptyList()
                    (context.contentResolver.openOutputStream(uri) ?: error("no output")).use { com.pulsepointlabs.sarahmonitor.session.ResearchExport.write(it, dao, zd, episodes) }
                }.isSuccess
            }
            onMessage(if (ok) "Research export saved" else "Couldn't save the research export")
        }
    }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId, reload) {
        runCatching { withContext(Dispatchers.IO) { SessionRepository.load(dao, sessionId) } }
            .onSuccess { if (it == null) failed = "This session no longer exists." else { data = it; failed = null } }
            .onFailure { failed = "Couldn't open this session (${it.message ?: it.javaClass.simpleName})." }
    }
    // A session that's still recording refreshes itself.
    LaunchedEffect(data?.session?.active) {
        while (data?.session?.active == true) {
            delay(3_000L)
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
    var shownEnd by remember(sessionId) { mutableStateOf(fullEnd) }
    if (fullEnd != shownEnd) {
        // While recording: if you're looking at the live edge, keep following it as new data arrives.
        val atLiveEdge = viewport.endMs >= shownEnd - 2_000L
        viewport = when {
            !atLiveEdge -> viewport.clampTo(fullStart, fullEnd)
            viewport.startMs <= fullStart + 1_000L -> Viewport(fullStart, fullEnd)
            else -> Viewport(fullEnd - viewport.spanMs, fullEnd)
        }
        shownEnd = fullEnd
    }
    if (viewport.endMs > fullEnd || viewport.startMs < fullStart) viewport = viewport.clampTo(fullStart, fullEnd)
    var cursor by remember(sessionId) { mutableStateOf<Long?>(null) }
    var selectedMarker by remember(sessionId) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<MarkerEntity?>(null) }
    var addingAt by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var editingDetails by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val chartMarkers = d.markers.map { ChartMarker(it.timestampMs, markerColor(it.types), it.id == selectedMarker) }
    val flags = remember(d) { SessionFlags.detect(d) }
    val category = com.pulsepointlabs.sarahmonitor.session.SessionCategory.of(d.session.category)
    val responseLane = category != null && category.sexual
    val exploration = category == com.pulsepointlabs.sarahmonitor.session.SessionCategory.Exploration
    // Replaying the arousal engine is heavy: recompute about once a minute of new data while recording.
    val episodes by androidx.compose.runtime.produceState(emptyList<com.pulsepointlabs.sarahmonitor.response.ResponseEpisode>(), d.session.id, d.hr.size / 60, responseLane) {
        value = if (!responseLane) emptyList() else withContext(Dispatchers.Default) {
            runCatching { com.pulsepointlabs.sarahmonitor.response.ResponseEpisodes.detect(d, com.pulsepointlabs.sarahmonitor.response.ResponseSettings.profile.value) }.getOrDefault(emptyList())
        }
    }
    var showFlags by remember { mutableStateOf(true) }
    var selectedFlag by remember(sessionId) { mutableStateOf<SessionFlag?>(null) }
    var selectedEpisode by remember(sessionId) { mutableStateOf<com.pulsepointlabs.sarahmonitor.response.ResponseEpisode?>(null) }
    val kegelSets = remember(d) { d.markers.mapNotNull { com.pulsepointlabs.sarahmonitor.kegel.KegelRecord.from(it) } }
    var kegelDetail by remember(sessionId) { mutableStateOf<com.pulsepointlabs.sarahmonitor.kegel.KegelRecord?>(null) }
    kegelDetail?.let { k ->
        // The set's own pressure from the recording (every sample), in the same units the set was scored in.
        val pts = remember(k, d.pelvic.size) {
            d.pelvic.filter { it.timestampMs in (k.startMs - 3_000L)..(k.endMs + 5_000L) }
                .mapNotNull { p -> p.aboveRestKpa?.let { com.pulsepointlabs.sarahmonitor.kegel.KegelPoint(p.timestampMs, com.pulsepointlabs.sarahmonitor.kegel.KegelAnalysis.toValue(it, k.referenceKpa)) } }
        }
        com.pulsepointlabs.sarahmonitor.kegel.KegelSetReview(k, pts) { kegelDetail = null }
    }
    fun bands(chart: FlagChart): List<ChartBand> =
        (if (!showFlags) emptyList() else flags.filter { it.kind.chart == chart }.map { ChartBand(it.startMs, it.endMs, flagColor(it.severity), it == selectedFlag) }) +
            (if (chart == FlagChart.Hr) episodes.map { ChartBand(it.startMs, it.endMs, EpisodePink, it == selectedEpisode) } else emptyList()) +
            (if (chart == FlagChart.Pelvic) kegelSets.flatMap { k -> k.squeezes.map { (a, b, _) -> ChartBand(a, b, EpisodePink.copy(alpha = 0.6f)) } } else emptyList())
    // Tooltip lines: the event, flag or episode under a moment.
    val describe: (Long) -> List<Pair<String, Color>> = { t ->
        buildList {
            d.markers.filter { kotlin.math.abs(it.timestampMs - t) <= maxOf(10_000L, viewport.spanMs / 80) }.forEach { m ->
                add(("● " + m.label + if (m.note.isNotBlank()) " — " + m.note.take(40) else "") to markerColor(m.types))
            }
            episodes.filter { t in it.startMs..it.endMs }.forEach { e ->
                add("${episodeLabel(e, exploration)} · ${SessionAnalysis.durationWords(e.durationMs)} · ${e.peakApproach}%" to EpisodePink)
            }
            if (showFlags) flags.filter { t in (it.startMs - 2_000L)..(it.endMs + 2_000L) && it.kind != com.pulsepointlabs.sarahmonitor.session.FlagKind.HrSignalGap }.take(2).forEach { f ->
                add("${f.kind.title} · ${SessionAnalysis.durationWords(f.durationMs.coerceAtLeast(1_000L))}" to flagColor(f.severity))
            }
        }
    }
    fun focusFlag(flag: SessionFlag) {
        selectedFlag = flag
        selectedMarker = null
        cursor = flag.startMs
        val span = maxOf(flag.durationMs * 3, 2 * 60_000L).coerceAtMost(30 * 60_000L)
        viewport = viewport.centeredOn((flag.startMs + flag.endMs) / 2, span, fullStart, fullEnd)
    }
    val zoom: (Double, Long) -> Unit = { f, anchor -> viewport = viewport.zoomed(f, anchor, fullStart, fullEnd) }
    val setCursor: (Long) -> Unit = { cursor = it; selectedMarker = null }
    fun focus(marker: MarkerEntity) {
        selectedMarker = marker.id
        cursor = marker.timestampMs
        viewport = viewport.centeredOn(marker.timestampMs, minOf(viewport.spanMs, 4 * 60_000L), fullStart, fullEnd)
    }
    fun deleteMarker(m: MarkerEntity) {
        scope.launch {
            withContext(Dispatchers.IO) { dao.deleteMarker(m.id); dao.setMarkerCount(sessionId, dao.getMarkers(sessionId).size) }
            reload++
        }
    }
    fun saveMarker(marker: MarkerEntity) = scope.launch {
        withContext(Dispatchers.IO) {
            dao.upsertMarker(marker)
            dao.setMarkerCount(sessionId, dao.getMarkers(sessionId).size)
        }
        reload++
    }

    class ChartEntry(val nominal: Float, val draw: @Composable (Modifier, Boolean) -> Unit)
    val entries = buildList {
        if (d.hr.isNotEmpty()) {
            val avg = d.session.averageHr ?: d.hr.map { it.bpm }.average()
            add(ChartEntry(170f) { m, fill -> ReviewChart("Heart rate", "bpm", listOf(series.hr), viewport, chartMarkers, cursor, setCursor, zoom, 170.dp, m, minSpan = 20.0,
                refLines = listOf(avg to "avg ${"%.0f".format(avg)}"), bands = bands(FlagChart.Hr), fillHeight = fill, describe = describe) })
        }
        if (series.hasHrv) add(ChartEntry(140f) { m, fill -> ReviewChart("HRV (rolling 2 min)", "ms", listOf(series.rmssd, series.sdnn), viewport, chartMarkers, cursor, setCursor, zoom, 140.dp, m, minSpan = 10.0, decimals = 1, bands = bands(FlagChart.Hrv), fillHeight = fill, describe = describe) })
        if (series.hasStress) add(ChartEntry(100f) { m, fill -> ReviewChart("Stress", "0–100", listOf(series.stress), viewport, chartMarkers, cursor, setCursor, zoom, 100.dp, m, fixedBounds = 0.0 to 100.0, refLines = listOf(50.0 to "elevated"), fillHeight = fill, describe = describe) })
        if (series.hasBreaths) add(ChartEntry(90f) { m, fill -> ReviewChart("Breathing", "/min", listOf(series.breaths), viewport, chartMarkers, cursor, setCursor, zoom, 90.dp, m, minSpan = 6.0, fillHeight = fill, describe = describe) })
        if (series.hasLoad) add(ChartEntry(100f) { m, fill -> ReviewChart("Load", "0–100", listOf(series.load), viewport, chartMarkers, cursor, setCursor, zoom, 100.dp, m, fixedBounds = 0.0 to 100.0, refLines = listOf(58.0 to "elevated"), bands = bands(FlagChart.Load), fillHeight = fill, describe = describe) })
        if (d.emg.isNotEmpty()) add(ChartEntry(150f) { m, fill -> ReviewChart("EMG", if (series.emgCalibrated) "% of hold" else "raw ENV", listOfNotNull(series.emg1, series.emg2.takeIf { series.hasEmg2 }), viewport, chartMarkers, cursor, setCursor, zoom, 150.dp, m, minSpan = 20.0, fillHeight = fill, describe = describe) })
        if (d.pelvic.isNotEmpty()) add(ChartEntry(170f) { m, fill -> ReviewChart("Pelvic pressure", "kPa above rest", listOf(series.pelvic), viewport, chartMarkers, cursor, setCursor, zoom, 170.dp, m, minSpan = 2.0, decimals = 1, bands = bands(FlagChart.Pelvic), fillHeight = fill, describe = describe) })
        if (d.bp.isNotEmpty()) add(ChartEntry(120f) { m, fill -> ReviewChart("Blood pressure", "mmHg", listOf(series.sys, series.dia), viewport, chartMarkers, cursor, setCursor, zoom, 120.dp, m, minSpan = 40.0,
            emptyText = "Readings are outside the zoomed range", bands = bands(FlagChart.Bp), fillHeight = fill, describe = describe) })
        if (d.glucose.any { it.timestampMs in fullStart..fullEnd }) add(ChartEntry(110f) { m, fill -> ReviewChart("Glucose", "mg/dL", listOf(series.glucose), viewport, chartMarkers, cursor, setCursor, zoom, 110.dp, m, minSpan = 40.0,
            refLines = listOf(70.0 to "70", 180.0 to "180"), emptyText = "Readings are outside the zoomed range", bands = bands(FlagChart.Glucose), fillHeight = fill, describe = describe) })
    }
    val charts: @Composable ColumnScope.() -> Unit = {
        RangeBar(viewport, fullStart, fullEnd, cursor, series, chartMarkers) { viewport = it }
        CursorReadout(cursor, d, series)
        entries.forEach { it.draw(Modifier, false) }
        if (d.hasEcg && cursor != null) EcgAtCursor(sessionId, cursor!!)
        if (d.hr.isEmpty() && d.pelvic.isEmpty()) Text("No sensor data was recorded in this session.", color = MonitorColors.label, fontSize = 14.sp)
    }
    /** Wide screens: the graphs share the full height (no scrolling) as long as each keeps a readable size. */
    val fittedCharts: @Composable ColumnScope.() -> Unit = {
        RangeBar(viewport, fullStart, fullEnd, cursor, series, chartMarkers) { viewport = it }
        CursorReadout(cursor, d, series)
        entries.forEach { e -> e.draw(Modifier.weight(e.nominal), true) }
        if (d.hasEcg && cursor != null) EcgAtCursor(sessionId, cursor!!)
    }
    val flagsCard: @Composable () -> Unit = {
        InsightFlagsCard(flags, d, selectedFlag, showFlags, onToggle = { showFlags = it }, onFocus = ::focusFlag)
    }
    fun focusEpisode(e: com.pulsepointlabs.sarahmonitor.response.ResponseEpisode) {
        selectedEpisode = e; selectedFlag = null; selectedMarker = null
        cursor = e.peakMs
        viewport = viewport.centeredOn((e.startMs + e.endMs) / 2, maxOf(e.durationMs * 3, 6 * 60_000L).coerceAtMost(40 * 60_000L), fullStart, fullEnd)
    }
    val episodesCard: @Composable () -> Unit = {
        if (responseLane) EpisodesCard(episodes, d, exploration, ::focusEpisode)
        if (kegelSets.isNotEmpty()) KegelCard(kegelSets, onFocus = { k ->
            cursor = k.startMs
            viewport = viewport.centeredOn((k.startMs + k.endMs) / 2, ((k.endMs - k.startMs) * 1.3).toLong().coerceAtLeast(60_000L), fullStart, fullEnd)
        }, onDetails = { kegelDetail = it })
    }
    val details: @Composable ColumnScope.() -> Unit = {
        SummaryCard(d, flags)
        DetailsCard(com.pulsepointlabs.sarahmonitor.session.SessionDetails.parse(d.session.details), category) { editingDetails = true }
        episodesCard()
        flagsCard()
        EventsCard(d, selectedMarker, sessionId, onFocus = ::focus, onEdit = { editing = it }, onAdd = { addingAt = cursor ?: d.endMs }, onDelete = ::deleteMarker)
        NotesCard(d.session.notes) { notes ->
            scope.launch { withContext(Dispatchers.IO) { dao.setNotes(sessionId, notes) }; reload++ }
        }
    }

    Column(modifier) {
        ReviewHeader(
            d,
            onBack = onBack,
            onRename = { renaming = true },
            onAskAi = { SessionExport.askAi(context, d) },
            onCsv = { onExportCsv(SessionExport.fileStem(d) + ".csv", SessionExport.csv(d)) },
            onResearch = { pendingZip = d; zipLauncher.launch(SessionExport.fileStem(d) + "-research.zip") },
            onDelete = { confirmDelete = true },
            // Continue recording into this session (only when nothing else is recording).
            onResume = if (!d.session.active && liveSession == null) ({ MonitorHub.resumeSession(sessionId) { s -> if (s != null) { onMessage("Continuing \"${s.title}\""); reload++ } } }) else null
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 900.dp && maxHeight >= 500.dp) {
                val chartRoom = maxHeight.value - 150f - (if (d.hasEcg && cursor != null) 200f else 0f)
                val fits = entries.isNotEmpty() && chartRoom / entries.size >= 80f
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (fits) Column(Modifier.weight(1.7f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) { fittedCharts() }
                    else Column(Modifier.weight(1.7f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { charts() }
                    Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { details() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(d, flags)
                    DetailsCard(com.pulsepointlabs.sarahmonitor.session.SessionDetails.parse(d.session.details), category) { editingDetails = true }
                    charts()
                    episodesCard()
                    flagsCard()
                    EventsCard(d, selectedMarker, sessionId, onFocus = ::focus, onEdit = { editing = it }, onAdd = { addingAt = cursor ?: d.endMs }, onDelete = ::deleteMarker)
                    NotesCard(d.session.notes) { notes ->
                        scope.launch { withContext(Dispatchers.IO) { dao.setNotes(sessionId, notes) }; reload++ }
                    }
                }
            }
        }
    }

    editing?.let { m ->
        var timing by remember(m.id) { mutableStateOf(com.pulsepointlabs.sarahmonitor.session.EventTiming.of(m)) }
        MarkEventDialog(
            pressedAtMs = m.timestampMs,
            editing = m,
            timing = timing, onTiming = { timing = it }, sessionStartMs = d.session.startTimeMs,
            onSave = { types, note, checkIn ->
                saveMarker(m.copy(
                    types = MarkerTypes.join(types), label = MarkerTypes.label(types), note = note.trim(), wellbeing = checkIn.wellbeing, selfStress = checkIn.selfStress,
                    timestampMs = timing.startMs, endTimeMs = timing.endMs?.takeIf { it > timing.startMs },
                    source = timing.source.name.takeIf { timing.source != com.pulsepointlabs.sarahmonitor.session.EventSource.Live }, uncertaintyS = timing.uncertaintyS
                ))
                editing = null
            },
            onDelete = { deleteMarker(m); editing = null },
            onDismiss = { editing = null },
            category = com.pulsepointlabs.sarahmonitor.session.SessionCategory.of(data?.session?.category) ?: com.pulsepointlabs.sarahmonitor.session.SessionCategory.Hybrid
        )
    }
    addingAt?.let { t ->
        // Added after the session: defaults to "from memory", time adjustable (typed from video or notes).
        var timing by remember(t) { mutableStateOf(com.pulsepointlabs.sarahmonitor.session.EventTiming(t, null, com.pulsepointlabs.sarahmonitor.session.EventSource.Recollection, null)) }
        MarkEventDialog(
            pressedAtMs = t,
            editing = MarkerEntity("", sessionId, t, "event", "Event"),
            timing = timing, onTiming = { timing = it }, sessionStartMs = d.session.startTimeMs,
            onSave = { types, note, checkIn ->
                saveMarker(MarkerEntity(
                    UUID.randomUUID().toString(), sessionId, timing.startMs, MarkerTypes.join(types), MarkerTypes.label(types), note.trim(), wellbeing = checkIn.wellbeing, selfStress = checkIn.selfStress,
                    source = timing.source.name.takeIf { timing.source != com.pulsepointlabs.sarahmonitor.session.EventSource.Live },
                    endTimeMs = timing.endMs?.takeIf { it > timing.startMs }, uncertaintyS = timing.uncertaintyS
                ))
                addingAt = null
            },
            onDismiss = { addingAt = null },
            category = com.pulsepointlabs.sarahmonitor.session.SessionCategory.of(data?.session?.category) ?: com.pulsepointlabs.sarahmonitor.session.SessionCategory.Hybrid
        )
    }
    if (editingDetails) {
        SessionDetailsDialog(
            mode = DetailsMode.Edit, category = category,
            initial = com.pulsepointlabs.sarahmonitor.session.SessionDetails.parse(d.session.details),
            onDismiss = { editingDetails = false },
            onSave = { nd -> editingDetails = false; MonitorHub.saveDetails(sessionId, nd) { reload++ } }
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
                    scope.launch { withContext(Dispatchers.IO) { dao.setTitle(sessionId, title.ifBlank { d.session.title }) }; reload++ }
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
private fun ReviewHeader(d: SessionData, onBack: () -> Unit, onRename: () -> Unit, onAskAi: () -> Unit, onCsv: () -> Unit, onResearch: () -> Unit, onDelete: () -> Unit, onResume: (() -> Unit)? = null) {
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
                if (!narrow) Actions(onAskAi, onCsv, onResearch, onDelete, onResume)
            }
            if (narrow) Row(Modifier.padding(start = 8.dp)) { Actions(onAskAi, onCsv, onResearch, onDelete, onResume) }
        }
    }
}

@Composable
private fun Actions(onAskAi: () -> Unit, onCsv: () -> Unit, onResearch: () -> Unit, onDelete: () -> Unit, onResume: (() -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onResume != null) androidx.compose.material3.Button(
            onClick = onResume,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MonitorColors.pelvic, contentColor = MonitorColors.background)
        ) { Text("↻ Continue", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        OutlinedButton(onClick = onAskAi) { Text("✦ Ask AI", fontSize = 13.sp) }
        // One button, two exports: keeps the row the same width on a phone.
        Box {
            var open by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { open = true }) { Text("Export ▾", fontSize = 13.sp) }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Column { Text("CSV", fontSize = 15.sp, fontWeight = FontWeight.Bold); Text("One row per second", fontSize = 12.sp, color = MonitorColors.label) } },
                    onClick = { open = false; onCsv() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Column { Text("Research ZIP", fontSize = 15.sp, fontWeight = FontWeight.Bold); Text("Every sample, events, sensor log, README", fontSize = 12.sp, color = MonitorColors.label) } },
                    onClick = { open = false; onResearch() }
                )
            }
        }
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
private fun SummaryCard(d: SessionData, flags: List<SessionFlag>) {
    val s = d.session
    val pelvic = remember(d) { SessionAnalysis.pelvic(d.pelvic) }
    val loads = d.hr.mapNotNull { it.load }
    Section("Summary") {
        SessionAnalysis.plainSummary(d).forEach { Text(it, color = MonitorColors.text, fontSize = 14.sp) }
        SessionFlags.describeCounts(flags)?.let { Text("Flagged automatically: $it.", color = MonitorColors.warn, fontSize = 14.sp) }
        BalancedTiles(buildList {
            add(TileSpec("Avg HR", fmt(s.averageHr), "bpm", MonitorColors.hr))
            add(TileSpec("Min HR", s.minHr?.toString() ?: "--", "bpm", MonitorColors.sdnn))
            add(TileSpec("Max HR", s.maxHr?.toString() ?: "--", "bpm", MonitorColors.alarm))
            add(TileSpec("RMSSD", fmt(s.rmssdMs, 1), "ms", MonitorColors.rmssd))
            add(TileSpec("SDNN", fmt(s.sdnnMs, 1), "ms", MonitorColors.sdnn))
            add(TileSpec("pNN50", fmt(s.pnn50Percent, 0), "%", MonitorColors.pnn50))
            if (loads.isNotEmpty()) add(TileSpec("Load avg / max", "${"%.0f".format(loads.average())}/${loads.max()}", "", loadColor(loads.max())))
            d.hr.mapNotNull { it.stress }.takeIf { it.isNotEmpty() }?.let { add(TileSpec("Stress avg / max", "${it.average().toInt()}/${it.max()}", "", com.pulsepointlabs.sarahmonitor.ui.StressColor)) }
            d.hr.mapNotNull { it.breathsPerMin }.takeIf { it.isNotEmpty() }?.let { add(TileSpec("Breathing", "%.0f".format(it.average()), "/min", MonitorColors.pelvic)) }
            add(TileSpec("Events", d.markers.size.toString(), "", MonitorColors.text))
            if (d.pelvic.isNotEmpty()) {
                add(TileSpec("Pressure peak", fmt(pelvic.maxKpa, 1), "kPa", MonitorColors.pelvic))
                add(TileSpec("Contractions", pelvic.pulses.size.toString(), "", MonitorColors.pelvic))
                add(TileSpec("Longest hold", fmt(pelvic.longestHoldS, 1), "s", MonitorColors.pelvic))
            }
            d.bp.lastOrNull()?.let { add(TileSpec("Last BP", "${it.systolic}/${it.diastolic}", "mmHg", bpColor(it.systolic, it.diastolic))) }
            d.glucose.lastOrNull()?.let { add(TileSpec("Last glucose", fmt(it.mgDl), "mg/dL", MonitorColors.glucose)) }
        })
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

/** A metric at a moment, with its change over the minute before: 0–3 arrows by how sharp the change was. */
private data class EventMetric(val label: String, val value: String, val color: Color, val delta: Double, val scale: Double, val higherIsWorse: Boolean)

/** Readings at an event from the recording itself (so older events get them too), vs the minute before. */
private fun eventMetrics(d: SessionData, t: Long, snapshot: String?, sexual: Boolean): List<EventMetric> {
    val at = d.hr.minByOrNull { kotlin.math.abs(it.timestampMs - t) }?.takeIf { kotlin.math.abs(it.timestampMs - t) < 10_000L } ?: return emptyList()
    val before = d.hr.filter { it.timestampMs in (t - 90_000L)..(t - 30_000L) }
    fun avg(f: (com.pulsepointlabs.sarahmonitor.session.HrSampleEntity) -> Double?) = before.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
    val out = mutableListOf<EventMetric>()
    out += EventMetric("HR", "${at.bpm}", com.pulsepointlabs.sarahmonitor.ui.hrBandColor(at.bpm.toDouble()) ?: MonitorColors.hr, at.bpm - (avg { it.bpm.toDouble() } ?: at.bpm.toDouble()), 6.0, true)
    at.rmssdMs?.let { r -> out += EventMetric("RMSSD", SessionAnalysis.num(r), MonitorColors.rmssd, r - (avg { it.rmssdMs } ?: r), 4.0, false) }
    at.stress?.let { v -> out += EventMetric("Stress", "$v", com.pulsepointlabs.sarahmonitor.ui.percentBandColor(v) ?: com.pulsepointlabs.sarahmonitor.ui.StressColor, v - (avg { it.stress?.toDouble() } ?: v.toDouble()), 6.0, true) }
    at.load?.let { v -> out += EventMetric("Load", "$v", com.pulsepointlabs.sarahmonitor.ui.percentBandColor(v) ?: MonitorColors.load, v - (avg { it.load?.toDouble() } ?: v.toDouble()), 8.0, true) }
    at.breathsPerMin?.let { v -> out += EventMetric("Breathing", "${v.toInt()}/min", MonitorColors.pelvic, v - (avg { it.breathsPerMin } ?: v), 2.5, true) }
    d.pelvic.minByOrNull { kotlin.math.abs(it.timestampMs - t) }?.takeIf { kotlin.math.abs(it.timestampMs - t) < 2_000L }?.aboveRestKpa?.let { k ->
        out += EventMetric("Pelvic", "${SessionAnalysis.num(k)} kPa", MonitorColors.pelvic, 0.0, 1.0, true)
    }
    // Arousal phase only belongs on sexual sessions.
    if (sexual) runCatching { org.json.JSONObject(snapshot ?: "").optJSONObject("response") }.getOrNull()?.let { r ->
        val phase = r.optString("phase")
        if (phase.isNotBlank() && (phase != "Baseline" || r.optInt("approach") > 0))
            out += EventMetric("Phase", phase.lowercase() + " · " + r.optInt("approach"), MonitorColors.load, 0.0, 1.0, true)
    }
    return out
}

/** Chip like the overlays: value in its color, then ↑ / ↑↑ / ↑↑↑ (red when the change is the bad direction). */
@Composable
private fun MetricChip(m: EventMetric) {
    val n = (kotlin.math.abs(m.delta) / m.scale).let { when { it >= 3 -> 3; it >= 1.5 -> 2; it >= 0.6 -> 1; else -> 0 } }
    val up = m.delta > 0
    val arrowColor = if (up == m.higherIsWorse) MonitorColors.alarm else MonitorColors.good
    Row(
        Modifier.background(m.color.copy(alpha = 0.12f), RoundedCornerShape(7.dp)).border(1.dp, m.color.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(m.label + " ", color = MonitorColors.label, fontSize = 11.sp)
        Text(m.value, color = m.color, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        if (n > 0) Text(" " + (if (up) "↑" else "↓").repeat(n), color = arrowColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Heart rate (and RMSSD, faint) around an event, with the moment marked. */
@Composable
private fun EventSpark(d: SessionData, t: Long, spanMs: Long, color: Color, modifier: Modifier) {
    val win = d.hr.filter { it.timestampMs in (t - spanMs)..(t + spanMs) }
    Canvas(modifier) {
        if (win.size < 2) return@Canvas
        fun x(ts: Long) = (ts - (t - spanMs)).toFloat() / (2 * spanMs) * size.width
        fun line(values: List<Double?>, c: Color, w: Float) {
            val v = values.filterNotNull(); if (v.size < 2) return
            val lo = v.min(); val hi = v.max().let { if (it - lo < 1) lo + 1 else it }
            val path = Path(); var started = false
            win.forEachIndexed { i, s -> val y = values[i] ?: return@forEachIndexed
                val px = x(s.timestampMs); val py = size.height - ((y - lo) / (hi - lo)).toFloat() * (size.height - 4f) - 2f
                if (!started) path.moveTo(px, py) else path.lineTo(px, py); started = true }
            drawPath(path, c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        line(win.map { it.rmssdMs }, MonitorColors.rmssd.copy(alpha = 0.45f), 1.2f * density)
        line(win.map { it.bpm.toDouble() }, MonitorColors.hr, 1.6f * density)
        drawLine(color, Offset(x(t), 0f), Offset(x(t), size.height), strokeWidth = 1.5f * density)
    }
}

private fun isVoice(m: MarkerEntity) = m.snapshot?.contains("\"voice\"") == true

/** Chips for the numbers he gave in a note (from Sarah's read, or the plain read of a typed note) and her answer to a question. */
private fun procedureChips(m: MarkerEntity): Pair<List<EventMetric>, String?> {
    val voice = runCatching { org.json.JSONObject(m.snapshot ?: "").optJSONObject("voice") }.getOrNull()
    val ms = com.pulsepointlabs.sarahmonitor.session.Measures.fromJson(voice?.optJSONObject("measures")).takeIf { !it.empty }
        ?: com.pulsepointlabs.sarahmonitor.session.ProcedureLog.parseLocal(m.note)
    val out = mutableListOf<EventMetric>()
    fun chip(label: String, value: String, color: Color) { out += EventMetric(label, value, color, 0.0, 1.0, true) }
    ms.totalMl?.let { chip("In", com.pulsepointlabs.sarahmonitor.session.UsUnits.volume(it), MonitorColors.pelvic) }
    ms.addedMl?.let { chip("Added", "+" + com.pulsepointlabs.sarahmonitor.session.UsUnits.volume(it), MonitorColors.pelvic) }
    ms.depthCm?.let { chip("Depth", com.pulsepointlabs.sarahmonitor.session.UsUnits.depth(it), MonitorColors.label) }
    ms.urge?.let { chip("Urge", "$it/10", com.pulsepointlabs.sarahmonitor.ui.StressColor) }
    ms.discomfort?.let { chip("Discomfort", "$it/10", MonitorColors.alarm) }
    ms.fullness?.let { chip("Fullness", "$it/10", MonitorColors.pelvic) }
    ms.arousal?.let { chip("Arousal", "$it/10", MonitorColors.hr) }
    ms.erection?.let { chip("Erection", it, MonitorColors.hr) }
    return out to voice?.optString("answer")?.takeIf { it.isNotBlank() && it != "null" }
}

/** Events, split by how they were made: marked by you, or heard by Sarah ("Sarah, …"). */
@Composable
private fun EventsCard(
    d: SessionData, selected: String?, sessionId: String,
    onFocus: (MarkerEntity) -> Unit, onEdit: (MarkerEntity) -> Unit, onAdd: () -> Unit, onDelete: (MarkerEntity) -> Unit
) {
    var filter by remember { mutableStateOf(0) }
    val voice = d.markers.filter(::isVoice)
    val manual = d.markers.filterNot(::isVoice)
    val shown = when (filter) { 1 -> manual; 2 -> voice; else -> d.markers }
    var open by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<MarkerEntity?>(null) }
    val sexual = com.pulsepointlabs.sarahmonitor.session.SessionCategory.of(d.session.category)
        .let { it != null && it.sexual && it != com.pulsepointlabs.sarahmonitor.session.SessionCategory.Exploration }
    Section("Events", trailing = { TextButton(onClick = onAdd) { Text("+ Add at cursor", fontSize = 13.sp) } }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All" to d.markers.size, "Marked" to manual.size, "Sarah heard" to voice.size).forEachIndexed { i, (label, n) ->
                val on = filter == i
                Text(
                    "$label · $n", color = if (on) MonitorColors.background else MonitorColors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).background(if (on) MonitorColors.text else Color.Transparent, RoundedCornerShape(8.dp))
                        .border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(8.dp)).clickable { filter = i }.padding(vertical = 6.dp)
                )
            }
        }
        if (shown.isEmpty()) Text(if (filter == 2) "Nothing heard yet. Say \"Sarah, …\" during a recording." else "No events.", color = MonitorColors.label, fontSize = 13.sp)
        shown.forEach { m ->
            val color = markerColor(m.types)
            val expanded = open == m.id
            val isSel = m.id == selected
            val metrics = remember(m, d.hr.size) { eventMetrics(d, m.timestampMs, m.snapshot, sexual) }
            Column(
                Modifier.fillMaxWidth()
                    .background(if (isSel || expanded) color.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(10.dp))
                    .border(1.dp, if (isSel || expanded) color.copy(alpha = 0.6f) else MonitorColors.panelBorder, RoundedCornerShape(10.dp))
                    .clickable { open = if (expanded) null else m.id; onFocus(m) }
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                    Text(m.label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isVoice(m)) Text("🎙", fontSize = 12.sp)
                    Text("${clockLabel(m.timestampMs, seconds = true)} · +${SessionAnalysis.elapsed(m.timestampMs - d.session.startTimeMs)}", color = MonitorColors.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (m.note.isNotBlank()) Text(m.note, color = MonitorColors.text, fontSize = 13.sp, lineHeight = 18.sp)
                // The numbers in what he said (mL, depth, scores, erection) and, for a question, Sarah's answer.
                val said = remember(m) { procedureChips(m) }
                if (said.first.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { said.first.forEach { MetricChip(it) } }
                said.second?.let { Text("Sarah: $it", color = MonitorColors.rmssd, fontSize = 13.sp, lineHeight = 18.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
                com.pulsepointlabs.sarahmonitor.session.CheckIn.of(m).label?.let { Text("Check-in: $it", color = com.pulsepointlabs.sarahmonitor.ui.StressColor, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                if (metrics.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { metrics.forEach { MetricChip(it) } }
                if (!expanded) EventSpark(d, m.timestampMs, 120_000L, color, Modifier.fillMaxWidth().height(36.dp))
                if (expanded) {
                    // The moment in detail: pick a window, graphs around it, the ECG at that second.
                    var span by remember(m.id) { mutableStateOf(2 * 60_000L) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Window ±", color = MonitorColors.label, fontSize = 12.sp)
                        listOf(30_000L to "30 s", 60_000L to "1 min", 120_000L to "2 min", 300_000L to "5 min", 600_000L to "10 min").forEach { (ms, l) ->
                            val on = span == ms
                            Text(l, color = if (on) MonitorColors.background else MonitorColors.label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(if (on) MonitorColors.label else Color.Transparent, RoundedCornerShape(6.dp))
                                    .border(1.dp, MonitorColors.label.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).clickable { span = ms }.padding(horizontal = 7.dp, vertical = 3.dp))
                        }
                    }
                    val from = m.timestampMs - span; val to = m.timestampMs + span
                    val win = d.hr.filter { it.timestampMs in from..to }
                    val ts = win.map { it.timestampMs }.toLongArray()
                    MiniTraces(listOfNotNull(
                        com.pulsepointlabs.sarahmonitor.session.InsightTrace("Heart rate", "bpm", ts, win.map { it.bpm.toDouble() }.toDoubleArray()),
                        com.pulsepointlabs.sarahmonitor.session.InsightTrace("RMSSD", "ms", ts, win.map { it.rmssdMs ?: Double.NaN }.toDoubleArray()).takeIf { win.any { it.rmssdMs != null } },
                        com.pulsepointlabs.sarahmonitor.session.InsightTrace("Stress", "", ts, win.map { it.stress?.toDouble() ?: Double.NaN }.toDoubleArray()).takeIf { win.any { it.stress != null } },
                        d.pelvic.filter { it.timestampMs in from..to }.takeIf { it.isNotEmpty() }?.let { p -> com.pulsepointlabs.sarahmonitor.session.InsightTrace("Pelvic", "kPa", p.map { it.timestampMs }.toLongArray(), p.map { it.aboveRestKpa ?: Double.NaN }.toDoubleArray()) }
                    ), from, to, m.timestampMs - 1_000L, m.timestampMs + 1_000L, color)
                    if (d.hasEcg) EcgAtCursor(sessionId, m.timestampMs)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { confirmDelete = m }) { Text("Delete", color = MonitorColors.alarm, fontSize = 13.sp) }
                        TextButton(onClick = { onEdit(m) }) { Text("Edit", fontSize = 13.sp) }
                    }
                }
            }
        }
    }
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null }, containerColor = MonitorColors.panel,
            title = { Text("Delete this event?", color = MonitorColors.text) },
            text = { Text("${m.label} at ${clockLabel(m.timestampMs, seconds = true)}" + if (m.note.isNotBlank()) ": “${m.note.take(80)}”" else "", color = MonitorColors.label) },
            confirmButton = { TextButton(onClick = { onDelete(m); confirmDelete = null }) { Text("Delete", color = MonitorColors.alarm) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } }
        )
    }
}

fun flagColor(severity: FlagSeverity): Color = when (severity) {
    FlagSeverity.High -> MonitorColors.alarm
    FlagSeverity.Notable -> MonitorColors.warn
    FlagSeverity.Info -> MonitorColors.label
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

// ---- Insights: balanced tiles, expandable flags, response episodes, mini traces ----

private data class TileSpec(val label: String, val value: String, val unit: String, val color: Color)

/** Tiles in even rows that fill the width (no lonely last tile): columns chosen so rows balance. */
@Composable
private fun BalancedTiles(tiles: List<TileSpec>, minTileDp: Float = 100f) {
    if (tiles.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxCols = (maxWidth.value / minTileDp).toInt().coerceIn(2, 8)
        val rows = (tiles.size + maxCols - 1) / maxCols
        val cols = (tiles.size + rows - 1) / rows
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tiles.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { t ->
                        Column(
                            Modifier.weight(1f).background(t.color.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).border(1.dp, t.color.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(t.label, color = MonitorColors.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(t.value, color = t.color, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
                                if (t.unit.isNotBlank()) Text(" ${t.unit}", color = MonitorColors.label, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FactGrid(facts: List<com.pulsepointlabs.sarahmonitor.session.InsightFact>, color: Color) =
    BalancedTiles(facts.map { TileSpec(it.label, it.value, "", color) }, minTileDp = 110f)

/** Small stacked traces for a window, with the interesting span shaded. */
@Composable
private fun MiniTraces(traces: List<com.pulsepointlabs.sarahmonitor.session.InsightTrace>, fromMs: Long, toMs: Long, hiStart: Long, hiEnd: Long, color: Color) {
    val palette = listOf(color, MonitorColors.hr, MonitorColors.rmssd)
    traces.take(3).forEachIndexed { i, tr ->
        val c = if (tr.label == "Heart rate") MonitorColors.hr else if (tr.label == "RMSSD") MonitorColors.rmssd else palette[i]
        val vals = tr.values.filter { !it.isNaN() }
        if (vals.size < 2) return@forEachIndexed
        val lo = vals.min(); val hi = vals.max().let { if (it - lo < 1e-6) lo + 1 else it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(64.dp)) {
                Text(tr.label, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                fun r(v: Double) = if (hi - lo >= 10) "${kotlin.math.round(v).toInt()}" else SessionAnalysis.num(v)
                Text("${r(lo)}–${r(hi)}", color = MonitorColors.label, fontSize = 10.sp, maxLines = 1)
            }
            Canvas(Modifier.weight(1f).height(46.dp)) {
                val span = (toMs - fromMs).coerceAtLeast(1L).toFloat()
                fun x(t: Long) = (t - fromMs) / span * size.width
                drawRect(c.copy(alpha = 0.12f), Offset(x(hiStart), 0f), androidx.compose.ui.geometry.Size((x(hiEnd) - x(hiStart)).coerceAtLeast(2f), size.height))
                if (hiEnd - hiStart <= 2_000L) drawLine(color, Offset(x(hiStart), 0f), Offset(x(hiStart), size.height), 1.5f * density)
                drawLine(MonitorColors.trendGrid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                val path = Path(); var started = false; var lastT = Long.MIN_VALUE
                for (k in tr.timestamps.indices) {
                    val v = tr.values[k]
                    if (v.isNaN()) { started = false; continue }
                    val px = x(tr.timestamps[k]); val py = size.height - ((v - lo) / (hi - lo)).toFloat() * (size.height - 4f) - 2f
                    if (!started || tr.timestamps[k] - lastT > 10_000L) path.moveTo(px, py) else path.lineTo(px, py)
                    started = true; lastT = tr.timestamps[k]
                }
                drawPath(path, c, style = Stroke(width = 1.6f * density / 2f + 0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Things Sarah Monitor noticed by itself. Tap a row to open the breakdown (and jump to it on the graphs). */
@Composable
internal fun InsightFlagsCard(
    flags: List<SessionFlag>,
    d: SessionData,
    selected: SessionFlag?,
    showOnGraphs: Boolean,
    onToggle: (Boolean) -> Unit,
    onFocus: (SessionFlag) -> Unit,
    initiallyOpen: SessionFlag? = null
) {
    var showAll by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(initiallyOpen) }
    val sorted = flags.filter { it.kind != com.pulsepointlabs.sarahmonitor.session.FlagKind.HrSignalGap || flags.size < 4 }
        .sortedWith(compareByDescending<SessionFlag> { it.severity.ordinal }.thenBy { it.startMs })
    val visible = if (showAll) sorted else sorted.take(8)
    Section("Automatic flags (${flags.size})", trailing = {
        TextButton(onClick = { onToggle(!showOnGraphs) }) { Text(if (showOnGraphs) "Hide on graphs" else "Show on graphs", fontSize = 13.sp) }
    }) {
        if (flags.isEmpty()) Text("Nothing stood out.", color = MonitorColors.label, fontSize = 13.sp)
        visible.forEach { f ->
            val color = flagColor(f.severity)
            val expanded = open == f
            Column(
                Modifier.fillMaxWidth()
                    .background(if (f == selected || expanded) color.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .border(1.dp, if (f == selected || expanded) color.copy(alpha = 0.6f) else MonitorColors.panelBorder, RoundedCornerShape(8.dp))
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (expanded) null else f; onFocus(f) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(if (f.severity == FlagSeverity.High) "▲" else if (f.severity == FlagSeverity.Notable) "◆" else "•", color = color, fontSize = 13.sp)
                    Column(Modifier.weight(1f)) {
                        Text(f.kind.title, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(f.detail, color = MonitorColors.text, fontSize = 12.sp, maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(clockLabel(f.startMs, seconds = true) + if (f.durationMs >= 1_000L) "\n" + SessionAnalysis.durationWords(f.durationMs) else "",
                        color = MonitorColors.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MonitorColors.label)
                }
                if (expanded) {
                    val b = remember(f, d) { com.pulsepointlabs.sarahmonitor.session.FlagInsight.explain(f, d) }
                    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(b.story, color = MonitorColors.text, fontSize = 13.sp, lineHeight = 18.sp)
                        FactGrid(b.facts, color)
                        MiniTraces(b.traces, b.fromMs, b.toMs, f.startMs, f.endMs, color)
                    }
                }
            }
        }
        if (sorted.size > 8) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show fewer" else "Show all ${sorted.size}", fontSize = 13.sp) }
    }
}

val EpisodePink = Color(0xFFFF7AB6)

fun episodeLabel(e: com.pulsepointlabs.sarahmonitor.response.ResponseEpisode, exploration: Boolean): String =
    if (!exploration) e.kind.label else when (e.kind) {
        com.pulsepointlabs.sarahmonitor.response.EpisodeKind.Climax -> "Climax"
        com.pulsepointlabs.sarahmonitor.response.EpisodeKind.Build -> "Arousal rise"
        else -> "Strong arousal peak"
    }

/** Build → peak → recovery cycles found in the recording, each one openable for its numbers and a plain read. */
@Composable
internal fun EpisodesCard(
    episodes: List<com.pulsepointlabs.sarahmonitor.response.ResponseEpisode>,
    d: SessionData,
    exploration: Boolean,
    onFocus: (com.pulsepointlabs.sarahmonitor.response.ResponseEpisode) -> Unit,
    initiallyOpen: com.pulsepointlabs.sarahmonitor.response.ResponseEpisode? = null
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val near = episodes.count { it.kind == com.pulsepointlabs.sarahmonitor.response.EpisodeKind.NearClimax || it.kind == com.pulsepointlabs.sarahmonitor.response.EpisodeKind.Edge }
    val climax = episodes.count { it.kind == com.pulsepointlabs.sarahmonitor.response.EpisodeKind.Climax }
    // Exploration: a response is not assumed to be sexual arousal.
    Section(if (exploration) "Response peaks (${episodes.size})" else "Sexual response (${episodes.size} episodes)") {
        if (episodes.isEmpty()) {
            Text("No clear arousal build in this recording.", color = MonitorColors.label, fontSize = 13.sp)
            return@Section
        }
        if (!exploration) BalancedTiles(listOfNotNull(
            TileSpec("Near climax", near.toString(), "", MonitorColors.load),
            TileSpec("Climax", climax.toString(), "", EpisodePink),
            TileSpec("Closest", "${episodes.maxOf { it.peakApproach }}", "%", EpisodePink),
            episodes.mapNotNull { it.riseBpm }.maxOrNull()?.let { TileSpec("Biggest rise", "+${it.toInt()}", "bpm", MonitorColors.hr) },
            episodes.mapNotNull { it.halfRecoveryS }.takeIf { it.isNotEmpty() }?.let { TileSpec("Typical recovery", SessionAnalysis.durationWords(it.sorted()[it.size / 2] * 1000L), "", MonitorColors.pelvic) }
        ))
        episodes.forEachIndexed { i, e ->
            val color = when (e.kind) {
                com.pulsepointlabs.sarahmonitor.response.EpisodeKind.Climax -> EpisodePink
                com.pulsepointlabs.sarahmonitor.response.EpisodeKind.NearClimax, com.pulsepointlabs.sarahmonitor.response.EpisodeKind.Edge -> MonitorColors.load
                com.pulsepointlabs.sarahmonitor.response.EpisodeKind.Close -> MonitorColors.rmssd
                else -> MonitorColors.sdnn
            }
            val expanded = open == e
            Column(
                Modifier.fillMaxWidth()
                    .background(if (expanded) color.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .border(1.dp, if (expanded) color.copy(alpha = 0.6f) else MonitorColors.panelBorder, RoundedCornerShape(8.dp))
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (expanded) null else e; onFocus(e) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${i + 1}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(episodeLabel(e, exploration), color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${clockLabel(e.startMs, seconds = true)} · ${SessionAnalysis.durationWords(e.durationMs)}", color = MonitorColors.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        // Proximity bar: how close to climax it got.
                        Box(Modifier.fillMaxWidth().height(6.dp).background(MonitorColors.trendGrid, RoundedCornerShape(3.dp))) {
                            Box(Modifier.fillMaxWidth(e.peakApproach / 100f).height(6.dp).background(color, RoundedCornerShape(3.dp)))
                        }
                    }
                    Text("${e.peakApproach}%", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MonitorColors.label)
                }
                if (expanded) Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.story(), color = MonitorColors.text, fontSize = 13.sp, lineHeight = 18.sp)
                    FactGrid(listOfNotNull(
                        com.pulsepointlabs.sarahmonitor.session.InsightFact(if (exploration) "Peak intensity" else "Proximity", "${e.peakApproach}%"),
                        com.pulsepointlabs.sarahmonitor.session.InsightFact("Arousal", "${e.peakArousal}%"),
                        com.pulsepointlabs.sarahmonitor.session.InsightFact("Duration", SessionAnalysis.durationWords(e.durationMs)),
                        e.buildS?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("Build", SessionAnalysis.durationWords(it * 1000L)) },
                        e.peakHr?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("Peak HR", "${it.toInt()} bpm") },
                        e.riseBpm?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("Rise", "+${it.toInt()} bpm") },
                        e.rmssdAtPeak?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("HRV at peak", "${SessionAnalysis.num(it)} ms") },
                        e.pelvicPeakKpa?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("Pelvic peak", "${SessionAnalysis.num(it)} kPa") },
                        if (e.contractions > 0) com.pulsepointlabs.sarahmonitor.session.InsightFact("Contractions", "${e.contractions}") else null,
                        e.halfRecoveryS?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("Half recovery", SessionAnalysis.durationWords(it * 1000L)) },
                        e.drop60?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("HR drop 60 s", "−${it.toInt()} bpm") },
                        e.breathsAtPeak?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("Breathing", "${it.toInt()}/min") },
                        e.loadPeak?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("Load peak", "$it") },
                        e.recoveryFraction?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("Recovered", "${(it * 100).toInt()}% of rise") },
                        e.nextGapS?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("Next build", "in ${SessionAnalysis.durationWords(it * 1000L)}") },
                        e.stimAtPeak?.let { com.pulsepointlabs.sarahmonitor.session.InsightFact("Howl at peak", it.substringBefore(" · playing").substringBefore(" · paused")) },
                        com.pulsepointlabs.sarahmonitor.session.InsightFact("Data quality", e.confidence + (e.pressureUsablePct?.takeIf { it < 100 }?.let { " · pressure $it% usable" } ?: ""))
                    ), color)
                    val from = e.startMs - 180_000L; val to = e.endMs + 120_000L
                    val win = d.hr.filter { it.timestampMs in from..to }
                    val ts = win.map { it.timestampMs }.toLongArray()
                    MiniTraces(listOfNotNull(
                        com.pulsepointlabs.sarahmonitor.session.InsightTrace("Heart rate", "bpm", ts, win.map { it.bpm.toDouble() }.toDoubleArray()),
                        com.pulsepointlabs.sarahmonitor.session.InsightTrace("RMSSD", "ms", ts, win.map { it.rmssdMs ?: Double.NaN }.toDoubleArray()).takeIf { win.any { it.rmssdMs != null } },
                        d.pelvic.filter { it.timestampMs in from..to }.takeIf { it.isNotEmpty() }?.let { p -> com.pulsepointlabs.sarahmonitor.session.InsightTrace("Pelvic", "kPa", p.map { it.timestampMs }.toLongArray(), p.map { it.aboveRestKpa ?: Double.NaN }.toDoubleArray()) }
                    ), from, to, e.startMs, e.endMs, color)
                    if (e.markedEvents.isNotEmpty()) Text("Marked: " + e.markedEvents.joinToString(", "), color = MonitorColors.label, fontSize = 12.sp)
                }
            }
        }
        // Episode to episode: does each one peak higher, start higher, recover less? Plain numbers, no assumed trend.
        val progression = remember(episodes) { com.pulsepointlabs.sarahmonitor.response.ResponseEpisodes.progression(episodes) }
        if (progression.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Episode to episode", color = MonitorColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            progression.forEach { Text(it, color = MonitorColors.label, fontSize = 13.sp, lineHeight = 18.sp) }
            Text("Candidate episodes from heart rate, HRV and pressure; not confirmed events.", color = MonitorColors.dim, fontSize = 12.sp)
        }
    }
}

/** Kegel sets in this session: score, per-rep quality, and a plain read (fatigue, relaxation). */
@Composable
internal fun KegelCard(sets: List<com.pulsepointlabs.sarahmonitor.kegel.KegelRecord>, onFocus: (com.pulsepointlabs.sarahmonitor.kegel.KegelRecord) -> Unit, onDetails: (com.pulsepointlabs.sarahmonitor.kegel.KegelRecord) -> Unit = {}) {
    val pink = EpisodePink
    Section(if (sets.size == 1) "Kegel set" else "Kegel sets (${sets.size})") {
        sets.forEach { k ->
            Column(
                Modifier.fillMaxWidth().border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(8.dp)).clickable { onFocus(k) }.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k.routineLabel, color = pink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${clockLabel(k.startMs, seconds = true)} · ${k.reps} reps · ${"%.0f".format(k.holdS)} s / ${"%.0f".format(k.restS)} s", color = MonitorColors.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                BalancedTiles(listOf(
                    TileSpec("Score", "${k.score}", "", if (k.score >= 70) MonitorColors.good else if (k.score >= 40) MonitorColors.warn else MonitorColors.alarm),
                    TileSpec("Strength", k.strengthPct?.toString() ?: "--", "% ref", pink),
                    TileSpec("Relax", k.relaxPct?.toString() ?: "--", "%", MonitorColors.pelvic),
                    TileSpec("On target", k.repScores.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.toInt()?.toString() ?: "--", "%", MonitorColors.good)
                ))
                // Per-rep bars: height = on-target %, dot = strength.
                if (k.repScores.isNotEmpty()) Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                    val w = size.width / k.repScores.size
                    k.repScores.forEachIndexed { i, (_, on, str) ->
                        val h = size.height * on / 100f
                        val c = if (on >= 70) MonitorColors.good else if (on >= 40) MonitorColors.warn else MonitorColors.alarm
                        drawRoundRect(c.copy(alpha = 0.8f), Offset(i * w + w * 0.18f, size.height - h), androidx.compose.ui.geometry.Size(w * 0.64f, h), androidx.compose.ui.geometry.CornerRadius(3f))
                        str?.let { drawCircle(pink, 3.5f * density / 2f + 1.5f, Offset(i * w + w / 2, size.height - size.height * (it.coerceIn(0, 120) / 120f))) }
                    }
                }
                Text("Bars: time on target per rep · dots: strength (% of reference)", color = MonitorColors.dim, fontSize = 11.sp)
                Text(k.story(), color = MonitorColors.text, fontSize = 13.sp, lineHeight = 18.sp)
                Text(
                    "See every squeeze in detail", color = MonitorColors.background, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(pink, RoundedCornerShape(8.dp)).clickable { onDetails(k) }.padding(vertical = 9.dp)
                )
            }
        }
    }
}
