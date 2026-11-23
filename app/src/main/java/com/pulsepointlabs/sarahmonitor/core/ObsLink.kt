package com.pulsepointlabs.sarahmonitor.core

import android.content.Context
import android.content.SharedPreferences
import com.pulsepointlabs.sarahmonitor.session.SessionCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** OBS link settings (obs-websocket 5, built into OBS 28+: Tools → WebSocket Server Settings). */
object ObsSettings {
    private var prefs: SharedPreferences? = null
    val enabled = MutableStateFlow(false)
    val url = MutableStateFlow("ws://192.168.1.10:4455")
    val password = MutableStateFlow("")
    /** End the session when OBS stops recording. */
    val autoStop = MutableStateFlow(true)
    /** Session type used when OBS starts recording with nothing prepared. */
    val category = MutableStateFlow(SessionCategory.Masturbation)

    @Synchronized
    fun load(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("sarahmonitor_obs", Context.MODE_PRIVATE)
        prefs = p
        enabled.value = p.getBoolean("enabled", false)
        url.value = p.getString("url", url.value) ?: url.value
        password.value = p.getString("password", "") ?: ""
        autoStop.value = p.getBoolean("auto_stop", true)
        category.value = SessionCategory.of(p.getString("category", null)) ?: SessionCategory.Masturbation
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) { prefs?.edit()?.apply(block)?.apply() }
    fun setEnabled(v: Boolean) { enabled.value = v; edit { putBoolean("enabled", v) } }
    fun setUrl(v: String) { url.value = v.trim(); edit { putString("url", url.value) } }
    fun setPassword(v: String) { password.value = v; edit { putString("password", v) } }
    fun setAutoStop(v: Boolean) { autoStop.value = v; edit { putBoolean("auto_stop", v) } }
    fun setCategory(v: SessionCategory) { category.value = v; edit { putString("category", v.name) } }

    /** "ws://host:4455" from whatever was typed ("192.168.1.10", "host:4455", "ws://host"). */
    fun normalize(raw: String): String {
        var s = raw.trim().removeSuffix("/")
        if (!s.startsWith("ws://") && !s.startsWith("wss://")) s = "ws://$s"
        val hostPart = s.substringAfter("://")
        if (!hostPart.contains(':')) s += ":4455"
        return s
    }
}

data class ObsStatus(val connected: Boolean = false, val message: String = "Off", val recording: Boolean = false, val paused: Boolean = false)

/**
 * Talks to OBS over obs-websocket 5 and reports recording start / pause / resume / stop. Same rules as SarahVS:
 * STARTING/STOPPING are intentions and are ignored; a recording already running when we connect counts as
 * started at (now − its duration); the connection retries on its own while enabled.
 */
class ObsLink(
    private val scope: CoroutineScope,
    private val onStart: (startedAtMs: Long) -> Unit,
    private val onPause: (atMs: Long) -> Unit,
    private val onResume: (atMs: Long) -> Unit,
    private val onStop: (stoppedAtMs: Long, outputPath: String?) -> Unit
) {
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private val _status = MutableStateFlow(ObsStatus())
    val status: StateFlow<ObsStatus> = _status
    private var socket: WebSocket? = null
    private var retry: Job? = null
    private var attempt = 0
    private var wantedUrl: String? = null
    private var recording = false
    private var paused = false
    private var nextId = 1

    /** Connects / disconnects to match the settings. Safe to call often. */
    @Synchronized
    fun sync() {
        val url = if (ObsSettings.enabled.value) ObsSettings.normalize(ObsSettings.url.value) else null
        if (url == wantedUrl && (socket != null || retry?.isActive == true)) return
        close()
        wantedUrl = url
        if (url == null) { _status.value = ObsStatus(message = "Off"); return }
        connect(url)
    }

    /** Drop and reconnect now (after editing the address / password). */
    @Synchronized
    fun reconnect() { close(); wantedUrl = null; attempt = 0; sync() }

    @Synchronized
    private fun close() {
        retry?.cancel(); retry = null
        socket?.close(1000, null); socket = null
    }

    private fun connect(url: String) {
        _status.value = _status.value.copy(connected = false, message = "Connecting to $url…")
        val request = runCatching { Request.Builder().url(url.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")).build() }.getOrElse {
            _status.value = ObsStatus(message = "That address doesn't look right")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, t.message ?: "can't reach OBS")
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, if (code == 4009) "wrong password" else reason.ifBlank { "connection closed" })
        })
    }

    @Synchronized
    private fun lost(ws: WebSocket, why: String) {
        if (ws !== socket) return
        socket = null
        _status.value = ObsStatus(message = "Not connected: $why", recording = recording, paused = paused)
        val url = wantedUrl ?: return
        val wait = (3_000L shl attempt.coerceAtMost(3)).coerceAtMost(30_000L)
        attempt++
        retry = scope.launch { delay(wait); synchronized(this@ObsLink) { if (wantedUrl == url && socket == null) connect(url) } }
    }

    private fun handle(ws: WebSocket, text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        val d = msg.optJSONObject("d") ?: JSONObject()
        when (msg.optInt("op", -1)) {
            0 -> { // Hello → Identify (with auth if OBS asks for it). 64 = Outputs events.
                val identify = JSONObject().put("rpcVersion", d.optInt("rpcVersion", 1)).put("eventSubscriptions", 64)
                d.optJSONObject("authentication")?.let { a -> identify.put("authentication", auth(ObsSettings.password.value, a.optString("salt"), a.optString("challenge"))) }
                ws.send(JSONObject().put("op", 1).put("d", identify).toString())
            }
            2 -> { // Identified
                attempt = 0
                _status.value = ObsStatus(connected = true, message = "Connected", recording = recording, paused = paused)
                ws.send(JSONObject().put("op", 6).put("d", JSONObject().put("requestType", "GetRecordStatus").put("requestId", "status-${nextId++}")).toString())
            }
            5 -> if (d.optString("eventType") == "RecordStateChanged") onRecordState(d.optJSONObject("eventData") ?: JSONObject(), System.currentTimeMillis())
            7 -> if (d.optString("requestType") == "GetRecordStatus" && d.optJSONObject("requestStatus")?.optBoolean("result") == true) {
                val r = d.optJSONObject("responseData") ?: return
                val now = System.currentTimeMillis()
                if (r.optBoolean("outputActive") && !recording) {
                    recording = true; paused = r.optBoolean("outputPaused")
                    onStart(now - r.optLong("outputDuration", 0L))
                } else if (!r.optBoolean("outputActive") && recording) {
                    recording = false; paused = false; onStop(now, null)
                }
                publish()
            }
        }
    }

    /** Pure state machine for RecordStateChanged (also used by tests). */
    fun onRecordState(e: JSONObject, at: Long) {
        val state = e.optString("outputState").uppercase()
        if (state.endsWith("_STARTING") || state.endsWith("_STOPPING")) return
        val active = e.optBoolean("outputActive")
        when {
            active && state.endsWith("_PAUSED") -> if (!paused) { paused = true; onPause(at) }
            active && state.endsWith("_RESUMED") -> if (paused) { paused = false; onResume(at) }
            active && !recording -> { recording = true; paused = false; onStart(at) }
            !active && recording -> {
                if (paused) onResume(at)
                recording = false; paused = false
                onStop(at, e.optString("outputPath").takeIf { it.isNotBlank() && it != "null" })
            }
        }
        publish()
    }

    private fun publish() { _status.value = _status.value.copy(recording = recording, paused = paused) }

    companion object {
        private fun sha256b64(s: String): String = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
        /** obs-websocket 5 auth: base64(sha256(base64(sha256(password + salt)) + challenge)). */
        fun auth(password: String, salt: String, challenge: String): String = sha256b64(sha256b64(password + salt) + challenge)
    }
}
