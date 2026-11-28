package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.layout.onPlaced
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.drag as dragPointer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val GAP_DP = 6f

/** Two-finger touchpad swipes arrive as scroll steps; this many dp of movement per step. */
private const val SCROLL_STEP_DP = 48f

/**
 * Free-form monitor board. On wide screens (DeX, tablets) cards sit on a 24-column grid that fills the screen.
 *
 * Arrange mode (top-bar button, or press-and-hold any card): grab a card anywhere — mouse hold-drag, touch
 * drag, or two-finger touchpad swipe — and it lifts and follows the pointer while a translucent placeholder
 * shows where it will land and the other cards slide aside to make room. Drag the corner grip to resize.
 * On a phone the same cards stack in one column in layout order.
 */
@Composable
fun Dashboard(
    slots: List<CardSlot>,
    arranging: Boolean,
    onArrangingChange: (Boolean) -> Unit,
    onChange: (List<CardSlot>) -> Unit,
    modifier: Modifier = Modifier,
    /** Phone layout (portrait or landscape): fit-to-screen rows instead of the free grid. */
    compact: Boolean = false
) {
    // Howl's card is part of the board only while Howl is connected (its saved place is kept).
    val howlOn = com.pulsepointlabs.sarahmonitor.howl.Howl.state.collectAsStateCompat()
    val shown = if (howlOn) slots else GridLayoutEngine.compact(slots.map { if (it.type == CardType.Howl) it.copy(visible = false) else it })
    val keep: (List<CardSlot>) -> Unit = { new -> onChange(if (howlOn) new else new.map { n -> if (n.type == CardType.Howl) slots.firstOrNull { it.type == CardType.Howl } ?: n else n }) }
    BoxWithConstraints(modifier) {
        if (compact) {
            FitDashboard(shown, maxWidth.value, maxHeight.value, keep)
        } else {
            Column(Modifier.fillMaxSize()) {
                if (arranging) ArrangeBanner { onArrangingChange(false) }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    GridDashboard(shown, arranging, onArrangingChange, keep, maxWidth, maxHeight)
                }
            }
        }
    }
}

/** True for phone-sized windows (portrait or landscape), which use the fit-to-screen layout and their own card set. */
@Composable
fun isCompactWindow(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return c.screenWidthDp < 600 || c.screenHeightDp < 480
}

@Composable
private fun ArrangeBanner(onDone: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(MonitorColors.pelvic.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .border(1.dp, MonitorColors.pelvic.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Arranging",
            color = MonitorColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f)
        )
        Text(
            "Done",
            color = MonitorColors.background,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(MonitorColors.pelvic, RoundedCornerShape(6.dp))
                .clickable(onClick = onDone)
                .padding(horizontal = 14.dp, vertical = 5.dp)
        )
    }
}

/**
 * Every visible card on one screen: rows share the height, cards in a row share the width by their span, so there
 * is never empty space. Editing on the phone (like home-screen widgets):
 * - press and hold a card: it's selected, with a round handle on each bottom corner;
 * - drag either corner handle outward to grow (the left one grows to the left, the right one to the right), inward
 *   to shrink, down or up for height. A card against the right edge can still grow from its left corner;
 * - drag the selected card's middle (or any card with two fingers) to move it: the others slide aside;
 * - tap another card, Done, or empty space: finished.
 * Other cards glide to their new places (no jumps), and a change that would squash any row below a readable height
 * is refused. One touch handler for the whole board (cards move between rows mid-drag, so per-card handlers would be
 * torn down), working in the board's own coordinates.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun FitDashboard(slots: List<CardSlot>, widthDp: Float, heightDp: Float, onChange: (List<CardSlot>) -> Unit) {
    // One state for the life of the board. (Keying it on [slots] made a fresh one after every save while the touch
    // handler kept writing to the old one, so only the first move or resize after opening did anything.)
    val workingState = remember { mutableStateOf(slots) }
    var working by workingState
    LaunchedEffect(slots) { working = slots }
    var selected by remember { mutableStateOf<CardType?>(null) }
    var moving by remember { mutableStateOf<CardType?>(null) }
    /** The card the moving one will swap with if released now (lit up while dragging). */
    var dropOn by remember { mutableStateOf<CardType?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var boardHeightPx by remember { mutableStateOf(0) }

    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<CardType, androidx.compose.ui.geometry.Rect>() }
    // Read live. (rememberUpdatedState only refreshed on the next redraw, so a save right after a swap stored the
    // layout from one move earlier, and that stale save then undid the next move.)
    val current by workingState
    val onChangeNow by rememberUpdatedState(onChange)
    val visible = working.filter { it.visible }.sortedWith(compareBy({ it.y }, { it.x }))
    // Units freeze while editing so the grid doesn't change under your finger.
    var frozenUnits by remember { mutableStateOf<Int?>(null) }
    val units = frozenUnits ?: FitLayout.bestUnitsFor(visible, widthDp, heightDp)
    val unitsNow by rememberUpdatedState(units)
    val rows = FitLayout.slotRows(visible, units)
    val density = LocalDensity.current
    val handlePx = with(density) { CORNER_HANDLE_HIT_DP.dp.toPx() }
    val gapPx = with(density) { GAP_DP.dp.toPx() }
    val editing = selected != null || moving != null
    if (!editing && frozenUnits != null) frozenUnits = null

    fun reorder(type: CardType, before: CardType) {
        val order = current.filter { it.visible }.sortedWith(compareBy({ it.y }, { it.x })).map { it.type }.toMutableList()
        val from = order.indexOf(type); val to = order.indexOf(before)
        if (from < 0 || to < 0 || from == to) return
        // Swap: the two cards trade places; nothing else moves.
        order[from] = before; order[to] = type
        working = current.map { s -> order.indexOf(s.type).takeIf { it >= 0 }?.let { s.copy(x = 0, y = it) } ?: s.copy(y = order.size + s.y) }
    }
    /** Applies a resize. Nothing gets squashed: if rows would get too short, the board scrolls instead. */
    fun tryResize(type: CardType, change: (CardSlot) -> CardSlot) {
        val candidate = current.map { if (it.type == type) change(it) else it }
        if (candidate != current) working = candidate
    }
    fun save() { onChangeNow(current) }
    fun cardAt(p: Offset): CardType? = bounds.entries.firstOrNull { it.value.contains(p) }?.key

    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val longPress = viewConfiguration.longPressTimeoutMillis
                val initial = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = initial)
                    val p0 = down.position + origin
                    val hit = cardAt(p0)
                    val sel = selected
                    val selBounds = sel?.let { bounds[it] }
                    // 1) A corner handle of the selected card: resize. The handles sit on the bottom corners and reach
                    //    a little outside the card, so a fingertip on the corner always catches one.
                    if (sel != null && selBounds != null) {
                        val nearBottom = p0.y in (selBounds.bottom - handlePx)..(selBounds.bottom + handlePx * 0.5f)
                        val leftHandle = nearBottom && p0.x in (selBounds.left - handlePx * 0.5f)..(selBounds.left + handlePx)
                        val rightHandle = nearBottom && p0.x in (selBounds.right - handlePx)..(selBounds.right + handlePx * 0.5f)
                        if (leftHandle || rightHandle) {
                            down.consume()
                            val slot0 = current.first { it.type == sel }
                            val span0 = FitLayout.span(slot0, unitsNow)
                            val f0 = FitLayout.heightFactor(slot0)
                            val w0 = selBounds.width; val h0 = selBounds.height
                            val unitW = (w0 + gapPx) / span0
                            while (true) {
                                val ev = awaitPointerEvent(initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) { ch.consume(); break }
                                ch.consume()
                                val d = ch.position - down.position
                                // Outward is wider: right for the right handle, left for the left one.
                                val outward = if (rightHandle) d.x else -d.x
                                val span = (span0 + (outward / unitW).roundToInt()).coerceIn(1, unitsNow)
                                val q = if (h0 > 0f) (Math.round(((h0 + d.y) / h0 * f0).coerceIn(0.5f, 3f) * 4) / 4f * 100).roundToInt() else (f0 * 100).roundToInt()
                                tryResize(sel) { it.copy(fitSpan = span, fitHeight = q) }
                            }
                            save()
                            return@awaitEachGesture
                        }
                    }
                    if (hit == null) return@awaitEachGesture
                    // While a card is selected, it's a handle: its own buttons don't react.
                    // While editing, every card is a handle (its own buttons don't react).
                    if (sel != null) down.consume()
                    val t0 = System.currentTimeMillis()
                    var mode = 0 // 0 undecided, 1 moving, 2 just selected by holding
                    fun begin(centroid: Offset) {
                        mode = 1; moving = hit; frozenUnits = unitsNow
                        val b = bounds[hit] ?: return
                        grab = centroid - b.topLeft; finger = centroid
                    }
                    while (true) {
                        val remaining = (longPress - (System.currentTimeMillis() - t0)).coerceAtLeast(1L)
                        val ev = if (mode == 0) withTimeoutOrNull(remaining) { awaitPointerEvent(initial) } else awaitPointerEvent(initial)
                        if (ev == null) { // held still: select this card
                            if (mode == 0) { mode = 2; selected = hit; frozenUnits = unitsNow }
                            continue
                        }
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            // A quick tap on another card while editing: finished.
                            // A quick tap on another card while editing selects that one.
                            if (mode == 0 && sel != null && hit != sel) { selected = hit; save() }
                            if (mode == 2 || sel != null) ev.changes.forEach { it.consume() }
                            break
                        }
                        val centroid = Offset(pressed.map { it.position.x }.average().toFloat(), pressed.map { it.position.y }.average().toFloat()) + origin
                        val traveled = ((pressed.firstOrNull { it.id == down.id }?.position ?: pressed.first().position) - down.position).getDistance()
                        when {
                            mode == 0 && pressed.size >= 2 -> begin(centroid)
                            // While editing, dragging any card moves it; otherwise it's an ordinary scroll.
                            mode == 0 && traveled > slop -> if (sel != null) begin(centroid) else break
                            mode == 2 && traveled > slop -> begin(centroid)
                        }
                        if (mode != 0 || sel != null) ev.changes.forEach { it.consume() }
                        if (mode == 1) {
                            finger = centroid
                            // The card under the finger lights up; letting go swaps the two. Swapping on release (not
                            // while passing over) means a long drag doesn't shuffle every card it crosses.
                            dropOn = bounds.entries.firstOrNull { (t, r) -> t != hit && r.contains(finger) }?.key
                        }
                    }
                    if (mode == 1) {
                        dropOn?.let { reorder(hit, it) }
                        dropOn = null; moving = null; selected = hit; save()
                    }
                }
            }
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            // While editing: one strip with how it works and Done (kept off the cards themselves).
            if (editing) Row(
                Modifier.fillMaxWidth().background(MonitorColors.pelvic.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .border(1.dp, MonitorColors.pelvic.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Drag a card to move it · drag a corner to resize", color = MonitorColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    "Done", color = MonitorColors.background, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(MonitorColors.pelvic, RoundedCornerShape(6.dp)).clickable { selected = null; save() }.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            // Cards glide to their new size and place (lookahead animation) instead of jumping when the board reflows.
            Box(Modifier.fillMaxWidth().weight(1f)) { run {
                // Rows share the screen by what their cards need. If that would make any row shorter than readable
                // (lots of cards, or one made big), every row keeps its proportion at a readable size and the board
                // scrolls instead of squashing cards.
                val needs = rows.map { r -> r.maxOf { FitLayout.rowNeed(it.type) * FitLayout.heightFactor(it) } }
                val boardDp = with(density) { boardHeightPx.toDp().value }
                val room = boardDp - GAP_DP * (rows.size - 1).coerceAtLeast(0)
                val stretch = FitLayout.scrollStretch(needs, room)
                var content by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                Column(
                    Modifier.fillMaxSize().onSizeChanged { boardHeightPx = it.height }
                        .then(if (stretch > 1f) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        // Inside the scroll: card positions measured from here don't change when the board scrolls.
                        .onPlaced { content = it },
                    verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)
                ) {
                    rows.forEachIndexed { i, row ->
                        val need = needs[i]
                        val rowModifier = if (stretch > 1f) Modifier.fillMaxWidth().height((room * need / needs.sum() * stretch).dp)
                            else Modifier.fillMaxWidth().weight(need)
                        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
                            row.forEach { slot ->
                                key(slot.type) {
                                    val type = slot.type
                                    val isMoving = moving == type
                                    // A selected card scrolls fully into view, so both corner handles can be reached
                                    // (in landscape a tall card's handles could sit below the screen).
                                    val inView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                                    LaunchedEffect(selected == type) { if (selected == type) runCatching { inView.bringIntoView() } }
                                    Box(
                                        Modifier
                                            .weight(FitLayout.span(slot, units).toFloat())
                                            .fillMaxHeight()
                                            // Measured before the glide: where the card is going, not mid-animation.
                                            .bringIntoViewRequester(inView)
                                            .onGloballyPositioned { bounds[type] = it.boundsInRoot() }
                                            .glide(type) { content }
                                    ) {
                                        if (isMoving) {
                                            // Where it will land; the card itself follows the finger above everything.
                                            Box(
                                                Modifier.fillMaxSize()
                                                    .background(MonitorColors.pelvic.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                                    .border(2.dp, MonitorColors.pelvic.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                            )
                                        } else {
                                            MonitorCard(type, Modifier.fillMaxSize())
                                            if (selected == type) FitEditOverlay(showHandles = true)
                                            if (dropOn == type) Box(
                                                Modifier.fillMaxSize()
                                                    .background(MonitorColors.pelvic.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                                                    .border(3.dp, MonitorColors.pelvic, RoundedCornerShape(10.dp))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } }
        }
        // The card being moved, drawn above every other card at the finger (it used to slide under the rows below).
        val m = moving
        val mb = m?.let { bounds[it] }
        if (m != null && mb != null) {
            Box(
                Modifier
                    .offset { IntOffset((finger.x - grab.x - origin.x).roundToInt(), (finger.y - grab.y - origin.y).roundToInt()) }
                    .size(with(density) { mb.width.toDp() }, with(density) { mb.height.toDp() })
                    .zIndex(10f)
                    .graphicsLayer { scaleX = 1.03f; scaleY = 1.03f; alpha = 0.94f; shadowElevation = 24f * density.density; shape = RoundedCornerShape(10.dp); clip = false }
            ) {
                MonitorCard(m, Modifier.fillMaxSize())
                FitEditOverlay(showHandles = false)
            }
        }
    }
}

/**
 * When the board reflows, a card slides from where it was to its new place instead of jumping. Drawing only (a
 * translation that eases back to zero): layout and touch use the real place. (The lookahead animation this replaces
 * crashed with the cards' own measuring.)
 */
@Composable
private fun Modifier.glide(key: Any, board: () -> androidx.compose.ui.layout.LayoutCoordinates?): Modifier {
    val shift = remember(key) { androidx.compose.animation.core.Animatable(Offset.Zero, Offset.VectorConverter) }
    val last = remember(key) { arrayOfNulls<Offset>(1) }
    val scope = rememberCoroutineScope()
    return this
        .onPlaced { c ->
            val b = board()?.takeIf { it.isAttached } ?: return@onPlaced
            if (!c.isAttached) return@onPlaced
            val now = b.localPositionOf(c, Offset.Zero)
            val before = last[0]
            last[0] = now
            if (before != null && (before - now).getDistance() > 1f) scope.launch {
                shift.snapTo(shift.value + (before - now))
                shift.animateTo(Offset.Zero, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            }
        }
        .graphicsLayer { translationX = shift.value.x; translationY = shift.value.y }
}

/** The selected card's outline and its two corner handles (the board's touch handler does the work). */
@Composable
private fun FitEditOverlay(showHandles: Boolean) {
    val c = MonitorColors.pelvic
    Box(Modifier.fillMaxSize().background(c.copy(alpha = 0.06f), RoundedCornerShape(10.dp)).border(2.dp, c, RoundedCornerShape(10.dp))) {
        if (showHandles) {
            CornerHandle(Modifier.align(Alignment.BottomStart), mirrored = true)
            CornerHandle(Modifier.align(Alignment.BottomEnd), mirrored = false)
        }
    }
}

/** A round corner handle with a diagonal double arrow, like a widget's resize handle. */
@Composable
private fun CornerHandle(modifier: Modifier, mirrored: Boolean) {
    val pad = with(LocalDensity.current) { ((CORNER_HANDLE_HIT_DP - CORNER_HANDLE_DP) / 2 + 4).dp.toPx() }
    Canvas(
        modifier.size(CORNER_HANDLE_DP.dp)
            // A handle at the screen's edge sits where Android's back swipe starts; claim it (and the touch area around
            // it) so dragging the handle resizes instead of closing the app.
            .systemGestureExclusion { c -> androidx.compose.ui.geometry.Rect(-pad, -pad, c.size.width + pad, c.size.height + pad) }
            .padding(3.dp)
    ) {
        val c = MonitorColors.pelvic
        val r = size.minDimension / 2f
        drawCircle(c, radius = r)
        drawCircle(Color.White.copy(alpha = 0.35f), radius = r, style = Stroke(width = 1.5f * density))
        // Diagonal double arrow toward the corner it grows (down-left or down-right).
        val k = r * 0.5f
        val s = if (mirrored) -1f else 1f
        val a = Offset(center.x - s * k, center.y - k); val b = Offset(center.x + s * k, center.y + k)
        val dark = MonitorColors.background
        val w = 2.4f * density
        drawLine(dark, a, b, strokeWidth = w, cap = StrokeCap.Round)
        val h = k * 0.75f
        drawLine(dark, b, Offset(b.x - s * h, b.y), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(dark, b, Offset(b.x, b.y - h), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(dark, a, Offset(a.x + s * h, a.y), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(dark, a, Offset(a.x, a.y + h), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** Size of the corner handles, and how far around them a touch still catches one. */
private const val CORNER_HANDLE_DP = 40
private const val CORNER_HANDLE_HIT_DP = 52

private fun Modifier.onGloballyPositionedCompat(block: (androidx.compose.ui.geometry.Rect) -> Unit): Modifier =
    this.onGloballyPositioned { block(it.boundsInRoot()) }

private fun Modifier.onSizeChangedCompat(block: (androidx.compose.ui.unit.IntSize) -> Unit): Modifier = this.onSizeChanged(block)

/** A card being moved or resized: [dx]/[dy] are how far the finger (or pointer) has gone, in board pixels. */
private data class DragState(val type: CardType, val dx: Float, val dy: Float, val resize: Boolean, val fromLeft: Boolean = false)

/**
 * The free grid (DeX, tablets, big windows). One touch handler on the board itself does all moving and resizing,
 * in the board's own coordinates. (Each card used to track the finger in its own coordinates while the card itself
 * moved, so its own movement cancelled the finger's: dragging stuttered and resizing barely moved with a finger.)
 * - Arranging: drag a card to move it; drag a bottom corner handle to resize (the left one grows to the left, the
 *   right one to the right, either one up/down for height), the same as on the phone.
 * - Not arranging: press and hold a card to start arranging and pick it up in one go.
 * - Touchpad: a two-finger swipe over a card moves it while arranging.
 */
@Composable
private fun GridDashboard(
    slots: List<CardSlot>,
    arranging: Boolean,
    onArrangingChange: (Boolean) -> Unit,
    onChange: (List<CardSlot>) -> Unit,
    width: Dp,
    height: Dp
) {
    val density = LocalDensity.current
    val gapPx = with(density) { GAP_DP.dp.toPx() }
    val colPx = (with(density) { width.toPx() } + gapPx) / GridLayoutEngine.COLUMNS
    // One screen holds SCREEN_ROWS rows. A taller layout (a card made big) scrolls instead of shrinking every row:
    // shrinking squashed the other cards until their readings disappeared.
    val rowPx = (with(density) { height.toPx() } + gapPx) / GridLayoutEngine.SCREEN_ROWS
    val scrollStepPx = with(density) { SCROLL_STEP_DP.dp.toPx() }
    val edgePx = with(density) { EDGE_GRIP_DP.dp.toPx() }
    var drag by remember { mutableStateOf<DragState?>(null) }
    val currentSlots by rememberUpdatedState(slots)
    val currentArranging by rememberUpdatedState(arranging)
    val colNow by rememberUpdatedState(colPx)
    val rowNow by rememberUpdatedState(rowPx)
    val scope = rememberCoroutineScope()
    var scrollCommit by remember { mutableStateOf<Job?>(null) }

    fun target(slot: CardSlot, d: DragState): CardSlot = if (d.resize) {
        val cols = (d.dx / colPx).roundToInt()
        if (d.fromLeft) {
            // Growing to the left: the left edge follows the finger, the right edge stays put.
            val grow = (-cols).coerceAtMost(slot.x).coerceAtLeast(slot.type.minW - slot.w)
            GridLayoutEngine.clampSlot(slot.copy(x = slot.x - grow, w = slot.w + grow, h = slot.h + (d.dy / rowPx).roundToInt()))
        } else {
            GridLayoutEngine.clampSlot(slot.copy(w = slot.w + cols, h = slot.h + (d.dy / rowPx).roundToInt()))
        }
    } else {
        GridLayoutEngine.clampSlot(slot.copy(x = slot.x + (d.dx / colPx).roundToInt(), y = slot.y + (d.dy / rowPx).roundToInt()))
    }

    fun commit() {
        val d = drag ?: return
        val s = currentSlots.first { it.type == d.type }
        onChange(GridLayoutEngine.place(currentSlots, target(s, d)))
        drag = null
    }

    // Live preview: everything laid out as if the card were dropped now. Others animate into these spots.
    val preview = drag?.let { d -> GridLayoutEngine.place(slots, target(slots.first { it.type == d.type }, d)) } ?: slots
    val bottomRows = maxOf(
        preview.filter { it.visible }.maxOfOrNull { it.bottom } ?: 0,
        slots.filter { it.visible }.maxOfOrNull { it.bottom } ?: 0,
        GridLayoutEngine.SCREEN_ROWS
    )
    fun px(slot: CardSlot) = IntOffset((colPx * slot.x).roundToInt(), (rowPx * slot.y).roundToInt())
    fun Float.toDpValue(): Dp = with(density) { this@toDpValue.toDp() }
    /** Where a card sits on the board right now (board pixels). */
    fun rectOf(s: CardSlot) = androidx.compose.ui.geometry.Rect(
        colNow * s.x, rowNow * s.y, colNow * (s.x + s.w) - gapPx, rowNow * (s.y + s.h) - gapPx
    )

    Box(Modifier.fillMaxSize().then(if (bottomRows > GridLayoutEngine.SCREEN_ROWS) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height((rowPx * bottomRows).toDpValue())
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    val longPress = viewConfiguration.longPressTimeoutMillis
                    val initial = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = initial)
                        val p0 = down.position
                        val slot = currentSlots.filter { it.visible }.lastOrNull { rectOf(it).contains(p0) } ?: return@awaitEachGesture
                        val r = rectOf(slot)
                        var state: DragState
                        if (currentArranging) {
                            down.consume()
                            val nearBottom = p0.y > r.bottom - edgePx
                            val leftCorner = nearBottom && p0.x < r.left + edgePx
                            val rightCorner = nearBottom && p0.x > r.right - edgePx
                            state = DragState(slot.type, 0f, 0f, resize = leftCorner || rightCorner, fromLeft = leftCorner)
                        } else {
                            // Not arranging: the card works normally unless it's held still for a long press.
                            val released = withTimeoutOrNull(longPress) {
                                while (true) {
                                    val ev = awaitPointerEvent(initial)
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                                    if (!c.pressed || (c.position - p0).getDistance() > slop) return@withTimeoutOrNull true
                                }
                                @Suppress("UNREACHABLE_CODE") true
                            }
                            if (released != null) return@awaitEachGesture
                            onArrangingChange(true)
                            state = DragState(slot.type, 0f, 0f, resize = false)
                        }
                        var started = false
                        while (true) {
                            val ev = awaitPointerEvent(initial)
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) { c.consume(); break }
                            c.consume()
                            val d = c.position - p0
                            if (!started && d.getDistance() < slop) continue
                            started = true
                            state = state.copy(dx = d.x, dy = d.y)
                            drag = state
                        }
                        if (started) commit() else drag = null
                    }
                }
        ) {
            // Translucent placeholder where the dragged card will land.
            drag?.let { d ->
                val landing = preview.first { it.type == d.type }
                Box(
                    Modifier
                        .offset { px(landing) }
                        .size((colPx * landing.w - gapPx).toDpValue(), (rowPx * landing.h - gapPx).toDpValue())
                        .background(MonitorColors.pelvic.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                        .border(2.dp, MonitorColors.pelvic.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                )
            }
            preview.filter { it.visible }.forEach { slot ->
                key(slot.type) {
                    val d = drag
                    val active = d?.type == slot.type
                    val original = slots.first { it.type == slot.type }
                    val animated by animateIntOffsetAsState(
                        targetValue = px(slot),
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                        label = "card-${slot.type}"
                    )
                    val base = if (active) original else slot
                    val minWpx = colPx * base.type.minW - gapPx
                    val leftGrowPx = if (active && d!!.resize && d.fromLeft) (-d.dx).coerceAtMost(colPx * base.x).coerceAtLeast(minWpx - (colPx * base.w - gapPx)) else 0f
                    val offset = if (active && d != null && !d.resize) {
                        IntOffset((px(original).x + d.dx).roundToInt(), (px(original).y + d.dy).roundToInt())
                    } else if (active) IntOffset((px(original).x - leftGrowPx).roundToInt(), px(original).y) else animated
                    val w = (colPx * base.w - gapPx + when {
                        active && d!!.resize && d.fromLeft -> leftGrowPx
                        active && d!!.resize -> d.dx
                        else -> 0f
                    }).coerceAtLeast(minWpx)
                    val h = (rowPx * base.h - gapPx + if (active && d!!.resize) d.dy else 0f).coerceAtLeast(rowPx * base.type.minH - gapPx)

                    Box(
                        Modifier
                            .zIndex(if (active) 3f else 1f)
                            .offset { offset }
                            .size(w.toDpValue(), h.toDpValue())
                            .graphicsLayer {
                                if (active) {
                                    scaleX = 1.02f; scaleY = 1.02f
                                    alpha = 0.88f
                                    shadowElevation = 24f * density.density
                                    shape = RoundedCornerShape(10.dp)
                                    clip = false
                                }
                            }
                            .pointerHoverIcon(PointerIcon(if (arranging) android.view.PointerIcon.TYPE_GRAB else android.view.PointerIcon.TYPE_ARROW))
                            // Two-finger touchpad swipe (scroll events) over a card moves it while arranging.
                            .pointerInput(slot.type) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type != PointerEventType.Scroll || !currentArranging) continue
                                        val change = event.changes.firstOrNull() ?: continue
                                        val s = change.scrollDelta
                                        if (s == Offset.Zero) continue
                                        change.consume()
                                        val current = drag?.takeIf { it.type == slot.type && !it.resize } ?: DragState(slot.type, 0f, 0f, false)
                                        drag = current.copy(dx = current.dx - s.x * scrollStepPx, dy = current.dy - s.y * scrollStepPx)
                                        // A swipe has no "release": drop the card once the fingers stop.
                                        scrollCommit?.cancel()
                                        scrollCommit = scope.launch { delay(350L); commit() }
                                    }
                                }
                            }
                    ) {
                        MonitorCard(slot.type, Modifier.fillMaxSize())
                        if (arranging) {
                            // Covers the card's own buttons so the whole card is a handle while arranging, and shows
                            // the two corner handles.
                            val c = MonitorColors.pelvic
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .background(if (active) Color.Transparent else Color(0x0FFFFFFF), RoundedCornerShape(10.dp))
                                    .border(if (active) 2.dp else 1.dp, c.copy(alpha = if (active) 0.9f else 0.45f), RoundedCornerShape(10.dp))
                            ) {
                                CornerHandle(Modifier.align(Alignment.BottomStart).pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW)), mirrored = true)
                                CornerHandle(Modifier.align(Alignment.BottomEnd).pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW)), mirrored = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How far in from a bottom corner a press resizes instead of moving (a fingertip's width). */
private const val EDGE_GRIP_DP = 44

@Composable
private fun ResizeGrip(modifier: Modifier) {
    Canvas(
        modifier
            .size(30.dp)
            .padding(4.dp)
            .background(MonitorColors.panel.copy(alpha = 0.85f), RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
    ) {
        val c = MonitorColors.pelvic
        val w = size.width
        val tri = Path().apply { moveTo(w, w * 0.15f); lineTo(w, w); lineTo(w * 0.15f, w); close() }
        drawPath(tri, c.copy(alpha = 0.35f))
        drawPath(tri, c, style = Stroke(width = 1.5f, pathEffect = PathEffect.cornerPathEffect(3f)))
        for (i in 1..2) {
            val o = w * i / 2.6f
            drawLine(c, Offset(w - o, w - 2f), Offset(w - 2f, w - o), strokeWidth = 1.6f)
        }
    }
}

@Composable
private fun kotlinx.coroutines.flow.StateFlow<com.pulsepointlabs.sarahmonitor.howl.HowlState>.collectAsStateCompat(): Boolean {
    val s by this.collectAsState()
    return s.connected
}

