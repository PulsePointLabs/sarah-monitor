package com.pulsepointlabs.sarahmonitor.overlay

import com.pulsepointlabs.sarahmonitor.core.duringSession
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.core.Metric
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.core.Pressure
import com.pulsepointlabs.sarahmonitor.ui.LocalPanelAlpha
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import com.pulsepointlabs.sarahmonitor.ui.TrendArrows
import com.pulsepointlabs.sarahmonitor.ui.bpColor
import com.pulsepointlabs.sarahmonitor.ui.drawSmoothTrend
import com.pulsepointlabs.sarahmonitor.ui.fmt
import com.pulsepointlabs.sarahmonitor.ui.loadColor
import com.pulsepointlabs.sarahmonitor.ui.metricBand
import com.pulsepointlabs.sarahmonitor.ui.percentBandColor
import com.pulsepointlabs.sarahmonitor.ui.trendColor
import kotlin.math.min

/** Below this size (dp) an overlay card switches to the compact strip. */
object TinyOverlaySize {
    const val MAX_WIDTH = 170
    const val MAX_HEIGHT = 112
}

/** What the strip shows for one overlay. */
private data class Strip(
    val label: String, val unit: String, val value: String, val maxChars: Int, val color: Color,
    val trend: List<Double>, val trendStrength: Double?, val band: ((Double) -> Color)?,
    val extras: List<Pair<String, String>>
)

/**
 * A small overlay, like a vital-signs strip: the label, one big number in its live color, the rise/fall arrows,
 * and — when there's room — a couple of small extra numbers and a tiny trend along the bottom. Sized from the
 * window it's in, so it stays readable from about 76×44 dp up.
 */
@Composable
fun TinyOverlay(kind: OverlayKind, modifier: Modifier = Modifier) {
    val strip = strip(kind) ?: return
    val alpha = LocalPanelAlpha.current
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(
        modifier
            .background(MonitorColors.panel.copy(alpha = MonitorColors.panel.alpha * alpha), shape)
            .border(1.dp, strip.color.copy(alpha = 0.45f * alpha), shape)
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val chart = strip.trend.size >= 3 && h >= 58f
        val chartH = if (chart) (h * 0.28f).coerceIn(14f, 30f) else 0f
        val headH = 15f
        val topH = h - chartH - 4f
        val numH = topH - headH
        val showExtras = strip.extras.isNotEmpty() && w >= 140f && topH >= 38f
        val extrasW = if (showExtras) min(88f, w * 0.42f) else 0f
        val arrowW = if (strip.trendStrength != null) 9f else 0f
        val numW = w - extrasW - arrowW - 14f
        val sizeDp = min(numH * 0.86f, numW / (strip.maxChars * 0.62f + 0.1f)).coerceAtLeast(12f)
        val fs = with(LocalDensity.current) { sizeDp.dp.toSp() }
        if (chart) {
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(chartH.dp).padding(horizontal = 6.dp, vertical = 3.dp)) {
                val v = strip.trend
                val lo = v.min(); val hi = v.max()
                val span = if (hi - lo < 1e-6) 1.0 else hi - lo
                fun yOf(x: Double) = size.height - ((x - lo) / span).toFloat() * size.height
                drawSmoothTrend(v, 0f, size.width, ::yOf, strip.color, strip.band, lo, hi, fill = true)
            }
        }
        Row(Modifier.fillMaxWidth().height(topH.dp).padding(start = 6.dp, end = 6.dp, top = 3.dp)) {
            Column(Modifier.weight(1f)) {
                // Always says what it is, even at the smallest size.
                Row(Modifier.height(headH.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(strip.label, color = strip.color, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    if (w >= 100f) Text(" " + strip.unit, color = MonitorColors.label, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false)
                }
                Row(Modifier.fillMaxWidth().height(numH.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(
                        strip.value, color = strip.color, maxLines = 1, softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                        style = TextStyle(fontSize = fs, lineHeight = fs, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, shadow = Shadow(strip.color.copy(alpha = 0.6f), Offset.Zero, 18f))
                    )
                    if (strip.trendStrength != null) TrendArrows(strip.trendStrength, Modifier.padding(start = 2.dp).size(arrowW.dp, (sizeDp * 0.8f).coerceAtLeast(12f).dp))
                }
            }
            if (showExtras) Column(
                Modifier.width(extrasW.dp).height(topH.dp).padding(start = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
            ) {
                strip.extras.take(((topH - 4f) / 19f).toInt().coerceIn(1, 3)).forEach { (l, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(l, color = MonitorColors.label, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
                        Text(v, color = MonitorColors.text, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** Short names for the small strip (the full card has room for the long ones). */
private fun short(label: String) = when (label.lowercase()) {
    "breathing" -> "Breath"
    "restless" -> "Moving"
    "tension" -> "Tense"
    else -> label.take(7)
}
@Composable
private fun strip(kind: OverlayKind): Strip? {
    val recent by MonitorHub.recent.collectAsState()
    return when (kind) {
        OverlayKind.HeartRate -> {
            val heart by MonitorHub.heart.collectAsState()
            val fresh = heart.updatedAtMs?.let { System.currentTimeMillis() - it < 5_000L } == true
            Strip(
                "HR", "bpm", if (fresh) heart.heartRate?.toString() ?: "--" else "--", 3, trendColor(recent.trend(Metric.Hr)),
                recent.values(Metric.Hr), recent.trend(Metric.Hr), metricBand(Metric.Hr),
                listOfNotNull("RMSSD" to fmt(heart.rmssdMs), heart.load.score?.let { "load" to "$it" }, "avg" to fmt(heart.averageHr))
            )
        }
        OverlayKind.Hrv -> {
            val heart by MonitorHub.heart.collectAsState()
            Strip(
                "HRV", "RMSSD", fmt(heart.rmssdMs), 3, trendColor(recent.trend(Metric.Rmssd)),
                recent.values(Metric.Rmssd), recent.trend(Metric.Rmssd), metricBand(Metric.Rmssd),
                listOf("SDNN" to fmt(heart.sdnnMs), "pNN50" to fmt(heart.pnn50Percent))
            )
        }
        OverlayKind.Load -> {
            val heart by MonitorHub.heart.collectAsState()
            val l = heart.load
            Strip(
                "Load", "0–100", l.score?.toString() ?: "--", 3, loadColor(l.score),
                recent.values(Metric.Load), recent.trend(Metric.Load), metricBand(Metric.Load),
                listOfNotNull(l.hrLoadPercent?.let { "HR" to "$it%" }, l.hrvSuppressionPercent?.let { "HRV" to "$it%" })
            )
        }
        OverlayKind.Stress -> {
            val st by MonitorHub.stress.collectAsState()
            Strip(
                "Stress", "0–100", st.score?.toString() ?: "--", 3, st.score?.let { percentBandColor(it) } ?: MonitorColors.dim,
                recent.values(Metric.Stress), recent.trend(Metric.Stress), metricBand(Metric.Stress),
                // Fixed slots (HRV, heart, breathing): a missing reading shows "--" instead of reshuffling the strip.
                com.pulsepointlabs.sarahmonitor.core.StressComponent.slots(st.components).take(3).map { short(it.label) to (it.score?.toString() ?: "--") }
            )
        }
        OverlayKind.Pelvic -> {
            val p by MonitorHub.pelvic.collectAsState()
            val fresh = p.trace.lastOrNull()?.let { System.currentTimeMillis() - it.timestampMs < 2_000L } == true
            Strip(
                "Pelvic", Pressure.label, if (fresh) Pressure.fmt(p.aboveRestKpa) else "--", 4,
                if (p.quality.usable) trendColor(recent.trend(Metric.Pelvic)) else MonitorColors.label,
                recent.values(Metric.Pelvic).map(Pressure::value), recent.trend(Metric.Pelvic), metricBand(Metric.Pelvic),
                listOf("pulse/m" to "${p.pulsesLastMinute}", "max" to Pressure.fmt(p.sessionMax))
            )
        }
        OverlayKind.BloodPressure -> {
            val all by MonitorHub.bp.items.collectAsState()
            val session by MonitorHub.session.collectAsState()
            val last = all.duringSession(session?.startTimeMs).lastOrNull()
            Strip(
                "BP", "mmHg", last?.let { "${it.systolic}/${it.diastolic}" } ?: "--/--", 7,
                last?.let { bpColor(it.systolic, it.diastolic) } ?: MonitorColors.dim,
                emptyList(), null, null,
                listOfNotNull(last?.pulse?.let { "pulse" to "$it" }, last?.let { "MAP" to fmt(it.meanArterial) })
            )
        }
        OverlayKind.MarkEvent -> null
    }
}
