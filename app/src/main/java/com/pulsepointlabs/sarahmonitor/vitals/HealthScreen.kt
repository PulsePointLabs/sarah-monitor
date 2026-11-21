package com.pulsepointlabs.sarahmonitor.vitals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.ui.ChoiceChip
import com.pulsepointlabs.sarahmonitor.ui.ChoiceRow
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import com.pulsepointlabs.sarahmonitor.ui.RefLine
import com.pulsepointlabs.sarahmonitor.ui.Series
import com.pulsepointlabs.sarahmonitor.ui.TrendChart

val SpO2Color = Color(0xFF60A5FA)
val BodyColor = Color(0xFFA3E635)

private enum class HealthPage(val label: String) { Body("Body"), SpO2("SpO2"), Sleep("Sleep") }

@Composable
private fun Panel(title: String, color: Color, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(MonitorColors.panel, RoundedCornerShape(12.dp)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/** Health: body composition (scale), SpO2 and sleep, each with trends. */
@Composable
fun HealthScreen(onCsvImport: () -> Unit, onHealthConnect: () -> Unit, modifier: Modifier = Modifier, sleep: @Composable (Modifier) -> Unit = {}) {
    var page by remember { mutableStateOf(HealthPage.Body) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceRow { HealthPage.entries.forEach { p -> ChoiceChip(p.label, p == page, BodyColor) { page = p } } }
        when (page) {
            HealthPage.Body -> BodyPage(onHealthConnect, Modifier.fillMaxSize())
            HealthPage.SpO2 -> SpO2Page(onCsvImport, onHealthConnect, Modifier.fillMaxSize())
            HealthPage.Sleep -> sleep(Modifier.fillMaxSize())
        }
    }
}

private enum class Range(val label: String, val days: Int) { M1("30 d", 30), M3("90 d", 90), Y1("1 yr", 365), All("All", 100_000) }

private data class BodyMetric(val label: String, val unit: String, val decimals: Int, val value: (BodyEntry) -> Double?, val goodDown: Boolean? = null)

private fun bodyMetrics(): List<BodyMetric> = listOf(
    BodyMetric("Weight", BodyProfile.massUnit, 1, { BodyProfile.massValue(it.weightKg) }, true),
    BodyMetric("Body fat", "%", 1, { it.bodyFatPct }, true),
    BodyMetric("Muscle mass", BodyProfile.massUnit, 1, { it.muscleMassKg?.let(BodyProfile::massValue) }, false),
    BodyMetric("Skeletal muscle", "%", 1, { it.skeletalMusclePct }, false),
    BodyMetric("Lean mass", BodyProfile.massUnit, 1, { it.leanMassKg?.let(BodyProfile::massValue) }, false),
    BodyMetric("Body water", "%", 1, { it.bodyWaterPct }),
    BodyMetric("BMI", "", 1, { it.bmi }, true),
    BodyMetric("Visceral fat", "", 0, { it.visceralFat?.toDouble() }, true),
    BodyMetric("Subcutaneous fat", "%", 1, { it.subcutaneousFatPct }, true),
    BodyMetric("Bone mass", BodyProfile.massUnit, 1, { it.boneMassKg?.let(BodyProfile::massValue) }),
    BodyMetric("Protein", "%", 1, { it.proteinPct }, false),
    BodyMetric("BMR", "kcal", 0, { it.bmrKcal?.toDouble() }),
    BodyMetric("Metabolic age", "yr", 0, { it.metabolicAge?.toDouble() }, true)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BodyPage(onHealthConnect: () -> Unit, modifier: Modifier) {
    val all by MonitorHub.body.items.collectAsState()
    val lb by BodyProfile.pounds.collectAsState()
    val status by MonitorHub.scale.status.collectAsState()
    val busy by MonitorHub.scale.busy.collectAsState()
    var range by remember { mutableStateOf(Range.M3) }
    var selected by remember { mutableStateOf("Weight") }
    val now = System.currentTimeMillis()
    val items = all.filter { now - it.timestampMs <= range.days * 86_400_000L }
    val latest = all.lastOrNull()
    val metrics = remember(lb) { bodyMetrics() }
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 1000.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Panel("Weigh in", BodyColor, Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(latest?.let { BodyProfile.weightText(it.weightKg) } ?: "--", color = MonitorColors.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Column {
                            fun delta(days: Int): String? {
                                val ref = all.lastOrNull { now - it.timestampMs >= days * 86_400_000L } ?: return null
                                val d = BodyProfile.massValue(latest!!.weightKg) - BodyProfile.massValue(ref.weightKg)
                                return "%+.1f %s vs %d d".format(d, BodyProfile.massUnit, days)
                            }
                            if (latest != null) {
                                delta(7)?.let { Text(it, color = MonitorColors.label, fontSize = 13.sp) }
                                delta(30)?.let { Text(it, color = MonitorColors.label, fontSize = 13.sp) }
                                Text(latest.source, color = MonitorColors.dim, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                    Text(status, color = if (busy) MonitorColors.warn else MonitorColors.label, fontSize = 13.sp)
                    ChoiceRow {
                        ChoiceChip(if (busy) "Cancel" else "⚖ Read ESF-551 scale", busy, BodyColor) { if (busy) MonitorHub.scale.cancel() else MonitorHub.scale.measure() }
                        ChoiceChip("Health Connect (VeSync)", false, BodyColor) { onHealthConnect() }
                    }
                }
                if (wide) Panel("Composition", BodyColor, Modifier.weight(1f)) { Composition(latest) }
            }
            if (!wide) Panel("Composition", BodyColor, Modifier.fillMaxWidth()) { Composition(latest) }
            Panel("Trend · $selected", BodyColor, Modifier.fillMaxWidth(), trailing = {
                ChoiceRow { Range.entries.forEach { r -> ChoiceChip(r.label, r == range, BodyColor) { range = r } } }
            }) {
                val m = metrics.first { it.label == selected }
                val pts = items.mapNotNull { e -> m.value(e)?.let { e.timestampMs to it } }
                if (pts.size < 2) Text("Not enough ${m.label.lowercase()} readings in this range yet.", color = MonitorColors.label, fontSize = 13.sp)
                else TrendChart(
                    listOf(Series(m.label, BodyColor, pts.map { it.first to it.second as Double? })),
                    pts.first().first, maxOf(pts.last().first, pts.first().first + 86_400_000L),
                    Modifier.fillMaxWidth().height(if (wide) 260.dp else 200.dp), minSpan = 1.0, gapMs = Long.MAX_VALUE, decimals = m.decimals, dots = true
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = if (wide) 5 else 2) {
                metrics.forEach { m ->
                    val series = items.mapNotNull { e -> m.value(e)?.let { e.timestampMs to it } }
                    MetricTile(m, series, m.label == selected, Modifier.weight(1f)) { selected = m.label }
                }
            }
            ProfilePanel()
        }
    }
}

@Composable
private fun MetricTile(m: BodyMetric, series: List<Pair<Long, Double>>, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val last = series.lastOrNull()?.second
    val first = series.firstOrNull()?.second
    val change = if (last != null && first != null && series.size >= 2) last - first else null
    val tone = when {
        change == null || m.goodDown == null || kotlin.math.abs(change) < 1e-6 -> MonitorColors.label
        (change < 0) == m.goodDown -> MonitorColors.good
        else -> MonitorColors.warn
    }
    Column(
        modifier
            .background(if (selected) BodyColor.copy(alpha = 0.12f) else MonitorColors.panel, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) BodyColor else MonitorColors.panelBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Text(m.label, color = MonitorColors.label, fontSize = 12.sp, maxLines = 1)
        Text(last?.let { "%.${m.decimals}f".format(it) + if (m.unit.isNotEmpty()) " ${m.unit}" else "" } ?: "--", color = MonitorColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(change?.let { "%+.${m.decimals}f in range".format(it) } ?: " ", color = tone, fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            if (series.size < 2) return@Canvas
            val lo = series.minOf { it.second }; val hi = series.maxOf { it.second }
            val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
            val t0 = series.first().first; val t1 = series.last().first.coerceAtLeast(t0 + 1)
            val p = Path()
            series.forEachIndexed { i, (t, v) ->
                val x = (t - t0).toFloat() / (t1 - t0) * size.width
                val y = size.height - ((v - lo) / span).toFloat() * size.height
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, BodyColor, style = Stroke(1.8f * density, cap = StrokeCap.Round))
        }
    }
}

/** Where the weight is: fat, muscle, bone and the rest, as one bar plus labelled values. */
@Composable
private fun Composition(e: BodyEntry?) {
    if (e?.bodyFatPct == null) {
        Text("A full ESF-551 reading (barefoot, profile filled in) or a scale app writing body fat to Health Connect fills this in.", color = MonitorColors.label, fontSize = 13.sp)
        return
    }
    val fatKg = e.weightKg * e.bodyFatPct / 100
    val muscle = e.muscleMassKg ?: ((e.leanMassKg ?: (e.weightKg - fatKg)) - (e.boneMassKg ?: 0.0))
    val bone = e.boneMassKg ?: 0.0
    val other = (e.weightKg - fatKg - muscle - bone).coerceAtLeast(0.0)
    val parts = listOf(
        Triple("Fat", fatKg, Color(0xFFFB923C)), Triple("Muscle", muscle, Color(0xFFF472B6)),
        Triple("Bone", bone, Color(0xFFE5E7EB)), Triple("Other", other, MonitorColors.dim)
    ).filter { it.second > 0.01 }
    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
        var x = 0f
        parts.forEach { (_, kg, c) ->
            val w = (kg / e.weightKg).toFloat() * size.width
            drawRoundRect(c, Offset(x, 0f), Size((w - 2f).coerceAtLeast(1f), size.height), CornerRadius(6f))
            x += w
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        parts.forEach { (l, kg, c) ->
            Column {
                Text(l, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("%.1f %s".format(BodyProfile.massValue(kg), BodyProfile.massUnit), color = MonitorColors.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Text("${(kg / e.weightKg * 100).toInt()}%", color = MonitorColors.label, fontSize = 11.sp)
            }
        }
    }
    Text(
        listOfNotNull(e.bodyWaterPct?.let { "water %.1f%%".format(it) }, e.visceralFat?.let { "visceral $it" }, e.bmrKcal?.let { "BMR $it kcal" }, e.metabolicAge?.let { "metabolic age $it" }).joinToString(" · "),
        color = MonitorColors.label, fontSize = 12.sp
    )
    Text("Bioimpedance estimates — trends are more meaningful than any single number.", color = MonitorColors.dim, fontSize = 11.sp)
}

@Composable
private fun ProfilePanel() {
    val height by BodyProfile.heightCm.collectAsState()
    val birth by BodyProfile.birthYear.collectAsState()
    val male by BodyProfile.male.collectAsState()
    val lb by BodyProfile.pounds.collectAsState()
    var heightText by remember(height, lb) { mutableStateOf(height?.let { if (lb) "%.1f".format(it / 2.54) else "%.0f".format(it) } ?: "") }
    var birthText by remember(birth) { mutableStateOf(birth?.toString() ?: "") }
    Panel("Body profile (for composition)", BodyColor, Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(heightText, { heightText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text(if (lb) "Height (in)" else "Height (cm)") }, singleLine = true, modifier = Modifier.width(140.dp))
            OutlinedTextField(birthText, { birthText = it.filter(Char::isDigit).take(4) }, label = { Text("Birth year") }, singleLine = true, modifier = Modifier.width(120.dp))
            TextButton(onClick = {
                val h = heightText.toDoubleOrNull()?.let { if (lb) it * 2.54 else it }?.takeIf { it in 100.0..250.0 }
                BodyProfile.save(h, birthText.toIntOrNull()?.takeIf { it in 1920..2015 }, male, lb)
            }) { Text("Save") }
        }
        ChoiceRow {
            ChoiceChip("Male", male, BodyColor) { BodyProfile.save(height, birth, true, lb) }
            ChoiceChip("Female", !male, BodyColor) { BodyProfile.save(height, birth, false, lb) }
            ChoiceChip("lb / in", lb, BodyColor) { BodyProfile.save(height, birth, male, true) }
            ChoiceChip("kg / cm", !lb, BodyColor) { BodyProfile.save(height, birth, male, false) }
        }
    }
}

// ---------------------------------------------------------------- SpO2

@Composable
private fun SpO2Page(onCsvImport: () -> Unit, onHealthConnect: () -> Unit, modifier: Modifier) {
    val all by MonitorHub.spo2.items.collectAsState()
    var range by remember { mutableStateOf(1) }
    val now = System.currentTimeMillis()
    val items = all.filter { now - it.timestampMs <= range * 86_400_000L }
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 1000.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Panel("SpO2", SpO2Color, Modifier.weight(1f)) {
                    val last = all.lastOrNull()
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(last?.let { "${it.spo2}%" } ?: "--", color = spo2Color(last?.spo2), fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Column {
                            last?.pulse?.let { Text("pulse $it", color = MonitorColors.text, fontSize = 14.sp) }
                            last?.let { Text(com.pulsepointlabs.sarahmonitor.ui.agoLabel(it.timestampMs), color = MonitorColors.label, fontSize = 12.sp) }
                        }
                    }
                    ChoiceRow {
                        ChoiceChip("Import CSV (EMAY etc.)", false, SpO2Color) { onCsvImport() }
                        ChoiceChip("Health Connect", false, SpO2Color) { onHealthConnect() }
                    }
                    Text("You can also share the oximeter app's export straight to Sarah Monitor.", color = MonitorColors.label, fontSize = 12.sp)
                }
                if (wide) Panel("Time in range", SpO2Color, Modifier.weight(1f)) { Bands(items) }
            }
            if (!wide) Panel("Time in range", SpO2Color, Modifier.fillMaxWidth()) { Bands(items) }
            Panel("Trend", SpO2Color, Modifier.fillMaxWidth(), trailing = {
                ChoiceRow { listOf(1 to "24 h", 7 to "7 d", 30 to "30 d", 365 to "1 yr").forEach { (d, l) -> ChoiceChip(l, d == range, SpO2Color) { range = d } } }
            }) {
                if (items.size < 2) Text("No SpO2 readings in this range.", color = MonitorColors.label, fontSize = 13.sp)
                else {
                    TrendChart(
                        listOf(Series("SpO2", SpO2Color, items.map { it.timestampMs to it.spo2.toDouble() as Double? })),
                        items.first().timestampMs, items.last().timestampMs.coerceAtLeast(items.first().timestampMs + 60_000L),
                        Modifier.fillMaxWidth().height(if (wide) 240.dp else 190.dp), minSpan = 8.0, gapMs = 10 * 60_000L,
                        refLines = listOf(RefLine(90.0, "90%", MonitorColors.warn)), dots = items.size < 200
                    )
                    val pulse = items.filter { it.pulse != null }
                    if (pulse.size >= 2) TrendChart(
                        listOf(Series("Pulse", MonitorColors.hr, pulse.map { it.timestampMs to it.pulse!!.toDouble() as Double? })),
                        items.first().timestampMs, items.last().timestampMs.coerceAtLeast(items.first().timestampMs + 60_000L),
                        Modifier.fillMaxWidth().height(130.dp), minSpan = 20.0, gapMs = 10 * 60_000L
                    )
                }
            }
        }
    }
}

fun spo2Color(v: Int?): Color = when {
    v == null -> MonitorColors.dim
    v >= 95 -> MonitorColors.good
    v >= 90 -> MonitorColors.warn
    else -> MonitorColors.alarm
}

/** Share of readings in each SpO2 band, plus the low points. */
@Composable
private fun Bands(items: List<SpO2Entry>) {
    if (items.isEmpty()) { Text("No readings in this range.", color = MonitorColors.label, fontSize = 13.sp); return }
    val bands = listOf(
        Triple("≥ 95%", items.count { it.spo2 >= 95 }, MonitorColors.good),
        Triple("90–94", items.count { it.spo2 in 90..94 }, MonitorColors.rmssd),
        Triple("85–89", items.count { it.spo2 in 85..89 }, MonitorColors.warn),
        Triple("< 85", items.count { it.spo2 < 85 }, MonitorColors.alarm)
    )
    bands.forEach { (l, n, c) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(l, color = MonitorColors.text, fontSize = 13.sp, modifier = Modifier.width(56.dp))
            Canvas(Modifier.weight(1f).height(14.dp)) {
                drawRoundRect(MonitorColors.panelBorder, size = size, cornerRadius = CornerRadius(7f))
                drawRoundRect(c, size = Size(size.width * n / items.size, size.height), cornerRadius = CornerRadius(7f))
            }
            Text("${n * 100 / items.size}%", color = MonitorColors.label, fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }
    }
    val low = items.minBy { it.spo2 }
    Text("Lowest ${low.spo2}% · average ${"%.1f".format(items.map { it.spo2 }.average())}% · ${items.size} readings", color = MonitorColors.label, fontSize = 12.sp)
}

// ---------------------------------------------------------------- dashboard cards

@Composable
fun SpO2Card() {
    val all by MonitorHub.spo2.items.collectAsState()
    val last = all.lastOrNull()
    val day = all.filter { System.currentTimeMillis() - it.timestampMs <= 86_400_000L }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        com.pulsepointlabs.sarahmonitor.ui.CardTitle("SpO2", SpO2Color, "%") {
            Text(last?.let { com.pulsepointlabs.sarahmonitor.ui.agoLabel(it.timestampMs) } ?: "", color = MonitorColors.label, fontSize = 11.sp, maxLines = 1)
        }
        com.pulsepointlabs.sarahmonitor.ui.BigReading(last?.spo2?.toString() ?: "--", spo2Color(last?.spo2), Modifier.fillMaxWidth().weight(1f), maxChars = 3)
        Text(
            listOfNotNull(last?.pulse?.let { "pulse $it" }, day.minByOrNull { it.spo2 }?.let { "24h low ${it.spo2}" }).joinToString(" · ").ifBlank { "Import from the oximeter app" },
            color = MonitorColors.label, fontSize = 12.sp, maxLines = 1
        )
    }
}

@Composable
fun BodyCard() {
    val all by MonitorHub.body.items.collectAsState()
    BodyProfile.pounds.collectAsState().value
    val last = all.lastOrNull()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        com.pulsepointlabs.sarahmonitor.ui.CardTitle("Body", BodyColor, BodyProfile.massUnit) {
            Text(last?.let { com.pulsepointlabs.sarahmonitor.ui.agoLabel(it.timestampMs) } ?: "", color = MonitorColors.label, fontSize = 11.sp, maxLines = 1)
        }
        com.pulsepointlabs.sarahmonitor.ui.BigReading(last?.let { "%.1f".format(BodyProfile.massValue(it.weightKg)) } ?: "--", BodyColor, Modifier.fillMaxWidth().weight(1f), maxChars = 5)
        Text(
            listOfNotNull(last?.bodyFatPct?.let { "fat %.1f%%".format(it) }, last?.muscleMassKg?.let { "muscle %.1f".format(BodyProfile.massValue(it)) }, last?.bodyWaterPct?.let { "water %.0f%%".format(it) })
                .joinToString(" · ").ifBlank { "Read the scale on the Health tab" },
            color = MonitorColors.label, fontSize = 12.sp, maxLines = 1
        )
    }
}
