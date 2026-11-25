package com.pulsepointlabs.sarahmonitor.core

import java.util.Locale
import kotlin.math.abs

/**
 * Pelvic pressure as Ben reads it: mmHg, the US clinical unit (blood pressure, anorectal manometry). The CIVET
 * reports kPa and every stored sample, threshold and research export stays in kPa; this converts only what is shown
 * or spoken. One place, so the unit can change (cmH2O for urodynamics-style reading) without touching the screens.
 */
object Pressure {
    enum class Unit(val label: String, val spoken: String, val perKpa: Double) {
        MmHg("mmHg", "millimeters of mercury", 7.500617),
        CmH2O("cmH₂O", "centimeters of water", 10.19716),
        KPa("kPa", "kilopascals", 1.0)
    }

    @Volatile var unit: Unit = Unit.MmHg

    val label: String get() = unit.label

    /** kPa → the display unit. */
    fun value(kpa: Double): Double = kpa * unit.perKpa

    /** "17" / "3.8": whole numbers from 10 up, one decimal below (small pelvic-floor changes still show). */
    fun fmt(kpa: Double?): String {
        if (kpa == null || !kpa.isFinite()) return "--"
        val v = value(kpa)
        return if (unit == Unit.KPa) String.format(Locale.US, "%.1f", v)
        else if (abs(v) >= 10) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
    }

    /** With the unit: "17 mmHg". */
    fun withUnit(kpa: Double?): String = "${fmt(kpa)} $label"

    /** Stored text (sensor events, exports): the display unit, with the native kPa kept alongside for the record. */
    fun record(kpa: Double): String = if (unit == Unit.KPa) String.format(Locale.US, "%.2f kPa", kpa)
        else "${fmt(kpa)} $label (${String.format(Locale.US, "%.2f", kpa)} kPa)"

    /** For speech: "17 millimeters of mercury". */
    fun spoken(kpa: Double): String = "${fmt(kpa)} ${unit.spoken}"
}
