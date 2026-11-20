package com.pulsepointlabs.sarahmonitor.session

import android.content.Context

/** Remembers the last event so it can be repeated with one tap (like SarahVS's "Repeat last"). */
object MarkerPreferences {
    private const val PREFS = "sarahmonitor_markers"

    data class Last(val types: Set<String>, val note: String) {
        val label: String get() = MarkerTypes.label(types) + if (note.isNotBlank()) ": $note" else ""
    }

    fun remember(context: Context, types: Set<String>, note: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("types", MarkerTypes.join(types)).putString("note", note.trim()).apply()
    }

    fun last(context: Context): Last? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val types = prefs.getString("types", null) ?: return null
        return Last(MarkerTypes.split(types), prefs.getString("note", "").orEmpty())
    }
}
