package com.pulsepointlabs.sarahmonitor

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.pulsepointlabs.sarahmonitor.review.MarkEventDialog
import com.pulsepointlabs.sarahmonitor.review.ReviewScreen
import com.pulsepointlabs.sarahmonitor.review.SessionsScreen
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.pulsepointlabs.sarahmonitor.ble.ConnectionState
import com.pulsepointlabs.sarahmonitor.core.GlucoseEntry
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.core.MonitorService
import com.pulsepointlabs.sarahmonitor.glucose.GlucoseCsv
import com.pulsepointlabs.sarahmonitor.glucose.HealthConnectImporter
import com.pulsepointlabs.sarahmonitor.overlay.OverlayDisplay
import com.pulsepointlabs.sarahmonitor.overlay.OverlayKind
import com.pulsepointlabs.sarahmonitor.overlay.OverlaySettings
import com.pulsepointlabs.sarahmonitor.ui.CardActions
import com.pulsepointlabs.sarahmonitor.ui.CardType
import com.pulsepointlabs.sarahmonitor.ui.Dashboard
import com.pulsepointlabs.sarahmonitor.ui.SensorsScreen
import com.pulsepointlabs.sarahmonitor.ui.SettingsScreen
import androidx.compose.material.icons.filled.Settings
import com.pulsepointlabs.sarahmonitor.ui.GridLayoutEngine
import com.pulsepointlabs.sarahmonitor.ui.LayoutStore
import com.pulsepointlabs.sarahmonitor.ui.LocalCardActions
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val healthImporter by lazy { HealthConnectImporter(this) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        MonitorService.start(this)
        MonitorHub.connectRemembered()
    }

    private val healthPermissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(healthImporter.permissions)) importHealthConnect()
        else toast("Health Connect access wasn't granted.")
    }

    private var pendingCsv: String? = null
    private val csvExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val text = pendingCsv
        pendingCsv = null
        if (uri != null && text != null) {
            runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                .onSuccess { toast("CSV saved") }.onFailure { toast("Couldn't save the CSV") }
        }
    }

    private val csvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importCsv) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MonitorHub.init(this)
        OverlaySettings.load(this)
        requestPermissions()
        val layoutStore = LayoutStore(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = MonitorColors.background, surface = MonitorColors.panel, primary = MonitorColors.pelvic)) {
                MonitorScreen(
                    layoutStore = layoutStore,
                    onCsvImport = { csvLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) },
                    onHealthConnect = ::requestHealthConnect,
                    onExportCsv = { name, text -> pendingCsv = text; csvExportLauncher.launch(name) },
                    onMessage = ::toast
                )
            }
        }
        handleSharedExport(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedExport(intent)
    }

    /**
     * Glucose results shared to Sarah Monitor from another app (OneTouch Reveal's share/export, a file manager,
     * email…). Same behaviour as Sarah's share target: a CSV file or shared text, parsed by the glucose importer.
     */
    private fun handleSharedExport(intent: android.content.Intent?) {
        if (intent == null || (intent.action != android.content.Intent.ACTION_SEND && intent.action != android.content.Intent.ACTION_VIEW)) return
        @Suppress("DEPRECATION")
        val uri: Uri? = when (intent.action) {
            android.content.Intent.ACTION_SEND -> intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) ?: intent.clipData?.getItemAt(0)?.uri
            else -> intent.data
        }
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        // Consume it so a rotation or resume doesn't import twice.
        setIntent(android.content.Intent(this, MainActivity::class.java))
        when {
            uri != null -> importCsv(uri)
            text != null -> importText(text, "shared text")
            else -> toast("The shared export didn't include a readable file.")
        }
    }

    private fun importText(text: String, source: String) {
        val result = GlucoseCsv.parse(text, source)
        if (result.error != null) toast(result.error) else {
            val added = MonitorHub.glucose.addAll(result.rows)
            toast("Imported $added new glucose readings${if (result.skipped > 0) " (${result.skipped} rows skipped)" else ""}.")
        }
    }

    // Overlays hide while this window is the focused one (on DeX: the active window) and come back after.
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        OverlaySettings.setAppInFront(isTopResumedActivity)
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < 29) OverlaySettings.setAppInFront(false)
    }

    override fun onStop() {
        super.onStop()
        OverlaySettings.setAppInFront(false)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 29) OverlaySettings.setAppInFront(true)
        OverlayDisplay.recordActivityDisplay(this)
        MonitorHub.connectRemembered()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        OverlayDisplay.recordActivityDisplay(this)
    }

    private fun requestPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "shared file"

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun requestHealthConnect() {
        if (!healthImporter.available()) {
            toast("Health Connect isn't available on this device.")
            return
        }
        kotlinx.coroutines.MainScope().launch {
            if (healthImporter.hasPermissions()) importHealthConnect()
            else healthPermissionLauncher.launch(healthImporter.requestPermissions)
        }
    }

    private fun importHealthConnect() {
        kotlinx.coroutines.MainScope().launch {
            val glucose = runCatching { healthImporter.readGlucose() }.getOrDefault(emptyList())
            val bp = runCatching { healthImporter.readBloodPressure() }.getOrDefault(emptyList())
            val g = MonitorHub.glucose.addAll(glucose)
            val b = MonitorHub.bp.addAll(bp)
            toast("Health Connect: $g new glucose, $b new BP readings.")
        }
    }

    private fun importCsv(uri: Uri) {
        kotlinx.coroutines.MainScope().launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            require(out.size() <= 16 * 1024 * 1024) { "File is larger than 16 MB" }
                        }
                        out.toByteArray()
                    } ?: ByteArray(0)
                    GlucoseCsv.parse(GlucoseCsv.decode(bytes), displayName(uri))
                }.getOrNull()
            }
            when {
                result == null -> toast("Couldn't read that file.")
                result.error != null -> toast(result.error)
                else -> {
                    val added = MonitorHub.glucose.addAll(result.rows)
                    toast("Imported $added new glucose readings${if (result.skipped > 0) " (${result.skipped} rows skipped)" else ""}.")
                }
            }
        }
    }
}

private enum class Screen(val label: String) { Monitor("Monitor"), Sessions("Sessions"), Sensors("Sensors"), Settings("Settings") }

@Composable
private fun MonitorScreen(
    layoutStore: LayoutStore,
    onCsvImport: () -> Unit,
    onHealthConnect: () -> Unit,
    onExportCsv: (String, String) -> Unit,
    onMessage: (String) -> Unit
) {
    var slots by remember { mutableStateOf(layoutStore.load()) }
    var arranging by remember { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf(Screen.Monitor) }
    var reviewId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCards by remember { mutableStateOf(false) }
    var showAddGlucose by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var markAt by remember { mutableStateOf<Long?>(null) }
    val session by MonitorHub.session.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun mark() {
        if (session == null) onMessage("Start recording (● Record) to mark events.") else markAt = System.currentTimeMillis()
    }
    BackHandler(enabled = reviewId != null || screen != Screen.Monitor) {
        if (reviewId != null) reviewId = null else screen = Screen.Monitor
    }
    val actions = remember {
        CardActions(
            onAddGlucose = { showAddGlucose = true },
            onImportGlucose = onCsvImport,
            onOpenDevices = { screen = Screen.Sensors; reviewId = null },
            onMessage = onMessage
        )
    }
    CompositionLocalProvider(LocalCardActions provides actions) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MonitorColors.background)
                .safeDrawingPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .focusRequester(focus)
                .focusable()
                // DeX / keyboard: M marks an event, R starts or stops recording.
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || markAt != null) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.M -> { mark(); true }
                        Key.R -> { if (session == null) MonitorHub.startSession() else confirmStop = true; true }
                        else -> false
                    }
                }
        ) {
            TopBar(
                screen = if (reviewId != null) Screen.Sessions else screen,
                recording = session,
                arranging = arranging,
                onScreen = { screen = it; reviewId = null },
                onRecord = { if (session == null) MonitorHub.startSession() else confirmStop = true },
                onMark = ::mark,
                onDevices = { screen = Screen.Sensors; reviewId = null },
                onCards = { showCards = true },
                onOverlays = { screen = Screen.Settings; reviewId = null },
                onArrange = { arranging = !arranging },
                onReset = { confirmReset = true }
            )
            Spacer(Modifier.size(4.dp))
            val openReview = reviewId
            when {
                openReview != null -> ReviewScreen(
                    sessionId = openReview,
                    onBack = { reviewId = null },
                    onExportCsv = onExportCsv,
                    onMessage = onMessage,
                    modifier = Modifier.fillMaxSize()
                )
                screen == Screen.Sessions -> SessionsScreen(onOpen = { reviewId = it }, modifier = Modifier.fillMaxSize())
                screen == Screen.Sensors -> SensorsScreen(onHealthConnect, onCsvImport, onMessage, Modifier.fillMaxSize())
                screen == Screen.Settings -> SettingsScreen(onMessage, Modifier.fillMaxSize())
                else -> Dashboard(
                    slots = slots,
                    arranging = arranging,
                    onArrangingChange = { arranging = it },
                    onChange = { slots = it; layoutStore.save(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
    markAt?.let { t ->
        MarkEventDialog(
            pressedAtMs = t,
            onSave = { types, note ->
                MonitorHub.markEvent(t, types, note) { ok -> if (!ok) onMessage("Not recording; event not saved.") }
                markAt = null
            },
            onDismiss = { markAt = null }
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            containerColor = MonitorColors.panel,
            title = { Text("Stop recording?", color = MonitorColors.text) },
            text = { Text("The session is saved and opens for review. Sensors stay connected.", color = MonitorColors.label) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    MonitorHub.stopSession { stopped -> stopped?.let { reviewId = it.id; screen = Screen.Sessions } }
                }) { Text("Stop & review") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep recording") } }
        )
    }
    if (showCards) {
        CardsDialog(
            slots = slots,
            onToggle = { type, on -> slots = GridLayoutEngine.setVisible(slots, type, on); layoutStore.save(slots) },
            onResetLayout = { slots = GridLayoutEngine.defaultLayout; layoutStore.save(slots) },
            onDismiss = { showCards = false }
        )
    }
    if (showAddGlucose) AddGlucoseDialog(onDismiss = { showAddGlucose = false }, onMessage = onMessage)
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Start fresh?") },
            text = { Text("Clears the live HR/HRV/Load statistics, pressure counts and trend graphs. Sensors stay connected; recorded sessions, BP and glucose are kept.") },
            confirmButton = { TextButton(onClick = { MonitorHub.resetSession(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun TopBar(
    screen: Screen,
    recording: com.pulsepointlabs.sarahmonitor.session.SessionEntity?,
    arranging: Boolean,
    onScreen: (Screen) -> Unit,
    onRecord: () -> Unit,
    onMark: () -> Unit,
    onDevices: () -> Unit,
    onCards: () -> Unit,
    onOverlays: () -> Unit,
    onArrange: () -> Unit,
    onReset: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000L); now = System.currentTimeMillis() } }
    val h10 by MonitorHub.h10.state.collectAsState()
    val civet by MonitorHub.civet.state.collectAsState()
    val omron by MonitorHub.omron.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Three widths: full desktop bar (DeX), medium (tablet / phone landscape) and narrow (phone portrait).
        val full = maxWidth >= 1150.dp
        val narrow = maxWidth < 600.dp
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrow) 6.dp else 8.dp)) {
            if (full) Text("Sarah Monitor", color = MonitorColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (!narrow) {
                Text(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(clockFormat), color = MonitorColors.label, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                if (full) recording?.let { SessionClock(it.startTimeMs, now) }
            }
            Tabs(screen, compact = narrow, onScreen = onScreen)
            Spacer(Modifier.weight(1f))
            RecordButton(recording, now, compact = narrow, onClick = onRecord)
            MarkButton(enabled = recording != null, compact = !full, onClick = onMark)
            OverlayToggle()
            if (full) {
                DevicePill("H10", h10, onDevices)
                DevicePill("CIVET", civet, onDevices)
                DevicePill("BP", omron, onDevices)
                if (screen == Screen.Monitor) {
                    BarIcon(Icons.Default.Dashboard, "Cards", onCards)
                    BarIcon(Icons.Default.OpenWith, if (arranging) "Done arranging" else "Arrange cards", onArrange, highlighted = arranging)
                    BarIcon(Icons.Default.RestartAlt, "Start fresh", onReset)
                }
            } else {
                Box {
                    BarIcon(Icons.Default.MoreVert, "More", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MonitorColors.panel) {
                        DropdownMenuItem(text = { Text("Cards") }, onClick = { menu = false; onScreen(Screen.Monitor); onCards() })
                        if (!narrow) DropdownMenuItem(text = { Text(if (arranging) "Done arranging" else "Arrange cards") }, onClick = { menu = false; onScreen(Screen.Monitor); onArrange() })
                        DropdownMenuItem(text = { Text("Start fresh") }, onClick = { menu = false; onReset() })
                    }
                }
            }
        }
    }
}

/** Quick on/off for the floating overlays on this screen (phone or DeX each remember their own). */
@Composable
private fun OverlayToggle() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val on by OverlaySettings.on.collectAsState()
    val profile by OverlaySettings.profile.collectAsState()
    BarIcon(
        if (on) Icons.Default.Layers else Icons.Default.LayersClear,
        "Overlays on ${profile.label}: ${if (on) "on" else "off"}",
        {
            OverlaySettings.toggle(context)
            MonitorService.start(context)
        },
        highlighted = on
    )
}

/** Elapsed session time shown beside the clock. */
@Composable
private fun SessionClock(startMs: Long, now: Long) {
    Row(
        Modifier
            .background(MonitorColors.alarm.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .border(1.dp, MonitorColors.alarm.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(8.dp).background(MonitorColors.alarm, CircleShape))
        Text("Session", color = MonitorColors.label, fontSize = 12.sp)
        Text(com.pulsepointlabs.sarahmonitor.session.SessionAnalysis.elapsed(now - startMs), color = MonitorColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Tabs(screen: Screen, compact: Boolean, onScreen: (Screen) -> Unit) {
    Row(Modifier.border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(8.dp))) {
        Screen.entries.forEach { s ->
            val on = s == screen
            val tint = if (on) MonitorColors.background else MonitorColors.label
            Box(
                Modifier
                    .background(if (on) MonitorColors.label else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onScreen(s) }
                    .padding(horizontal = if (compact) 10.dp else 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                if (compact) {
                    Icon(
                        when (s) {
                            Screen.Monitor -> Icons.Default.MonitorHeart
                            Screen.Sessions -> Icons.Default.History
                            Screen.Sensors -> Icons.Default.Bluetooth
                            Screen.Settings -> Icons.Default.Settings
                        },
                        contentDescription = s.label, tint = tint, modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(s.label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RecordButton(recording: com.pulsepointlabs.sarahmonitor.session.SessionEntity?, now: Long, compact: Boolean, onClick: () -> Unit) {
    val active = recording != null
    Row(
        Modifier
            .background(if (active) MonitorColors.alarm else Color.Transparent, RoundedCornerShape(8.dp))
            .border(1.dp, MonitorColors.alarm, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (active) "■ " + com.pulsepointlabs.sarahmonitor.session.SessionAnalysis.elapsed(now - recording!!.startTimeMs) else if (compact) "● Rec" else "● Record",
            color = if (active) Color.White else MonitorColors.alarm,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = if (active) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun MarkButton(enabled: Boolean, compact: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MonitorColors.warn else MonitorColors.dim
    Text(
        if (compact) "★" else "★ Mark",
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, highlighted: Boolean = false) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp).then(if (highlighted) Modifier.background(MonitorColors.pelvic.copy(alpha = 0.2f), CircleShape) else Modifier)
    ) {
        Icon(icon, contentDescription = label, tint = if (highlighted) MonitorColors.pelvic else MonitorColors.label)
    }
}

@Composable
private fun DevicePill(label: String, state: ConnectionState, onClick: () -> Unit) {
    val color = when (state) {
        ConnectionState.Connected, ConnectionState.Receiving, ConnectionState.WaitingForCuff, ConnectionState.ReadingSaved -> MonitorColors.good
        ConnectionState.Connecting, ConnectionState.Reconnecting, ConnectionState.Scanning, ConnectionState.Stale -> MonitorColors.warn
        ConnectionState.NotConfigured -> MonitorColors.dim
        else -> MonitorColors.alarm
    }
    Row(
        Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, color = MonitorColors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CardsDialog(
    slots: List<com.pulsepointlabs.sarahmonitor.ui.CardSlot>,
    onToggle: (CardType, Boolean) -> Unit,
    onResetLayout: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MonitorColors.panel,
        title = { Text("Cards", color = MonitorColors.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Choose which cards show. On a big screen (DeX, tablet) use Arrange to move and resize them.", color = MonitorColors.label, fontSize = 13.sp)
                CardType.entries.forEach { type ->
                    val on = slots.firstOrNull { it.type == type }?.visible ?: false
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = { onToggle(type, it) })
                        Text(type.title, color = MonitorColors.text)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onResetLayout) { Text("Default layout") } }
    )
}

@Composable
private fun AddGlucoseDialog(onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    var mmol by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MonitorColors.panel,
        title = { Text("Add glucose reading", color = MonitorColors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(if (mmol) "mmol/L" else "mg/dL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = mmol, onCheckedChange = { mmol = it })
                    Text("Value is in mmol/L", color = MonitorColors.label)
                }
                Text("Saved with the current time.", color = MonitorColors.label, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val number = value.toDoubleOrNull()
                val mgDl = number?.let { if (mmol) it * 18.0182 else it }
                if (mgDl == null || mgDl < 20 || mgDl > 700) {
                    onMessage("Enter a glucose value between 20 and 700 mg/dL.")
                } else {
                    MonitorHub.glucose.addAll(listOf(GlucoseEntry(System.currentTimeMillis(), Math.round(mgDl * 10) / 10.0, "Manual")))
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
