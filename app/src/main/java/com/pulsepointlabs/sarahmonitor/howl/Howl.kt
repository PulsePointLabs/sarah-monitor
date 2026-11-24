package com.pulsepointlabs.sarahmonitor.howl

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Howl's built-in activities (patterns), by the name its API takes. */
enum class HowlActivity(val label: String, vararg val aliases: String) {
    LICKS("Infinite licks", "licks"), PENETRATION("Penetration"), VIBRATOR("Sliding vibrator", "vibrator"),
    MILKMASTER("Milkmaster 3000", "milkmaster", "milk master"), CHAOS("Chaos"), HJ("Luxury HJ", "handjob"),
    OPPOSITES("Opposites"), BJ("BJ Megamix"), FASTSLOW("Fast/slow", "fast slow"), SIMPLEX("Simplex"),
    RELENTLESS("Relentless"), OVERFLOWING("Overflowing"), SUCCUBUS("Succubus"), SINETIME("Sine time"),
    CALIBRATION1("Calibration 1"), CALIBRATION2("Calibration 2");

    companion object {
        private fun norm(s: String?) = s.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        /** Which activity a status names (explicitly, or through the player's file name / title). */
        fun identify(vararg values: String?): HowlActivity? {
            val c = values.map(::norm).filter { it.isNotEmpty() }
            return entries.firstOrNull { a -> (listOf(a.name, a.label) + a.aliases).map(::norm).any { k -> c.any { it == k || it.contains(k) } } }
        }
    }
}

enum class HowlChannel { A, B, Both }

/** What Howl is doing right now, from its /status. Power is Howl's own 0–200 scale. */
data class HowlState(
    val connected: Boolean = false,
    val powerA: Int = 0,
    val powerB: Int = 0,
    val limitA: Int = Howl.MAX_POWER,
    val limitB: Int = Howl.MAX_POWER,
    val muted: Boolean = false,
    val playing: Boolean = false,
    val activity: HowlActivity? = null,
    val title: String? = null,
    val position: Double? = null,
    val duration: Double? = null,
    val error: String? = null,
    val updatedAtMs: Long = 0
) {
    /** One line for the recording: "A 34 · B 30 · Milkmaster 3000 · playing". */
    fun describe(): String = "A $powerA · B $powerB · ${activity?.label ?: title ?: "no activity"} · ${if (playing) "playing" else "paused"}" + if (muted) " · muted" else ""
    /** The parts that matter for the recording (changes here get logged). */
    val signature: String get() = "$connected|$powerA|$powerB|$muted|$playing|${activity?.name}"
}

/**
 * Howl (the DG-LAB Coyote app) over its remote-control API: HTTP POST to the Howl phone on port 4695, JSON bodies,
 * optional bearer key ("Allow remote access" in Howl). Polls /status once a second while enabled.
 *
 * Safety: power never goes above the user's ceiling or Howl's own channel limit; each tap raises power by at most
 * [stepUp] (lowering is twice as fast); Stop mutes, zeroes both channels and stops the player in one go.
 */
object Howl {
    const val MAX_POWER = 200
    private const val PREFS = "sarahmonitor_howl"

    val enabled = MutableStateFlow(false)
    val url = MutableStateFlow("http://127.0.0.1:4695")
    val key = MutableStateFlow("")
    /** Highest power Sarah Monitor will ever set (0–200). */
    val ceiling = MutableStateFlow(100)
    /** Power added per tap of +. */
    val stepUp = MutableStateFlow(2)

    val state = MutableStateFlow(HowlState())
    /** Last ~2 minutes of (time, A, B) for the live trace. */
    val history = MutableStateFlow<List<Triple<Long, Int, Int>>>(emptyList())

    /** Called (from a background thread) when anything that matters changes: for the recording. */
    var onChange: (HowlState) -> Unit = {}

    private var prefs: android.content.SharedPreferences? = null
    private val lock = Mutex()
    private var started = false
    private lateinit var scope: CoroutineScope

    fun load(c: Context) {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabled.value = p.getBoolean("enabled", false)
        url.value = p.getString("url", url.value) ?: url.value
        key.value = p.getString("key", "") ?: ""
        ceiling.value = p.getInt("ceiling", 100).coerceIn(0, MAX_POWER)
        stepUp.value = p.getInt("step", 2).coerceIn(1, 10)
    }

    private fun save() = prefs?.edit()?.putBoolean("enabled", enabled.value)?.putString("url", url.value)?.putString("key", key.value)
        ?.putInt("ceiling", ceiling.value)?.putInt("step", stepUp.value)?.apply()

    fun setEnabled(v: Boolean) { enabled.value = v; save(); if (!v) publish(HowlState()) }
    fun setUrl(v: String) { url.value = v.trim(); save() }
    fun setKey(v: String) { key.value = v.trim(); save() }
    fun setCeiling(v: Int) { ceiling.value = v.coerceIn(0, MAX_POWER); save() }
    fun setStepUp(v: Int) { stepUp.value = v.coerceIn(1, 10); save() }

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        this.scope = scope
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (enabled.value) runCatching { lock.withLock { poll() } }
                delay(1_000L)
            }
        }
    }

    private fun publish(s: HowlState) {
        val before = state.value
        state.value = s
        if (s.connected) history.value = (history.value + Triple(s.updatedAtMs, s.powerA, s.powerB)).takeLast(240)
        if (before.signature != s.signature) runCatching { onChange(s) }
    }

    private fun baseUrl(): String {
        val raw = url.value.ifBlank { "http://127.0.0.1:4695" }
        val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        val u = URL(withScheme)
        return "${u.protocol}://${u.host}:${if (u.port > 0) u.port else 4695}"
    }

    /** POSTs [body] to [endpoint]; returns the JSON reply (or throws with a plain reason). */
    private fun post(endpoint: String, body: JSONObject = JSONObject()): JSONObject? {
        val c = URL(baseUrl() + endpoint).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 2_500; c.readTimeout = 2_500; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            key.value.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(if (code == 401 || code == 403) "Howl refused the key (check Remote access key)" else "Howl replied $code")
            return text.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        } finally { c.disconnect() }
    }

    private fun poll() {
        val s = try {
            parse(post("/status"))
        } catch (e: Exception) {
            HowlState(connected = false, error = when (e) {
                is java.net.SocketTimeoutException, is java.net.ConnectException -> "Can't reach Howl. Is it open with remote access on, at ${url.value}?"
                else -> e.message ?: "Howl not reachable"
            }, updatedAtMs = System.currentTimeMillis())
        }
        publish(s)
    }

    private fun parse(o: JSONObject?): HowlState {
        val opt = o?.optJSONObject("options") ?: JSONObject()
        val player = o?.optJSONObject("player")
        fun int(j: JSONObject, k: String, d: Int) = if (j.has(k) && !j.isNull(k)) j.optInt(k, d) else d
        fun num(j: JSONObject?, k: String) = j?.takeIf { it.has(k) && !it.isNull(k) }?.optDouble(k)?.takeIf { it.isFinite() }
        val explicit = o?.optString("activity").takeIf { !it.isNullOrBlank() } ?: o?.optString("activity_name")
        return HowlState(
            connected = true,
            powerA = int(opt, "power_a", 0), powerB = int(opt, "power_b", 0),
            limitA = int(opt, "power_a_limit", MAX_POWER), limitB = int(opt, "power_b_limit", MAX_POWER),
            muted = opt.optBoolean("mute", false),
            playing = player?.optBoolean("playing", false) ?: false,
            activity = HowlActivity.identify(explicit, player?.optString("activity"), player?.optString("filename"), player?.optString("file"), player?.optString("title")),
            title = player?.optString("title")?.takeIf { it.isNotBlank() },
            position = num(player, "position"), duration = num(player, "duration"),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun run(block: () -> Unit) {
        if (!::scope.isInitialized) return
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                runCatching(block).onFailure { e -> state.value = state.value.copy(error = e.message ?: "Howl command failed") }
                runCatching { poll() }
            }
        }
    }

    /** The highest power allowed on a channel right now: the user's ceiling and Howl's own limit. */
    fun allowed(s: HowlState, ch: HowlChannel): Int = minOf(ceiling.value, if (ch == HowlChannel.B) s.limitB else s.limitA, MAX_POWER)

    /** Raise (+1) or lower (-1) power by one step on a channel, or both. */
    fun step(ch: HowlChannel, direction: Int) {
        val s = state.value
        if (!s.connected) return
        val d = if (direction > 0) stepUp.value else -2 * stepUp.value
        val a = (s.powerA + d).coerceIn(0, allowed(s, HowlChannel.A))
        val b = (s.powerB + d).coerceIn(0, allowed(s, HowlChannel.B))
        val body = when (ch) {
            HowlChannel.A -> JSONObject().put("power_a", a)
            HowlChannel.B -> JSONObject().put("power_b", b)
            HowlChannel.Both -> JSONObject().put("power_a", a).put("power_b", b)
        }
        // Show it at once; the poll right after confirms what Howl actually set.
        state.value = s.copy(powerA = if (ch != HowlChannel.B) a else s.powerA, powerB = if (ch != HowlChannel.A) b else s.powerB)
        run { post("/set_power", body) }
    }

    fun loadActivity(a: HowlActivity) = run { post("/load_activity", JSONObject().put("name", a.name).put("play", true)) }
    fun play() = run { post("/start_player") }
    fun pause() = run { post("/stop_player") }
    fun setMuted(m: Boolean) = run { post("/set_mute", JSONObject().put("value", m)) }

    /** Everything off, now: mute, both channels to 0, player stopped. */
    fun stopAll() {
        state.value = state.value.copy(powerA = 0, powerB = 0, muted = true, playing = false)
        run {
            runCatching { post("/set_mute", JSONObject().put("value", true)) }
            runCatching { post("/set_power", JSONObject().put("power_a", 0).put("power_b", 0)) }
            post("/stop_player")
        }
    }

    /** One status check right away (Settings "Test connection"). */
    suspend fun testNow(): HowlState = withContext(Dispatchers.IO) { lock.withLock { poll() }; state.value }
}
