package com.pulsepointlabs.sarahmonitor.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pulsepointlabs.sarahmonitor.MainActivity
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.pulsepointlabs.sarahmonitor.ui.CardType
import com.pulsepointlabs.sarahmonitor.ui.MonitorCard
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** The independent floating overlays, one per sensor. */
enum class OverlayKind(
    val label: String,
    val card: CardType?,
    val defaultWidthDp: Int,
    val defaultHeightDp: Int,
    val minWidthDp: Int = 150,
    val minHeightDp: Int = 100
) {
    MarkEvent("＋ Mark event button", null, 64, 64, 40, 40),
    HeartRate("Heart rate", CardType.HeartRate, 250, 180),
    Hrv("HRV (RMSSD · SDNN · pNN50)", CardType.Hrv, 300, 200),
    Load("Physiological load", CardType.Load, 230, 160),
    BloodPressure("Blood pressure", CardType.BloodPressure, 280, 230),
    Pelvic("Pelvic pressure", CardType.Pelvic, 320, 340)
}

/** Which overlays are on, plus each one's remembered position and size. */
object OverlaySettings {
    private const val PREFS = "sarahmonitor_overlays"
    private val _enabled = MutableStateFlow<Set<OverlayKind>>(emptySet())
    val enabled: StateFlow<Set<OverlayKind>> = _enabled

    /** True while Sarah Monitor's own window is the focused one; overlays step aside then. */
    private val _appInFront = MutableStateFlow(false)
    val appInFront: StateFlow<Boolean> = _appInFront

    fun setAppInFront(inFront: Boolean) { _appInFront.value = inFront }

    /** While the Overlays panel is open, overlays stay visible even with the app in front, as a live preview. */
    private val _previewing = MutableStateFlow(false)
    val previewing: StateFlow<Boolean> = _previewing
    fun setPreviewing(on: Boolean) { _previewing.value = on }

    private val _opacity = MutableStateFlow(1f)
    /** Opacity for all overlays, 0.2–1. */
    val opacity: StateFlow<Float> = _opacity

    private val _fadeReadings = MutableStateFlow(false)
    /** false: only the card background fades (readings stay solid); true: the whole overlay fades. */
    val fadeReadings: StateFlow<Boolean> = _fadeReadings

    private val _plusSizeDp = MutableStateFlow(64)
    /** Diameter of the floating ＋ button (set in Settings, not by dragging). */
    val plusSizeDp: StateFlow<Int> = _plusSizeDp

    fun setOpacity(context: Context, value: Float) {
        val v = value.coerceIn(MIN_OPACITY, 1f)
        _opacity.value = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("alpha", v).apply()
    }

    fun setFadeReadings(context: Context, on: Boolean) {
        _fadeReadings.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("fade_readings", on).apply()
    }

    fun setPlusSize(context: Context, dp: Int) {
        val v = dp.coerceIn(MIN_PLUS_DP, MAX_PLUS_DP)
        _plusSizeDp.value = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("plus_dp", v).apply()
    }

    const val MIN_OPACITY = 0.2f
    const val MIN_PLUS_DP = 40
    const val MAX_PLUS_DP = 140

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = OverlayKind.entries.filter { prefs.getBoolean("on_${it.name}", false) }.toSet()
        _opacity.value = prefs.getFloat("alpha", 1f).coerceIn(MIN_OPACITY, 1f)
        _fadeReadings.value = prefs.getBoolean("fade_readings", false)
        _plusSizeDp.value = prefs.getInt("plus_dp", 64).coerceIn(MIN_PLUS_DP, MAX_PLUS_DP)
    }

    fun set(context: Context, kind: OverlayKind, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on_${kind.name}", on).apply()
        _enabled.value = if (on) _enabled.value + kind else _enabled.value - kind
    }

    fun bounds(context: Context, kind: OverlayKind, index: Int): IntArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val density = context.resources.displayMetrics.density
        return intArrayOf(
            prefs.getInt("x_${kind.name}", (16 * density).roundToInt()),
            prefs.getInt("y_${kind.name}", ((96 + index * 210) * density).roundToInt()),
            prefs.getInt("w_${kind.name}", (kind.defaultWidthDp * density).roundToInt()),
            prefs.getInt("h_${kind.name}", (kind.defaultHeightDp * density).roundToInt())
        )
    }

    fun saveBounds(context: Context, kind: OverlayKind, x: Int, y: Int, w: Int, h: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("x_${kind.name}", x).putInt("y_${kind.name}", y)
            .putInt("w_${kind.name}", w).putInt("h_${kind.name}", h).apply()
    }

    fun canDraw(context: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
}

private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun start() {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * Shows the enabled overlays as floating windows on the display the app was last on (phone or DeX). Cards are
 * dragged by their body and resized from the corner grip; the card inside rescales to the new size. The ＋
 * button is dragged to move; its size is set in Settings.
 */
class OverlayManager(private val base: Context) {
    private class Shown(val view: ComposeView, val owner: OverlayOwner, val params: WindowManager.LayoutParams, val wm: WindowManager, val displayId: Int)

    private val shown = mutableMapOf<OverlayKind, Shown>()

    fun sync(requested: Set<OverlayKind>) {
        val enabled = if (OverlaySettings.appInFront.value && !OverlaySettings.previewing.value) emptySet() else requested
        val canDraw = OverlaySettings.canDraw(base)
        val display = OverlayDisplay.targetDisplayId(base)
        // Hide removed ones and ones that are on the wrong display.
        shown.keys.toList().forEach { kind ->
            if (!canDraw || kind !in enabled || shown[kind]?.displayId != display) hide(kind)
        }
        if (!canDraw) return
        enabled.forEachIndexed { index, kind -> if (kind !in shown) show(kind, index, display) }
    }

    fun hideAll() = shown.keys.toList().forEach(::hide)

    private fun hide(kind: OverlayKind) {
        val s = shown.remove(kind) ?: return
        runCatching { s.wm.removeView(s.view) }
        s.owner.destroy()
    }

    private fun show(kind: OverlayKind, index: Int, displayId: Int) {
        val context = OverlayDisplay.overlayContext(base, displayId)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        val (x, y, w, h) = OverlaySettings.bounds(base, kind, index).let { listOf(it[0], it[1], it[2], it[3]) }
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        val owner = OverlayOwner().also { it.start() }
        val minW = ((if (kind.card == null) OverlaySettings.MIN_PLUS_DP else kind.minWidthDp) * density).roundToInt()
        val minH = ((if (kind.card == null) OverlaySettings.MIN_PLUS_DP else kind.minHeightDp) * density).roundToInt()

        fun bounds(): android.graphics.Rect = OverlayDisplay.displayBounds(context, wm)
        fun commit() {
            val b = bounds()
            params.width = params.width.coerceIn(minW, b.width())
            params.height = params.height.coerceIn(minH, b.height())
            params.x = params.x.coerceIn(0, (b.width() - params.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (b.height() - params.height).coerceAtLeast(0))
            shown[kind]?.let { runCatching { wm.updateViewLayout(it.view, params) } }
        }

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val alpha by OverlaySettings.opacity.collectAsState()
                val fadeAll by OverlaySettings.fadeReadings.collectAsState()
                val plusDp by OverlaySettings.plusSizeDp.collectAsState()
                if (kind.card == null) {
                    // The ＋ window is exactly the button: no invisible margin that swallows taps.
                    androidx.compose.runtime.LaunchedEffect(plusDp) {
                        val px = (plusDp * density).roundToInt()
                        params.width = px
                        params.height = px
                        commit()
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    com.pulsepointlabs.sarahmonitor.ui.LocalPanelAlpha provides if (fadeAll) 1f else alpha,
                    com.pulsepointlabs.sarahmonitor.ui.LocalOverlayMode provides true
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { this.alpha = if (fadeAll) alpha else 1f }
                            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_GRAB))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDrag = { change, amount ->
                                        change.consume()
                                        params.x += amount.x.roundToInt()
                                        params.y += amount.y.roundToInt()
                                        commit()
                                    },
                                    onDragEnd = { OverlaySettings.saveBounds(base, kind, params.x, params.y, params.width, params.height) }
                                )
                            }
                    ) {
                        if (kind.card == null) {
                            PlusButton(onTap = { openMarkEvent(displayId) })
                        } else {
                            MonitorCard(kind.card, Modifier.fillMaxSize())
                            // Corner buttons: open the app, close this overlay.
                            Row(Modifier.align(Alignment.TopEnd).padding(3.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
                                CornerButton("⤢") { openApp(displayId) }
                                CornerButton("×") { OverlaySettings.set(base, kind, false) }
                            }
                            Canvas(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(22.dp)
                                    .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW))
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDrag = { change, amount ->
                                                change.consume()
                                                params.width += amount.x.roundToInt()
                                                params.height += amount.y.roundToInt()
                                                commit()
                                            },
                                            onDragEnd = { OverlaySettings.saveBounds(base, kind, params.x, params.y, params.width, params.height) }
                                        )
                                    }
                                    .padding(5.dp)
                            ) {
                                val c = MonitorColors.label.copy(alpha = 0.7f)
                                val sz = size.width
                                for (i in 1..3) {
                                    val o = sz * i / 3.2f
                                    drawLine(c, Offset(sz - o, sz), Offset(sz, sz - o), strokeWidth = 1.5f)
                                }
                            }
                        }
                    }
                }
            }
        }
        val added = runCatching { wm.addView(view, params) }.isSuccess
        if (!added) {
            owner.destroy()
            return
        }
        shown[kind] = Shown(view, owner, params, wm, displayId)
        commit()
    }

    private fun openMarkEvent(displayId: Int) {
        val intent = Intent(base, com.pulsepointlabs.sarahmonitor.review.MarkEventActivity::class.java)
            .putExtra(com.pulsepointlabs.sarahmonitor.review.MarkEventActivity.EXTRA_PRESSED_AT, System.currentTimeMillis())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        OverlayDisplay.startActivityOnDisplay(base, intent, displayId)
    }

    private fun openApp(displayId: Int) {
        val intent = Intent(base, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        OverlayDisplay.startActivityOnDisplay(base, intent, displayId)
    }
}


/** Round glowing ＋ that fills its window; the symbol scales with the button. */
@androidx.compose.runtime.Composable
private fun PlusButton(onTap: () -> Unit) {
    val recording by MonitorHub.session.collectAsState()
    val panelAlpha = com.pulsepointlabs.sarahmonitor.ui.LocalPanelAlpha.current
    val color = (if (recording != null) MonitorColors.warn else MonitorColors.label).let { it.copy(alpha = it.alpha * panelAlpha) }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onTap() } },
        contentAlignment = Alignment.Center
    ) {
        val d = minOf(maxWidth, maxHeight)
        Box(
            Modifier
                .size(d)
                .plusDisc(color)
        )
        Text(
            "+",
            color = MonitorColors.background,
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (d * 0.62f).toSp() },
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}

private fun Modifier.plusDisc(color: androidx.compose.ui.graphics.Color): Modifier =
    this
        .then(Modifier.background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color, color.copy(alpha = 0.75f))), androidx.compose.foundation.shape.CircleShape))
        .then(Modifier.border(2.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape))


@androidx.compose.runtime.Composable
private fun CornerButton(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .background(MonitorColors.background.copy(alpha = 0.55f), androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(symbol, color = MonitorColors.label, fontSize = 13.sp) }
}
