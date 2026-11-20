package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    Box(
        modifier
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(if (alarm) MonitorColors.alarm.copy(alpha = 0.28f) else identity.copy(alpha = 0.10f), MonitorColors.panel)
                ),
                shape
            )
            .border(if (alarm) 2.dp else 1.dp, if (alarm) MonitorColors.alarm else identity.copy(alpha = 0.35f), shape)
    ) { content() }
}

@Composable
fun CardTitle(label: String, color: Color, unit: String = "", modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val s = LocalCardScale.current
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
 * The big glowing reading, fitted to the space it has: as large as the height allows without overflowing the
 * width, centred both ways. [arrow] is +1 / -1 / 0.
 */
@Composable
fun BigReading(
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    arrow: Int = 0,
    arrowGood: Int = 0,
    suffix: String? = null,
    maxSizeDp: Float = 260f
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val showArrow = arrow != 0 && value != "--"
        val chars = max(value.length, 2) + (if (showArrow) 1 else 0) + (suffix?.length?.times(0.45f) ?: 0f).toInt()
        val byHeight = maxHeight.value * 0.92f
        val byWidth = maxWidth.value / (chars * 0.62f + 0.25f)
        val sizeDp = min(min(byHeight, byWidth), maxSizeDp).coerceAtLeast(MinTextSp + 6f)
        val fontSize = with(LocalDensity.current) { sizeDp.dp.toSp() }
        val arrowColor = when {
            arrowGood == 0 -> color
            arrow == arrowGood -> MonitorColors.good
            else -> MonitorColors.load
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                color = color,
                maxLines = 1,
                softWrap = false,
                style = TextStyle(
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    shadow = Shadow(color.copy(alpha = 0.7f), Offset.Zero, blurRadius = 26f)
                )
            )
            if (suffix != null) {
                Text(
                    suffix,
                    color = color.copy(alpha = 0.8f),
                    fontSize = max(MinTextSp, fontSize.value * 0.32f).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 3.dp)
                )
            }
            if (showArrow) {
                Text(
                    if (arrow > 0) "▲" else "▼",
                    color = arrowColor,
                    fontSize = max(MinTextSp, fontSize.value * 0.34f).sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

fun fmt(value: Double?, decimals: Int = 0): String = value?.let { "%.${decimals}f".format(it) } ?: "--"

fun loadColor(score: Int?): Color = when {
    score == null -> MonitorColors.dim
    score < 25 -> MonitorColors.good
    score < 50 -> Color(0xFFA3E635)
    score < 75 -> MonitorColors.warn
    else -> MonitorColors.alarm
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
