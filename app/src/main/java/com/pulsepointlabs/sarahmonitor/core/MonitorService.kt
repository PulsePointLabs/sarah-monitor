package com.pulsepointlabs.sarahmonitor.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pulsepointlabs.sarahmonitor.MainActivity
import com.pulsepointlabs.sarahmonitor.R
import com.pulsepointlabs.sarahmonitor.glucose.HealthConnectImporter
import com.pulsepointlabs.sarahmonitor.overlay.OverlayManager
import com.pulsepointlabs.sarahmonitor.overlay.OverlaySettings
import com.pulsepointlabs.sarahmonitor.overlay.OverlayTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the sensor links alive while Sarah Monitor is in the background or minimized on DeX, and hosts the
 * floating overlays.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastText = "Monitoring sensors"
    private lateinit var overlays: OverlayManager

    override fun onCreate() {
        super.onCreate()
        MonitorHub.init(this)
        OverlaySettings.load(this)
        overlays = OverlayManager(this)
        createChannel()
        val started = goForeground()
        if (!started) {
            // Android refuses a connected-device service without Bluetooth permission; try again after it's granted.
            stopSelf()
            return
        }
        MonitorHub.connectRemembered()
        scope.launch {
            combine(OverlaySettings.showing, OverlaySettings.appInFront, OverlaySettings.previewing, OverlaySettings.lent) { _, _, _, _ -> Unit }.collect { overlays.sync() }
        }
        scope.launch {
            OverlaySettings.on.collect {
                OverlayTile.refresh(this@MonitorService)
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(lastText))
            }
        }
        // Follow the app between the phone screen and DeX, and pick up a newly granted overlay permission.
        scope.launch {
            while (isActive) {
                delay(1_500L)
                overlays.sync()
            }
        }
        // Glucose meters such as the OneTouch Verio Reflect sync to their own app; when that app shares to
        // Health Connect, new readings are picked up here every 2 minutes (BP from Omron Connect likewise).
        scope.launch {
            val health = HealthConnectImporter(this@MonitorService)
            while (isActive) {
                runCatching {
                    if (health.hasPermissions()) {
                        MonitorHub.glucose.addAll(health.readGlucose(days = 2))
                        MonitorHub.bp.addAll(health.readBloodPressure(days = 2))
                    }
                    MonitorHub.spo2.addAll(health.readSpO2(days = 2))
                    MonitorHub.body.addAll(health.readBody(days = 7))
                }
                delay(120_000L)
            }
        }
        scope.launch {
            combine(MonitorHub.heart, MonitorHub.pelvic, MonitorHub.session) { h, p, _ -> h to p }.collect { (h, p) ->
                val parts = listOfNotNull(
                    h.heartRate?.let { "HR $it" },
                    p.aboveRestKpa?.let { "Pelvic ${com.pulsepointlabs.sarahmonitor.core.Pressure.withUnit(it)}" },
                    MonitorHub.bp.items.value.duringSession(MonitorHub.session.value?.startTimeMs).lastOrNull()?.let { "BP ${it.systolic}/${it.diastolic}" }
                )
                val text = parts.joinToString(" · ").ifBlank { "Monitoring sensors" }
                lastText = text
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
                delay(5_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MARK) {
            MonitorHub.markEvent(System.currentTimeMillis(), setOf("event"), "") { ok ->
                android.os.Handler(mainLooper).post {
                    android.widget.Toast.makeText(this, if (ok) "Event marked — add details in review" else "Not recording", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_TOGGLE_OVERLAYS) {
            OverlaySettings.toggle(this)
            OverlayTile.refresh(this)
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(lastText))
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            overlays.hideAll()
            stopSelf()
            return START_NOT_STICKY
        }
        // Started again while the app is on screen: (re)claim microphone use so voice notes work from overlays.
        goForeground()
        MonitorHub.connectRemembered()
        return START_STICKY
    }

    /**
     * Foreground with Bluetooth (always) plus microphone (when the permission is granted and Android allows it
     * now — it does while the app is on screen). Without the microphone type, Android gives a background app
     * silence instead of audio, which is what broke voice notes in the floating ＋ panel.
     */
    private fun goForeground(): Boolean {
        val connected = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val micGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (micGranted && Build.VERSION.SDK_INT >= 30) {
            val withMic = runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(lastText), connected or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }.isSuccess
            if (withMic) { micReady = true; return true }
        }
        return runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(lastText), connected) }.isSuccess
    }

    override fun onDestroy() {
        overlays.hideAll()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sarah Monitor", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val mark = PendingIntent.getService(
            this, 2, Intent(this, MonitorService::class.java).setAction(ACTION_MARK), PendingIntent.FLAG_IMMUTABLE
        )
        val overlayToggle = PendingIntent.getService(
            this, 3, Intent(this, MonitorService::class.java).setAction(ACTION_TOGGLE_OVERLAYS), PendingIntent.FLAG_IMMUTABLE
        )
        val recording = MonitorHub.session.value
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("Sarah Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (recording != null) addAction(0, "★ Mark event", mark) }
            .setSubText(if (recording != null) "● Recording" else null)
            .addAction(0, if (OverlaySettings.on.value) "Hide overlays" else "Show overlays", overlayToggle)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        /** True once the service holds microphone use (voice notes can record from overlays). */
        @Volatile var micReady = false
        private const val CHANNEL = "monitor"
        private const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "com.pulsepointlabs.sarahmonitor.STOP"
        const val ACTION_MARK = "com.pulsepointlabs.sarahmonitor.MARK"
        const val ACTION_TOGGLE_OVERLAYS = "com.pulsepointlabs.sarahmonitor.TOGGLE_OVERLAYS"

        fun start(context: Context) {
            val bluetoothGranted = Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!bluetoothGranted) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
        }
    }
}
