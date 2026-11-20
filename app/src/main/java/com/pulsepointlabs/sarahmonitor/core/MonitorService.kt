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
import com.pulsepointlabs.sarahmonitor.overlay.OverlayManager
import com.pulsepointlabs.sarahmonitor.overlay.OverlaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the sensor links alive while Sarah Monitor is in the background or minimised on DeX, and hosts the
 * floating overlays.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlays: OverlayManager

    override fun onCreate() {
        super.onCreate()
        MonitorHub.init(this)
        OverlaySettings.load(this)
        overlays = OverlayManager(this)
        createChannel()
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification("Monitoring sensors"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
        }.isSuccess
        if (!started) {
            // Android refuses a connected-device service without Bluetooth permission; try again after it's granted.
            stopSelf()
            return
        }
        MonitorHub.connectRemembered()
        scope.launch {
            OverlaySettings.enabled.collect { overlays.sync(it) }
        }
        // Follow the app between the phone screen and DeX, and pick up a newly granted overlay permission.
        scope.launch {
            while (isActive) {
                delay(1_500L)
                overlays.sync(OverlaySettings.enabled.value)
            }
        }
        scope.launch {
            combine(MonitorHub.heart, MonitorHub.pelvic) { h, p -> h to p }.collect { (h, p) ->
                val parts = listOfNotNull(
                    h.heartRate?.let { "HR $it" },
                    p.aboveRestKpa?.let { "Pelvic ${"%.1f".format(it)} kPa" },
                    MonitorHub.bp.items.value.lastOrNull()?.let { "BP ${it.systolic}/${it.diastolic}" }
                )
                val text = parts.joinToString(" · ").ifBlank { "Monitoring sensors" }
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
                delay(5_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            overlays.hideAll()
            stopSelf()
            return START_NOT_STICKY
        }
        MonitorHub.connectRemembered()
        return START_STICKY
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("Sarah Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "com.pulsepointlabs.sarahmonitor.STOP"

        fun start(context: Context) {
            val bluetoothGranted = Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!bluetoothGranted) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
        }
    }
}
