package com.pulsepointlabs.sarahmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A page section with a rough height (in "rows"), so sections can be balanced across columns. */
class PageSection(val weight: Int, val content: @Composable () -> Unit)

/**
 * Lays page sections out in as many columns as the window fits (1 on a phone, up to [maxColumns] on DeX), in
 * reading order down each column, balanced so no column runs much longer than the others. Scrolls as one page.
 */
@Composable
fun AdaptiveColumns(sections: List<PageSection>, modifier: Modifier = Modifier, minColumn: Dp = 420.dp, maxColumns: Int = 3) {
    BoxWithConstraints(modifier) {
        val cols = (maxWidth / minColumn).toInt().coerceIn(1, maxColumns).coerceAtMost(sections.size.coerceAtLeast(1))
        if (cols == 1) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) { sections.forEach { it.content() } }
            }
        } else {
            val groups = partition(sections.map { it.weight }, cols)
            Row(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                groups.forEach { range ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        range.forEach { sections[it].content() }
                    }
                }
            }
        }
    }
}

/** Splits [weights] (in order) into [k] consecutive groups, minimizing the heaviest group. */
fun partition(weights: List<Int>, k: Int): List<IntRange> {
    val n = weights.size
    if (n == 0) return emptyList()
    val parts = k.coerceIn(1, n)
    val prefix = IntArray(n + 1).also { p -> weights.forEachIndexed { i, w -> p[i + 1] = p[i] + w } }
    fun sum(a: Int, b: Int) = prefix[b] - prefix[a]
    // best[j][i] = minimal max-group weight for the first i items in j groups
    val best = Array(parts + 1) { IntArray(n + 1) { Int.MAX_VALUE } }
    val cut = Array(parts + 1) { IntArray(n + 1) }
    best[0][0] = 0
    for (j in 1..parts) for (i in j..n) for (c in (j - 1) until i) {
        if (best[j - 1][c] == Int.MAX_VALUE) continue
        val v = maxOf(best[j - 1][c], sum(c, i))
        if (v < best[j][i]) { best[j][i] = v; cut[j][i] = c }
    }
    val ranges = ArrayDeque<IntRange>()
    var i = n
    for (j in parts downTo 1) { val c = cut[j][i]; ranges.addFirst(c until i); i = c }
    return ranges.toList()
}
