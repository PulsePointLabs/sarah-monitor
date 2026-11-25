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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
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
internal fun designSize(type: CardType): Pair<Float, Float> = when (type) {
    CardType.HeartRate -> 260f to 230f
    CardType.Hrv -> 260f to 230f
    CardType.Load -> 260f to 150f
    CardType.Ecg -> 560f to 230f
    CardType.HrTrend, CardType.HrvTrend, CardType.PelvicTrend -> 560f to 190f
    CardType.Pelvic -> 380f to 420f
    CardType.BloodPressure -> 380f to 260f
    CardType.Glucose -> 260f to 200f
    CardType.SpO2, CardType.Body -> 260f to 200f
    CardType.Stress -> 300f to 210f
    CardType.Emg -> 380f to 260f
    CardType.Phase -> 380f to 220f
    CardType.NearClimax -> 360f to 240f
    CardType.Matrix -> 360f to 220f
    CardType.ResponseTimeline, CardType.Cardiac -> 600f to 220f
    CardType.Recovery, CardType.Respiration -> 220f to 200f
    CardType.SarahLive -> 380f to 240f
    CardType.QuickMarks -> 380f to 180f
    CardType.Howl -> 380f to 300f
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
    CardType.Stress -> StressColor
    CardType.Phase, CardType.Matrix, CardType.ResponseTimeline, CardType.QuickMarks -> MonitorColors.label
    CardType.NearClimax, CardType.SarahLive -> com.pulsepointlabs.sarahmonitor.response.ClimaxPink
    CardType.Cardiac -> MonitorColors.hr
    CardType.Recovery, CardType.Respiration -> MonitorColors.pelvic
    CardType.Howl -> com.pulsepointlabs.sarahmonitor.howl.HowlColor
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
                    CardType.Stress -> StressCard()
                    CardType.Phase -> com.pulsepointlabs.sarahmonitor.response.PhaseCard()
                    CardType.NearClimax -> com.pulsepointlabs.sarahmonitor.response.NearClimaxCard()
                    CardType.Matrix -> com.pulsepointlabs.sarahmonitor.response.MatrixCard()
                    CardType.ResponseTimeline -> com.pulsepointlabs.sarahmonitor.response.ResponseTimelineCard()
                    CardType.Cardiac -> com.pulsepointlabs.sarahmonitor.response.CardiacCard()
                    CardType.Recovery -> com.pulsepointlabs.sarahmonitor.response.RecoveryCard()
                    CardType.Respiration -> com.pulsepointlabs.sarahmonitor.response.RespirationCard()
                    CardType.SarahLive -> com.pulsepointlabs.sarahmonitor.response.SarahLiveCard()
                    CardType.QuickMarks -> com.pulsepointlabs.sarahmonitor.response.QuickMarksCard()
                    CardType.Howl -> com.pulsepointlabs.sarahmonitor.howl.HowlCard { com.pulsepointlabs.sarahmonitor.howl.HowlSheetState.open.value = true }
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
            // Grow with the overlay, but stay in proportion to the reading.
            val f = (maxWidth.value / 340f).coerceIn(0.8f, 1.6f)
            androidx.compose.runtime.CompositionLocalProvider(LocalCardScale provides base.copy(factor = maxOf(base.factor, f))) {
                // Spread edge to edge in even rows (the same grid as the dashboard), not bunched in the middle.
                ChipGrid(Modifier.fillMaxWidth(), hGap = 4.dp, vGap = 3.dp) { content() }
            }
        }
        return
    }
    ChipGrid(Modifier.fillMaxWidth()) { content() }
}

/** One or two lines of plain status under a card's readings (left out by the card layout when space is short). */
@Composable
private fun StatusLine(text: String, color: Color = MonitorColors.label) {
    if (LocalOverlayMode.current || text.isBlank()) return
    val s = LocalCardScale.current
    Text(text, color = color, fontSize = s.sp(11f), lineHeight = s.sp(13f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
    FitColumn(Modifier.fillMaxSize()) {
        fixed { CardTitle("HR", if (LocalOverlayMode.current) tint else MonitorColors.hr, "bpm") { Text(link, color = linkColor, fontSize = LocalCardScale.current.sp(11f), maxLines = 1) } }
        flex { BigReading(
            value = if (fresh) heart.heartRate?.toString() ?: "--" else "--",
            color = tint,
            maxChars = 3,
            trend = recent.values(Metric.Hr),
            trendStrength = recent.trend(Metric.Hr),
            band = metricBand(Metric.Hr),
            leading = { size -> BeatingHeart(beatMs, Color(0xFFFB7185), size) },
            modifier = Modifier.fillMaxSize()
        ) }
        fixed(drop = 1) { Chips {
            StatChip("avg", fmt(heart.averageHr), tint = hrBandColor(heart.averageHr))
            StatChip("min", heart.minHr?.toString() ?: "--", tint = hrBandColor(heart.minHr?.toDouble()))
            StatChip("max", heart.maxHr?.toString() ?: "--", tint = hrBandColor(heart.maxHr?.toDouble()))
            // Load has its own card; repeated here only when there's a value to show.
            heart.load.score?.let { StatChip("load", "$it", loadColor(it), tint = percentBandColor(it)) }
        } }
    }
}

@Composable
private fun HrvCard() {
    val heart by MonitorHub.heart.collectAsState()
    val recent by MonitorHub.recent.collectAsState()
    val s = LocalCardScale.current
    FitColumn(Modifier.fillMaxSize()) {
        fixed { CardTitle("HRV", MonitorColors.rmssd, "2 min") { Text(heart.hrvQuality, color = MonitorColors.label, fontSize = s.sp(11f), maxLines = 1) } }
        flex(minDp = 56f) { BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight * 1.5f
            val items = listOf(
                HrvItem("RMSSD", fmt(heart.rmssdMs, 1), MonitorColors.rmssd, 5, Metric.Rmssd),
                HrvItem("SDNN", fmt(heart.sdnnMs, 1), MonitorColors.sdnn, 5, Metric.Sdnn),
                HrvItem("pNN50", fmt(heart.pnn50Percent), MonitorColors.pnn50, 3, Metric.Pnn50)
            )
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items.forEach { HrvValue(it, recent, Modifier.weight(1f).fillMaxSize()) }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.forEach { HrvValue(it, recent, Modifier.weight(1f).fillMaxWidth()) }
                }
            }
        } }
        fixed(drop = 2) { StatusLine("RR coverage ${fmt(heart.rrCoveragePercent)}%") }
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
                BigReading(item.value, tint, Modifier.weight(0.7f).fillMaxSize(), maxChars = item.maxChars, trend = values, trendStrength = recent.trend(item.metric), band = metricBand(item.metric))
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.label, color = if (LocalOverlayMode.current) tint else item.color, fontSize = s.sp(12f), fontWeight = FontWeight.Bold)
                BigReading(item.value, tint, Modifier.fillMaxWidth().weight(1f), maxChars = item.maxChars, trend = values, trendStrength = recent.trend(item.metric), band = metricBand(item.metric))
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
    FitColumn(Modifier.fillMaxSize()) {
        fixed { CardTitle("Load", if (LocalOverlayMode.current) trendColor(recent.trend(Metric.Load)) else MonitorColors.load, "0–100") { Text(loadStatus(load), color = if (load.score == null) MonitorColors.warn else color, fontSize = LocalCardScale.current.sp(12f), fontWeight = FontWeight.Bold, maxLines = 1) } }
        flex { BigReading(
            load.score?.toString() ?: "--", trendColor(recent.trend(Metric.Load)), Modifier.fillMaxSize(),
            maxChars = 3, trend = recent.values(Metric.Load), trendStrength = recent.trend(Metric.Load), band = metricBand(Metric.Load)
        ) }
        fixed(drop = 1) { Chips {
            StatChip("HR load", load.hrLoadPercent?.let { "$it%" } ?: "--", tint = percentBandColor(load.hrLoadPercent), trend = rememberTrend("load-hr", load.hrLoadPercent?.toDouble(), 12.0))
            StatChip("HRV supp.", load.hrvSuppressionPercent?.let { "$it%" } ?: "--", tint = percentBandColor(load.hrvSuppressionPercent), trend = rememberTrend("load-hrv", load.hrvSuppressionPercent?.toDouble(), 12.0))
            StatChip("conf.", load.confidencePercent?.let { "$it%" } ?: "--")
        } }
        fixed(drop = 2) { StatusLine(loadDetail(load), if (load.score == null) MonitorColors.warn else MonitorColors.label) }
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
    // Real buttons: at least 30 dp tall and 34 dp wide, so they're easy to hit.
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TrendWindow.entries.forEach { w ->
            val on = w == selected
            Box(
                Modifier
                    .heightIn(min = 30.dp).widthIn(min = 34.dp)
                    .background(if (on) MonitorColors.label else Color.Transparent, RoundedCornerShape(6.dp))
                    .border(1.dp, MonitorColors.label.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .clickable { onSelect(w) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(w.label, color = if (on) MonitorColors.background else MonitorColors.label, fontSize = s.sp(12f), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
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
    FitColumn(Modifier.fillMaxSize()) {
        // The window buttons always stay; the current value beside the title gives way first if space is short.
        fixed { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(title, color, unit, Modifier.weight(1f)) { current() }
            WindowPicker(window) { window = it }
        } }
        flex(minDp = 50f) { Box(Modifier.fillMaxSize()) { content(start, end) } }
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

/** A live number for a card header, padded to a fixed width so it never shifts its neighbors. */
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
    TrendCardFrame("Pressure trend", MonitorColors.pelvic, com.pulsepointlabs.sarahmonitor.core.Pressure.label, current = {
        LiveValue(null, pelvic.aboveRestKpa?.let { com.pulsepointlabs.sarahmonitor.core.Pressure.fmt(it) }, 4, trendColor(recent.trend(Metric.Pelvic)))
    }) { start, end ->
        TrendChart(
            series = listOf(Series("Above rest", MonitorColors.pelvic, trends.map { it.timestampMs to it.pelvic?.let(com.pulsepointlabs.sarahmonitor.core.Pressure::value) })),
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
    // Not logging or faulted: the number is still shown (for positioning) but grayed, so it doesn't read as data.
    val color = if (p.quality.usable) trendColor(recent.trend(Metric.Pelvic)) else MonitorColors.label
    val overlay = LocalOverlayMode.current
    val connected = state == ConnectionState.Connected || state == ConnectionState.Stale
    FitColumn(Modifier.fillMaxSize()) {
        fixed { CardTitle(if (s.widthDp < 230f) "Pelvic" else "Pelvic pressure", if (overlay) color else MonitorColors.pelvic, com.pulsepointlabs.sarahmonitor.core.Pressure.label) { Text(link, color = linkColor, fontSize = s.sp(11f), maxLines = 1) } }
        flex { BigReading(
            value = if (fresh) com.pulsepointlabs.sarahmonitor.core.Pressure.fmt(p.aboveRestKpa) else "--",
            color = color,
            maxChars = 4,
            trend = recent.values(Metric.Pelvic).map(com.pulsepointlabs.sarahmonitor.core.Pressure::value),
            trendStrength = recent.trend(Metric.Pelvic),
            band = metricBand(Metric.Pelvic),
            modifier = Modifier.fillMaxSize()
        ) }
        // In a fault the stats are meaningless and the warning matters: chips give way first.
        val faulted = p.quality.fault
        fixed(drop = if (faulted) 3 else 1) { Chips {
            if (p.referenceKpa != null) StatChip("of ref", p.percentOfReference?.takeIf { fresh }?.let { "${"%.0f".format(it)}%" } ?: "--", MonitorColors.pelvic)
            StatChip("30s avg", com.pulsepointlabs.sarahmonitor.core.Pressure.fmt(p.average30s))
            StatChip("30s max", com.pulsepointlabs.sarahmonitor.core.Pressure.fmt(p.max30s))
            StatChip("max", com.pulsepointlabs.sarahmonitor.core.Pressure.fmt(p.sessionMax))
            StatChip(if (p.holding) "hold" else "last", holdText.substringAfter(' '), if (p.holding) MonitorColors.pelvic else MonitorColors.text)
            StatChip("pulses", "${p.pulsesLastMinute}/min")
            if (s.roomy) StatChip("longest", p.longestHoldS?.let { "${fmt(it, 1)}s" } ?: "--")
        } }
        if (!s.compact) flex(weight = 1.1f, minDp = 50f, drop = 3) {
            PelvicWaveform(
                points = p.trace,
                thresholdKpa = p.thresholdKpa,
                referenceKpa = p.referenceKpa,
                windowMs = if (p.holding) 10_000L else 20_000L,
                modifier = Modifier.fillMaxSize()
            )
        }
        val capture = p.capture
        val status = when {
            capture != null -> "${capture.message} ${"%.0f".format(kotlin.math.ceil(capture.remainingS))}s"
            // Narrow cards get the short reason; the buttons below say what to do.
            p.quality.fault -> if (s.widthDp < 320f) "⚠ ${p.quality.label}" else p.message ?: p.quality.label
            error != null && !fresh -> error!!
            p.message != null -> p.message!!
            !p.logging && p.restSource == RestSource.None -> "Preview, not logging. Relax for auto rest or capture rest."
            !p.logging -> "Preview, not logging · ${if (p.restSource == RestSource.Captured) "rest ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(p.restKpa)}" else "auto rest"}"
            p.sustained -> "Sustained elevation, not counted as a contraction"
            p.restSource == RestSource.Auto -> "Logging · auto rest"
            p.restSource == RestSource.Captured -> "Logging · rest ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(p.restKpa)} · threshold ${com.pulsepointlabs.sarahmonitor.core.Pressure.fmt(p.thresholdKpa)}"
            else -> ""
        }
        val statusColor = when {
            p.quality.fault -> MonitorColors.alarm
            capture != null || p.sustained -> MonitorColors.warn
            else -> MonitorColors.label
        }
        fixed(drop = if (faulted) 0 else 2) { StatusLine(status, statusColor) }
        // The one action that matters right now, full width so it stays reachable on the narrow phone card.
        if (actions != null && !overlay && connected && fresh && capture == null) {
            when {
                p.quality.fault -> fixed {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionPill("New rest", MonitorColors.pelvic, true, Modifier.weight(1f)) {
                            MonitorHub.startPelvicCapture(CaptureKind.Rest)?.let(actions.onMessage)
                        }
                        ActionPill("Resume", MonitorColors.alarm, false, Modifier.weight(1f)) { MonitorHub.resumePelvic()?.let(actions.onMessage) }
                    }
                }
                !p.logging && p.restSource != RestSource.None -> fixed {
                    ActionPill("Start logging", MonitorColors.pelvic, true, Modifier.fillMaxWidth()) {
                        MonitorHub.startPelvicLogging()?.let(actions.onMessage)
                    }
                }
            }
        }
        if (actions != null && !s.compact) fixed(drop = 4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                MiniButton("Capture rest", MonitorColors.pelvic) {
                    MonitorHub.startPelvicCapture(CaptureKind.Rest)?.let(actions.onMessage)
                }
                if (p.logging) MiniButton("Reference hold", MonitorColors.pelvic) {
                    MonitorHub.startPelvicCapture(CaptureKind.Reference)?.let(actions.onMessage)
                }
                if (p.logging) MiniButton("Stop logging", MonitorColors.label) { MonitorHub.stopPelvicLogging() }
                else if (p.restSource == RestSource.Captured) MiniButton("Auto rest", MonitorColors.label) { MonitorHub.clearPelvicCalibration() }
            }
        }
    }
}

/** A full-width card action: big enough to hit on a phone, never truncated (labels are kept short). */
@Composable
private fun ActionPill(label: String, color: Color, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = LocalCardScale.current
    Box(
        modifier
            .heightIn(min = 36.dp)
            .background(if (filled) color else color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (filled) MonitorColors.background else color, fontSize = s.sp(14f), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
    FitColumn(Modifier.fillMaxSize()) {
        fixed { CardTitle(if (s.widthDp < 230f) "BP" else "Blood pressure", if (LocalOverlayMode.current) latest?.let { bpColor(it.systolic, it.diastolic) } ?: MonitorColors.bp else MonitorColors.bp, "mmHg") {
            Text(latest?.let { agoLabel(it.timestampMs) } ?: "", color = MonitorColors.label, fontSize = s.sp(11f), maxLines = 1)
        } }
        flex { BigReading(
            value = latest?.let { "${it.systolic}/${it.diastolic}" } ?: "--/--",
            maxChars = 7,
            color = latest?.let { bpColor(it.systolic, it.diastolic) } ?: MonitorColors.dim,
            modifier = Modifier.fillMaxSize()
        ) }
        fixed(drop = 1) { Chips {
            StatChip("pulse", latest?.pulse?.toString() ?: "--")
            StatChip("MAP", latest?.let { fmt(it.meanArterial) } ?: "--")
            if (latest?.irregular == true) StatChip("IHB", "yes", MonitorColors.warn)
            val previous = readings.dropLast(1).lastOrNull()
            if (latest != null && previous != null) {
                val d = latest.systolic - previous.systolic
                StatChip("Δ sys", "${if (d > 0) "+" else ""}$d")
            }
        } }
        fixed(drop = 3) { BpPositionLine(latest) }
        if (!s.compact && readings.size >= 2) flex(weight = 0.9f, minDp = 50f, drop = 5) {
            val recent = readings.takeLast(20)
            TrendChart(
                series = listOf(
                    Series("SYS", MonitorColors.bp, recent.map { it.timestampMs to it.systolic.toDouble() }),
                    Series("DIA", MonitorColors.bpDia, recent.map { it.timestampMs to it.diastolic.toDouble() })
                ),
                startMs = recent.first().timestampMs, endMs = recent.last().timestampMs.coerceAtLeast(recent.first().timestampMs + 60_000L),
                modifier = Modifier.fillMaxSize(), minSpan = 30.0, gapMs = Long.MAX_VALUE, dots = true
            )
        }
        fixed(drop = 2) { StatusLine(cuff) }
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
    FitColumn(Modifier.fillMaxSize()) {
        fixed { CardTitle("Glucose", MonitorColors.glucose, "mg/dL") {
            Text(latest?.let { agoLabel(it.timestampMs) } ?: "", color = MonitorColors.label, fontSize = s.sp(11f), maxLines = 1)
        } }
        flex { BigReading(latest?.let { fmt(it.mgDl) } ?: "--", glucoseColor(latest?.mgDl), Modifier.fillMaxSize(), maxChars = 3) }
        fixed(drop = 1) { Chips {
            val previous = readings.dropLast(1).lastOrNull()
            if (latest != null && previous != null) StatChip("Δ", "${if (latest.mgDl >= previous.mgDl) "+" else ""}${fmt(latest.mgDl - previous.mgDl)}")
            StatChip("24h avg", day.takeIf { it.isNotEmpty() }?.let { fmt(it.map { r -> r.mgDl }.average()) } ?: "--")
            StatChip("24h n", day.size.toString())
        } }
        val week = readings.filter { now - it.timestampMs <= 7 * 86_400_000L }
        if (s.roomy && week.size >= 2) flex(weight = 0.9f, minDp = 50f, drop = 5) {
            val recent = week
            run {
                TrendChart(
                    series = listOf(Series("Glucose", MonitorColors.glucose, recent.map { it.timestampMs to it.mgDl })),
                    startMs = recent.first().timestampMs, endMs = now,
                    modifier = Modifier.fillMaxSize(), minSpan = 40.0, gapMs = Long.MAX_VALUE, dots = true,
                    refLines = listOf(RefLine(70.0, "70", MonitorColors.warn), RefLine(180.0, "180", MonitorColors.warn))
                )
            }
        }
        if (actions != null) fixed(drop = 2) {
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


val StressColor = Color(0xFFF59E0B)

/** Overall stress (0–100) with what's driving it; separate from Load (cardiovascular strain). */
@Composable
fun StressCard() {
    val st by MonitorHub.stress.collectAsState()
    val recent by MonitorHub.recent.collectAsState()
    val s = LocalCardScale.current
    val color = st.score?.let { percentBandColor(it) } ?: MonitorColors.dim
    val voiceOn by com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.announce.collectAsState()
    val relaxOn by com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.relax.collectAsState()
    FitColumn(Modifier.fillMaxSize()) {
        fixed { CardTitle("Stress", if (LocalOverlayMode.current) color else StressColor, "0–100") {
            // Only when there's room: the title always wins over the speaker toggle.
            if (!LocalOverlayMode.current && s.widthDp >= 180f) {
                // Quick mute/unmute for Sarah's stress voice (announcements; coaching follows its own switch).
                val on = voiceOn || relaxOn
                androidx.compose.material3.Icon(
                    if (on) androidx.compose.material.icons.Icons.AutoMirrored.Filled.VolumeUp else androidx.compose.material.icons.Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (on) "Stress voice on" else "Stress voice off",
                    tint = if (on) StressColor else MonitorColors.dim,
                    modifier = Modifier.padding(end = 6.dp).size(s.dp(20f)).clickable {
                        if (on) { com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.setAnnounce(false); com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.setRelax(false) }
                        else com.pulsepointlabs.sarahmonitor.core.StressVoiceSettings.setAnnounce(true)
                    }
                )
            }
            Text(
                if (st.moving) "Moving" else st.level?.label ?: "Learning",
                color = if (st.moving) MonitorColors.label else color, fontSize = s.sp(12f), fontWeight = FontWeight.Bold, maxLines = 1
            )
        } }
        flex { BigReading(
            st.score?.toString() ?: "--", color, Modifier.fillMaxSize(),
            maxChars = 3, trend = recent.values(Metric.Stress), trendStrength = recent.trend(Metric.Stress), band = metricBand(Metric.Stress)
        ) }
        fixed(drop = 1) { Chips {
            st.components.filter { it.score != null }.forEach { c ->
                StatChip(c.label, if (c.key == "breath") st.breathsPerMin?.let { "%.0f/min".format(it) } ?: "${c.score}" else "${c.score}", tint = percentBandColor(c.score), trend = rememberTrend("stress-${c.key}", c.score?.toDouble(), 12.0))
            }
        } }
    }
}
