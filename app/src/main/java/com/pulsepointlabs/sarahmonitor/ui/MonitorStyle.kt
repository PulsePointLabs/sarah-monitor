package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.togetherWith
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Bedside-monitor palette from the SarahVS telemetry monitor, plus pressure, BP and glucose colours. */
object MonitorColors {
    val background = Color(0xFF02060B)
    val panel = Color(0xFF07111A)
    val panelBorder = Color(0xFF16283A)
    val gridMinor = Color(0xFF0B1E17)
    val gridMajor = Color(0xFF123526)
    val trendGrid = Color(0xFF13202E)
    val label = Color(0xFF8CA3B8)
    val dim = Color(0xFF5B6E80)
    val text = Color(0xFFE6EEF5)
    val hr = Color(0xFF34E37E)
    val ecg = Color(0xFF3CFF8F)
    val rmssd = Color(0xFFFFD43B)
    val sdnn = Color(0xFF38C8FF)
    val pnn50 = Color(0xFFE38BFF)
    val load = Color(0xFFFF9F43)
    val pelvic = Color(0xFF55E4CE)
    val bp = Color(0xFFFF6B8B)
    val bpDia = Color(0xFFFFA8BA)
    val glucose = Color(0xFFB794FF)
    val alarm = Color(0xFFFF4D5E)
    val warn = Color(0xFFFFC53D)
    val good = Color(0xFF4ADE80)
}

/** Text never gets smaller than this, however small a card is made. */
val MinTextSp = 11f

/**
 * How much bigger than its "design size" a card currently is. Text and spacing follow it, so a card that is
 * made larger fills the space with bigger readings, and a small card stays compact but readable.
 */
data class CardScale(val factor: Float, val widthDp: Float, val heightDp: Float) {
    fun sp(base: Float): TextUnit = max(MinTextSp, base * factor).sp
    fun dp(base: Float): Dp = (base * factor.coerceIn(0.6f, 2.2f)).dp
    val compact: Boolean get() = heightDp < 150f || widthDp < 220f
    val roomy: Boolean get() = heightDp >= 260f && widthDp >= 300f
}

val LocalCardScale = compositionLocalOf { CardScale(1f, 300f, 200f) }

/** Measures the space a card has and provides a [CardScale] for its content. */
@Composable
fun ScaledCard(
    designWidthDp: Float,
    designHeightDp: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier) {
        val w = maxWidth.value
        val h = maxHeight.value
        val factor = sqrt((w / designWidthDp) * (h / designHeightDp)).coerceIn(0.55f, 3.2f)
        // Don't let one long axis blow text past what the short axis can hold.
        val bounded = min(factor, min(w / designWidthDp, h / designHeightDp) * 1.35f).coerceAtLeast(0.55f)
        CompositionLocalProvider(LocalCardScale provides CardScale(bounded, w, h)) { content() }
    }
}

/** Panel chrome: identity-tinted gradient, thin border, tight padding. */
@Composable
fun MonitorPanel(
    identity: Color,
    modifier: Modifier = Modifier,
    alarm: Boolean = false,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    // Overlays can make the card background see-through while the readings stay solid.
    val a = LocalPanelAlpha.current
    Box(
        modifier
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        if (alarm) MonitorColors.alarm.copy(alpha = 0.28f * a) else identity.copy(alpha = 0.10f * a),
                        MonitorColors.panel.copy(alpha = a)
                    )
                ),
                shape
            )
            .border(if (alarm) 2.dp else 1.dp, if (alarm) MonitorColors.alarm else identity.copy(alpha = 0.35f * maxOf(a, 0.4f)), shape)
    ) { content() }
}

/** Card background opacity (1 = solid). Set by overlays. */
val LocalPanelAlpha = compositionLocalOf { 1f }

@Composable
fun CardTitle(label: String, color: Color, unit: String = "", modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val s = LocalCardScale.current
    if (LocalOverlayMode.current) {
        // Overlays: one slim label line (scales with the overlay), leaving room for the corner buttons.
        Row(modifier.padding(end = 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(width = 3.dp, height = s.dp(13f)).background(color, RoundedCornerShape(2.dp)))
            Text(
                if (unit.isNotBlank()) "$label · $unit" else label,
                color = color, fontSize = s.sp(13f), fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 4.dp, height = s.dp(15f)).background(color, RoundedCornerShape(2.dp)))
        Text(label, color = color, fontSize = s.sp(14f), fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        trailing()
        if (unit.isNotBlank()) UnitTag(unit, color)
    }
}

@Composable
fun UnitTag(unit: String, color: Color) {
    val s = LocalCardScale.current
    Text(
        unit,
        color = color,
        fontSize = s.sp(11f),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
fun StatChip(label: String, value: String, accent: Color = MonitorColors.text) {
    val s = LocalCardScale.current
    Row(
        Modifier
            .background(Color(0x1FFFFFFF), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(6.dp))
            .padding(horizontal = s.dp(6f), vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = MonitorColors.label, fontSize = s.sp(11f), maxLines = 1)
        Text(value, color = accent, fontSize = s.sp(13f), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/**
 * Blue → green → yellow → red for a trend from -1 (falling) through 0 (steady) to +1 (rising).
 */
fun trendColor(trend: Double): Color {
    val stops = listOf(
        -1.0 to Color(0xFF38BDF8),
        -0.5 to Color(0xFF2DD4BF),
        0.0 to Color(0xFF4ADE80),
        0.5 to Color(0xFFFACC15),
        1.0 to Color(0xFFF87171)
    )
    val t = trend.coerceIn(-1.0, 1.0)
    for (i in 0 until stops.size - 1) {
        val (a, ca) = stops[i]
        val (b, cb) = stops[i + 1]
        if (t <= b) return androidx.compose.ui.graphics.lerp(ca, cb, ((t - a) / (b - a)).toFloat())
    }
    return stops.last().second
}

/**
 * The big glowing reading, fitted to the space it has and centred. Its size comes from [maxChars] (the widest
 * value this metric can show), not the current text, and the trend line always has its slot reserved — so
 * nothing jumps or shifts when digits change or the trend appears.
 */
@Composable
fun BigReading(
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    maxChars: Int = value.length,
    trend: List<Double>? = null,
    trendColor: Color = color,
    /** -1…+1 trend strength for the arrow stack (null = no arrows, no slot). */
    trendStrength: Double? = null,
    leading: (@Composable (sizeDp: Float) -> Unit)? = null,
    maxSizeDp: Float = 260f
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val chars = max(maxChars, value.length).coerceAtLeast(2)
        val leadingEm = if (leading != null) 0.8f else 0f
        val arrowEm = if (trendStrength != null) 0.42f else 0f
        val textEm = chars * 0.6f + 0.1f
        // Two ways to place the trend line: beside the number, or underneath it. Use whichever lets the
        // number be bigger. The choice depends only on the box size, so it doesn't flip as values change.
        val besideSize = min(maxWidth.value / (textEm + leadingEm + arrowEm + (if (trend != null) 1.7f else 0f) + 0.05f), maxHeight.value * 0.9f)
        val belowSize = if (trend == null) 0f else min(maxWidth.value / (textEm + leadingEm + arrowEm + 0.05f), maxHeight.value * 0.9f / 1.4f)
        val below = trend != null && belowSize > besideSize * 1.15f
        val sizeDp = min(if (below) belowSize else besideSize, maxSizeDp).coerceAtLeast(MinTextSp + 6f)
        val fontSize = with(LocalDensity.current) { sizeDp.dp.toSp() }
        val shown by androidx.compose.animation.animateColorAsState(color, androidx.compose.animation.core.tween(400), label = "reading-color")
        val number: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    Box(Modifier.size((sizeDp * leadingEm).dp, sizeDp.dp), contentAlignment = Alignment.Center) { leading(sizeDp * 0.62f) }
                }
                // Fixed-width slot; new values cross-fade in place so nothing jumps.
                androidx.compose.animation.AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
                    },
                    modifier = Modifier.width((sizeDp * textEm).dp),
                    contentAlignment = Alignment.Center,
                    label = "reading"
                ) { v ->
                    Text(
                        v,
                        color = shown,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.width((sizeDp * textEm).dp),
                        style = TextStyle(
                            fontSize = fontSize,
                            lineHeight = fontSize,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            shadow = Shadow(shown.copy(alpha = 0.7f), Offset.Zero, blurRadius = 26f)
                        )
                    )
                }
                if (trendStrength != null) {
                    TrendArrows(trendStrength, Modifier.size((sizeDp * arrowEm).dp, (sizeDp * 0.82f).dp))
                }
                if (trend != null && !below) {
                    TrendLine(trend, trendColor, Modifier.size((sizeDp * 1.7f).dp, (sizeDp * 0.62f).dp).padding(start = (sizeDp * 0.12f).dp))
                }
            }
        }
        if (below) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                number()
                TrendLine(trend!!, trendColor, Modifier.size((sizeDp * (textEm + arrowEm)).dp, (sizeDp * 0.36f).dp).padding(top = (sizeDp * 0.04f).dp))
            }
        } else {
            number()
        }
    }
}

/**
 * Up to three stacked chevrons: more appear (fading in one by one) the stronger and more sustained the
 * rise or fall, coloured on the same blue→red spectrum. Always occupies its slot, so nothing shifts.
 */
@Composable
fun TrendArrows(strength: Double, modifier: Modifier) {
    val s by androidx.compose.animation.core.animateFloatAsState(strength.toFloat().coerceIn(-1f, 1f), androidx.compose.animation.core.tween(600), label = "trend")
    val color = trendColor(s.toDouble())
    androidx.compose.foundation.Canvas(modifier) {
        val mag = kotlin.math.abs(s)
        val up = s > 0
        val w = size.width
        val h = size.height
        val step = h / 3.6f
        val stroke = (w * 0.16f).coerceAtLeast(2f)
        val thresholds = floatArrayOf(0.12f, 0.4f, 0.7f)
        for (i in 0 until 3) {
            val a = ((mag - thresholds[i]) / 0.15f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            // Rising chevrons stack upward from the middle, falling ones downward.
            val cy = if (up) h * 0.62f - i * step else h * 0.38f + i * step
            val dy = step * 0.42f * (if (up) -1f else 1f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.1f, cy - dy / 2)
                lineTo(w * 0.5f, cy + dy / 2)
                lineTo(w * 0.9f, cy - dy / 2)
            }
            drawPath(path, color.copy(alpha = a), style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

/** True inside floating overlays: cards drop chrome and padding so the readings use all the space. */
val LocalOverlayMode = compositionLocalOf { false }

/** Small trend line (last ~2 minutes). Draws nothing until there are a few points, but always keeps its space. */
@Composable
fun TrendLine(values: List<Double>, color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        if (values.size < 3) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = if (hi - lo < 1e-6) 1.0 else hi - lo
        val pad = size.height * 0.12f
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = i.toFloat() / (values.size - 1) * size.width
            val y = size.height - pad - ((v - lo) / span).toFloat() * (size.height - 2 * pad)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        val lastY = size.height - pad - ((values.last() - lo) / span).toFloat() * (size.height - 2 * pad)
        drawCircle(color, radius = 3f * density, center = Offset(size.width, lastY))
    }
}

/** A heart that pulses once per beat at the current RR interval. */
@Composable
fun BeatingHeart(intervalMs: Long?, color: Color, sizeDp: Float) {
    val scale = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    val interval by androidx.compose.runtime.rememberUpdatedState(intervalMs)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            val i = interval
            if (i == null) { kotlinx.coroutines.delay(500L); continue }
            scale.animateTo(1.28f, androidx.compose.animation.core.tween(90))
            scale.animateTo(1f, androidx.compose.animation.core.tween(160))
            kotlinx.coroutines.delay((i - 250L).coerceAtLeast(60L))
        }
    }
    Text(
        "♥",
        color = if (intervalMs == null) MonitorColors.dim else color,
        fontSize = with(LocalDensity.current) { sizeDp.dp.toSp() },
        modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        style = TextStyle(shadow = Shadow(color.copy(alpha = 0.6f), Offset.Zero, blurRadius = 18f))
    )
}

fun fmt(value: Double?, decimals: Int = 0): String = value?.let { "%.${decimals}f".format(it) } ?: "--"

fun loadColor(score: Int?): Color = when {
    score == null -> MonitorColors.dim
    score < 25 -> MonitorColors.good
    score < 50 -> Color(0xFFA3E635)
    score < 75 -> MonitorColors.warn
    else -> MonitorColors.alarm
}

/** What the Load reading is doing right now, in plain words. */
fun loadStatus(r: com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressReading): String {
    if (r.score != null) return loadLabel(r.score)
    return when (r.level) {
        com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressLevel.EstablishingBaseline ->
            "Learning baseline ${r.baselineWindowsObserved}/${r.baselineWindowsRequired.coerceAtLeast(1)} min"
        com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressLevel.Moving -> "Moving · paused"
        com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressLevel.PoorSignal -> "Signal too noisy"
        else -> when {
            r.motionSamplesObserved < r.motionSamplesRequired && r.hrSamplesObserved >= r.hrSamplesRequired -> "Waiting for motion data"
            else -> "Collecting (1 min)"
        }
    }
}

/** One-line explanation under the Load reading. */
fun loadDetail(r: com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressReading): String = when {
    r.score != null -> "vs your resting baseline · HR ${r.baselineHr?.let { "%.0f".format(it) } ?: "--"}, RMSSD ${r.baselineRmssdMs?.let { "%.0f".format(it) } ?: "--"} ms"
    r.level == com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressLevel.EstablishingBaseline -> "Sit still and relaxed for a few minutes; it's saved after that"
    r.level == com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressLevel.Moving -> "Load pauses while you're moving"
    r.level == com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressLevel.PoorSignal -> "Too many rejected beats; check strap contact"
    r.motionSamplesObserved < r.motionSamplesRequired && r.hrSamplesObserved >= r.hrSamplesRequired -> "H10 motion stream hasn't started"
    else -> "Needs about a minute of H10 data"
}

fun loadLabel(score: Int?): String = when {
    score == null -> "Establishing"
    score < 25 -> "Settled"
    score < 50 -> "Mild"
    score < 75 -> "Elevated"
    else -> "High"
}

/** AHA-style blood pressure category colour. */
fun bpColor(systolic: Int, diastolic: Int): Color = when {
    systolic >= 180 || diastolic >= 120 -> MonitorColors.alarm
    systolic >= 140 || diastolic >= 90 -> Color(0xFFFF7A45)
    systolic >= 130 || diastolic >= 80 -> MonitorColors.warn
    systolic >= 120 -> Color(0xFFD9F99D)
    else -> MonitorColors.good
}

fun glucoseColor(mgDl: Double?): Color = when {
    mgDl == null -> MonitorColors.dim
    mgDl < 54 -> MonitorColors.alarm
    mgDl < 70 -> MonitorColors.warn
    mgDl <= 180 -> MonitorColors.glucose
    mgDl <= 250 -> MonitorColors.warn
    else -> MonitorColors.alarm
}

fun agoLabel(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val s = ((nowMs - timestampMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min ago"
        else -> "${s / 86_400} d ago"
    }
}
