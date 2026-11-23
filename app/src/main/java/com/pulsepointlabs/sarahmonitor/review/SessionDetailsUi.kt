package com.pulsepointlabs.sarahmonitor.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pulsepointlabs.sarahmonitor.session.DetailForms
import com.pulsepointlabs.sarahmonitor.session.FieldKind
import com.pulsepointlabs.sarahmonitor.session.FieldSpec
import com.pulsepointlabs.sarahmonitor.session.GearItem
import com.pulsepointlabs.sarahmonitor.session.GearType
import com.pulsepointlabs.sarahmonitor.session.SessionCategory
import com.pulsepointlabs.sarahmonitor.session.SessionDetails
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors

private val accent = Color(0xFFFF7AB6)
private val deviceColor = Color(0xFF93C5FD)

/** What the details dialog is for. */
enum class DetailsMode(val title: String) { Setup("Session setup"), WrapUp("How did it go?"), Edit("Session details") }

/** Chip for a choice; selected chips fill with the colour. */
@Composable
private fun Pill(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Text(
        (if (on) "✓ " else "") + label, color = if (on) MonitorColors.background else color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.heightIn(min = 34.dp).background(if (on) color else color.copy(alpha = 0.07f), RoundedCornerShape(17.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(17.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/** One field, rendered for its kind. Values are strings ("true" for toggles, "a|b" for multi-choice). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldEditor(spec: FieldSpec, value: String?, color: Color, onChange: (String?) -> Unit) {
    val v = value.orEmpty()
    when (spec.kind) {
        FieldKind.Toggle -> Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, color = MonitorColors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = v == "true", onCheckedChange = { onChange(if (it) "true" else null) }, colors = SwitchDefaults.colors(checkedTrackColor = color, checkedThumbColor = Color.White))
        }
        FieldKind.Choice, FieldKind.Multi -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(spec.label, color = MonitorColors.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            val chosen = v.split('|').filter { it.isNotBlank() }.toSet()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                spec.options.forEach { o ->
                    val on = o in chosen
                    Pill(o, on, color) {
                        onChange(
                            if (spec.kind == FieldKind.Choice) (if (on) null else o)
                            else (if (on) chosen - o else chosen + o).let { set -> spec.options.filter { it in set }.joinToString("|").ifBlank { null } }
                        )
                    }
                }
            }
        }
        FieldKind.Scale -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.label, color = MonitorColors.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                spec.hint?.let { Text(it, color = MonitorColors.dim, fontSize = 11.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0..10).forEach { n ->
                    val on = v == n.toString()
                    Box(
                        Modifier.weight(1f).heightIn(min = 32.dp).background(if (on) color else color.copy(alpha = 0.07f), RoundedCornerShape(7.dp))
                            .border(1.dp, color.copy(alpha = if (on) 0.9f else 0.35f), RoundedCornerShape(7.dp)).clickable { onChange(if (on) null else n.toString()) },
                        contentAlignment = Alignment.Center
                    ) { Text("$n", color = if (on) MonitorColors.background else MonitorColors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        FieldKind.Number -> OutlinedTextField(
            value = v, onValueChange = { t -> onChange(t.filter { it.isDigit() || it == '.' }.take(7).ifBlank { null }) },
            label = { Text(spec.label) }, singleLine = true, suffix = spec.unit?.let { u -> { Text(u) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.widthIn(min = 140.dp, max = 220.dp)
        )
        FieldKind.Text -> OutlinedTextField(
            value = v, onValueChange = { onChange(it.ifBlank { null }) }, label = { Text(spec.label) },
            placeholder = spec.hint?.let { h -> { Text(h) } }, modifier = Modifier.fillMaxWidth(), maxLines = 4
        )
    }
}

/** A set of fields: numbers sit side by side, everything else full width; dependent fields appear when relevant. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldsForm(specs: List<FieldSpec>, values: Map<String, String>, color: Color, onChange: (Map<String, String>) -> Unit) {
    val shown = specs.filter { it.applies(values) }
    val extra = shown.filter { it.more }
    // "More details" opens by itself when one of those fields already has a value.
    var showMore by remember { mutableStateOf(extra.any { !values[it.key].isNullOrBlank() }) }
    val visible = shown.filter { !it.more || showMore }
    fun set(key: String, v: String?) = onChange(if (v == null) values - key else values + (key to v))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var i = 0
        while (i < visible.size) {
            val f = visible[i]
            if (f.kind == FieldKind.Number) {
                val run = visible.drop(i).takeWhile { it.kind == FieldKind.Number }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    run.forEach { n -> FieldEditor(n, values[n.key], color) { set(n.key, it) } }
                }
                i += run.size
            } else {
                FieldEditor(f, values[f.key], color) { set(f.key, it) }
                i++
            }
        }
        if (extra.isNotEmpty()) Row(
            Modifier.clickable { showMore = !showMore }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (showMore) "Fewer details" else "More details", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Icon(if (showMore) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
    }
}

/** Devices / products: add from the list, fill in what matters for each, remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GearEditor(items: List<GearItem>, available: List<GearType>, lastGear: List<GearItem> = emptyList(), onChange: (List<GearItem>) -> Unit) {
    var open by remember { mutableStateOf(items.lastOrNull()?.id) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty() && lastGear.isNotEmpty()) {
            Pill("↺ Same as last time: " + lastGear.joinToString(", ") { it.type.label }, false, deviceColor) { onChange(lastGear); open = null }
        }
        items.forEach { g ->
            val expanded = open == g.id
            Column(
                Modifier.fillMaxWidth().background(deviceColor.copy(alpha = 0.05f), RoundedCornerShape(10.dp)).border(1.dp, deviceColor.copy(alpha = if (expanded) 0.6f else 0.3f), RoundedCornerShape(10.dp))
            ) {
                Row(Modifier.fillMaxWidth().clickable { open = if (expanded) null else g.id }.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.type.label, color = deviceColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        g.type.summary(g.values).takeIf { it.isNotBlank() }?.let { Text(it, color = MonitorColors.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MonitorColors.label)
                    IconButton(onClick = { onChange(items.filter { it.id != g.id }) }) { Icon(Icons.Default.Close, contentDescription = "Remove ${g.type.label}", tint = MonitorColors.dim) }
                }
                if (expanded) Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    FieldsForm(g.type.fields, g.values, deviceColor) { v -> onChange(items.map { if (it.id == g.id) it.copy(values = v) else it }) }
                }
            }
        }
        Text(if (items.isEmpty()) "Add what you'll use" else "Add another", color = MonitorColors.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            available.forEach { t -> Pill("＋ " + t.label, false, deviceColor) { val g = GearItem(type = t); onChange(items + g); open = g.id } }
        }
    }
}

@Composable
private fun Block(title: String, color: Color, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MonitorColors.panel, RoundedCornerShape(12.dp)).border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

/**
 * Full-screen sheet for a session's details. Setup: before + devices, then Start now / Start with OBS.
 * Wrap-up: how it went. Edit: everything. Two columns on wide screens.
 */
@Composable
fun SessionDetailsDialog(
    mode: DetailsMode,
    category: SessionCategory?,
    initial: SessionDetails,
    onDismiss: () -> Unit,
    onSave: (SessionDetails) -> Unit,
    /** Setup only: start when OBS starts recording (shown when the OBS link is on). */
    onArmObs: ((SessionDetails) -> Unit)? = null,
    /** Devices from last time, offered as one tap (never pre-filled). */
    lastGear: List<GearItem> = emptyList()
) {
    var d by remember { mutableStateOf(initial) }
    val showPre = mode != DetailsMode.WrapUp
    val showGear = mode != DetailsMode.WrapUp && DetailForms.gearFor(category).isNotEmpty()
    val showPost = mode != DetailsMode.Setup
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MonitorColors.background).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = MonitorColors.text) }
                Column(Modifier.weight(1f)) {
                    Text(mode.title, color = MonitorColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    category?.let { Text(it.label, color = accent, fontSize = 13.sp) }
                }
                if (onArmObs != null) TextButton(onClick = { onArmObs(d) }) { Text("With OBS", color = accent, fontWeight = FontWeight.Bold) }
                Text(
                    when (mode) { DetailsMode.Setup -> "Start now"; DetailsMode.WrapUp -> "Save"; DetailsMode.Edit -> "Save" },
                    color = MonitorColors.background, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.background(accent, RoundedCornerShape(20.dp)).clickable { onSave(d) }.padding(horizontal = 18.dp, vertical = 9.dp)
                )
            }
            Spacer(Modifier.size(8.dp))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val before: @Composable () -> Unit = { if (showPre) Block("Before", MonitorColors.sdnn) { FieldsForm(DetailForms.pre, d.pre, MonitorColors.sdnn) { d = d.copy(pre = it) } } }
                val after: @Composable () -> Unit = { if (showPost) Block("After", MonitorColors.good) { FieldsForm(DetailForms.post(category), d.post, MonitorColors.good) { d = d.copy(post = it) } } }
                val devices: @Composable () -> Unit = { if (showGear) Block("Devices & products", deviceColor) { GearEditor(d.gear, DetailForms.gearFor(category), lastGear) { d = d.copy(gear = it) } } }
                if (maxWidth >= 900.dp) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { before(); after() }
                        if (showGear) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { devices() }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { before(); devices(); after() }
                }
            }
        }
    }
}

/** Review: what was recorded about the session, at a glance, with an edit button. */
@Composable
fun DetailsCard(details: SessionDetails, category: SessionCategory?, onEdit: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().background(MonitorColors.panel, shape).border(1.dp, MonitorColors.panelBorder, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Session details", color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text(if (details.empty) "+ Add details" else "Edit", fontSize = 13.sp) }
        }
        if (details.empty) {
            Text("Nothing recorded yet: how you felt, what you used, how it went.", color = MonitorColors.label, fontSize = 13.sp)
            return@Column
        }
        fun lines(specs: List<FieldSpec>, values: Map<String, String>) = specs.filter { it.applies(values) }.mapNotNull { f ->
            val v = values[f.key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when (f.kind) {
                FieldKind.Toggle -> if (v == "true") f.label else null
                FieldKind.Scale -> "${f.label} $v/10"
                FieldKind.Multi -> "${f.label}: ${v.replace("|", ", ")}"
                else -> "${f.label}: $v" + (f.unit?.let { u -> if (u == "×") "" else " $u" } ?: "")
            }
        }
        @Composable
        fun group(title: String, color: Color, items: List<String>) {
            if (items.isEmpty()) return
            Text(title, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(items.joinToString(" · "), color = MonitorColors.text, fontSize = 13.sp, lineHeight = 18.sp)
        }
        group("Before", MonitorColors.sdnn, lines(DetailForms.pre, details.pre))
        details.gear.forEach { g -> group(g.type.label, deviceColor, lines(g.type.fields, g.values).ifEmpty { listOf("no details") }) }
        group("After", MonitorColors.good, lines(DetailForms.post(category), details.post))
        details.video?.let { v ->
            group("Video", accent, listOfNotNull(
                "OBS recording ${clockLabel(v.startedAtMs, seconds = true)}" + (v.stoppedAtMs?.let { "–" + clockLabel(it, seconds = true) } ?: " (recording)"),
                v.pauses.takeIf { it.isNotEmpty() }?.let { "${it.size} pause${if (it.size == 1) "" else "s"}" },
                v.path
            ))
        }
    }
}
