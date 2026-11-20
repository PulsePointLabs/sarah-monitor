package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

private const val GAP_DP = 6f

/**
 * Free-form monitor board. On wide screens (DeX, tablets) cards sit on a 24-column grid that fills the screen:
 * drag a card anywhere to move it, drag the corner grip to resize it. Others make room and gaps close up.
 * On a phone the same cards stack in one column in layout order.
 */
@Composable
fun Dashboard(
    slots: List<CardSlot>,
    locked: Boolean,
    onChange: (List<CardSlot>) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        if (maxWidth < 600.dp) {
            StackedDashboard(slots)
        } else {
            GridDashboard(slots, locked, onChange, maxWidth, maxHeight)
        }
    }
}

@Composable
private fun StackedDashboard(slots: List<CardSlot>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        slots.filter { it.visible }.sortedWith(compareBy({ it.y }, { it.x })).forEach { slot ->
            val height = when (slot.type) {
                CardType.Pelvic -> 420.dp
                CardType.Ecg -> 200.dp
                CardType.BloodPressure -> 260.dp
                CardType.Load -> 150.dp
                else -> 210.dp
            }
            MonitorCard(slot.type, Modifier.fillMaxWidth().height(height))
        }
    }
}

private data class DragState(val type: CardType, val dx: Float, val dy: Float, val resize: Boolean)

@Composable
private fun GridDashboard(
    slots: List<CardSlot>,
    locked: Boolean,
    onChange: (List<CardSlot>) -> Unit,
    width: Dp,
    height: Dp
) {
    val density = LocalDensity.current
    val gap = GAP_DP.dp
    val colW = (width + gap) / GridLayoutEngine.COLUMNS
    val rowH = maxOf(28.dp, (height + gap) / GridLayoutEngine.SCREEN_ROWS)
    val colPx = with(density) { colW.toPx() }
    val rowPx = with(density) { rowH.toPx() }
    var drag by remember { mutableStateOf<DragState?>(null) }
    val currentSlots by rememberUpdatedState(slots)
    val visible = slots.filter { it.visible }
    val bottomRows = visible.maxOfOrNull { it.bottom } ?: GridLayoutEngine.SCREEN_ROWS

    fun target(slot: CardSlot, d: DragState): CardSlot = if (d.resize) {
        GridLayoutEngine.clampSlot(slot.copy(w = slot.w + (d.dx / colPx).roundToInt(), h = slot.h + (d.dy / rowPx).roundToInt()))
    } else {
        GridLayoutEngine.clampSlot(slot.copy(x = slot.x + (d.dx / colPx).roundToInt(), y = slot.y + (d.dy / rowPx).roundToInt()))
    }

    // Live preview: lay everything out as if the drag ended now.
    val preview = drag?.let { d ->
        val slot = slots.first { it.type == d.type }
        GridLayoutEngine.place(slots, target(slot, d))
    } ?: slots
    val previewBottom = preview.filter { it.visible }.maxOfOrNull { it.bottom } ?: bottomRows

    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Box(Modifier.fillMaxWidth().height(maxOf(height, rowH * maxOf(previewBottom, bottomRows)))) {
            preview.filter { it.visible }.forEach { slot -> key(slot.type) {
                val active = drag?.type == slot.type
                val original = slots.first { it.type == slot.type }
                // The dragged card follows the pointer; the rest animate into their preview spots.
                val shown = if (active) original else slot
                val d = drag
                val extraX = if (active && d != null && !d.resize) d.dx else 0f
                val extraY = if (active && d != null && !d.resize) d.dy else 0f
                val extraW = if (active && d != null && d.resize) d.dx else 0f
                val extraH = if (active && d != null && d.resize) d.dy else 0f
                val cardW = with(density) { (colPx * shown.w - gap.toPx() + extraW).coerceAtLeast(colPx * shown.type.minW - gap.toPx()).toDp() }
                val cardH = with(density) { (rowPx * shown.h - gap.toPx() + extraH).coerceAtLeast(rowPx * shown.type.minH - gap.toPx()).toDp() }
                if (active) {
                    // Drop target outline.
                    Box(
                        Modifier
                            .offset { IntOffset((colPx * slot.x).roundToInt(), (rowPx * slot.y).roundToInt()) }
                            .size(colW * slot.w - gap, rowH * slot.h - gap)
                            .border(2.dp, MonitorColors.label.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    )
                }
                Box(
                    Modifier
                        .zIndex(if (active) 2f else 0f)
                        .offset { IntOffset((colPx * shown.x + extraX).roundToInt(), (rowPx * shown.y + extraY).roundToInt()) }
                        .size(cardW, cardH)
                        .alpha(if (active) 0.92f else 1f)
                        .then(
                            if (locked) Modifier else Modifier
                                .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_GRAB))
                                .pointerInput(slot.type) {
                                    detectDragGestures(
                                        onDragStart = { drag = DragState(slot.type, 0f, 0f, resize = false) },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            drag = drag?.let { it.copy(dx = it.dx + amount.x, dy = it.dy + amount.y) }
                                        },
                                        onDragEnd = {
                                            drag?.let { d2 ->
                                                val s = currentSlots.first { it.type == d2.type }
                                                onChange(GridLayoutEngine.place(currentSlots, target(s, d2)))
                                            }
                                            drag = null
                                        },
                                        onDragCancel = { drag = null }
                                    )
                                }
                        )
                ) {
                    MonitorCard(slot.type, Modifier.fillMaxSize())
                    if (!locked) {
                        ResizeGrip(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW))
                                .pointerInput(slot.type) {
                                    detectDragGestures(
                                        onDragStart = { drag = DragState(slot.type, 0f, 0f, resize = true) },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            drag = drag?.let { it.copy(dx = it.dx + amount.x, dy = it.dy + amount.y) }
                                        },
                                        onDragEnd = {
                                            drag?.let { d2 ->
                                                val s = currentSlots.first { it.type == d2.type }
                                                onChange(GridLayoutEngine.place(currentSlots, target(s, d2)))
                                            }
                                            drag = null
                                        },
                                        onDragCancel = { drag = null }
                                    )
                                }
                        )
                    }
                }
            } }
        }
    }
}

@Composable
private fun ResizeGrip(modifier: Modifier) {
    Canvas(modifier.size(22.dp).padding(5.dp)) {
        val c = MonitorColors.label.copy(alpha = 0.7f)
        val w = size.width
        for (i in 1..3) {
            val o = w * i / 3.2f
            drawLine(c, Offset(w - o, w), Offset(w, w - o), strokeWidth = 1.6f)
        }
    }
}
