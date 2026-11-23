package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** A short in-app banner: [title] · [filed] on top, his words below, and an optional quiet footnote. */
data class Notice(val title: String, val filed: String, val words: String, val footnote: String? = null, val atMs: Long = System.currentTimeMillis())

/**
 * Banner at the top of the app for [notice]; disappears by itself after a few seconds, or on tap.
 * Lives above every screen, so it shows wherever you are in the app.
 */
@Composable
fun NoticeHost(notice: Notice?, onDismiss: (Notice) -> Unit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<Notice?>(null) }
    if (notice != null) shown = notice
    LaunchedEffect(notice) { if (notice != null) { delay(6_000L); onDismiss(notice) } }
    Box(modifier.fillMaxWidth().safeDrawingPadding().padding(top = 52.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = notice != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            val n = shown ?: return@AnimatedVisibility
            Row(
                Modifier.padding(horizontal = 16.dp).widthIn(max = 620.dp)
                    .shadow(12.dp, RoundedCornerShape(14.dp))
                    .background(MonitorColors.panel, RoundedCornerShape(14.dp))
                    .border(1.dp, MonitorColors.good.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .clickable { onDismiss(n) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text("🎙", fontSize = 20.sp)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✓ ${n.title}", color = MonitorColors.good, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(n.filed, color = MonitorColors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("“${n.words}”", color = MonitorColors.text, fontSize = 15.sp, lineHeight = 20.sp)
                    n.footnote?.let { Text(it, color = MonitorColors.label, fontSize = 11.sp, fontStyle = FontStyle.Italic) }
                }
            }
        }
    }
}
