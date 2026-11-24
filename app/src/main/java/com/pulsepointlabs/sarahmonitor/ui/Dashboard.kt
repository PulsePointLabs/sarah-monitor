package com.pulsepointlabs.sarahmonitor.ui

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
 * is never empty space. Editing on the phone:
 * - hold one finger on a card: it's selected, with grips on the right edge (width), bottom edge (height) and corner;
 * - two fingers on any card (or one finger on the selected card): pick it up and move it; the others slide aside;
 * - tap another card, the strip's Done, or empty space: finished. Every change reflows the board live and is saved.
 * One touch handler for the whole board (cards move between rows mid-drag, so per-card handlers would be torn down).
 */
@Composable
private fun FitDashboard(slots: List<CardSlot>, widthDp: Float, heightDp: Float, onChange: (List<CardSlot>) -> Unit) {
    var working by remember(slots) { mutableStateOf(slots) }
    var selected by remember { mutableStateOf<CardType?>(null) }
    var moving by remember { mutableStateOf<CardType?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<CardType, androidx.compose.ui.geometry.Rect>() }
    val current by rememberUpdatedState(working)
    val visible = working.filter { it.visible }.sortedWith(compareBy({ it.y }, { it.x }))
    // Units freeze while editing so the grid doesn't change under your finger.
    var frozenUnits by remember { mutableStateOf<Int?>(null) }
    val units = frozenUnits ?: FitLayout.bestUnitsFor(visible, widthDp, heightDp)
    val unitsNow by rememberUpdatedState(units)
    val rows = FitLayout.slotRows(visible, units)
    val density = LocalDensity.current
    val gripPx = with(density) { 34.dp.toPx() }
    val gapPx = with(density) { GAP_DP.dp.toPx() }
    val editing = selected != null || moving != null
    if (!editing && frozenUnits != null) frozenUnits = null

    fun reorder(type: CardType, before: CardType) {
        val order = current.filter { it.visible }.sortedWith(compareBy({ it.y }, { it.x })).map { it.type }.toMutableList()
        val from = order.indexOf(type); val to = order.indexOf(before)
        if (from < 0 || to < 0 || from == to) return
        order.removeAt(from); order.add(to, type)
        working = current.map { s -> order.indexOf(s.type).takeIf { it >= 0 }?.let { s.copy(x = 0, y = it) } ?: s.copy(y = order.size + s.y) }
    }
    fun update(type: CardType, change: (CardSlot) -> CardSlot) { working = current.map { if (it.type == type) change(it) else it } }
    fun save() { onChange(current) }
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
                    // 1) Grips on the selected card: resize.
                    if (sel != null && selBounds != null && selBounds.contains(p0)) {
                        val onRight = p0.x > selBounds.right - gripPx
                        val onBottom = p0.y > selBounds.bottom - gripPx
                        if (onRight || onBottom) {
                            down.consume()
                            val slot0 = current.first { it.type == sel }
                            val span0 = FitLayout.span(slot0, unitsNow)
                            val f0 = FitLayout.heightFactor(slot0)
                            val w0 = selBounds.width; val h0 = selBounds.height
                            val unitW = (w0 + gapPx) / span0
                            while (true) {
                                val ev = awaitPointerEvent(initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                val d = ch.position - down.position
                                if (onRight) {
                                    val span = ((w0 + d.x + gapPx) / unitW).roundToInt().coerceIn(1, unitsNow)
                                    if (span != FitLayout.span(current.first { it.type == sel }, unitsNow)) update(sel) { it.copy(fitSpan = span) }
                                }
                                if (onBottom && h0 > 0f) {
                                    val q = (Math.round(((h0 + d.y) / h0 * f0).coerceIn(0.5f, 3f) * 4) / 4f * 100).roundToInt()
                                    if (q != (FitLayout.heightFactor(current.first { it.type == sel }) * 100).roundToInt()) update(sel) { it.copy(fitHeight = q) }
                                }
                            }
                            save()
                            return@awaitEachGesture
                        }
                    }
                    if (hit == null) {
                        // Empty space (or the strip): a tap ends editing; the strip's Done handles itself.
                        return@awaitEachGesture
                    }
                    // While a card is selected, it's a handle: its own buttons don't react.
                    if (hit == sel) down.consume()
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
                            if (mode == 0 && sel != null && hit != sel) { selected = null; save() }
                            if (mode == 2 || hit == sel) ev.changes.forEach { it.consume() }
                            break
                        }
                        val centroid = Offset(pressed.map { it.position.x }.average().toFloat(), pressed.map { it.position.y }.average().toFloat()) + origin
                        val travelled = ((pressed.firstOrNull { it.id == down.id }?.position ?: pressed.first().position) - down.position).getDistance()
                        when {
                            mode == 0 && pressed.size >= 2 -> begin(centroid)
                            mode == 0 && travelled > slop -> if (hit == sel) begin(centroid) else break // ordinary scroll/drag on a card
                            mode == 2 && travelled > slop -> begin(centroid)
                        }
                        if (mode != 0 || hit == sel) ev.changes.forEach { it.consume() }
                        if (mode == 1) {
                            finger = centroid
                            // Crossing into another card takes its place; the rest slide aside.
                            bounds.entries.firstOrNull { (t, r) -> t != hit && r.contains(finger) }?.let { (t, _) -> reorder(hit, t) }
                        }
                    }
                    if (mode == 1) { moving = null; selected = hit; save() }
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
                Text("Drag to move · pull the edges to resize", color = MonitorColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    "Done", color = MonitorColors.background, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(MonitorColors.pelvic, RoundedCornerShape(6.dp)).clickable { selected = null; save() }.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            rows.forEach { row ->
                // Rows get height by what their cards need, times the height the user gave them.
                val need = row.maxOf { FitLayout.rowNeed(it.type) * FitLayout.heightFactor(it) }
                // Keyed on its height: a changed weight alone doesn't re-measure the column, so the row is rebuilt.
                key(need) { Row(Modifier.fillMaxWidth().weight(need), horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
                    row.forEach { slot ->
                        key(slot.type) {
                            val type = slot.type
                            val isMoving = moving == type
                            Box(Modifier.weight(FitLayout.span(slot, units).toFloat()).fillMaxHeight().onGloballyPositioned { bounds[type] = it.boundsInRoot() }) {
                                val b = bounds[type]
                                Box(
                                    Modifier.fillMaxSize().zIndex(if (isMoving) 3f else 1f).graphicsLayer {
                                        if (isMoving && b != null) {
                                            translationX = finger.x - grab.x - b.left
                                            translationY = finger.y - grab.y - b.top
                                            scaleX = 1.03f; scaleY = 1.03f; alpha = 0.92f
                                            shadowElevation = 24f * density.density
                                        }
                                    }
                                ) {
                                    MonitorCard(type, Modifier.fillMaxSize())
                                    if (selected == type || isMoving) FitEditOverlay()
                                }
                            }
                        }
                    }
                } }
            }
        }
    }
}

/** The selected card's outline and grips (the board's touch handler does the work). */
@Composable
private fun FitEditOverlay() {
    val c = MonitorColors.pelvic
    Box(Modifier.fillMaxSize().background(c.copy(alpha = 0.06f), RoundedCornerShape(10.dp)).border(2.dp, c, RoundedCornerShape(10.dp))) {
        Box(Modifier.align(Alignment.CenterEnd).size(28.dp, 64.dp).padding(3.dp).background(c, RoundedCornerShape(6.dp)))
        Box(Modifier.align(Alignment.BottomCenter).size(64.dp, 28.dp).padding(3.dp).background(c, RoundedCornerShape(6.dp)))
        Box(Modifier.align(Alignment.BottomEnd)) { ResizeGrip(Modifier) }
    }
}
private fun Modifier.onGloballyPositionedCompat(block: (androidx.compose.ui.geometry.Rect) -> Unit): Modifier =
    this.onGloballyPositioned { block(it.boundsInRoot()) }

private fun Modifier.onSizeChangedCompat(block: (androidx.compose.ui.unit.IntSize) -> Unit): Modifier = this.onSizeChanged(block)

private data class DragState(val type: CardType, val dx: Float, val dy: Float, val resize: Boolean)

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
    // Rows stretch or shrink so the whole layout always fits the screen (no scrolling).
    val committedRows = maxOf(GridLayoutEngine.SCREEN_ROWS, slots.filter { it.visible }.maxOfOrNull { it.bottom } ?: 0)
    val rowPx = (with(density) { height.toPx() } + gapPx) / committedRows
    val scrollStepPx = with(density) { SCROLL_STEP_DP.dp.toPx() }
    var drag by remember { mutableStateOf<DragState?>(null) }
    val currentSlots by rememberUpdatedState(slots)
    val currentArranging by rememberUpdatedState(arranging)
    val scope = rememberCoroutineScope()
    var scrollCommit by remember { mutableStateOf<Job?>(null) }

    fun target(slot: CardSlot, d: DragState): CardSlot = if (d.resize) {
        GridLayoutEngine.clampSlot(slot.copy(w = slot.w + (d.dx / colPx).roundToInt(), h = slot.h + (d.dy / rowPx).roundToInt()))
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

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height((rowPx * bottomRows).toDpValue())) {
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
                    val offset = if (active && d != null && !d.resize) {
                        IntOffset((px(original).x + d.dx).roundToInt(), (px(original).y + d.dy).roundToInt())
                    } else if (active) px(original) else animated
                    val base = if (active) original else slot
                    val w = (colPx * base.w - gapPx + if (active && d!!.resize) d.dx else 0f).coerceAtLeast(colPx * base.type.minW - gapPx)
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
                            // Pick up: immediately while arranging; press-and-hold otherwise (which turns arranging on).
                            .pointerInput(slot.type) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val started = if (currentArranging) {
                                        awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                    } else {
                                        awaitLongPressOrCancellation(down.id)?.also { onArrangingChange(true) }
                                    } ?: return@awaitEachGesture
                                    drag = DragState(slot.type, 0f, 0f, resize = false)
                                    val ok = dragPointer(started.id) { change ->
                                        val delta = change.position - change.previousPosition
                                        drag = drag?.let { it.copy(dx = it.dx + delta.x, dy = it.dy + delta.y) }
                                        change.consume()
                                    }
                                    if (ok) commit() else drag = null
                                }
                            }
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
                            // Covers the card's own buttons so the whole card is a handle while arranging.
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .background(if (active) Color.Transparent else Color(0x0FFFFFFF), RoundedCornerShape(10.dp))
                                    .border(1.dp, MonitorColors.pelvic.copy(alpha = if (active) 0.9f else 0.35f), RoundedCornerShape(10.dp))
                                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                            )
                            ResizeGrip(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW))
                                    .pointerInput(slot.type) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            down.consume()
                                            drag = DragState(slot.type, 0f, 0f, resize = true)
                                            val ok = dragPointer(down.id) { change ->
                                                val delta = change.position - change.previousPosition
                                                drag = drag?.let { it.copy(dx = it.dx + delta.x, dy = it.dy + delta.y) }
                                                change.consume()
                                            }
                                            if (ok) commit() else drag = null
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

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

