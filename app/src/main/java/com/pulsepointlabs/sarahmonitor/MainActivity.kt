package com.pulsepointlabs.sarahmonitor

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.MonitorWeight
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
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // The EMG Arduino was plugged in (phone or DeX dock).
            MonitorHub.emgClient.connect()
            return
        }
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

    /** Pulse-ox CSVs (EMAY etc.) go to SpO2; everything else to the glucose importer. */
    private fun importSpO2(text: String, source: String): Boolean {
        if (com.pulsepointlabs.sarahmonitor.vitals.VitalCsv.classify(text, source) != "spo2") return false
        val r = com.pulsepointlabs.sarahmonitor.vitals.VitalCsv.parseSpO2(text, source)
        if (r.error != null) toast(r.error) else {
            val added = MonitorHub.spo2.addAll(r.rows)
            toast("Imported $added new SpO2 readings${if (r.skipped > 0) " (${r.skipped} rows skipped)" else ""}.")
        }
        return true
    }

    private fun importText(text: String, source: String) {
        if (importSpO2(text, source)) return
        val result = GlucoseCsv.parse(text, source)
        if (result.error != null) toast(result.error) else {
            val added = MonitorHub.glucose.addAll(result.rows)
            toast("Imported $added new glucose readings${if (result.skipped > 0) " (${result.skipped} rows skipped)" else ""}.")
        }
    }

    // Overlays hide while the app is on screen. Phone: whenever it's resumed. Multi-window (DeX, split screen):
    // only while it's the active window, so they come back when you click into another app.
    private var resumedNow = false
    private var topResumed = true
    private fun updateAppInFront() = OverlaySettings.setAppInFront(resumedNow && (topResumed || !isInMultiWindowMode))

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        topResumed = isTopResumedActivity
        updateAppInFront()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        updateAppInFront()
    }

    override fun onPause() {
        super.onPause()
        resumedNow = false
        updateAppInFront()
    }

    override fun onStop() {
        super.onStop()
        resumedNow = false
        OverlaySettings.setAppInFront(false)
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        updateAppInFront()
        OverlayDisplay.recordActivityDisplay(this)
        MonitorHub.connectRemembered()
        // While on screen, let the service (re)claim microphone use for voice notes from the floating panel.
        MonitorService.start(this)
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
            add(Manifest.permission.RECORD_AUDIO)
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
            val o = MonitorHub.spo2.addAll(runCatching { healthImporter.readSpO2() }.getOrDefault(emptyList()))
            val w = MonitorHub.body.addAll(runCatching { healthImporter.readBody() }.getOrDefault(emptyList()))
            val g = MonitorHub.glucose.addAll(glucose)
            val b = MonitorHub.bp.addAll(bp)
            toast("Health Connect: $g glucose, $b BP, $o SpO2, $w weigh-ins new.")
        }
    }

    private fun importCsv(uri: Uri) {
        kotlinx.coroutines.MainScope().launch {
            val file = withContext(Dispatchers.IO) {
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
                    GlucoseCsv.decode(bytes) to displayName(uri)
                }.getOrNull()
            }
            if (file == null) { toast("Couldn't read that file."); return@launch }
            val (text, name) = file
            if (importSpO2(text, name)) return@launch
            val result = GlucoseCsv.parse(text, name)
            if (result.error != null) toast(result.error) else {
                val added = MonitorHub.glucose.addAll(result.rows)
                toast("Imported $added new glucose readings${if (result.skipped > 0) " (${result.skipped} rows skipped)" else ""}.")
            }
        }
    }
}

internal enum class Screen(val label: String) { Monitor("Monitor"), Response("Response"), Kegel("Kegels"), Health("Health"), Sessions("Sessions"), Sensors("Sensors"), Settings("Settings") }

@Composable
private fun MonitorScreen(
    layoutStore: LayoutStore,
    onCsvImport: () -> Unit,
    onHealthConnect: () -> Unit,
    onExportCsv: (String, String) -> Unit,
    onMessage: (String) -> Unit
) {
    var slots by remember { mutableStateOf(layoutStore.load()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val responseStore = remember { LayoutStore(context, "response_v1", GridLayoutEngine.responseLayout) }
    var responseSlots by remember { mutableStateOf(responseStore.load()) }
    // Phones (portrait or landscape) have their own card sets, laid out to fit one screen.
    val compact = com.pulsepointlabs.sarahmonitor.ui.isCompactWindow()
    val fitStore = remember { LayoutStore(context, "fit_monitor_v1", com.pulsepointlabs.sarahmonitor.ui.FitLayout.monitorDefaults) }
    var fitSlots by remember { mutableStateOf(fitStore.load()) }
    val fitResponseStore = remember { LayoutStore(context, "fit_response_v1", com.pulsepointlabs.sarahmonitor.ui.FitLayout.responseDefaults) }
    var fitResponseSlots by remember { mutableStateOf(fitResponseStore.load()) }
    var arranging by remember { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf(Screen.Monitor) }
    var reviewId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCards by remember { mutableStateOf(false) }
    var showAddGlucose by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var chooseCategory by remember { mutableStateOf(false) }
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
                onRecord = { if (session == null) chooseCategory = true else confirmStop = true },
                onMark = ::mark,
                onDevices = { screen = Screen.Sensors; reviewId = null },
                onCards = { showCards = true },
                onOverlays = { screen = Screen.Settings; reviewId = null },
                onArrange = { arranging = !arranging },
                onReset = { confirmReset = true }
            )
            Spacer(Modifier.size(8.dp))
            val openReview = reviewId
            when {
                openReview != null -> ReviewScreen(
                    sessionId = openReview,
                    onBack = { reviewId = null },
                    onExportCsv = onExportCsv,
                    onMessage = onMessage,
                    modifier = Modifier.fillMaxSize()
                )
                screen == Screen.Response -> com.pulsepointlabs.sarahmonitor.response.ResponseScreen(
                    slots = if (compact) fitResponseSlots else responseSlots,
                    arranging = arranging,
                    onArrangingChange = { arranging = it },
                    onChange = { responseSlots = it; responseStore.save(it) },
                    modifier = Modifier.fillMaxSize(),
                    compact = compact
                )
                screen == Screen.Kegel -> com.pulsepointlabs.sarahmonitor.kegel.KegelScreen(Modifier.fillMaxSize())
                screen == Screen.Health -> com.pulsepointlabs.sarahmonitor.vitals.HealthScreen(onCsvImport, onHealthConnect, Modifier.fillMaxSize()) { m -> com.pulsepointlabs.sarahmonitor.sleep.SleepScreen(m) }
                screen == Screen.Sessions -> SessionsScreen(onOpen = { reviewId = it }, modifier = Modifier.fillMaxSize())
                screen == Screen.Sensors -> SensorsScreen(onHealthConnect, onCsvImport, onMessage, Modifier.fillMaxSize())
                screen == Screen.Settings -> SettingsScreen(onMessage, Modifier.fillMaxSize())
                else -> Dashboard(
                    slots = if (compact) fitSlots else slots,
                    arranging = arranging,
                    onArrangingChange = { arranging = it },
                    onChange = { slots = it; layoutStore.save(it) },
                    modifier = Modifier.fillMaxSize(),
                    compact = compact
                )
            }
        }
    }
    markAt?.let { t ->
        MarkEventDialog(
            pressedAtMs = t,
            onSave = { types, note, checkIn ->
                MonitorHub.markEvent(t, types, note, checkIn) { ok -> if (!ok) onMessage("Not recording; event not saved.") }
                markAt = null
            },
            onDismiss = { markAt = null }
        )
    }
    if (chooseCategory) StartSessionDialog(onStart = { cat -> chooseCategory = false; MonitorHub.startSession(cat) }, onDismiss = { chooseCategory = false })
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
        if (compact && screen == Screen.Response) CardsDialog(
            slots = fitResponseSlots,
            types = GridLayoutEngine.responseLayout.map { it.type },
            onToggle = { type, on -> fitResponseSlots = GridLayoutEngine.setVisible(fitResponseSlots, type, on); fitResponseStore.save(fitResponseSlots) },
            onResetLayout = { fitResponseSlots = com.pulsepointlabs.sarahmonitor.ui.FitLayout.responseDefaults; fitResponseStore.save(fitResponseSlots) },
            onDismiss = { showCards = false }
        ) else if (compact) CardsDialog(
            slots = fitSlots,
            types = GridLayoutEngine.defaultLayout.map { it.type },
            onToggle = { type, on -> fitSlots = GridLayoutEngine.setVisible(fitSlots, type, on); fitStore.save(fitSlots) },
            onResetLayout = { fitSlots = com.pulsepointlabs.sarahmonitor.ui.FitLayout.monitorDefaults; fitStore.save(fitSlots) },
            onDismiss = { showCards = false }
        ) else if (screen == Screen.Response) CardsDialog(
            slots = responseSlots,
            types = GridLayoutEngine.responseLayout.map { it.type },
            onToggle = { type, on -> responseSlots = GridLayoutEngine.setVisible(responseSlots, type, on); responseStore.save(responseSlots) },
            onResetLayout = { responseSlots = GridLayoutEngine.responseLayout; responseStore.save(responseSlots) },
            onDismiss = { showCards = false }
        ) else CardsDialog(
            slots = slots,
            types = GridLayoutEngine.defaultLayout.map { it.type },
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
internal fun TopBar(
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
        // Phone-sized windows use the fit-to-screen board (no free arranging).
        val fit = com.pulsepointlabs.sarahmonitor.ui.isCompactWindow()
        val moreMenu: @Composable () -> Unit = {
            Box {
                BarIcon(Icons.Default.MoreVert, "More", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MonitorColors.panel) {
                    val board = screen == Screen.Response
                    DropdownMenuItem(text = { Text("Cards") }, onClick = { menu = false; if (!board) onScreen(Screen.Monitor); onCards() })
                    if (!fit) DropdownMenuItem(text = { Text(if (arranging) "Done arranging" else "Arrange cards") }, onClick = { menu = false; if (!board) onScreen(Screen.Monitor); onArrange() })
                    DropdownMenuItem(text = { Text("Start fresh") }, onClick = { menu = false; onReset() })
                }
            }
        }
        if (narrow) {
            // Phone portrait: tabs get the full width on top; clock, record, mark and toggles get their own row.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Tabs(screen, compact = true, fill = true, onScreen = onScreen)
                Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(clockFormat), color = MonitorColors.label, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    RecordButton(recording, now, compact = false, onClick = onRecord)
                    MarkButton(enabled = recording != null, compact = false, onClick = onMark)
                    OverlayToggle()
                    moreMenu()
                }
            }
            return@BoxWithConstraints
        }
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (full) Text("Sarah Monitor", color = MonitorColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(clockFormat), color = MonitorColors.label, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            if (full) recording?.let { SessionClock(it.startTimeMs, now) }
            Tabs(screen, compact = !full, onScreen = onScreen)
            Spacer(Modifier.weight(1f))
            RecordButton(recording, now, compact = false, onClick = onRecord)
            MarkButton(enabled = recording != null, compact = !full, onClick = onMark)
            OverlayToggle()
            if (full) {
                DevicePill("H10", h10, onDevices)
                DevicePill("CIVET", civet, onDevices)
                DevicePill("BP", omron, onDevices)
                if (screen == Screen.Monitor || screen == Screen.Response) {
                    BarIcon(Icons.Default.Dashboard, "Cards", onCards)
                    BarIcon(Icons.Default.OpenWith, if (arranging) "Done arranging" else "Arrange cards", onArrange, highlighted = arranging)
                    BarIcon(Icons.Default.RestartAlt, "Start fresh", onReset)
                }
            } else moreMenu()
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
private fun Tabs(screen: Screen, compact: Boolean, fill: Boolean = false, onScreen: (Screen) -> Unit) {
    Row((if (fill) Modifier.fillMaxWidth() else Modifier).border(1.dp, MonitorColors.panelBorder, RoundedCornerShape(8.dp))) {
        Screen.entries.forEach { s ->
            val on = s == screen
            val tint = if (on) MonitorColors.background else MonitorColors.label
            Box(
                (if (fill) Modifier.weight(1f) else Modifier)
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
                            Screen.Response -> Icons.Default.Favorite
                            Screen.Kegel -> Icons.Default.SelfImprovement
                            Screen.Health -> Icons.Default.MonitorWeight
                            Screen.Sensors -> Icons.Default.Bluetooth
                            Screen.Settings -> Icons.Default.Settings
                        },
                        contentDescription = s.label, tint = tint, modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(s.label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

private val BAR_BUTTON_H = 32.dp

@Composable
private fun RecordButton(recording: com.pulsepointlabs.sarahmonitor.session.SessionEntity?, now: Long, compact: Boolean, onClick: () -> Unit) {
    val active = recording != null
    Row(
        Modifier
            .height(BAR_BUTTON_H)
            .background(if (active) MonitorColors.alarm else Color.Transparent, RoundedCornerShape(8.dp))
            .border(1.dp, MonitorColors.alarm, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
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
    Box(
        Modifier
            .height(BAR_BUTTON_H)
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(if (compact) "★" else "★ Mark", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, highlighted: Boolean = false) {
    // Same height and corner as the Record / Mark buttons; the highlight stays inside its own box.
    Box(
        Modifier
            .size(BAR_BUTTON_H)
            .then(
                if (highlighted) Modifier.background(MonitorColors.pelvic.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                    .border(1.dp, MonitorColors.pelvic.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (highlighted) MonitorColors.pelvic else MonitorColors.label, modifier = Modifier.size(18.dp))
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
    types: List<CardType>,
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
                types.forEach { type ->
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


/** Pick what this recording is; tap to start. The last choice is highlighted. */
@Composable
private fun StartSessionDialog(onStart: (com.pulsepointlabs.sarahmonitor.session.SessionCategory) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val last = remember { com.pulsepointlabs.sarahmonitor.session.SessionCategory.last(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MonitorColors.panel,
        title = { Text("Start recording", color = MonitorColors.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.pulsepointlabs.sarahmonitor.session.SessionCategory.entries.filter { it.pickable }.forEach { c ->
                    val on = c == last
                    val color = if (c.sexual && c != com.pulsepointlabs.sarahmonitor.session.SessionCategory.Hybrid) Color(0xFFFF7AB6) else if (c == com.pulsepointlabs.sarahmonitor.session.SessionCategory.Hybrid) MonitorColors.pnn50 else MonitorColors.hr
                    Text(
                        "● " + c.label, color = if (on) MonitorColors.background else color, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (on) color else color.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .clickable { onStart(c) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
