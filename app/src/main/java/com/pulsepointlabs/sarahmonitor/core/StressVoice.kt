package com.pulsepointlabs.sarahmonitor.core

import android.content.Context
import android.content.SharedPreferences
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import com.pulsepointlabs.sarahmonitor.response.CoachModel
import com.pulsepointlabs.sarahmonitor.response.ResponseSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import kotlin.math.abs

/** Sarah's stress voice: spoken stress changes and optional relaxation coaching. Never in sexual sessions. */
object StressVoiceSettings {
    private var prefs: SharedPreferences? = null
    /** Say when stress rises or eases ("Stress is rising, now 54"). */
    val announce = MutableStateFlow(false)
    /** Sarah adds a calm relaxation cue when it climbs or stays high, and acknowledges it easing. */
    val relax = MutableStateFlow(false)
    /** Points of change (0–100 scale) that count as a real shift. */
    val threshold = MutableStateFlow(10)
    /** Minimum minutes between spoken stress lines. */
    val gapMin = MutableStateFlow(3)
    val onlyRecording = MutableStateFlow(false)

    @Synchronized
    fun load(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("sarahmonitor_stress_voice", Context.MODE_PRIVATE)
        prefs = p
        announce.value = p.getBoolean("announce", false)
        relax.value = p.getBoolean("relax", false)
        threshold.value = p.getInt("threshold", 10)
        gapMin.value = p.getInt("gap", 3)
        onlyRecording.value = p.getBoolean("only_rec", false)
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) { prefs?.edit()?.apply(block)?.apply() }
    fun setAnnounce(v: Boolean) { announce.value = v; edit { putBoolean("announce", v) } }
    fun setRelax(v: Boolean) { relax.value = v; edit { putBoolean("relax", v) } }
    fun setThreshold(v: Int) { threshold.value = v.coerceIn(5, 25); edit { putInt("threshold", threshold.value) } }
    fun setGapMin(v: Int) { gapMin.value = v.coerceIn(1, 15); edit { putInt("gap", gapMin.value) } }
    fun setOnlyRecording(v: Boolean) { onlyRecording.value = v; edit { putBoolean("only_rec", v) } }
    val enabled: Boolean get() = announce.value || relax.value
}

enum class StressShift { Rising, Easing, StillHigh }

data class StressEvent(val shift: StressShift, val from: Int, val to: Int, val level: StressLevel, val highMinutes: Int = 0)

/**
 * Decides when a stress change is worth saying. Pure logic (testable):
 *  - the first readings set a silent reference;
 *  - a change of [threshold] points from the last thing said, held for [holdMs], is a Rising/Easing event;
 *  - while Elevated/High with nothing new, a StillHigh reminder at most every 2× the gap (if [reminders]);
 *  - nothing while moving (exercise isn't stress) or for a minute after; then it re-references silently.
 */
class StressAnnouncer(private val holdMs: Long = 20_000L) {
    private var ref: Int? = null
    private var lastSpokeAt = 0L
    private var candidateSince: Long? = null
    private var candidateUp: Boolean? = null
    private var highSince: Long? = null
    private var movingUntil = 0L

    fun reset() { ref = null; candidateSince = null; candidateUp = null; highSince = null; movingUntil = 0L }

    fun update(t: Long, r: StressReading, threshold: Int, gapMs: Long, reminders: Boolean): StressEvent? {
        val score = r.score ?: return null
        val level = r.level ?: StressEstimator.level(score)
        if (r.moving) { movingUntil = t + 60_000L; ref = null; candidateSince = null; highSince = null; return null }
        if (t < movingUntil) return null
        val reference = ref ?: run { ref = score; lastSpokeAt = t; return null }
        highSince = if (level >= StressLevel.Elevated) highSince ?: t else null
        val diff = score - reference
        val up = diff > 0
        if (abs(diff) >= threshold) {
            if (candidateUp != up) { candidateUp = up; candidateSince = t }
        } else { candidateUp = null; candidateSince = null }
        val since = candidateSince
        if (since != null && t - since >= holdMs && t - lastSpokeAt >= gapMs) {
            candidateSince = null; candidateUp = null
            ref = score; lastSpokeAt = t
            return StressEvent(if (up) StressShift.Rising else StressShift.Easing, reference, score, level)
        }
        val hs = highSince
        if (reminders && hs != null && t - lastSpokeAt >= gapMs * 2 && t - hs >= gapMs) {
            ref = score; lastSpokeAt = t
            return StressEvent(StressShift.StillHigh, reference, score, level, ((t - hs) / 60_000L).toInt())
        }
        return null
    }
}

/** Turns stress events into Sarah's spoken lines: plain announcements, plus calm coaching when relax mode is on. */
class StressVoice(private val speak: suspend (String) -> Unit, private val scope: CoroutineScope) {
    private val announcer = StressAnnouncer()
    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status
    private val _last = MutableStateFlow<String?>(null)
    val last: StateFlow<String?> = _last
    private val recent = ArrayDeque<String>()
    @Volatile private var busy = false
    private var client: AnthropicClient? = null
    private var clientKey = ""

    fun reset() = announcer.reset()

    /** Called on every heart update. [quietReason] non-null = this lane is closed right now (e.g. a sexual session). */
    fun onStress(t: Long, r: StressReading, quietReason: String?) {
        val s = StressVoiceSettings
        if (!s.enabled) { _status.value = "Off"; announcer.reset(); return }
        if (quietReason != null) { _status.value = quietReason; announcer.reset(); return }
        if (r.score == null) { _status.value = "Waiting for heart data"; return }
        if (!busy) _status.value = if (r.moving) "Paused while you're moving" else "Listening"
        val e = announcer.update(t, r, s.threshold.value, s.gapMin.value * 60_000L, reminders = s.relax.value) ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val line = (if (s.relax.value) runCatching { withContext(Dispatchers.IO) { coachLine(e, r, s.announce.value) } }.getOrNull() else null)
                    ?: localLine(e, r, s.announce.value, s.relax.value)
                if (line != null) {
                    _last.value = line
                    recent.addLast(line); while (recent.size > 6) recent.removeFirst()
                    speak(line)
                }
            } finally { busy = false }
        }
    }

    /** ▶ Test: a sample line in the current style. */
    fun test() {
        scope.launch {
            val e = StressEvent(StressShift.Rising, 32, 51, StressLevel.Elevated)
            val r = StressReading(score = 51, level = StressLevel.Elevated, breathsPerMin = 19.0,
                components = listOf(StressComponent("hrv", "HRV", 62, ""), StressComponent("breath", "Breathing", 70, "19 /min")))
            val line = (if (StressVoiceSettings.relax.value) runCatching { withContext(Dispatchers.IO) { coachLine(e, r, StressVoiceSettings.announce.value) } }.getOrNull() else null)
                ?: localLine(e, r, announce = true, relax = StressVoiceSettings.relax.value)
            line?.let { _last.value = it; speak(it) }
        }
    }

    companion object {
        private fun comp(r: StressReading, key: String) = r.components.firstOrNull { it.key == key }?.score

        /** The relaxation cue that fits what's driving it. */
        fun cue(r: StressReading): String = when {
            (r.breathsPerMin ?: 0.0) >= 17 -> "Let your breathing slow down. Breathe in for four, and out for six."
            (comp(r, "tension") ?: 0) >= 50 -> "Let your shoulders drop, unclench your jaw, and let your pelvic floor soften."
            (comp(r, "restless") ?: 0) >= 50 -> "See if you can settle into stillness for a minute and just breathe."
            else -> "Take a slow breath in, and let a long, easy breath out."
        }

        /** Built-in wording (no key, or Claude unavailable). Null = nothing to say in this combination. */
        fun localLine(e: StressEvent, r: StressReading, announce: Boolean, relax: Boolean): String? {
            val level = e.level.label.lowercase()
            val news = when (e.shift) {
                StressShift.Rising -> "Stress is rising, now ${e.to}, $level."
                StressShift.Easing -> "Stress is easing, down to ${e.to}."
                StressShift.StillHigh -> "Stress is still $level, around ${e.to}."
            }
            val coaching = when {
                !relax -> null
                e.shift == StressShift.Easing && e.level == StressLevel.Calm -> "Nicely settled. Keep doing what you're doing."
                e.shift == StressShift.Easing -> "That's it, it's coming down. Keep that easy breathing going."
                else -> cue(r)
            }
            return when {
                announce && coaching != null -> "$news $coaching"
                announce -> news
                else -> coaching
            }
        }

        val SYSTEM_PROMPT = """
You are Sarah, speaking to Ben through his earbuds while Sarah Monitor tracks his stress during an ordinary, non-sexual part of his day. Ben is an adult paramedic and EMS educator with strong physiology knowledge. The stress estimate (0-100) combines HRV suppression against his resting baseline, heart rate above resting that movement doesn't explain, breathing rate, muscle tension and restlessness.

Voice: calm, warm and plain-spoken; somewhat clinical but soothing, like a good clinician talking someone down. Speak to him directly ("you").

Each turn you get a JSON event: rising, easing or still_high, with the numbers and what is driving it. Reply with ONE spoken line of at most 30 words. Rules:
- If announce is true, open by stating the change plainly with the number (for example: Stress is climbing, about 58.). If announce is false, give no numbers.
- When rising or still high, add one gentle, concrete relaxation cue that fits the drivers: fast breathing means a slower, longer exhale (about six breaths a minute); tension means drop the shoulders, soften the jaw and pelvic floor; suppressed HRV means slow, easy breathing; restlessness means settle into stillness.
- When easing, briefly acknowledge it and encourage him to keep doing what's working.
- Don't guess at causes, don't diagnose, don't alarm, and never mention sex or arousal.
- Don't repeat wording from recent_lines.
- Written for text-to-speech: no emoji, markdown, quotation marks, lists or preamble.
""".trimIndent()
    }

    private fun client(key: String): AnthropicClient {
        if (client == null || clientKey != key) {
            client = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(20)).maxRetries(1).build()
            clientKey = key
        }
        return client!!
    }

    private fun coachLine(e: StressEvent, r: StressReading, announce: Boolean): String? {
        val key = ResponseSettings.apiKey.value
        if (key.isBlank()) return null
        val model = ResponseSettings.model.value
        val ctx = JSONObject()
            .put("event", when (e.shift) { StressShift.Rising -> "rising"; StressShift.Easing -> "easing"; StressShift.StillHigh -> "still_high" })
            .put("announce", announce)
            .put("stress_from", e.from).put("stress_now", e.to).put("level", e.level.label)
            .put("minutes_high", e.highMinutes)
            .put("breaths_per_min", r.breathsPerMin?.let { Math.round(it * 10) / 10.0 })
            .put("drivers_0_100", JSONObject().apply { r.components.forEach { c -> c.score?.let { put(c.key, it) } } })
            .put("recent_lines", JSONArray(recent.toList()))
        val builder = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(1_000L)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(SYSTEM_PROMPT).cacheControl(BetaCacheControlEphemeral.builder().build()).build()))
            .addUserMessage(ctx.toString())
        if (model != CoachModel.Haiku) builder.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
        val response = client(key).beta().messages().create(builder.build())
        val stop = response.stopReason().orElse(null)
        if (stop == BetaStopReason.REFUSAL || stop == BetaStopReason.MAX_TOKENS) return null
        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" ").trim().trim('"', '\'')
        return text.takeIf { it.isNotBlank() && it.split(Regex("\\s+")).size <= 45 }
    }
}
