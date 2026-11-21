package com.pulsepointlabs.sarahmonitor.review

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pulsepointlabs.sarahmonitor.core.MonitorHub
import com.pulsepointlabs.sarahmonitor.ui.MonitorColors

/**
 * Small floating window opened by the ＋ overlay: the same event entry as the app (types, note, voice).
 * Opens on the display the ＋ is on and closes itself when done, leaving whatever was in front untouched.
 */
class MarkEventActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MonitorHub.init(this)
        val pressedAt = intent.getLongExtra(EXTRA_PRESSED_AT, System.currentTimeMillis())
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = MonitorColors.background, surface = MonitorColors.panel, primary = MonitorColors.pelvic)) {
                MarkEventFlow(pressedAt, inOverlay = false, onToast = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }, onDone = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_PRESSED_AT = "pressed_at"
    }
}

/** Event entry flow (start a session first if none is running). [inOverlay]: drawn as a floating panel. */
@Composable
fun MarkEventFlow(pressedAt: Long, inOverlay: Boolean, onToast: (String) -> Unit, onDone: () -> Unit) {
    val session by MonitorHub.session.collectAsState()
    var starting by remember { mutableStateOf(false) }
    val current = session
    when {
        current != null -> MarkEventDialog(
            // If the session was started from this window, the event can't be earlier than the session.
            pressedAtMs = maxOf(pressedAt, current.startTimeMs),
            onSave = { types, note, checkIn ->
                MonitorHub.markEvent(maxOf(pressedAt, current.startTimeMs), types, note, checkIn) { ok ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onToast(if (ok) "Event saved" else "Not recording; event not saved")
                        onDone()
                    }
                }
            },
            onDismiss = onDone,
            inOverlay = inOverlay
        )
        starting -> EventFrame(
            inOverlay, onDone,
            title = { Text("Starting session…", color = MonitorColors.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {},
            confirm = {},
            dismiss = { TextButton(onClick = onDone) { Text("Cancel") } }
        )
        else -> EventFrame(
            inOverlay, onDone,
            title = { Text("Not recording", color = MonitorColors.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = { Text("Start recording and mark this event?", color = MonitorColors.label) },
            confirm = { TextButton(onClick = { starting = true; MonitorHub.startSession() }) { Text("● Start & mark") } },
            dismiss = { TextButton(onClick = onDone) { Text("Cancel") } }
        )
    }
}
