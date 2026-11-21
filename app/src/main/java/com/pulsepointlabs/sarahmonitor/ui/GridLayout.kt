package com.pulsepointlabs.sarahmonitor.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class CardType(val title: String, val minW: Int, val minH: Int) {
    HeartRate("Heart rate", 3, 3),
    Ecg("ECG", 5, 3),
    Hrv("HRV", 3, 3),
    Load("Load", 3, 2),
    HrTrend("HR trend", 5, 3),
    HrvTrend("HRV trend", 5, 3),
    Pelvic("Pelvic pressure", 4, 4),
    PelvicTrend("Pressure trend", 5, 3),
    BloodPressure("Blood pressure", 4, 3),
    Glucose("Glucose", 3, 3),
    SpO2("SpO2", 3, 3),
    Body("Body (scale)", 3, 3),
    Emg("EMG", 4, 3),
    Stress("Stress", 3, 2),
    // Response board
    Phase("Phase", 4, 3),
    NearClimax("Near-climax watch", 4, 3),
    Matrix("Threshold matrix", 4, 3),
    ResponseTimeline("Phase timeline", 6, 3),
    Cardiac("Cardiac timeline", 6, 3),
    Recovery("Recovery", 3, 2),
    Respiration("Respiration & motion", 3, 2),
    SarahLive("Sarah, live", 4, 3),
    QuickMarks("Quick marks", 4, 2)
}

data class CardSlot(val type: CardType, val x: Int, val y: Int, val w: Int, val h: Int, val visible: Boolean = true) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
    fun overlaps(o: CardSlot): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom
}

/**
 * Snap-to-grid layout: the card being moved or resized keeps its place, cards it lands on are pushed down,
 * then everything floats up to close gaps. Layout stays tidy with no overlaps however cards are dragged.
 */
object GridLayoutEngine {
    const val COLUMNS = 24
    /** Rows that fill one screen height on a wide display. */
    const val SCREEN_ROWS = 16

    val defaultLayout: List<CardSlot> = listOf(
        CardSlot(CardType.HeartRate, 0, 0, 5, 5),
        CardSlot(CardType.Hrv, 0, 5, 5, 5),
        CardSlot(CardType.Load, 0, 10, 5, 3),
        CardSlot(CardType.Stress, 0, 13, 5, 3),
        CardSlot(CardType.Ecg, 5, 0, 11, 5),
        CardSlot(CardType.HrTrend, 5, 5, 11, 4),
        CardSlot(CardType.HrvTrend, 5, 9, 11, 3),
        CardSlot(CardType.PelvicTrend, 5, 12, 11, 4),
        CardSlot(CardType.Pelvic, 16, 0, 8, 10),
        CardSlot(CardType.BloodPressure, 16, 10, 8, 6),
        CardSlot(CardType.Glucose, 0, 16, 5, 3, visible = false),
        CardSlot(CardType.SpO2, 6, 16, 5, 4, visible = false),
        CardSlot(CardType.Body, 5, 16, 5, 4, visible = false),
        CardSlot(CardType.Emg, 10, 16, 8, 5, visible = false)
    )

    /** The Response board: phase and near-climax up top, pelvic floor / HRV / EMG, timelines underneath. */
    val responseLayout: List<CardSlot> = listOf(
        CardSlot(CardType.Phase, 0, 0, 6, 4),
        CardSlot(CardType.NearClimax, 6, 0, 6, 4),
        CardSlot(CardType.Pelvic, 12, 0, 6, 8),
        CardSlot(CardType.SarahLive, 18, 0, 6, 4),
        CardSlot(CardType.Matrix, 0, 4, 6, 4),
        CardSlot(CardType.Hrv, 6, 4, 6, 4),
        CardSlot(CardType.QuickMarks, 18, 4, 6, 4),
        CardSlot(CardType.ResponseTimeline, 0, 8, 12, 4),
        CardSlot(CardType.Emg, 12, 8, 6, 4),
        CardSlot(CardType.Recovery, 18, 8, 3, 4),
        CardSlot(CardType.Respiration, 21, 8, 3, 4),
        CardSlot(CardType.Cardiac, 0, 12, 12, 4),
        CardSlot(CardType.HeartRate, 12, 12, 6, 4),
        CardSlot(CardType.Load, 18, 12, 6, 4),
        CardSlot(CardType.Ecg, 0, 16, 12, 4, visible = false),
        CardSlot(CardType.BloodPressure, 12, 16, 6, 4, visible = false),
        CardSlot(CardType.PelvicTrend, 0, 20, 12, 4, visible = false),
        CardSlot(CardType.HrTrend, 12, 20, 12, 4, visible = false)
    )

    fun clampSlot(slot: CardSlot): CardSlot {
        val w = slot.w.coerceIn(slot.type.minW, COLUMNS)
        val h = slot.h.coerceAtLeast(slot.type.minH)
        return slot.copy(x = slot.x.coerceIn(0, COLUMNS - w), y = slot.y.coerceAtLeast(0), w = w, h = h)
    }

    /** Places [changed] (already moved/resized) and rearranges the rest around it. */
    fun place(slots: List<CardSlot>, changed: CardSlot): List<CardSlot> {
        val fixed = clampSlot(changed)
        val others = slots.filter { it.type != fixed.type }
        val placed = mutableListOf(fixed)
        others.filter { !it.visible }.forEach { placed += it }
        // Push visible cards down in reading order until nothing overlaps.
        others.filter { it.visible }.sortedWith(compareBy({ it.y }, { it.x })).forEach { card ->
            var candidate = card
            while (placed.any { it.visible && it.overlaps(candidate) }) {
                val blocker = placed.filter { it.visible && it.overlaps(candidate) }.maxOf { it.bottom }
                candidate = candidate.copy(y = blocker)
            }
            placed += candidate
        }
        return compact(placed, keep = fixed.type)
    }

    /** Floats every visible card up as far as it can go without overlapping. [keep] stays put. */
    fun compact(slots: List<CardSlot>, keep: CardType? = null): List<CardSlot> {
        if (slots.isEmpty()) return slots
        val result = mutableListOf<CardSlot>()
        slots.filter { !it.visible }.forEach { result += it }
        val kept = slots.firstOrNull { it.type == keep && it.visible }
        if (kept != null) result += kept
        slots.filter { it.visible && it.type != keep }.sortedWith(compareBy({ it.y }, { it.x })).forEach { card ->
            var candidate = card
            while (candidate.y > 0 && result.none { it.visible && it.overlaps(candidate.copy(y = candidate.y - 1)) }) {
                candidate = candidate.copy(y = candidate.y - 1)
            }
            result += candidate
        }
        // Keep a stable order for persistence.
        return CardType.entries.mapNotNull { type -> result.firstOrNull { it.type == type } }
    }

    fun setVisible(slots: List<CardSlot>, type: CardType, visible: Boolean): List<CardSlot> {
        val current = slots.firstOrNull { it.type == type } ?: return slots
        if (!visible) return compact(slots.map { if (it.type == type) it.copy(visible = false) else it })
        // Re-show at the bottom so nothing else moves.
        val bottom = slots.filter { it.visible }.maxOfOrNull { it.bottom } ?: 0
        return compact(slots.map { if (it.type == type) clampSlot(it.copy(x = 0, y = bottom, visible = true)) else it })
    }

    fun toJson(slots: List<CardSlot>): String = JSONArray().apply {
        slots.forEach { put(JSONObject().put("type", it.type.name).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h).put("visible", it.visible)) }
    }.toString()

    fun fromJson(json: String?, defaults: List<CardSlot> = defaultLayout): List<CardSlot> {
        val allowed = defaults.map { it.type }.toSet()
        val parsed = runCatching {
            val array = JSONArray(json ?: return defaults)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val type = runCatching { CardType.valueOf(o.getString("type")) }.getOrNull()?.takeIf { it in allowed } ?: return@mapNotNull null
                clampSlot(CardSlot(type, o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"), o.optBoolean("visible", true)))
            }
        }.getOrNull() ?: return defaults
        // Cards added in a newer version start hidden (turn them on from Cards) so a saved layout doesn't shift.
        val missing = defaults.map { it.type }.filter { t -> parsed.none { it.type == t } }
        val bottom = parsed.filter { it.visible }.maxOfOrNull { it.bottom } ?: 0
        // (Stress was asked for on the main screen, so it arrives visible.)
        return compact(parsed + missing.mapIndexed { i, t -> defaults.first { it.type == t }.copy(x = 0, y = bottom + i * 4, visible = t == CardType.Stress && defaults.first { it.type == t }.visible) })
    }
}

class LayoutStore(context: Context, private val key: String = "grid_v1", val defaults: List<CardSlot> = GridLayoutEngine.defaultLayout) {
    private val prefs = context.getSharedPreferences("sarahmonitor_layout", Context.MODE_PRIVATE)

    fun load(): List<CardSlot> = GridLayoutEngine.fromJson(prefs.getString(key, null), defaults)
    fun save(slots: List<CardSlot>) = prefs.edit().putString(key, GridLayoutEngine.toJson(slots)).apply()
}
