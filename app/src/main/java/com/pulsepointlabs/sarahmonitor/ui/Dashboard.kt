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
import androidx.compose.foundation.gestures.drag as dragPointer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        if (maxWidth < 600.dp) {
            StackedDashboard(slots, columns = 1)
        } else if (maxHeight < 480.dp) {
            // Phone held sideways: too short for the screen-filling grid; two scrolling columns instead.
            StackedDashboard(slots, columns = 2)
        } else {
            Column(Modifier.fillMaxSize()) {
                if (arranging) ArrangeBanner { onArrangingChange(false) }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    GridDashboard(slots, arranging, onArrangingChange, onChange, maxWidth, maxHeight)
                }
            }
        }
    }
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
            "Arranging · drag a card (mouse, finger or two-finger swipe) to move it · drag ◢ to resize",
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

@Composable
private fun StackedDashboard(slots: List<CardSlot>, columns: Int) {
    val ordered = slots.filter { it.visible }.sortedWith(compareBy({ it.y }, { it.x }))
    fun height(type: CardType) = when (type) {
        CardType.Pelvic -> if (columns == 1) 420.dp else 300.dp
        CardType.Ecg -> 190.dp
        CardType.BloodPressure -> 250.dp
        CardType.Load -> 170.dp
        CardType.HeartRate -> 200.dp
        else -> 210.dp
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ordered.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val h = row.maxOf { height(it.type).value }.dp
                row.forEach { slot -> MonitorCard(slot.type, Modifier.weight(1f).height(h)) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        Box(Modifier.height(8.dp))
    }
}

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
    val rowPx = maxOf(with(density) { 28.dp.toPx() }, (with(density) { height.toPx() } + gapPx) / GridLayoutEngine.SCREEN_ROWS)
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

    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = drag == null)
    ) {
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
