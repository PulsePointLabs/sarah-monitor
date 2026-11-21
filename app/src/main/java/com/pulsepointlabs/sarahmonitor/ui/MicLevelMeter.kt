package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "It's hearing you" feedback: a pulsing dot, a status word and a 16-bar level meter that lights up with your
 * voice — so speaking into the mic never feels like talking into the void.
 */
@Composable
fun MicLevelMeter(level: Float, label: String, modifier: Modifier = Modifier) {
    val smooth by animateFloatAsState(level.coerceIn(0f, 1f), tween(90), label = "mic")
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dotA")
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).alpha(pulse).background(MonitorColors.alarm, CircleShape))
        Text(label, color = MonitorColors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Canvas(Modifier.weight(1f).height(18.dp)) {
            val bars = 16
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (bars - 1)) / bars
            val lit = smooth * bars
            for (i in 0 until bars) {
                val on = i < lit
                val color = when {
                    i >= bars * 0.85 -> MonitorColors.alarm
                    i >= bars * 0.6 -> MonitorColors.warn
                    else -> MonitorColors.good
                }
                // bars grow taller to the right, like a classic level meter
                val h = size.height * (0.35f + 0.65f * (i + 1) / bars)
                drawRoundRect(
                    color.copy(alpha = if (on) 1f else 0.15f),
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}
