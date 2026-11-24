package com.pulsepointlabs.sarahmonitor.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

/** Shape of the picture-in-picture window. Android keeps this ratio while you pinch-resize it. */
enum class PipShape(val label: String, val w: Int, val h: Int) { Wide("Wide", 16, 9), Square("Square", 1, 1), Tall("Tall", 9, 16) }

/** Picture-in-picture: which cards it shows (in order), its shape, and whether leaving the app starts it. */
object PipSettings {
    private const val PREFS = "sarahmonitor_pip"
    const val MAX_CARDS = 4
    val cards = MutableStateFlow(listOf(CardType.HeartRate, CardType.Pelvic))
    val shape = MutableStateFlow(PipShape.Wide)
    val autoEnter = MutableStateFlow(false)
    /** True while the app is showing as a picture-in-picture window. */
    val active = MutableStateFlow(false)

    fun load(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("cards", null)?.split(',')?.mapNotNull { n -> CardType.entries.firstOrNull { it.name == n } }?.takeIf { it.isNotEmpty() }?.let { cards.value = it }
        shape.value = PipShape.entries.firstOrNull { it.name == p.getString("shape", null) } ?: PipShape.Wide
        autoEnter.value = p.getBoolean("auto", false)
    }

    private fun save(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("cards", cards.value.joinToString(",") { it.name }).putString("shape", shape.value.name).putBoolean("auto", autoEnter.value).apply()

    fun toggle(c: Context, type: CardType) {
        val now = cards.value
        cards.value = if (type in now) (now - type).ifEmpty { now } else (now + type).takeLast(MAX_CARDS)
        save(c)
    }
    fun setShape(c: Context, s: PipShape) { shape.value = s; save(c) }
    fun setAuto(c: Context, on: Boolean) { autoEnter.value = on; save(c) }

    fun params(): PictureInPictureParams {
        val s = shape.value
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(s.w, s.h))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(autoEnter.value).setSeamlessResizeEnabled(true)
        return b.build()
    }

    /** Starts picture-in-picture now. */
    fun enter(activity: Activity): Boolean = runCatching { activity.enterPictureInPictureMode(params()) }.getOrDefault(false)
}

/**
 * What the picture-in-picture window shows: the chosen cards in their compact overlay style, side by side in a wide
 * window, stacked in a tall one, 2 × 2 when four share a squarish window. Fills the window, no gaps.
 */
@Composable
fun PipContent() {
    val cards by PipSettings.cards.collectAsState()
    CompositionLocalProvider(LocalOverlayMode provides true) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MonitorColors.background).padding(3.dp)) {
            val wide = maxWidth > maxHeight * 1.25f
            val tall = maxHeight > maxWidth * 1.25f
            val gap = Arrangement.spacedBy(3.dp)
            when {
                cards.size == 4 && !wide && !tall -> Column(Modifier.fillMaxSize(), verticalArrangement = gap) {
                    cards.chunked(2).forEach { pair -> Row(Modifier.weight(1f), horizontalArrangement = gap) { pair.forEach { MonitorCard(it, Modifier.weight(1f).fillMaxHeight()) } } }
                }
                tall -> Column(Modifier.fillMaxSize(), verticalArrangement = gap) { cards.forEach { MonitorCard(it, Modifier.weight(1f).fillMaxWidth()) } }
                else -> Row(Modifier.fillMaxSize(), horizontalArrangement = gap) { cards.forEach { MonitorCard(it, Modifier.weight(1f).fillMaxHeight()) } }
            }
        }
    }
}

/** Choose the picture-in-picture cards (tap in the order you want, up to four), its shape, and auto-start. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PipSettingsDialog(onStart: () -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cards by PipSettings.cards.collectAsState()
    val shape by PipSettings.shape.collectAsState()
    val auto by PipSettings.autoEnter.collectAsState()
    val accent = MonitorColors.pelvic
    @Composable
    fun Chip(label: String, on: Boolean, badge: String? = null, onClick: () -> Unit) {
        Text(
            (badge?.let { "$it  " } ?: if (on) "✓ " else "") + label,
            color = if (on) MonitorColors.background else accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.heightIn(min = 36.dp).background(if (on) accent else accent.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MonitorColors.panel,
        title = { Text("Picture-in-picture", color = MonitorColors.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Cards (up to ${PipSettings.MAX_CARDS}, in the order you tap them)", color = MonitorColors.label, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardType.entries.forEach { t ->
                        val i = cards.indexOf(t)
                        Chip(t.title, i >= 0, if (i >= 0) "${i + 1}" else null) { PipSettings.toggle(context, t) }
                    }
                }
                Text("Window shape (pinch the window to resize it)", color = MonitorColors.label, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PipShape.entries.forEach { s -> Chip(s.label, s == shape) { PipSettings.setShape(context, s) } } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start when leaving the app", color = MonitorColors.text, fontSize = 15.sp)
                        Text("Home or switching apps opens it automatically", color = MonitorColors.label, fontSize = 12.sp)
                    }
                    Switch(auto, { PipSettings.setAuto(context, it) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onStart) { Text("Start picture-in-picture", color = accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = MonitorColors.label) } }
    )
}
