package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.response.urgent
import com.pulsepointlabs.sarahmonitor.ble.ConnectionState
import com.pulsepointlabs.sarahmonitor.core.CaptureKind
import com.pulsepointlabs.sarahmonitor.core.Metric
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.core.RecentHistory
import com.pulsepointlabs.sarahmonitor.core.RestSource
import kotlinx.coroutines.delay

/** Actions a card can ask the screen to perform (absent in overlays). */
class CardActions(
    val onAddGlucose: () -> Unit = {},
    val onImportGlucose: () -> Unit = {},
    val onOpenDevices: () -> Unit = {},
    val onMessage: (String) -> Unit = {}
)

val LocalCardActions = compositionLocalOf<CardActions?> { null }

/** Design size for each card (dp) — text scales relative to this. */
private fun designSize(type: CardType): Pair<Float, Float> = when (type) {
    CardType.HeartRate -> 260f to 230f
    CardType.Hrv -> 260f to 230f
    CardType.Load -> 260f to 150f
    CardType.Ecg -> 560f to 230f
    CardType.HrTrend, CardType.HrvTrend, CardType.PelvicTrend -> 560f to 190f
    CardType.Pelvic -> 380f to 420f
    CardType.BloodPressure -> 380f to 260f
    CardType.Glucose -> 260f to 200f
    CardType.SpO2, CardType.Body -> 260f to 200f
    CardType.Emg -> 380f to 260f
    CardType.Phase -> 380f to 220f
    CardType.NearClimax -> 360f to 240f
    CardType.Matrix -> 360f to 220f
    CardType.ResponseTimeline, CardType.Cardiac -> 600f to 220f
    CardType.Recovery, CardType.Respiration -> 220f to 200f
    CardType.SarahLive -> 380f to 240f
    CardType.QuickMarks -> 380f to 180f
}

private fun identity(type: CardType): Color = when (type) {
    CardType.HeartRate, CardType.HrTrend -> MonitorColors.hr
    CardType.Ecg -> MonitorColors.ecg
    CardType.Hrv, CardType.HrvTrend -> MonitorColors.rmssd
    CardType.Load -> MonitorColors.load
    CardType.Pelvic, CardType.PelvicTrend -> MonitorColors.pelvic
    CardType.BloodPressure -> MonitorColors.bp
    CardType.Glucose -> MonitorColors.glucose
    CardType.SpO2 -> com.pulsepointlabs.sarahmonitor.vitals.SpO2Color
    CardType.Body -> com.pulsepointlabs.sarahmonitor.vitals.BodyColor
    CardType.Emg -> com.pulsepointlabs.sarahmonitor.emg.EmgColor
    CardType.Phase, CardType.Matrix, CardType.ResponseTimeline, CardType.QuickMarks -> MonitorColors.label
    CardType.NearClimax, CardType.SarahLive -> com.pulsepointlabs.sarahmonitor.response.ClimaxPink
    CardType.Cardiac -> MonitorColors.hr
    CardType.Recovery, CardType.Respiration -> MonitorColors.pelvic
}

/** One dashboard card (also used, compact, in the floating overlays). */
@Composable
fun MonitorCard(type: CardType, modifier: Modifier = Modifier) {
    val (dw, dh) = designSize(type)
    val newBp by MonitorHub.lastLiveBpAt.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000L); now = System.currentTimeMillis() } }
    val response by MonitorHub.response.collectAsState()
    val flash = (type == CardType.BloodPressure && newBp != null && now - newBp!! < 15_000L) ||
        ((type == CardType.NearClimax || type == CardType.Phase) && response.urgent())
    MonitorPanel(identity(type), modifier, alarm = flash && (now / 500) % 2 == 0L) {
        ScaledCard(dw, dh, Modifier.fillMaxSize()) {
            val s = LocalCardScale.current
            val overlay = LocalOverlayMode.current
            Box(Modifier.fillMaxSize().padding(horizontal = if (overlay) 6.dp else s.dp(9f), vertical = if (overlay) 4.dp else s.dp(6f))) {
                when (type) {
                    CardType.HeartRate -> HeartRateCard()
                    CardType.Hrv -> HrvCard()
                    CardType.Load -> LoadCard()
                    CardType.Ecg -> EcgCard()
                    CardType.HrTrend -> HrTrendCard()
                    CardType.HrvTrend -> HrvTrendCard()
                    CardType.Pelvic -> PelvicCard()
                    CardType.PelvicTrend -> PelvicTrendCard()
                    CardType.BloodPressure -> BloodPressureCard()
                    CardType.Glucose -> GlucoseCard()
                    CardType.SpO2 -> com.pulsepointlabs.sarahmonitor.vitals.SpO2Card()
                    CardType.Body -> com.pulsepointlabs.sarahmonitor.vitals.BodyCard()
                    CardType.Emg -> com.pulsepointlabs.sarahmonitor.emg.EmgCard()
                    CardType.Phase -> com.pulsepointlabs.sarahmonitor.response.PhaseCard()
                    CardType.NearClimax -> com.pulsepointlabs.sarahmonitor.response.NearClimaxCard()
                    CardType.Matrix -> com.pulsepointlabs.sarahmonitor.response.MatrixCard()
                    CardType.ResponseTimeline -> com.pulsepointlabs.sarahmonitor.response.ResponseTimelineCard()
                    CardType.Cardiac -> com.pulsepointlabs.sarahmonitor.response.CardiacCard()
                    CardType.Recovery -> com.pulsepointlabs.sarahmonitor.response.RecoveryCard()
                    CardType.Respiration -> com.pulsepointlabs.sarahmonitor.response.RespirationCard()
                    CardType.SarahLive -> com.pulsepointlabs.sarahmonitor.response.SarahLiveCard()
                    CardType.QuickMarks -> com.pulsepointlabs.sarahmonitor.response.QuickMarksCard()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) {
    if (LocalOverlayMode.current) {
        // In overlays the chips grow with the overlay's width so labels never stay tiny on a big overlay.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val base = LocalCardScale.current
            val f = (maxWidth.value / 260f).coerceIn(0.85f, 2.4f)
            androidx.compose.runtime.CompositionLocalProvider(LocalCardScale provides base.copy(factor = maxOf(base.factor, f))) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) { content() }
            }
        }
        return
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) { content() }
}

@Composable
private fun StatusLine(text: String, color: Color = MonitorColors.label) {
    if (LocalOverlayMode.current) return
    val s = LocalCardScale.current
    Text(text, color = color, fontSize = s.sp(11f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun linkLabel(state: ConnectionState, fresh: Boolean): Pair<String, Color> = when {
    state == ConnectionState.Connected && fresh -> "Live" to MonitorColors.good
    state == ConnectionState.Connected || state == ConnectionState.Stale -> "Waiting for data" to MonitorColors.warn
    state == ConnectionState.Connecting || state == ConnectionState.Reconnecting -> "Connecting…" to MonitorColors.warn
    state == ConnectionState.Scanning -> "Scanning…" to MonitorColors.warn
    state == ConnectionState.NotConfigured -> "Not set up" to MonitorColors.dim
    else -> "Disconnected" to MonitorColors.alarm
}

@Composable
private fun HeartRateCard() {
    val heart by MonitorHub.heart.collectAsState()
    val recent by MonitorHub.recent.collectAsState()
    val state by MonitorHub.h10.state.collectAsState()
    val fresh = heart.updatedAtMs?.let { System.currentTimeMillis() - it < 5_000L } == true
    val (link, linkColor) = linkLabel(state, fresh)
    val tint = trendColor(recent.trend(Metric.Hr))
    val beatMs = if (!fresh) null else heart.latestRrMs?.takeIf { it in 300..2_000 }?.toLong()
        ?: heart.heartRate?.takeIf { it in 30..220 }?.let { 60_000L / it }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle("HR", if (LocalOverlayMode.current) tint else MonitorColors.hr, "bpm") { Text(link, color = linkColor, fontSize = LocalCardScale.current.sp(11f), maxLines = 1) }
        BigReading(
            value = if (fresh) heart.heartRate?.toString() ?: "--" else "--",
            color = tint,
            maxChars = 3,
            trend = recent.values(Metric.Hr),
            trendStrength = recent.trend(Metric.Hr),
            leading = { size -> BeatingHeart(beatMs, Color(0xFFFB7185), size) },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Chips {
            StatChip("avg", fmt(heart.averageHr))
            StatChip("min", heart.minHr?.toString() ?: "--")
            StatChip("max", heart.maxHr?.toString() ?: "--")
            StatChip("load", heart.load.score?.toString() ?: "--", loadColor(heart.load.score))
        }
    }
}

@Composable
private fun HrvCard() {
    val heart by MonitorHub.heart.collectAsState()
    val recent by MonitorHub.recent.collectAsState()
    val s = LocalCardScale.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle("HRV", MonitorColors.rmssd, "2 min") { Text(heart.hrvQuality, color = MonitorColors.label, fontSize = s.sp(11f), maxLines = 1) }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth > maxHeight * 1.5f
            val items = listOf(
                HrvItem("RMSSD", fmt(heart.rmssdMs, 1), MonitorColors.rmssd, 5, Metric.Rmssd),
                HrvItem("SDNN", fmt(heart.sdnnMs, 1), MonitorColors.sdnn, 5, Metric.Sdnn),
                HrvItem("pNN50", fmt(heart.pnn50Percent), MonitorColors.pnn50, 3, Metric.Pnn50)
            )
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEach { HrvValue(it, recent, Modifier.weight(1f).fillMaxSize()) }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.forEach { HrvValue(it, recent, Modifier.weight(1f).fillMaxWidth()) }
                }
            }
        }
        StatusLine("RR coverage ${fmt(heart.rrCoveragePercent)}% · ms, pNN50 in %")
    }
}

private data class HrvItem(val label: String, val value: String, val color: Color, val maxChars: Int, val metric: Metric)

@Composable
private fun HrvValue(item: HrvItem, recent: RecentHistory, modifier: Modifier) {
    val s = LocalCardScale.current
    val tint = trendColor(recent.trend(item.metric))
    val values = recent.values(item.metric)
    BoxWithConstraints(modifier) {
        if (maxWidth > maxHeight * 2.2f) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, color = if (LocalOverlayMode.current) tint else item.color, fontSize = s.sp(12f), fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.3f))
                BigReading(item.value, tint, Modifier.weight(0.7f).fillMaxSize(), maxChars = item.maxChars, trend = values, trendStrength = recent.trend(item.metric))
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.label, color = if (LocalOverlayMode.current) tint else item.color, fontSize = s.sp(12f), fontWeight = FontWeight.Bold)
                BigReading(item.value, tint, Modifier.fillMaxWidth().weight(1f), maxChars = item.maxChars, trend = values, trendStrength = recent.trend(item.metric))
            }
        }
    }
}

@Composable
private fun LoadCard() {
    val heart by MonitorHub.heart.collectAsState()
    val recent by MonitorHub.recent.collectAsState()
    val load = heart.load
    val color = loadColor(load.score)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle("Load", if (LocalOverlayMode.current) trendColor(recent.trend(Metric.Load)) else MonitorColors.load, "0–100") { Text(loadStatus(load), color = if (load.score == null) MonitorColors.warn else color, fontSize = LocalCardScale.current.sp(12f), fontWeight = FontWeight.Bold, maxLines = 1) }
        BigReading(
            load.score?.toString() ?: "--", trendColor(recent.trend(Metric.Load)), Modifier.fillMaxWidth().weight(1f),
            maxChars = 3, trend = recent.values(Metric.Load), trendStrength = recent.trend(Metric.Load)
        )
        Chips {
            StatChip("HR load", load.hrLoadPercent?.let { "$it%" } ?: "--")
            StatChip("HRV supp.", load.hrvSuppressionPercent?.let { "$it%" } ?: "--")
            StatChip("conf.", load.confidencePercent?.let { "$it%" } ?: "--")
        }
        StatusLine(loadDetail(load), if (load.score == null) MonitorColors.warn else MonitorColors.label)
    }
}

@Composable
private fun EcgCard() {
    val ecg by MonitorHub.h10.ecgSamples.collectAsState()
    val heart by MonitorHub.heart.collectAsState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle("ECG", MonitorColors.ecg, "H10") {
            Text("${heart.heartRate ?: "--"} bpm", color = MonitorColors.hr, fontSize = LocalCardScale.current.sp(12f), fontWeight = FontWeight.Bold, maxLines = 1)
        }
        EcgSweep(ecg, Modifier.fillMaxWidth().weight(1f))
    }
}

private enum class TrendWindow(val label: String, val ms: Long?) {
    M5("5m", 5 * 60_000L), M15("15m", 15 * 60_000L), H1("1h", 60 * 60_000L), H4("4h", 4 * 60 * 60_000L), All("All", null)
}

@Composable
private fun WindowPicker(selected: TrendWindow, onSelect: (TrendWindow) -> Unit) {
    val s = LocalCardScale.current
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        TrendWindow.entries.forEach { w ->
            val on = w == selected
            Text(
                w.label,
                color = if (on) MonitorColors.background else MonitorColors.label,
                fontSize = s.sp(11f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(if (on) MonitorColors.label else Color.Transparent, RoundedCornerShape(4.dp))
                    .border(1.dp, MonitorColors.label.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .clickable { onSelect(w) }
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun TrendCardFrame(
    title: String,
    color: Color,
    unit: String,
    current: @Composable () -> Unit = {},
    content: @Composable (startMs: Long, endMs: Long) -> Unit
) {
    var window by rememberSaveable(title) { mutableStateOf(TrendWindow.M15) }
    val trends by MonitorHub.trends.collectAsState()
    val end = trends.lastOrNull()?.timestampMs ?: System.currentTimeMillis()
    val start = window.ms?.let { end - it } ?: (trends.firstOrNull()?.timestampMs ?: end - 60_000L)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle(title, color, unit) {
            current()
            WindowPicker(window) { window = it }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) { content(start, end) }
    }
}

@Composable
private fun HrTrendCard() {
    val trends by MonitorHub.trends.collectAsState()
    val heart by MonitorHub.heart.collectAsState()
    val recent by MonitorHub.recent.collectAsState()
    TrendCardFrame("HR trend", MonitorColors.hr, "bpm", current = {
        LiveValue(null, heart.heartRate?.toString(), 3, trendColor(recent.trend(Metric.Hr)))
    }) { start, end ->
        val avg = trends.filter { it.timestampMs >= start }.mapNotNull { it.hr }.takeIf { it.isNotEmpty() }?.average()
        TrendChart(
            series = listOf(Series("HR", MonitorColors.hr, trends.map { it.timestampMs to it.hr })),
            startMs = start, endMs = end, modifier = Modifier.fillMaxSize(), minSpan = 20.0,
            refLines = listOfNotNull(avg?.let { RefLine(it, "avg ${"%.0f".format(it)}", MonitorColors.label) })
        )
    }
}

@Composable
private fun HrvTrendCard() {
    val trends by MonitorHub.trends.collectAsState()
    val heart by MonitorHub.heart.collectAsState()
    val recent by MonitorHub.recent.collectAsState()
    TrendCardFrame("HRV trend", MonitorColors.rmssd, "ms", current = {
        LiveValue("RMSSD", heart.rmssdMs?.let { fmt(it, 1) }, 5, trendColor(recent.trend(Metric.Rmssd)))
        LiveValue("SDNN", heart.sdnnMs?.let { fmt(it, 1) }, 5, trendColor(recent.trend(Metric.Sdnn)))
    }) { start, end ->
        Column(Modifier.fillMaxSize()) {
            TrendChart(
                series = listOf(
                    Series("RMSSD", MonitorColors.rmssd, trends.map { it.timestampMs to it.rmssd }),
                    Series("SDNN", MonitorColors.sdnn, trends.map { it.timestampMs to it.sdnn })
                ),
                startMs = start, endMs = end, modifier = Modifier.fillMaxWidth().weight(1f), minSpan = 10.0, decimals = 1
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Legend("RMSSD", MonitorColors.rmssd); Legend("SDNN", MonitorColors.sdnn)
            }
        }
    }
}

/** A live number for a card header, padded to a fixed width so it never shifts its neighbours. */
@Composable
private fun LiveValue(label: String?, value: String?, maxChars: Int, color: Color) {
    val s = LocalCardScale.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
        if (label != null) Text("$label ", color = MonitorColors.label, fontSize = s.sp(11f), maxLines = 1)
        Text(
            (value ?: "--").padStart(maxChars),
            color = if (value == null) MonitorColors.dim else color,
            fontSize = s.sp(15f),
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    val s = LocalCardScale.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        Box(Modifier.padding(end = 4.dp).background(color, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
        Text(label, color = color, fontSize = s.sp(11f))
    }
}

@Composable
private fun PelvicTrendCard() {
    val trends by MonitorHub.trends.collectAsState()
    val pelvic by MonitorHub.pelvic.collectAsState()
    val recent by MonitorHub.recent.collectAsState()
    TrendCardFrame("Pressure trend", MonitorColors.pelvic, "kPa", current = {
        LiveValue(null, pelvic.aboveRestKpa?.let { fmt(it, 1) }, 4, trendColor(recent.trend(Metric.Pelvic)))
    }) { start, end ->
        TrendChart(
            series = listOf(Series("Above rest", MonitorColors.pelvic, trends.map { it.timestampMs to it.pelvic })),
            startMs = start, endMs = end, modifier = Modifier.fillMaxSize(), minSpan = 1.0, decimals = 1,
            emptyText = "Connect CIVET to see the pressure trend"
        )
    }
}

@Composable
private fun PelvicCard() {
    val p by MonitorHub.pelvic.collectAsState()
    val state by MonitorHub.civet.state.collectAsState()
    val error by MonitorHub.civet.error.collectAsState()
    val s = LocalCardScale.current
    val actions = LocalCardActions.current
    val fresh = p.trace.lastOrNull()?.let { System.currentTimeMillis() - it.timestampMs < 2_000L } == true
    val (link, linkColor) = linkLabel(state, fresh)
    val holdText = when {
        p.holding -> "hold ${fmt(p.holdSeconds, 1)}s"
        p.lastPulse != null -> "last ${fmt(p.lastPulse!!.durationS, 1)}s"
        else -> "hold --"
    }
    val recent by MonitorHub.recent.collectAsState()
    val color = trendColor(recent.trend(Metric.Pelvic))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        CardTitle("Pelvic pressure", if (LocalOverlayMode.current) color else MonitorColors.pelvic, "kPa") { Text(link, color = linkColor, fontSize = s.sp(11f), maxLines = 1) }
        BigReading(
            value = if (fresh) fmt(p.aboveRestKpa, 1) else "--",
            color = color,
            maxChars = 4,
            trend = recent.values(Metric.Pelvic),
            trendStrength = recent.trend(Metric.Pelvic),
            modifier = Modifier.fillMaxWidth().weight(if (s.compact) 1f else 0.9f)
        )
        Chips {
            if (p.referenceKpa != null) StatChip("of ref", p.percentOfReference?.takeIf { fresh }?.let { "${"%.0f".format(it)}%" } ?: "--", MonitorColors.pelvic)
            StatChip("30s avg", fmt(p.average30s, 1))
            StatChip("30s max", fmt(p.max30s, 1))
            StatChip("max", fmt(p.sessionMax, 1))
            StatChip(if (p.holding) "hold" else "last", holdText.substringAfter(' '), if (p.holding) MonitorColors.pelvic else MonitorColors.text)
            StatChip("pulses/min", p.pulsesLastMinute.toString())
            if (s.roomy) StatChip("longest", p.longestHoldS?.let { "${fmt(it, 1)}s" } ?: "--")
        }
        if (!s.compact) {
            PelvicWaveform(
                points = p.trace,
                thresholdKpa = p.thresholdKpa,
                referenceKpa = p.referenceKpa,
                windowMs = if (p.holding) 10_000L else 20_000L,
                modifier = Modifier.fillMaxWidth().weight(1.1f)
            )
        }
        val capture = p.capture
        val status = when {
            capture != null -> "${capture.message} ${"%.0f".format(kotlin.math.ceil(capture.remainingS))}s"
            error != null && !fresh -> error!!
            p.message != null -> p.message!!
            p.restSource == RestSource.Auto -> "Auto rest"
            p.restSource == RestSource.Captured -> "Rest ${fmt(p.restKpa, 2)} kPa · threshold ${fmt(p.thresholdKpa, 2)}"
            else -> ""
        }
        StatusLine(status, if (capture != null) MonitorColors.warn else MonitorColors.label)
        if (actions != null && !s.compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                MiniButton("Capture rest", MonitorColors.pelvic) {
                    MonitorHub.startPelvicCapture(CaptureKind.Rest)?.let(actions.onMessage)
                }
                MiniButton("Reference hold", MonitorColors.pelvic) {
                    MonitorHub.startPelvicCapture(CaptureKind.Reference)?.let(actions.onMessage)
                }
                MiniButton("Auto rest", MonitorColors.label) { MonitorHub.clearPelvicCalibration() }
            }
        }
    }
}

@Composable
fun MiniButton(label: String, color: Color, onClick: () -> Unit) {
    val s = LocalCardScale.current
    Text(
        label,
        color = color,
        fontSize = s.sp(11f),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun BloodPressureCard() {
    val readings by MonitorHub.bp.items.collectAsState()
    val state by MonitorHub.omron.state.collectAsState()
    val s = LocalCardScale.current
    val latest = readings.lastOrNull()
    val cuff = when (state) {
        ConnectionState.WaitingForCuff, ConnectionState.Scanning -> "Cuff ready · take a reading"
        ConnectionState.Connecting, ConnectionState.Connected, ConnectionState.Receiving -> "Receiving from cuff…"
        ConnectionState.ReadingSaved -> "Reading received"
        ConnectionState.NotConfigured -> "Cuff not set up"
        else -> "Cuff idle"
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle("Blood pressure", if (LocalOverlayMode.current) latest?.let { bpColor(it.systolic, it.diastolic) } ?: MonitorColors.bp else MonitorColors.bp, "mmHg") {
            Text(latest?.let { agoLabel(it.timestampMs) } ?: "", color = MonitorColors.label, fontSize = s.sp(11f), maxLines = 1)
        }
        BigReading(
            value = latest?.let { "${it.systolic}/${it.diastolic}" } ?: "--/--",
            maxChars = 7,
            color = latest?.let { bpColor(it.systolic, it.diastolic) } ?: MonitorColors.dim,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Chips {
            StatChip("pulse", latest?.pulse?.toString() ?: "--")
            StatChip("MAP", latest?.let { fmt(it.meanArterial) } ?: "--")
            if (latest?.irregular == true) StatChip("IHB", "yes", MonitorColors.warn)
            val previous = readings.dropLast(1).lastOrNull()
            if (latest != null && previous != null) {
                val d = latest.systolic - previous.systolic
                StatChip("Δ sys", "${if (d > 0) "+" else ""}$d")
            }
        }
        BpPositionLine(latest)
        if (!s.compact && readings.size >= 2) {
            val recent = readings.takeLast(20)
            TrendChart(
                series = listOf(
                    Series("SYS", MonitorColors.bp, recent.map { it.timestampMs to it.systolic.toDouble() }),
                    Series("DIA", MonitorColors.bpDia, recent.map { it.timestampMs to it.diastolic.toDouble() })
                ),
                startMs = recent.first().timestampMs, endMs = recent.last().timestampMs.coerceAtLeast(recent.first().timestampMs + 60_000L),
                modifier = Modifier.fillMaxWidth().weight(0.9f), minSpan = 30.0, gapMs = Long.MAX_VALUE, dots = true
            )
        }
        StatusLine(cuff)
    }
}

@Composable
private fun GlucoseCard() {
    val readings by MonitorHub.glucose.items.collectAsState()
    val s = LocalCardScale.current
    val actions = LocalCardActions.current
    val latest = readings.lastOrNull()
    val now = System.currentTimeMillis()
    val day = readings.filter { now - it.timestampMs <= 86_400_000L }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle("Glucose", MonitorColors.glucose, "mg/dL") {
            Text(latest?.let { agoLabel(it.timestampMs) } ?: "", color = MonitorColors.label, fontSize = s.sp(11f), maxLines = 1)
        }
        BigReading(latest?.let { fmt(it.mgDl) } ?: "--", glucoseColor(latest?.mgDl), Modifier.fillMaxWidth().weight(1f), maxChars = 3)
        Chips {
            val previous = readings.dropLast(1).lastOrNull()
            if (latest != null && previous != null) StatChip("Δ", "${if (latest.mgDl >= previous.mgDl) "+" else ""}${fmt(latest.mgDl - previous.mgDl)}")
            StatChip("24h avg", day.takeIf { it.isNotEmpty() }?.let { fmt(it.map { r -> r.mgDl }.average()) } ?: "--")
            StatChip("24h n", day.size.toString())
        }
        if (s.roomy && readings.size >= 2) {
            val recent = readings.filter { now - it.timestampMs <= 7 * 86_400_000L }
            if (recent.size >= 2) {
                TrendChart(
                    series = listOf(Series("Glucose", MonitorColors.glucose, recent.map { it.timestampMs to it.mgDl })),
                    startMs = recent.first().timestampMs, endMs = now,
                    modifier = Modifier.fillMaxWidth().weight(0.9f), minSpan = 40.0, gapMs = Long.MAX_VALUE, dots = true,
                    refLines = listOf(RefLine(70.0, "70", MonitorColors.warn), RefLine(180.0, "180", MonitorColors.warn))
                )
            }
        }
        if (actions != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                MiniButton("+ Add", MonitorColors.glucose, actions.onAddGlucose)
                MiniButton("Import", MonitorColors.label, actions.onImportGlucose)
            }
        }
    }
}


/** "Upper arm · Sitting ✎" under the BP reading; tap to set the cuff site and posture. */
@Composable
private fun BpPositionLine(latest: com.pulsepointlabs.sarahmonitor.core.BpEntry?) {
    val s = LocalCardScale.current
    val defSite by com.pulsepointlabs.sarahmonitor.core.BpPosition.defaultSite.collectAsState()
    val defPosture by com.pulsepointlabs.sarahmonitor.core.BpPosition.defaultPosture.collectAsState()
    var open by remember { mutableStateOf(false) }
    val label = latest?.positionLabel ?: "Next: ${com.pulsepointlabs.sarahmonitor.core.BpPosition.siteLabel(defSite)} · ${com.pulsepointlabs.sarahmonitor.core.BpPosition.postureLabel(defPosture)}"
    Text(
        "$label  ✎",
        color = MonitorColors.bp, fontSize = s.sp(12f), maxLines = 1,
        modifier = Modifier.clickable { open = true }.padding(vertical = 2.dp)
    )
    if (open) BpPositionDialog(latest) { open = false }
}

@Composable
fun BpPositionDialog(entry: com.pulsepointlabs.sarahmonitor.core.BpEntry?, onDismiss: () -> Unit) {
    val pos = com.pulsepointlabs.sarahmonitor.core.BpPosition
    var site by remember { mutableStateOf(entry?.site ?: pos.defaultSite.value) }
    var posture by remember { mutableStateOf(entry?.posture ?: pos.defaultPosture.value) }
    var asDefault by remember { mutableStateOf(true) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MonitorColors.panel,
        title = { Text(if (entry != null) "Cuff site & position · ${entry.systolic}/${entry.diastolic}" else "Cuff site & position for the next reading", color = MonitorColors.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cuff site", color = MonitorColors.label, fontSize = 13.sp)
                ChoiceRow { pos.sites.forEach { (id, l) -> ChoiceChip(l, id == site, MonitorColors.bp) { site = id } } }
                Text("Position", color = MonitorColors.label, fontSize = 13.sp)
                ChoiceRow { pos.postures.forEach { (id, l) -> ChoiceChip(l, id == posture, MonitorColors.bp) { posture = id } } }
                if (site == "thigh" || site == "calf") {
                }
                if (entry != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(asDefault, { asDefault = it })
                    Text("Also use for new readings", color = MonitorColors.text, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (entry != null) MonitorHub.bp.setPosition(entry, site, posture)
                if (entry == null || asDefault) pos.setDefaults(site, posture)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
