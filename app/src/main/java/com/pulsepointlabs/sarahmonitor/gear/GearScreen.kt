package com.pulsepointlabs.sarahmonitor.gear

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pulsepointlabs.sarahmonitor.core.ClaudeText
import com.pulsepointlabs.sarahmonitor.ui.EvenGrid
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Each category's accent, used on cards, tags and the filter row. */
fun GearCategory.color(): Color = when (this) {
    GearCategory.Sounds -> Color(0xFF93C5FD)
    GearCategory.Catheters -> Color(0xFF60A5FA)
    GearCategory.Enema -> Color(0xFF4ADE80)
    GearCategory.Anal -> Color(0xFFF472B6)
    GearCategory.Sleeves -> Color(0xFFFF7AB6)
    GearCategory.Vibrators -> Color(0xFFC084FC)
    GearCategory.Estim -> Color(0xFFBD94FF)
    GearCategory.Sensors -> Color(0xFF2DD4BF)
    GearCategory.Lube -> Color(0xFF7DD3FC)
    GearCategory.Other -> Color(0xFFCBD5E1)
}

private val SarahPink = Color(0xFFFF7AB6)

/**
 * The gear catalog: everything he uses in sessions, by category, with photos and the measurements that matter.
 * Add something by snapping or picking photos (and saying what it is, if he likes); Sarah fills in the card.
 */
@Composable
fun GearScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { GearCatalog.load(context) }
    val items by GearCatalog.items.collectAsState()
    var filter by remember { mutableStateOf<GearCategory?>(null) }
    var editing by remember { mutableStateOf<CatalogItem?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val shown = items.filter { filter == null || it.category == filter }.sortedWith(compareBy({ it.category.ordinal }, { it.name.lowercase() }))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your gear", color = MonitorColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (items.isEmpty()) "Nothing added yet" else "${items.size} item${if (items.size == 1) "" else "s"}", color = MonitorColors.label, fontSize = 13.sp)
            }
            PrimaryPill("+ Add gear") { editing = CatalogItem() }
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your catalog", color = MonitorColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Add the devices and supplies you use: sounds, catheters, enema gear, toys, e-stim. Snap a photo and Sarah fills in what it is, its measurements, where it sits and how to prep.",
                    color = MonitorColors.label, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.widthIn(max = 460.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            // Only the categories he actually has, scrolling sideways if there are many.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("All · ${items.size}", filter == null, MonitorColors.text) { filter = null }
                GearCategory.entries.filter { c -> items.any { it.category == c } }.forEach { c ->
                    FilterPill("${c.label} · ${items.count { it.category == c }}", filter == c, c.color()) { filter = if (filter == c) null else c }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(shown, key = { it.id }) { GearCard(it) { viewing = it.id } }
            }
        }
    }
    editing?.let { GearEditor(it, isNew = items.none { i -> i.id == it.id }, onDone = { saved -> editing = null; if (saved != null) viewing = saved.id }) }
    viewing?.let { id -> items.firstOrNull { it.id == id }?.let { GearDetail(it, onEdit = { viewing = null; editing = it }, onClose = { viewing = null }) } }
}

@Composable
private fun GearCard(item: CatalogItem, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val c = item.category.color()
    Column(
        Modifier.fillMaxWidth().background(MonitorColors.panel, shape).border(1.dp, c.copy(alpha = 0.35f), shape).clip(shape).clickable(onClick = onOpen)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(c.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            val photo = item.photos.firstOrNull()
            if (photo != null) Photo(photo, Modifier.fillMaxSize(), 600)
            else Text(item.category.label, color = c.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name.ifBlank { "Unnamed" }, color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Tag(item.category.label, c)
            if (item.measurements.isNotEmpty()) Text(item.keyFacts(), color = MonitorColors.label, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A catalog photo, decoded off the main thread at about [maxPx]. */
@Composable
private fun Photo(name: String, modifier: Modifier, maxPx: Int) {
    val bmp by produceState<android.graphics.Bitmap?>(null, name) { value = withContext(Dispatchers.IO) { GearCatalog.thumbnail(name, maxPx) } }
    bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) }
}

/** One item in full: photos, measurements, what it is, where it sits, prep, cautions, notes, and a box to ask Sarah. */
@Composable
private fun GearDetail(item: CatalogItem, onEdit: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var question by remember(item.id) { mutableStateOf("") }
    var answer by remember(item.id) { mutableStateOf<String?>(null) }
    var thinking by remember(item.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val c = item.category.color()
    Sheet(onClose,
        header = {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name.ifBlank { "Unnamed" }, color = MonitorColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Tag(item.category.label, c)
                }
                CloseButton(onClose)
            }
        },
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietPill(if (confirmDelete) "Tap again to delete" else "Delete", MonitorColors.alarm) {
                    if (confirmDelete) { GearCatalog.delete(item); onClose() } else confirmDelete = true
                }
                Spacer(Modifier.weight(1f))
                PrimaryPill("Edit", onClick = onEdit)
            }
        }
    ) {
        if (item.photos.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.photos.forEach { Photo(it, Modifier.height(200.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)), 1000) }
        }
        if (item.sarahSays.isNotBlank()) Text("Sarah: ${item.sarahSays}", color = SarahPink, fontSize = 14.sp, fontStyle = FontStyle.Italic, lineHeight = 20.sp)
        if (item.measurements.isNotEmpty()) Section("Measurements") {
            Column(Modifier.fillMaxWidth().background(Color(0x0DFFFFFF), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                item.measurements.forEachIndexed { i, m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.label, color = MonitorColors.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(m.value + if (m.estimated) "  ≈" else "", color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    if (i < item.measurements.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(MonitorColors.panelBorder))
                }
            }
            if (item.measurements.any { it.estimated }) Text("≈ estimated from the photo", color = MonitorColors.label, fontSize = 12.sp)
        }
        Prose("What it is", listOfNotNull(item.about.ifBlank { null }, item.material.ifBlank { null }?.let { "Material: $it" }).joinToString("\n"))
        Prose("Where it sits", item.placement)
        Prose("Prep", item.prep)
        Prose("Cautions", item.cautions, MonitorColors.warn)
        Prose("Your notes", item.notes)
        Section("Ask Sarah about this") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(question, { question = it }, placeholder = { Text("How do I prep for this? How deep does it sit?", color = MonitorColors.label) }, modifier = Modifier.weight(1f), maxLines = 4)
                PrimaryPill(if (thinking) "…" else "Ask", enabled = question.isNotBlank() && !thinking) {
                    thinking = true; answer = null
                    scope.launch {
                        answer = GearCatalog.ask(item, question) ?: "I couldn't answer just now (${ClaudeText.lastProblem ?: "no connection"})."
                        thinking = false
                    }
                }
            }
            if (thinking) Text("Sarah is thinking…", color = MonitorColors.label, fontSize = 13.sp)
            answer?.let { Text(it, color = MonitorColors.text, fontSize = 14.sp, lineHeight = 21.sp) }
        }
    }
}

/**
 * Add or edit an item. Photos first (camera or library), then whatever he wants to say about it; "Let Sarah fill this
 * in" reads the photos and fills the card, which he can change before saving.
 */
@Composable
private fun GearEditor(start: CatalogItem, isNew: Boolean, onDone: (CatalogItem?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(start) }
    var said by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Photos added in this edit, removed again if he cancels.
    val added = remember { mutableStateListOf<String>() }
    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val names = uris.mapNotNull { GearCatalog.importPhoto(context, it) }
            added += names
            draft = draft.copy(photos = draft.photos + names)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { com.pulsepointlabs.sarahmonitor.overlay.OverlaySettings.setLent(false); addUris(it) }
    val captureUri = remember {
        val f = java.io.File(context.cacheDir, "shared").apply { mkdirs() }.let { java.io.File(it, "gear-capture.jpg") }
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> com.pulsepointlabs.sarahmonitor.overlay.OverlaySettings.setLent(false); if (ok) addUris(listOf(captureUri)) }
    fun cancel() {
        added.forEach { GearCatalog.photoFile(it)?.delete() }
        onDone(null)
    }
    Sheet(::cancel,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isNew) "Add gear" else "Edit gear", color = MonitorColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                CloseButton(::cancel)
            }
        },
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                QuietPill("Cancel", MonitorColors.label, ::cancel)
                PrimaryPill("Save", enabled = draft.name.isNotBlank() || draft.photos.isNotEmpty()) {
                    val toSave = draft.copy(name = draft.name.ifBlank { draft.category.label })
                    GearCatalog.save(toSave)
                    onDone(toSave)
                }
            }
        }
    ) {
        Section("Photos") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.photos.forEach { p ->
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp))) {
                        Photo(p, Modifier.fillMaxSize(), 300)
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).background(Color(0xCC000000), RoundedCornerShape(13.dp))
                                .clickable { draft = draft.copy(photos = draft.photos - p); if (p in added) { GearCatalog.photoFile(p)?.delete(); added -= p } },
                            contentAlignment = Alignment.Center
                        ) { Text("✕", color = Color.White, fontSize = 13.sp) }
                    }
                }
                // Overlays step aside while the camera or picker is open (they'd cover its buttons).
                AddTile("Camera") {
                    com.pulsepointlabs.sarahmonitor.overlay.OverlaySettings.setLent(true)
                    runCatching { camera.launch(captureUri) }.onFailure { com.pulsepointlabs.sarahmonitor.overlay.OverlaySettings.setLent(false); status = "No camera app available" }
                }
                AddTile("Photos") { com.pulsepointlabs.sarahmonitor.overlay.OverlaySettings.setLent(true); pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
        }
        Section("What is it?", hint = "optional") {
            OutlinedTextField(
                said, { said = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                placeholder = { Text("e.g. double-balloon enema nozzle, about 16 in long, balloons inflate to about 2 in", color = MonitorColors.label) }
            )
        }
        PrimaryPill(if (busy) "Sarah is looking…" else "✦ Let Sarah fill this in", enabled = !busy && (draft.photos.isNotEmpty() || said.isNotBlank()), modifier = Modifier.fillMaxWidth()) {
            busy = true; status = null
            scope.launch {
                val filled = GearCatalog.identify(draft, said)
                if (filled != null) draft = filled else status = "Sarah couldn't read it just now (${ClaudeText.lastProblem ?: "no connection"}). You can fill it in yourself."
                busy = false
            }
        }
        status?.let { Text(it, color = MonitorColors.warn, fontSize = 13.sp) }
        if (draft.sarahSays.isNotBlank()) Text("Sarah: ${draft.sarahSays}", color = SarahPink, fontSize = 14.sp, fontStyle = FontStyle.Italic, lineHeight = 20.sp)
        Section("Name") { OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        Section("Category") {
            EvenGrid(Modifier.fillMaxWidth()) {
                GearCategory.entries.forEach { c -> FilterPill(c.label, draft.category == c, c.color(), center = true) { draft = draft.copy(category = c) } }
            }
        }
        Section("Measurements") {
            draft.measurements.forEachIndexed { i, m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(m.label, { v -> draft = draft.copy(measurements = draft.measurements.toMutableList().also { it[i] = m.copy(label = v) }) },
                        singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text("What", color = MonitorColors.label) })
                    OutlinedTextField(m.value, { v -> draft = draft.copy(measurements = draft.measurements.toMutableList().also { it[i] = m.copy(value = v, estimated = false) }) },
                        singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("e.g. 4 in", color = MonitorColors.label) })
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { draft = draft.copy(measurements = draft.measurements.filterIndexed { j, _ -> j != i }) }, contentAlignment = Alignment.Center) {
                        Text("✕", color = MonitorColors.label, fontSize = 16.sp)
                    }
                }
                if (m.estimated) Text("≈ estimated from the photo — correct it if you know", color = MonitorColors.label, fontSize = 12.sp)
            }
            QuietPill("+ Add measurement", MonitorColors.pelvic) { draft = draft.copy(measurements = draft.measurements + Measurement("", "")) }
        }
        Section("Material") { OutlinedTextField(draft.material, { draft = draft.copy(material = it) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        Section("What it is") { OutlinedTextField(draft.about, { draft = draft.copy(about = it) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
        Section("Where it sits") { OutlinedTextField(draft.placement, { draft = draft.copy(placement = it) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
        Section("Prep") { OutlinedTextField(draft.prep, { draft = draft.copy(prep = it) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
        Section("Cautions") { OutlinedTextField(draft.cautions, { draft = draft.copy(cautions = it) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
        Section("Your notes") { OutlinedTextField(draft.notes, { draft = draft.copy(notes = it) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
    }
}

/** Full-screen panel that sits above the keyboard: fixed header and footer, the middle scrolls. */
@Composable
private fun Sheet(onDismiss: () -> Unit, header: @Composable () -> Unit, footer: @Composable () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(10.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth()
                    .background(MonitorColors.panel, RoundedCornerShape(22.dp)).border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                header()
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
                footer()
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, color = MonitorColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (hint != null) Text("  $hint", color = MonitorColors.label, fontSize = 12.sp)
        }
        content()
    }
}

/** A titled paragraph, left out entirely when there's nothing to say. */
@Composable
private fun Prose(title: String, text: String, color: Color = MonitorColors.text) {
    if (text.isBlank()) return
    Section(title) { Text(text, color = color, fontSize = 14.sp, lineHeight = 21.sp) }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(5.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun FilterPill(label: String, on: Boolean, color: Color, center: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp).background(if (on) color else color.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = if (on) 1f else 0.5f), RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart
    ) { Text(label, color = if (on) MonitorColors.background else color, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}

@Composable
private fun PrimaryPill(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MonitorColors.pelvic
    Box(
        modifier.heightIn(min = 46.dp).background(if (enabled) c else c.copy(alpha = 0.25f), RoundedCornerShape(23.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = MonitorColors.background, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}

@Composable
private fun QuietPill(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text("✕", color = MonitorColors.label, fontSize = 20.sp)
    }
}

@Composable
private fun AddTile(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(96.dp).border(1.dp, MonitorColors.pelvic.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).background(MonitorColors.pelvic.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("+", color = MonitorColors.pelvic, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(label, color = MonitorColors.pelvic, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
