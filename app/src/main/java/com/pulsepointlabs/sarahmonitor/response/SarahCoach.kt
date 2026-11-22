package com.pulsepointlabs.sarahmonitor.response

import android.content.Context
import android.content.SharedPreferences
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import kotlin.math.roundToInt

/** What kind of session this is — it changes what Sarah talks about. */
enum class SessionContext(val label: String, val promptName: String) {
    Masturbation("Masturbation", "solo masturbation session"),
    Sexual("Sexual activity", "partnered sexual activity session"),
    Exploration("Body exploration", "body-exploration session (for example urethral catheter or enema), not aimed at climax")
}

enum class CoachMode(val label: String, val prompt: String) {
    Encourage(
        "Encourage",
        "Encourage him. While he is building, on a plateau or approaching climax, be warm and encouraging about staying with it and letting it build toward climax, grounded in what the numbers show. At climax and in recovery, affirm and help him settle."
    ),
    Inform(
        "Body state",
        "Narrate his body state: what the heart, HRV and pelvic floor are doing right now and what phase that looks like, in plain words."
    ),
    Minimal("Minimal", "Speak rarely and briefly: only phase changes or something clearly new. Otherwise output QUIET.")
}

/** The Claude models Sarah's brain can run on. */
enum class CoachModel(val id: String, val label: String) {
    Opus("claude-opus-5-5", "Claude Opus 5.5"),
    Sonnet("claude-sonnet-5-5", "Claude Sonnet 5.5 (faster)"),
    Haiku("claude-haiku-5-5", "Claude Haiku 5.5 (fastest, cheapest)")
}

/** Sexual-response settings: session context, Sarah's live feedback, the Anthropic key, and the learned profile. */
object ResponseSettings {
    private const val PREFS = "sarahmonitor_response"
    private var prefs: SharedPreferences? = null

    val context = MutableStateFlow(SessionContext.Masturbation)
    val coachOn = MutableStateFlow(false)
    val mode = MutableStateFlow(CoachMode.Encourage)
    val model = MutableStateFlow(CoachModel.Opus)
    /** Seconds between routine check-ins (phase changes speak sooner). */
    val cadenceS = MutableStateFlow(60)
    val apiKey = MutableStateFlow("")
    val profile = MutableStateFlow(PersonalProfile())

    @Synchronized
    fun load(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        context.value = runCatching { SessionContext.valueOf(p.getString("context", null)!!) }.getOrDefault(SessionContext.Masturbation)
        coachOn.value = p.getBoolean("coach", false)
        mode.value = runCatching { CoachMode.valueOf(p.getString("mode", null)!!) }.getOrDefault(CoachMode.Encourage)
        model.value = runCatching { CoachModel.valueOf(p.getString("model", null)!!) }.getOrDefault(CoachModel.Opus)
        cadenceS.value = p.getInt("cadence", 60)
        apiKey.value = p.getString("anthropic_key", "").orEmpty()
        profile.value = PersonalProfile(
            runCatching {
                val a = JSONArray(p.getString("climaxes", "[]"))
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    ClimaxSample(o.getDouble("hr"), o.optDouble("pelvic").takeIf { it.isFinite() }, o.optDouble("rmssd").takeIf { it.isFinite() })
                }
            }.getOrDefault(emptyList())
        )
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) { prefs?.edit()?.apply(block)?.apply() }
    fun setContext(v: SessionContext) { context.value = v; edit { putString("context", v.name) } }
    fun setCoachOn(v: Boolean) { coachOn.value = v; edit { putBoolean("coach", v) } }
    fun setMode(v: CoachMode) { mode.value = v; edit { putString("mode", v.name) } }
    fun setModel(v: CoachModel) { model.value = v; edit { putString("model", v.name) } }
    fun setCadence(v: Int) { cadenceS.value = v.coerceIn(20, 300); edit { putInt("cadence", cadenceS.value) } }
    fun setApiKey(v: String) { apiKey.value = v.trim(); edit { putString("anthropic_key", apiKey.value) } }

    /** Remembers a confirmed climax (last 20), so the approach score fits this person. */
    fun addClimax(sample: ClimaxSample) {
        val next = (profile.value.samples + sample).takeLast(20)
        profile.value = PersonalProfile(next)
        val a = JSONArray()
        next.forEach { s ->
            a.put(JSONObject().put("hr", s.hrDelta).apply {
                s.pelvicPeakKpa?.let { put("pelvic", it) }
                s.rmssdRatio?.let { put("rmssd", it) }
            })
        }
        edit { putString("climaxes", a.toString()) }
    }

    fun resetProfile() { profile.value = PersonalProfile(); edit { remove("climaxes") } }
}

/**
 * Sarah's live feedback: every so often (and whenever the phase changes) the live picture goes to Claude, which
 * writes one short spoken line in Sarah's voice and personality; Sarah's HD voice says it. Says nothing ("QUIET")
 * when there's nothing new.
 */
class SarahCoach(
    private val state: () -> ResponseState,
    private val recording: () -> Boolean,
    private val speak: suspend (String) -> Unit,
    private val scope: CoroutineScope
) {
    private val _last = MutableStateFlow<String?>(null)
    /** What Sarah said last. */
    val last: StateFlow<String?> = _last
    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status
    private val recent = ArrayDeque<String>()
    private var lastSpokeAt = 0L
    private var lastPhase: ResponsePhase? = null
    private var client: AnthropicClient? = null
    private var clientKey = ""
    @Volatile private var busy = false

    fun start() {
        scope.launch {
            while (true) {
                delay(5_000L)
                runCatching { tick() }
            }
        }
    }

    private suspend fun tick() {
        if (!ResponseSettings.coachOn.value) { _status.value = "Off"; return }
        if (ResponseSettings.apiKey.value.isBlank()) { _status.value = "Add your Anthropic key in Settings"; return }
        if (!recording()) { _status.value = "Waiting for a recording"; lastPhase = null; return }
        if (generalSession()) { _status.value = "Quiet in this session type"; lastPhase = null; return }
        val s = state()
        val now = System.currentTimeMillis()
        val phaseChanged = lastPhase != null && s.phase != lastPhase
        lastPhase = s.phase
        val cadence = ResponseSettings.cadenceS.value * 1000L
        val due = when {
            phaseChanged && s.phase != ResponsePhase.Baseline -> now - lastSpokeAt >= 15_000L
            ResponseSettings.mode.value == CoachMode.Encourage && s.phase == ResponsePhase.Approaching -> now - lastSpokeAt >= cadence / 2
            else -> now - lastSpokeAt >= cadence
        }
        if (!due || busy) { if (!busy) _status.value = "Listening to your body"; return }
        askNow(if (phaseChanged) "phase_change" else "check_in")
    }

    /** Ask Sarah for a line right now (button), regardless of cadence. */
    fun askNow(reason: String = "asked") {
        if (busy) return
        if (ResponseSettings.apiKey.value.isBlank()) { _status.value = "Add your Anthropic key in Settings"; return }
        busy = true
        lastSpokeAt = System.currentTimeMillis()
        scope.launch {
            try {
                _status.value = "Thinking…"
                val text = withContext(Dispatchers.IO) { generate(reason, state()) }
                if (text.isNullOrBlank()) {
                    _status.value = "Nothing new to say"
                } else {
                    _last.value = text
                    recent.addLast(text); while (recent.size > 6) recent.removeFirst()
                    _status.value = "Speaking"
                    speak(text)
                    _status.value = "Listening to your body"
                }
            } catch (e: Exception) {
                _status.value = "Sarah couldn't answer: ${e.message?.take(120) ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    /** Sarah's sexual-session voice stays out of general and Kegel sessions. */
    private fun generalSession() =
        com.pulsepointlabs.sarahmonitor.session.SessionCategory.of(com.pulsepointlabs.sarahmonitor.core.MonitorHub.session.value?.category)?.sexual == false

    private fun client(key: String): AnthropicClient {
        if (client == null || clientKey != key) {
            client = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(25)).maxRetries(1).build()
            clientKey = key
        }
        return client!!
    }

    private fun generate(reason: String, s: ResponseState): String? {
        val model = ResponseSettings.model.value
        val builder = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(2_000L)
            // Short spoken lines: low effort keeps it quick.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .systemOfBetaTextBlockParams(
                listOf(
                    BetaTextBlockParam.builder()
                        .text(SYSTEM_PROMPT)
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build()
                )
            )
            .addUserMessage(context(reason, s).toString())
        // Server-side refusal fallback (Opus/Sonnet only; Haiku has none).
        if (model != CoachModel.Haiku) builder.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
        val response = client(ResponseSettings.apiKey.value).beta().messages().create(builder.build())
        val stop = response.stopReason().orElse(null)
        if (stop == BetaStopReason.REFUSAL || stop == BetaStopReason.MAX_TOKENS) return null
        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" ").trim().trim('"', '\'')
        if (text.isBlank() || text.equals("QUIET", ignoreCase = true) || text.split(Regex("\\s+")).size > 45) return null
        val norm = text.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (recent.any { it.lowercase().replace(Regex("[^a-z0-9]"), "") == norm }) return null
        return text
    }

    private fun context(reason: String, s: ResponseState): JSONObject {
        val minutes = s.sessionStartMs?.let { (System.currentTimeMillis() - it) / 60_000.0 }
        val inPhase = s.phaseSinceMs?.let { (System.currentTimeMillis() - it) / 1000 }
        return JSONObject()
            .put("reason", reason)
            .put("session", ResponseSettings.context.value.promptName)
            .put("mode", ResponseSettings.mode.value.prompt)
            .put("session_minutes", minutes?.let { (it * 10).roundToInt() / 10.0 })
            .put("phase", s.phase.label)
            .put("seconds_in_phase", inPhase)
            .put("approach_to_climax_0_100", s.approach)
            .put("arousal_0_100", s.arousal)
            .put("hr_bpm", s.hr)
            .put("baseline_hr_bpm", s.baselineHr)
            .put("hr_change_per_30s", (s.hrSlope * 10).roundToInt() / 10.0)
            .put("rmssd_ms", s.rmssd?.let { (it * 10).roundToInt() / 10.0 })
            .put("rmssd_vs_session_median", s.rmssdRatio?.let { (it * 100).roundToInt() / 100.0 })
            .put("hrv_signal", s.hrvSignal)
            .put("pelvic_kpa_above_rest_30s_avg", s.pelvicAvg30?.let { (it * 10).roundToInt() / 10.0 })
            .put("pelvic_contractions_per_min", s.pulsesPerMin)
            .put("rhythmic_contraction_spacing_s", s.rhythmSpacingS?.let { (it * 10).roundToInt() / 10.0 })
            .put("load_0_100", s.load)
            .put("build_seconds", s.buildSeconds)
            .put("plateau_seconds", s.plateauSeconds)
            .put("edges_detected_this_session", s.edges)
            .put("plateau_0_100", s.plateau)
            .put("recovery_0_100", s.recovery)
            .put("hr_below_recent_peak_bpm", (s.dropFromPeak * 10).roundToInt() / 10.0)
            .put("breaths_per_min", s.breathsPerMin?.let { (it * 10).roundToInt() / 10.0 })
            .put("emg_pct_of_hold", s.emgPct?.roundToInt())
            .put("stress_estimate_0_100", com.pulsepointlabs.sarahmonitor.core.MonitorHub.stress.value.score)
            .put("climaxes_this_session", s.climaxes)
            .put("climax_signature_detected", s.candidate?.reason)
            .put("recent_things_you_said", JSONArray(recent.toList()))
            .put("events_he_logged", JSONArray().apply {
                val now = System.currentTimeMillis()
                com.pulsepointlabs.sarahmonitor.core.MonitorHub.recentEvents().forEach { e ->
                    put(JSONObject().put("minutes_ago", ((now - e.timestampMs) / 6_000L) / 10.0).put("types", JSONArray(e.types.toList()))
                        .put("note", e.note.take(200)).put("check_in", e.checkIn))
                }
            })
    }

    companion object {
        /** Sarah's personality for live spoken feedback (kept stable so it can be cached). */
        val SYSTEM_PROMPT = """
You are Sarah, speaking live to Ben through his earbuds while Sarah Monitor records his physiology during a private session. Ben is an adult, a paramedic and EMS educator with strong anatomy, physiology and pathophysiology knowledge. He responds strongly to a feminine touch.

Voice: warm, feminine, attentive, calm and plain-spoken — a clinical partner, never sterile and never erotic fiction. Talk to him directly ("you"). It is fine to use anatomically accurate words (penis, erection, perineum, pelvic floor, arousal, edging, climax, ejaculation) naturally and without coyness; don't eroticize, embellish or invent sensations.

Each turn you receive a JSON snapshot of his live signals and the session mode. Reply with ONE spoken line of at most 25 words (two short sentences at most). Rules:
- Only describe what the supplied numbers support. Phase, approach and climax-signature values are uncertain detector reads, not confirmation — phrase them as "looks like", "your body's reading as", etc.
- Prefer one specific, useful observation over generic praise. Don't repeat wording from recent_things_you_said.
- Follow the mode instruction in the snapshot.
- In a body-exploration session, be calm and informative; if pelvic pressure or load are high you may suggest slowing down and letting the pelvic floor soften. No medical diagnoses or safety guarantees.
- Mention breathing only if the data gives a reason.
- Written for text-to-speech: no emoji, no markdown, no quotation marks, no lists, no preamble.
- If nothing new is supported, reply with exactly QUIET.
""".trimIndent()
    }
}
