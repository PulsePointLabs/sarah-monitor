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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
    val minWidthDp: Int = 76,
    val minHeightDp: Int = 44
) {
    MarkEvent("＋ Mark event button", null, 64, 64, 40, 40),
    // Cards shrink down to a compact strip (value, arrows, a tiny trend); see TinyOverlay.
    HeartRate("Heart rate", CardType.HeartRate, 250, 180),
    Hrv("HRV (RMSSD · SDNN · pNN50)", CardType.Hrv, 300, 200),
    Load("Physiological load", CardType.Load, 230, 160),
    BloodPressure("Blood pressure", CardType.BloodPressure, 280, 230),
    Pelvic("Pelvic pressure", CardType.Pelvic, 320, 340),
    Stress("Stress", CardType.Stress, 260, 170)
}

/** Overlays are set up separately for the phone screen and for DeX (or any external display). */
enum class OverlayProfile(val label: String, val key: String) {
    Phone("Phone", "phone"),
    Dex("DeX", "dex");

    companion object {
        fun forDisplay(displayId: Int) = if (displayId == android.view.Display.DEFAULT_DISPLAY) Phone else Dex
    }
}

/** One screen's overlay setup. [on] is the quick master switch; [enabled] is which overlays it shows. */
data class OverlayProfileState(
    val on: Boolean = true,
    val enabled: Set<OverlayKind> = emptySet(),
    val opacity: Float = 1f,
    val fadeReadings: Boolean = false,
    val plusSizeDp: Int = 64
)

/**
 * Which overlays are on, their look, and each one's remembered position and size — kept separately for the phone
 * and for DeX, so unplugging the monitor doesn't dump the DeX layout onto the phone. The flows without a profile
 * argument describe the screen the overlays are currently on.
 */
object OverlaySettings {
    private const val PREFS = "sarahmonitor_overlays"

    private val _states = MutableStateFlow(OverlayProfile.entries.associateWith { OverlayProfileState() })
    val states: StateFlow<Map<OverlayProfile, OverlayProfileState>> = _states

    private val _profile = MutableStateFlow(OverlayProfile.Phone)
    /** The screen overlays are showing on right now. */
    val profile: StateFlow<OverlayProfile> = _profile

    private val _enabled = MutableStateFlow<Set<OverlayKind>>(emptySet())
    /** Overlays switched on for the current screen (regardless of the master switch). */
    val enabled: StateFlow<Set<OverlayKind>> = _enabled
    private val _showing = MutableStateFlow<Set<OverlayKind>>(emptySet())
    /** What should be on screen now: the current screen's overlays, if its master switch is on. */
    val showing: StateFlow<Set<OverlayKind>> = _showing
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on
    private val _opacity = MutableStateFlow(1f)
    /** Opacity for all overlays on the current screen, 0.2–1. */
    val opacity: StateFlow<Float> = _opacity
    private val _fadeReadings = MutableStateFlow(false)
    /** false: only the card background fades (readings stay solid); true: the whole overlay fades. */
    val fadeReadings: StateFlow<Boolean> = _fadeReadings
    private val _othersHidden = MutableStateFlow(false)
    /** Long-press the ＋: everything but the ＋ steps aside for a clear look (until shown again). */
    val othersHidden: StateFlow<Boolean> = _othersHidden
    fun setOthersHidden(hidden: Boolean) { _othersHidden.value = hidden; publish() }

    private val _plusSizeDp = MutableStateFlow(64)
    /** Diameter of the floating ＋ button (set in Settings, not by dragging). */
    val plusSizeDp: StateFlow<Int> = _plusSizeDp

    /** True while Sarah Monitor's own window is the focused one; overlays step aside then. */
    private val _appInFront = MutableStateFlow(false)
    val appInFront: StateFlow<Boolean> = _appInFront
    fun setAppInFront(inFront: Boolean) { _appInFront.value = inFront }

    /** While the app has handed over to the camera or photo picker, overlays step aside (they covered the shutter). */
    private val _lent = MutableStateFlow(false)
    val lent: StateFlow<Boolean> = _lent
    fun setLent(on: Boolean) { _lent.value = on }

    /** While the Settings page is open, overlays stay visible even with the app in front, as a live preview. */
    private val _previewing = MutableStateFlow(false)
    val previewing: StateFlow<Boolean> = _previewing
    fun setPreviewing(on: Boolean) { _previewing.value = on }

    const val MIN_OPACITY = 0.2f
    const val MIN_PLUS_DP = 40
    const val MAX_PLUS_DP = 140

    private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        // Before 0.5.0 there was one setup, made on DeX: it becomes the DeX profile; the phone starts clean.
        if (!p.getBoolean("profiles_v1", false)) {
            val e = p.edit()
            OverlayKind.entries.forEach { k ->
                e.putBoolean("on_${k.name}_dex", p.getBoolean("on_${k.name}", false))
                listOf("x", "y", "w", "h").forEach { c -> if (p.contains("${c}_${k.name}")) e.putInt("${c}_${k.name}_dex", p.getInt("${c}_${k.name}", 0)) }
            }
            e.putFloat("alpha_dex", p.getFloat("alpha", 1f))
            e.putBoolean("fade_readings_dex", p.getBoolean("fade_readings", false))
            e.putInt("plus_dp_dex", p.getInt("plus_dp", 64))
            e.putBoolean("profiles_v1", true).apply()
        }
        _states.value = OverlayProfile.entries.associateWith { pr ->
            OverlayProfileState(
                on = p.getBoolean("master_${pr.key}", true),
                enabled = OverlayKind.entries.filter { p.getBoolean("on_${it.name}_${pr.key}", false) }.toSet(),
                opacity = p.getFloat("alpha_${pr.key}", 1f).coerceIn(MIN_OPACITY, 1f),
                fadeReadings = p.getBoolean("fade_readings_${pr.key}", false),
                plusSizeDp = p.getInt("plus_dp_${pr.key}", 64).coerceIn(MIN_PLUS_DP, MAX_PLUS_DP)
            )
        }
        publish()
    }

    private fun publish() {
        val s = _states.value[_profile.value] ?: OverlayProfileState()
        _enabled.value = s.enabled
        _on.value = s.on
        _showing.value = if (!s.on) emptySet() else if (_othersHidden.value) s.enabled.filter { it == OverlayKind.MarkEvent }.toSet() else s.enabled
        _opacity.value = s.opacity
        _fadeReadings.value = s.fadeReadings
        _plusSizeDp.value = s.plusSizeDp
    }

    fun setProfile(profile: OverlayProfile) {
        if (_profile.value == profile) return
        _profile.value = profile
        publish()
    }

    fun state(profile: OverlayProfile): OverlayProfileState = _states.value[profile] ?: OverlayProfileState()

    private fun update(profile: OverlayProfile, change: (OverlayProfileState) -> OverlayProfileState) {
        _states.value = _states.value + (profile to change(state(profile)))
        publish()
    }

    fun setOn(context: Context, on: Boolean, profile: OverlayProfile = _profile.value) {
        update(profile) { it.copy(on = on) }
        prefs(context).edit().putBoolean("master_${profile.key}", on).apply()
    }

    /** The quick on/off for the screen overlays are on now. Returns the new state. */
    fun toggle(context: Context): Boolean {
        val next = !state(_profile.value).on
        setOn(context, next)
        return next
    }

    fun setOpacity(context: Context, value: Float, profile: OverlayProfile = _profile.value) {
        val v = value.coerceIn(MIN_OPACITY, 1f)
        update(profile) { it.copy(opacity = v) }
        prefs(context).edit().putFloat("alpha_${profile.key}", v).apply()
    }

    fun setFadeReadings(context: Context, on: Boolean, profile: OverlayProfile = _profile.value) {
        update(profile) { it.copy(fadeReadings = on) }
        prefs(context).edit().putBoolean("fade_readings_${profile.key}", on).apply()
    }

    fun setPlusSize(context: Context, dp: Int, profile: OverlayProfile = _profile.value) {
        val v = dp.coerceIn(MIN_PLUS_DP, MAX_PLUS_DP)
        update(profile) { it.copy(plusSizeDp = v) }
        prefs(context).edit().putInt("plus_dp_${profile.key}", v).apply()
    }

    fun set(context: Context, kind: OverlayKind, on: Boolean, profile: OverlayProfile = _profile.value) {
        prefs(context).edit().putBoolean("on_${kind.name}_${profile.key}", on).apply()
        update(profile) { it.copy(enabled = if (on) it.enabled + kind else it.enabled - kind) }
    }

    /**
     * Saved x, y, width, height in pixels of that screen and orientation ([orient] = "port" / "land"): portrait and
     * landscape each keep their own layout, so rotating the phone doesn't scramble them. Falls back to the layout saved
     * before orientations were separate, then to stacking down the left edge.
     */
    fun bounds(context: Context, kind: OverlayKind, index: Int, density: Float, profile: OverlayProfile, orient: String): IntArray {
        val p = prefs(context)
        val old = "${kind.name}_${profile.key}"
        val k = "${old}_$orient"
        fun get(c: String, def: Int) = if (p.contains("${c}_$k")) p.getInt("${c}_$k", def) else p.getInt("${c}_$old", def)
        return intArrayOf(
            get("x", (16 * density).roundToInt()),
            get("y", ((96 + index * 150) * density).roundToInt()),
            get("w", (kind.defaultWidthDp * density).roundToInt()),
            get("h", (kind.defaultHeightDp * density).roundToInt())
        )
    }

    fun saveBounds(context: Context, kind: OverlayKind, x: Int, y: Int, w: Int, h: Int, profile: OverlayProfile, orient: String) {
        val k = "${kind.name}_${profile.key}_$orient"
        prefs(context).edit().putInt("x_$k", x).putInt("y_$k", y).putInt("w_$k", w).putInt("h_$k", h).apply()
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
 * Shows the enabled overlays as floating windows on the display the app was last on (phone or DeX). Drag a card to
 * move it (it clicks onto neighbors and screen edges and never ends up on top of another), drag the corner grip to
 * resize (edges and sizes snap too), and drag it onto the circle that appears at the bottom to close it. Tap the ＋
 * to mark an event; long-press it to hide or show the other overlays. Portrait and landscape each keep their own
 * layout. Touches are read in screen coordinates, so snapping never makes a window drift from under the finger.
 */
class OverlayManager(private val base: Context) {
    private class Shown(
        val view: android.view.View, val owner: OverlayOwner, val params: WindowManager.LayoutParams,
        val wm: WindowManager, val displayId: Int, val context: Context, var orient: String
    )

    private val shown = mutableMapOf<OverlayKind, Shown>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun orientOf(context: Context, wm: WindowManager): String =
        OverlayDisplay.overlayArea(context, wm).let { if (it.width() > it.height()) "land" else "port" }

    /** Shows/hides overlays to match the settings for the screen the app is on (phone or DeX). */
    fun sync() {
        val display = OverlayDisplay.targetDisplayId(base)
        OverlaySettings.setProfile(OverlayProfile.forDisplay(display))
        val requested = OverlaySettings.showing.value
        val hidden = (OverlaySettings.appInFront.value && !OverlaySettings.previewing.value) || OverlaySettings.lent.value
        val enabled = (if (hidden) emptySet() else requested).toList()
        val canDraw = OverlaySettings.canDraw(base)
        // Hide removed ones and ones that are on the wrong display.
        shown.keys.toList().forEach { kind ->
            if (!canDraw || kind !in enabled || shown[kind]?.displayId != display) hide(kind)
        }
        if (!canDraw) return
        enabled.forEachIndexed { index, kind -> if (kind !in shown) show(kind, index, display) }
        // Rotated: each orientation has its own remembered layout.
        shown.forEach { (kind, s) ->
            val o = orientOf(s.context, s.wm)
            if (o != s.orient) { s.orient = o; place(kind, s, enabled.indexOf(kind)) }
        }
    }

    fun hideAll() = shown.keys.toList().forEach(::hide)

    private fun hide(kind: OverlayKind) {
        val s = shown.remove(kind) ?: return
        runCatching { s.wm.removeView(s.view) }
        s.owner.destroy()
    }

    private fun density(s: Shown) = s.context.resources.displayMetrics.density

    private fun minSize(kind: OverlayKind, density: Float): Pair<Int, Int> =
        if (kind.card == null) (OverlaySettings.MIN_PLUS_DP * density).roundToInt().let { it to it }
        else (kind.minWidthDp * density).roundToInt() to (kind.minHeightDp * density).roundToInt()

    private fun commit(kind: OverlayKind, s: Shown) {
        val b = OverlayDisplay.overlayArea(s.context, s.wm)
        val (minW, minH) = minSize(kind, density(s))
        s.params.width = s.params.width.coerceIn(minW, b.width().coerceAtLeast(minW))
        s.params.height = s.params.height.coerceIn(minH, b.height().coerceAtLeast(minH))
        s.params.x = s.params.x.coerceIn(0, (b.width() - s.params.width).coerceAtLeast(0))
        s.params.y = s.params.y.coerceIn(0, (b.height() - s.params.height).coerceAtLeast(0))
        if (shown[kind] === s) runCatching { s.wm.updateViewLayout(s.view, s.params) }
    }

    private fun place(kind: OverlayKind, s: Shown, index: Int) {
        val b = OverlaySettings.bounds(base, kind, index.coerceAtLeast(0), density(s), OverlayProfile.forDisplay(s.displayId), s.orient)
        s.params.x = b[0]; s.params.y = b[1]
        if (kind.card != null) { s.params.width = b[2]; s.params.height = b[3] }
        commit(kind, s)
        unstack(kind, s)
    }

    /** Never left on top of another overlay (first time shown, after rotating, or an old saved layout). */
    private fun unstack(kind: OverlayKind, s: Shown) {
        val others = others(kind, s.displayId)
        if (others.none { boxOf(s).overlaps(it) }) return
        OverlaySnap.settle(boxOf(s), others, feel(s)).let { s.params.x = it.x; s.params.y = it.y }
        commit(kind, s)
        save(kind, s)
    }

    private fun save(kind: OverlayKind, s: Shown) =
        OverlaySettings.saveBounds(base, kind, s.params.x, s.params.y, s.params.width, s.params.height, OverlayProfile.forDisplay(s.displayId), s.orient)

    private fun boxOf(s: Shown) = OverlaySnap.Box(s.params.x, s.params.y, s.params.width, s.params.height)
    private fun others(kind: OverlayKind, displayId: Int) = shown.filter { it.key != kind && it.value.displayId == displayId }.map { boxOf(it.value) }
    private fun feel(s: Shown) = OverlayDisplay.overlayArea(s.context, s.wm).let { OverlaySnap.feel(density(s), it.width(), it.height()) }

    private fun show(kind: OverlayKind, index: Int, displayId: Int) {
        val context = OverlayDisplay.overlayContext(base, displayId)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        val orient = orientOf(context, wm)
        val profile = OverlayProfile.forDisplay(displayId)
        val (x, y, w, h) = OverlaySettings.bounds(base, kind, index, density, profile, orient).let { listOf(it[0], it[1], it[2], it[3]) }
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
                        shown[kind]?.let { commit(kind, it) }
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    com.pulsepointlabs.sarahmonitor.ui.LocalPanelAlpha provides if (fadeAll) 1f else alpha,
                    com.pulsepointlabs.sarahmonitor.ui.LocalOverlayMode provides true
                ) {
                    androidx.compose.foundation.layout.BoxWithConstraints(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { this.alpha = if (fadeAll) alpha else 1f }
                            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_GRAB))
                    ) {
                        when {
                            kind.card == null -> PlusButton()
                            // Small: one clear number, arrows and a little trend, like a vital-signs strip.
                            maxWidth < TinyOverlaySize.MAX_WIDTH.dp || maxHeight < TinyOverlaySize.MAX_HEIGHT.dp -> TinyOverlay(kind, Modifier.fillMaxSize())
                            else -> MonitorCard(kind.card, Modifier.fillMaxSize())
                        }
                        if (kind.card != null) ResizeGrip(Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
        }
        // The card is display-only: this frame takes every touch first (move, resize, tap, long-press).
        val frame = TouchFrame(context, Gesture(kind)).apply {
            // Compose finds its lifecycle from the window's root view, which is this frame.
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(view)
        }
        val added = runCatching { wm.addView(frame, params) }.isSuccess
        if (!added) {
            owner.destroy()
            return
        }
        val s = Shown(frame, owner, params, wm, displayId, context, orient)
        shown[kind] = s
        commit(kind, s)
        unstack(kind, s)
    }

    /** Move / resize / tap / long-press for one overlay, from raw screen coordinates. */
    private inner class Gesture(private val kind: OverlayKind) : android.view.View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var start = OverlaySnap.Box(0, 0, 0, 0)
        private var resizing = false
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            val s = shown[kind] ?: return@Runnable
            if (dragging) return@Runnable
            longPressed = true
            s.view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            openQuickMenu(s)
        }

        override fun onTouch(v: android.view.View, e: android.view.MotionEvent): Boolean {
            val s = shown[kind] ?: return false
            val d = density(s)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    start = boxOf(s)
                    val grip = 30 * d
                    resizing = kind.card != null && e.x >= v.width - grip && e.y >= v.height - grip
                    dragging = false; longPressed = false
                    if (kind.card == null) handler.postDelayed(longPress, 450L)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && kotlin.math.hypot(dx, dy) > 8 * d && !longPressed) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                        if (!resizing) target.show(s)
                    }
                    if (!dragging) return true
                    val f = feel(s)
                    val others = others(kind, s.displayId)
                    if (resizing) {
                        val (minW, minH) = minSize(kind, d)
                        val b = OverlaySnap.resize(start, start.w + dx.roundToInt(), start.h + dy.roundToInt(), minW, minH, others, f)
                        s.params.width = b.w; s.params.height = b.h
                    } else {
                        val b = OverlaySnap.move(start.copy(x = start.x + dx.roundToInt(), y = start.y + dy.roundToInt()), others, f)
                        s.params.x = b.x; s.params.y = b.y
                        target.track(e.rawX, e.rawY, s.view)
                    }
                    commit(kind, s)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    val dropped = dragging && !resizing && target.hot && e.actionMasked == android.view.MotionEvent.ACTION_UP
                    target.hide()
                    when {
                        dropped -> { OverlaySettings.set(base, kind, false, OverlayProfile.forDisplay(s.displayId)); return true }
                        dragging -> {
                            if (!resizing) OverlaySnap.settle(boxOf(s), others(kind, s.displayId), feel(s)).let { s.params.x = it.x; s.params.y = it.y }
                            commit(kind, s)
                            save(kind, s)
                        }
                        e.actionMasked == android.view.MotionEvent.ACTION_UP && !longPressed && kind.card == null -> openMarkEvent(s.displayId)
                    }
                    dragging = false
                }
            }
            return true
        }
    }

    /** The "drop here to close" circle at the bottom, shown only while an overlay is being dragged. */
    private val target = DropTarget()

    private inner class DropTarget {
        private var view: ComposeView? = null
        private var owner: OverlayOwner? = null
        private var wm: WindowManager? = null
        private val hotFlow = MutableStateFlow(false)
        val hot: Boolean get() = hotFlow.value
        private var center: Pair<Float, Float>? = null
        private var radius = 0f

        fun show(s: Shown) {
            if (view != null) return
            val d = density(s)
            val sizePx = (84 * d).roundToInt()
            val b = OverlayDisplay.overlayArea(s.context, s.wm)
            val params = WindowManager.LayoutParams(
                sizePx, sizePx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (b.width() - sizePx) / 2
                y = (b.height() - sizePx - 110 * d).roundToInt().coerceAtLeast(0)
            }
            val o = OverlayOwner().also { it.start() }
            val v = ComposeView(s.context).apply {
                setViewTreeLifecycleOwner(o)
                setViewTreeSavedStateRegistryOwner(o)
                setContent { DropCircle(hotFlow) }
            }
            if (runCatching { s.wm.addView(v, params) }.isFailure) { o.destroy(); return }
            view = v; owner = o; wm = s.wm
            radius = 64 * d
            center = null
            v.post { val loc = IntArray(2); v.getLocationOnScreen(loc); center = (loc[0] + v.width / 2f) to (loc[1] + v.height / 2f) }
        }

        /** Finger at screen point ([rx], [ry]): is it over the circle? A little buzz when it first is. */
        fun track(rx: Float, ry: Float, haptics: android.view.View) {
            val c = center ?: return
            val over = kotlin.math.hypot(rx - c.first, ry - c.second) <= radius
            if (over && !hotFlow.value) haptics.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            hotFlow.value = over
        }

        fun hide() {
            view?.let { v -> runCatching { wm?.removeView(v) } }
            owner?.destroy()
            view = null; owner = null; wm = null; center = null
            hotFlow.value = false
        }
    }

    private var quickMenu: Shown? = null

    /** Long-press on the ＋: hide or show the other overlays, open the app, or mark an event. */
    private fun openQuickMenu(plus: Shown) {
        if (quickMenu != null) return
        val context = plus.context
        val wm = plus.wm
        val d = density(plus)
        val owner = OverlayOwner().also { it.start() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        val b = OverlayDisplay.overlayArea(context, wm)
        // Beside the ＋, on whichever side has room.
        val menuW = 230 * d
        val menuH = 190 * d
        val left = if (plus.params.x + plus.params.width + menuW < b.width()) plus.params.x + plus.params.width + 8 * d else plus.params.x - menuW - 8 * d
        val top = (plus.params.y + plus.params.height / 2f - menuH / 2f).coerceIn(8 * d, (b.height() - menuH - 8 * d).coerceAtLeast(8 * d))
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val hidden by OverlaySettings.othersHidden.collectAsState()
                Box(
                    Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f))
                        .pointerInput(Unit) { detectTapGestures { closeQuickMenu() } }
                ) {
                    androidx.compose.foundation.layout.Column(
                        Modifier
                            .padding(start = (left.coerceAtLeast(8 * d) / d).dp, top = (top / d).dp)
                            .widthIn(min = 200.dp, max = 260.dp)
                            .background(MonitorColors.panel, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .border(1.dp, MonitorColors.panelBorder, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .pointerInput(Unit) { detectTapGestures { } }
                            .padding(8.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                    ) {
                        MenuItem(if (hidden) "Show overlays" else "Hide other overlays", MonitorColors.pelvic) {
                            OverlaySettings.setOthersHidden(!hidden); closeQuickMenu()
                        }
                        MenuItem("Mark event", MonitorColors.warn) { closeQuickMenu(); openMarkEvent(plus.displayId) }
                        MenuItem("Open Sarah Monitor", MonitorColors.label) { closeQuickMenu(); openApp(plus.displayId) }
                    }
                }
            }
        }
        if (runCatching { wm.addView(view, params) }.isFailure) { owner.destroy(); return }
        quickMenu = Shown(view, owner, params, wm, plus.displayId, context, plus.orient)
    }

    private fun closeQuickMenu() {
        val m = quickMenu ?: return
        quickMenu = null
        runCatching { m.wm.removeView(m.view) }
        m.owner.destroy()
    }

    private var markPanel: Shown? = null

    /**
     * Event entry as a floating panel over whatever is on screen (a real overlay, not an app window): dimmed
     * backdrop, tap outside or Cancel to close. Focusable, so typing and voice work; the panel moves up with the
     * keyboard.
     */
    private fun openMarkEvent(displayId: Int) {
        if (markPanel != null) return
        val pressedAt = System.currentTimeMillis()
        val context = OverlayDisplay.overlayContext(base, displayId)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val owner = OverlayOwner().also { it.start() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                androidx.compose.material3.MaterialTheme(
                    colorScheme = androidx.compose.material3.darkColorScheme(background = MonitorColors.background, surface = MonitorColors.panel, primary = MonitorColors.pelvic)
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                            .pointerInput(Unit) { detectTapGestures { closeMarkEvent() } }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.94f)
                                .widthIn(max = 620.dp)
                                .pointerInput(Unit) { detectTapGestures { } }
                        ) {
                            com.pulsepointlabs.sarahmonitor.review.MarkEventFlow(
                                pressedAt, inOverlay = true,
                                onToast = { android.widget.Toast.makeText(base, it, android.widget.Toast.LENGTH_SHORT).show() },
                                onDone = { closeMarkEvent() }
                            )
                        }
                    }
                }
            }
        }
        if (runCatching { wm.addView(view, params) }.isFailure) { owner.destroy(); return }
        markPanel = Shown(view, owner, params, wm, displayId, context, orientOf(context, wm))
    }

    private fun closeMarkEvent() {
        val p = markPanel ?: return
        markPanel = null
        runCatching { p.wm.removeView(p.view) }
        p.owner.destroy()
    }

    private fun openApp(displayId: Int) {
        val intent = Intent(base, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        OverlayDisplay.startActivityOnDisplay(base, intent, displayId)
    }
}

/** Holds an overlay's content and gives all its touches to the window's gesture handler (Compose would swallow them). */
private class TouchFrame(context: Context, private val gesture: android.view.View.OnTouchListener) : android.widget.FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean = true
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = gesture.onTouch(this, event)
}

/** Corner grip (drawn only; the overlay's touch handler resizes from this corner). */
@androidx.compose.runtime.Composable
private fun ResizeGrip(modifier: Modifier) {
    Canvas(modifier.size(22.dp).padding(5.dp)) {
        val c = MonitorColors.label.copy(alpha = 0.7f)
        val sz = size.width
        for (i in 1..3) {
            val o = sz * i / 3.2f
            drawLine(c, Offset(sz - o, sz), Offset(sz, sz - o), strokeWidth = 1.5f)
        }
    }
}

/** The close target: a ring with ✕ that grows and turns red while an overlay is held over it. */
@androidx.compose.runtime.Composable
private fun DropCircle(hot: StateFlow<Boolean>) {
    val on by hot.collectAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (on) 1.15f else 0.9f, label = "drop-scale")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(68.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .background((if (on) MonitorColors.alarm else MonitorColors.background).copy(alpha = if (on) 0.9f else 0.8f), androidx.compose.foundation.shape.CircleShape)
                .border(2.dp, if (on) androidx.compose.ui.graphics.Color.White else MonitorColors.label.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("✕", color = androidx.compose.ui.graphics.Color.White, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
    }
}

@androidx.compose.runtime.Composable
private fun MenuItem(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(color.copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) { Text(label, color = MonitorColors.text, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1) }
}

/** Round glowing ＋ that fills its window; the symbol scales with the button. Taps are handled by the window. */
@androidx.compose.runtime.Composable
private fun PlusButton() {
    val recording by MonitorHub.session.collectAsState()
    val hidden by OverlaySettings.othersHidden.collectAsState()
    val panelAlpha = com.pulsepointlabs.sarahmonitor.ui.LocalPanelAlpha.current
    val color = (if (recording != null) MonitorColors.warn else MonitorColors.label).let { it.copy(alpha = it.alpha * panelAlpha) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val d = minOf(maxWidth, maxHeight)
        Box(
            Modifier
                .size(d)
                .plusDisc(color, ring = if (hidden) MonitorColors.pelvic else null)
        )
        Text(
            "+",
            color = MonitorColors.background,
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (d * 0.62f).toSp() },
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}

/** [ring]: a colored ring while the other overlays are hidden, so it's clear they're only stepped aside. */
private fun Modifier.plusDisc(color: androidx.compose.ui.graphics.Color, ring: androidx.compose.ui.graphics.Color? = null): Modifier =
    this
        .then(Modifier.background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color, color.copy(alpha = 0.75f))), androidx.compose.foundation.shape.CircleShape))
        .then(Modifier.border(if (ring != null) 3.dp else 2.dp, ring ?: androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape))