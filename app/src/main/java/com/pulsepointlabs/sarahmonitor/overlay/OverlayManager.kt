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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    MarkEvent("＋ Mark event button", null, 76, 76, 44, 44),
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

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = OverlayKind.entries.filter { prefs.getBoolean("on_${it.name}", false) }.toSet()
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
 * Shows the enabled overlays as floating windows on the display the app was last on (phone or DeX). Each is
 * dragged by its body and resized from the corner grip; the card inside rescales its text to the new size.
 */
class OverlayManager(private val base: Context) {
    private class Shown(val view: ComposeView, val owner: OverlayOwner, val params: WindowManager.LayoutParams, val wm: WindowManager, val displayId: Int)

    private val shown = mutableMapOf<OverlayKind, Shown>()

    fun sync(requested: Set<OverlayKind>) {
        val enabled = if (OverlaySettings.appInFront.value) emptySet() else requested
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
        val minW = (kind.minWidthDp * density).roundToInt()
        val minH = (kind.minHeightDp * density).roundToInt()

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
                Box(
                    Modifier
                        .fillMaxSize()
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
                        // The floating ＋: tap to mark an event, drag to move, corner to resize.
                        PlusButton(onTap = { openMarkEvent(displayId) })
                    } else {
                    MonitorCard(kind.card, Modifier.fillMaxSize())
                    Text(
                        "×",
                        color = MonitorColors.label,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clickable { OverlaySettings.set(base, kind, false) }
                            .padding(horizontal = 8.dp, vertical = 0.dp)
                    )
                    val recording by MonitorHub.session.collectAsState()
                    if (recording != null) {
                        Text(
                            "＋",
                            color = MonitorColors.warn,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .clickable { quickMark() }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        "⤢",
                        color = MonitorColors.label,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .clickable { openApp(displayId) }
                            .padding(horizontal = 4.dp)
                    )
                    }
                    Canvas(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
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
                            .padding(6.dp)
                    ) {
                        val c = MonitorColors.label.copy(alpha = 0.75f)
                        val s = size.width
                        for (i in 1..3) {
                            val o = s * i / 3.2f
                            drawLine(c, Offset(s - o, s), Offset(s, s - o), strokeWidth = 1.6f)
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

    private fun quickMark() {
        MonitorHub.markEvent(System.currentTimeMillis(), setOf("event"), "") { ok ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(base, if (ok) "Event marked — add details in review" else "Not recording", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
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
    val color = if (recording != null) MonitorColors.warn else MonitorColors.label
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
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
