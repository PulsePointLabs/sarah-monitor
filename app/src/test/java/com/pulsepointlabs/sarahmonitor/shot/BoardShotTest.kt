package com.pulsepointlabs.sarahmonitor.shot

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pulsepointlabs.sarahmonitor.core.BpEntry
import com.pulsepointlabs.sarahmonitor.core.HeartSnapshot
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.core.PelvicSnapshot
import com.pulsepointlabs.sarahmonitor.core.StressComponent
import com.pulsepointlabs.sarahmonitor.core.StressReading
import com.pulsepointlabs.sarahmonitor.hrv.PhysiologicalStressReading
import com.pulsepointlabs.sarahmonitor.ui.Dashboard
import com.pulsepointlabs.sarahmonitor.ui.FitLayout
import com.pulsepointlabs.sarahmonitor.ui.GridLayoutEngine
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Renders the monitor boards with realistic readings at phone portrait, phone landscape and DeX sizes, so
 * layout problems (overlap, clipping, stranded chips, tiny buttons) can be seen before a release.
 * Run: ./gradlew :app:testDebugUnitTest -Pshots --tests "*ShotTest*"  → PNGs in app/build/shots.
 * To check another screen, add a shot(...) call with that composable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BoardShotTest {
    private val dir = File(System.getProperty("shots.dir") ?: "build/shots")

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(name: String) = MonitorHub::class.java.getDeclaredField(name).apply { isAccessible = true }.get(MonitorHub) as MutableStateFlow<T>

    /** Sample readings like a real session (values from the user's own screenshots). */
    private fun feed() {
        val fresh = System.currentTimeMillis() + 600_000L // stays "live" for the whole run
        flow<HeartSnapshot>("_heart").value = HeartSnapshot(heartRate = 119, averageHr = 118.0, minHr = 83, maxHr = 167, rmssdMs = 74.2, sdnnMs = 59.4, pnn50Percent = 44.0,
            hrvQuality = "Poor", rrCoveragePercent = 100.0, latestRrMs = 505, load = PhysiologicalStressReading(), updatedAtMs = fresh)
        flow<StressReading>("_stress").value = StressReading(score = 24, breathsPerMin = 24.0, components = listOf(
            StressComponent("hrv", "HRV", 0, ""), StressComponent("heart", "Heart", 44, ""), StressComponent("breath", "Breathing", 80, ""),
            StressComponent("tension", "Tension", 0, ""), StressComponent("restless", "Restless", 13, "")), updatedAtMs = fresh)
        flow<PelvicSnapshot>("_pelvic").value = PelvicSnapshot(pressureKpa = 3.0, aboveRestKpa = 1.4, average30s = -21.2, max30s = -16.8, sessionMax = 5.6,
            pulsesLastMinute = 1, message = "Sensor disconnected. Check pairing.")
        val t = System.currentTimeMillis()
        MonitorHub.bp.addAll(listOf(BpEntry(t - 4 * 3_600_000L, 148, 90, 92, false, "test", "arm", "sitting"), BpEntry(t - 3_600_000L, 127, 81, 94, false, "test", "arm", "sitting")))
    }

    private fun shot(name: String, wDp: Int, hDp: Int, content: @Composable () -> Unit) {
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val d = a.resources.displayMetrics.density
        a.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = MonitorColors.background, surface = MonitorColors.panel)) {
                // The real app provides card actions (buttons); without them the shots would hide controls.
                androidx.compose.runtime.CompositionLocalProvider(com.pulsepointlabs.sarahmonitor.ui.LocalCardActions provides com.pulsepointlabs.sarahmonitor.ui.CardActions()) {
                    Box(Modifier.size(wDp.dp, hDp.dp).background(MonitorColors.background).padding(6.dp)) { content() }
                }
            }
        }
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400)) }
        val v = a.window.decorView
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(b))
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun boards() {
        MonitorHub.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        feed()
        shot("monitor-phone", 411, 780) { Dashboard(FitLayout.monitorDefaults, false, {}, {}, Modifier.fillMaxSize(), compact = true) }
        shot("response-phone", 411, 780) { Dashboard(FitLayout.responseDefaults, false, {}, {}, Modifier.fillMaxSize(), compact = true) }
        shot("response-landscape", 891, 340) { Dashboard(FitLayout.responseDefaults, false, {}, {}, Modifier.fillMaxSize(), compact = true) }
        shot("monitor-landscape", 891, 340) { Dashboard(FitLayout.monitorDefaults, false, {}, {}, Modifier.fillMaxSize(), compact = true) }
        shot("monitor-dex", 1360, 700) { Dashboard(GridLayoutEngine.defaultLayout, false, {}, {}, Modifier.fillMaxSize(), compact = false) }
        pelvicStates()
        kegel()
        howl()
        overlays()
        eventPanel()
    }

    /** Two minutes of live history climbing then settling, so trends, colors and arrows show. */
    private fun feedHistory() {
        val now = System.currentTimeMillis() + 600_000L
        fun series(f: (Int) -> Double) = (0 until 120).map { i -> com.pulsepointlabs.sarahmonitor.core.TimedValue(now - (120 - i) * 1000L, f(i)) }
        flow<com.pulsepointlabs.sarahmonitor.core.RecentHistory>("_recent").value = com.pulsepointlabs.sarahmonitor.core.RecentHistory(mapOf(
            com.pulsepointlabs.sarahmonitor.core.Metric.Hr to series { i -> 84.0 + i * 0.35 + kotlin.math.sin(i / 4.0) * 3 },
            com.pulsepointlabs.sarahmonitor.core.Metric.Rmssd to series { i -> 60.0 - i * 0.35 + kotlin.math.sin(i / 5.0) * 4 },
            com.pulsepointlabs.sarahmonitor.core.Metric.Sdnn to series { i -> 55.0 - i * 0.2 + kotlin.math.sin(i / 6.0) * 3 },
            com.pulsepointlabs.sarahmonitor.core.Metric.Pnn50 to series { i -> 40.0 - i * 0.25 },
            com.pulsepointlabs.sarahmonitor.core.Metric.Load to series { i -> 20.0 + i * 0.45 },
            com.pulsepointlabs.sarahmonitor.core.Metric.Stress to series { i -> 15.0 + i * 0.5 + kotlin.math.sin(i / 3.0) * 4 },
            com.pulsepointlabs.sarahmonitor.core.Metric.Pelvic to series { i -> 0.3 + (if (i % 20 < 6) 2.5 else 0.2) + i * 0.01 }
        ))
    }

    /** The Mark event panel for a general session and an exploration session, phone and DeX. */
    private fun eventPanel() {
        val now = System.currentTimeMillis()
        listOf(com.pulsepointlabs.sarahmonitor.session.SessionCategory.General to "general", com.pulsepointlabs.sarahmonitor.session.SessionCategory.Exploration to "exploration").forEach { (cat, n) ->
            shot("event-$n-phone", 411, 891) { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { com.pulsepointlabs.sarahmonitor.review.MarkEventDialog(now, onSave = { _, _, _ -> }, onDismiss = {}, inOverlay = true, category = cat) } }
        }
        shot("event-general-dex", 1360, 700) { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { com.pulsepointlabs.sarahmonitor.review.MarkEventDialog(now, onSave = { _, _, _ -> }, onDismiss = {}, inOverlay = true, category = com.pulsepointlabs.sarahmonitor.session.SessionCategory.General) } }
    }

    /** Overlays as they float: full cards, small cards, and the compact strips (every kind at three small sizes). */
    private fun overlays() {
        feedHistory()
        val kinds = com.pulsepointlabs.sarahmonitor.overlay.OverlayKind.entries.filter { it.card != null }
        fun grid(name: String, wDp: Int, hDp: Int, cellW: Int, cellH: Int) = shot(name, wDp, hDp) {
            androidx.compose.runtime.CompositionLocalProvider(com.pulsepointlabs.sarahmonitor.ui.LocalOverlayMode provides true, com.pulsepointlabs.sarahmonitor.ui.LocalCardActions provides null) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    kinds.forEach { k ->
                        Box(Modifier.size(cellW.dp, cellH.dp)) {
                            if (cellW < com.pulsepointlabs.sarahmonitor.overlay.TinyOverlaySize.MAX_WIDTH || cellH < com.pulsepointlabs.sarahmonitor.overlay.TinyOverlaySize.MAX_HEIGHT)
                                com.pulsepointlabs.sarahmonitor.overlay.TinyOverlay(k, Modifier.fillMaxSize())
                            else com.pulsepointlabs.sarahmonitor.ui.MonitorCard(k.card!!, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        grid("overlay-tiny-76x44", 411, 200, 76, 44)
        grid("overlay-tiny-150x60", 411, 260, 150, 60)
        grid("overlay-tiny-160x100", 411, 400, 160, 100)
        grid("overlay-small-200x130", 411, 480, 200, 130)
        grid("overlay-cards-phone", 411, 900, 260, 180)
        grid("overlay-cards-dex", 1360, 700, 320, 220)
    }
    /** Howl connected and playing: the full controls and the response board with its card. */
    private fun howl() {
        val now = System.currentTimeMillis() + 600_000L
        com.pulsepointlabs.sarahmonitor.howl.Howl.state.value = com.pulsepointlabs.sarahmonitor.howl.HowlState(connected = true, powerA = 34, powerB = 71, playing = true,
            activity = com.pulsepointlabs.sarahmonitor.howl.HowlActivity.MILKMASTER, updatedAtMs = now)
        com.pulsepointlabs.sarahmonitor.howl.Howl.history.value = (0 until 120).map { i -> Triple(now - (120 - i) * 1000L, 20 + i / 6, 40 + (i / 4)) }
        shot("howl-panel-phone", 411, 891) { com.pulsepointlabs.sarahmonitor.howl.HowlPanel(Modifier.fillMaxSize()) }
        shot("howl-panel-dex", 1360, 700) { com.pulsepointlabs.sarahmonitor.howl.HowlPanel(Modifier.fillMaxSize()) }
        shot("howl-response-phone", 411, 780) { Dashboard(FitLayout.responseDefaults, false, {}, {}, Modifier.fillMaxSize(), compact = true) }
    }

    /** A synthetic 6-rep hold set (target 70 %, last reps fading) for the Kegel review and the live Detail mode. */
    private fun kegel() {
        val ref = 4.0
        val start = System.currentTimeMillis() - 70_000L
        val session = com.pulsepointlabs.sarahmonitor.kegel.KegelSession(com.pulsepointlabs.sarahmonitor.kegel.KegelRoutine.Holds, 6, 5.0, 5.0, start)
        val windows = session.stepWindows().filter { it.first.targetPct != null }
        fun pct(t: Long): Double {
            val w = windows.indexOfFirst { t in it.second..it.third }
            if (w < 0) return 4.0 + 3 * kotlin.math.sin(t / 700.0)
            val strength = if (w < 4) 82.0 else 82.0 - (w - 3) * 18
            val into = (t - windows[w].second) / 1000.0
            return strength * (1 - kotlin.math.exp(-into * 3)) + 3 * kotlin.math.sin(t / 300.0)
        }
        val pts = (0 until 700).map { i -> val t = start + i * 100L; com.pulsepointlabs.sarahmonitor.kegel.KegelPoint(t, pct(t)) }
        val result = session.result(start + 70_000L)
        val rec = com.pulsepointlabs.sarahmonitor.kegel.KegelRecord.fromJson("shot", session.toJson(result, false, ref, 0.3), start + 70_000L)!!
            .copy(score = 74, strengthPct = 71, relaxPct = 83, repScores = (1..6).map { Triple(it, if (it < 5) 92 else 40, if (it < 5) 80 else 55) })
        shot("kegel-review-phone", 411, 891) { com.pulsepointlabs.sarahmonitor.kegel.KegelSetReviewContent(rec, pts) {} }
        shot("kegel-review-dex", 1360, 700) { com.pulsepointlabs.sarahmonitor.kegel.KegelSetReviewContent(rec, pts) {} }
        // Live Detail mode, 52 s into the same set.
        val now = start + 52_000L
        val trace = pts.filter { it.t <= now }.map { com.pulsepointlabs.sarahmonitor.core.PelvicPoint(it.t, 6.0 + it.v / 100 * ref, it.v / 100 * ref, false) }
        val snap = PelvicSnapshot(pressureKpa = trace.last().pressureKpa, aboveRestKpa = trace.last().aboveRestKpa, restKpa = 6.0, referenceKpa = ref, trace = trace, logging = true,
            quality = com.pulsepointlabs.sarahmonitor.core.PressureQuality.Valid)
        pts.filter { it.t <= now }.forEach { session.sample(it.t, it.v / 100 * ref, ref, 0.3) }
        shot("kegel-detail-phone", 411, 780) { com.pulsepointlabs.sarahmonitor.kegel.DetailTrainer(session, now, snap, com.pulsepointlabs.sarahmonitor.kegel.KegelRoutine.Holds, {}, {}, Modifier.fillMaxSize()) }
        shot("kegel-detail-dex", 1360, 700) { com.pulsepointlabs.sarahmonitor.kegel.DetailTrainer(session, now, snap, com.pulsepointlabs.sarahmonitor.kegel.KegelRoutine.Holds, {}, {}, Modifier.fillMaxSize()) }
    }

    /** The pressure card while previewing (logging off) and during a suspected displacement, with live pressure. */
    private fun pelvicStates() {
        @Suppress("UNCHECKED_CAST")
        val civetState = com.pulsepointlabs.sarahmonitor.ble.CivetClient::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(MonitorHub.civet) as MutableStateFlow<com.pulsepointlabs.sarahmonitor.ble.ConnectionState>
        civetState.value = com.pulsepointlabs.sarahmonitor.ble.ConnectionState.Connected
        val live = System.currentTimeMillis() + 600_000L
        val trace = (0 until 200).map { i ->
            val t = live - (200 - i) * 100L
            com.pulsepointlabs.sarahmonitor.core.PelvicPoint(t, 6.0 + 0.4 * kotlin.math.sin(i / 6.0), 0.4 * kotlin.math.sin(i / 6.0), false)
        }
        flow<PelvicSnapshot>("_pelvic").value = PelvicSnapshot(pressureKpa = 6.2, aboveRestKpa = 0.2, restKpa = 6.0,
            restSource = com.pulsepointlabs.sarahmonitor.core.RestSource.Auto, trace = trace, logging = false,
            quality = com.pulsepointlabs.sarahmonitor.core.PressureQuality.Preview)
        shot("pelvic-preview-phone", 411, 780) { Dashboard(FitLayout.monitorDefaults, false, {}, {}, Modifier.fillMaxSize(), compact = true) }
        shot("pelvic-preview-landscape", 891, 340) { Dashboard(FitLayout.monitorDefaults, false, {}, {}, Modifier.fillMaxSize(), compact = true) }
        shot("pelvic-preview-dex", 1360, 700) { Dashboard(GridLayoutEngine.defaultLayout, false, {}, {}, Modifier.fillMaxSize(), compact = false) }
        flow<PelvicSnapshot>("_pelvic").value = PelvicSnapshot(pressureKpa = 0.9, aboveRestKpa = -5.1, restKpa = 6.0,
            restSource = com.pulsepointlabs.sarahmonitor.core.RestSource.Captured, trace = trace, logging = true, sessionMax = 4.2, pulseCount = 7,
            quality = com.pulsepointlabs.sarahmonitor.core.PressureQuality.SuspectedDisplacement,
            message = "Possible CIVET sensor displacement. Check the probe, then capture rest or tap Resume.")
        shot("pelvic-fault-phone", 411, 780) { Dashboard(FitLayout.monitorDefaults, false, {}, {}, Modifier.fillMaxSize(), compact = true) }
        shot("pelvic-fault-landscape", 891, 340) { Dashboard(FitLayout.monitorDefaults, false, {}, {}, Modifier.fillMaxSize(), compact = true) }
        shot("pelvic-fault-dex", 1360, 700) { Dashboard(GridLayoutEngine.defaultLayout, false, {}, {}, Modifier.fillMaxSize(), compact = false) }
    }
}
