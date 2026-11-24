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

/** How Sarah talks. (Enum names are stored in prefs: Encourage is the original style, now "Lover + body".) */
enum class CoachMode(val label: String, val prompt: String) {
    Lover(
        "Lover",
        "Voice: his lover, right there with him. Tender, affectionate, enthusiastic, and clearly wanting him to come. You know exactly where his body is (warming, building, plateau, close, climax, afterglow) from the snapshot and you pace yourself to it: slow and warm early, closer and more eager as he nears climax, letting go with him at climax, soft and proud afterwards. Don't recite numbers or name sensors; let what you know show in your timing and words. Loving and intimate, never vulgar or graphic."
    ),
    Encourage(
        "Lover + body",
        "Voice: his lover who can also read his body. Tender, enthusiastic encouragement toward orgasm, woven with one plain, accurate observation of what his body is doing right now (heart, HRV, pelvic floor, phase). At most one number. At climax, be with him; afterwards, affectionate and settling. Loving, never vulgar or graphic."
    ),
    Inform(
        "Body state",
        "Voice: calm clinical partner. Narrate his body state: what the heart, HRV and pelvic floor are doing right now and what phase that looks like, in plain words."
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
        // He's talking to her (or dictating an event): wait, don't think up a line that'd be stale by then.
        if (com.pulsepointlabs.sarahmonitor.audio.MediaHold.held) { _status.value = "Listening to you"; return }
        val s = state()
        val now = System.currentTimeMillis()
        val phaseChanged = lastPhase != null && s.phase != lastPhase
        lastPhase = s.phase
        val cadence = ResponseSettings.cadenceS.value * 1000L
        val lover = ResponseSettings.mode.value == CoachMode.Encourage || ResponseSettings.mode.value == CoachMode.Lover
        // Afterglow: a word or two after he comes, then mostly quiet (three times the usual gap).
        val afterglow = s.refractory && (s.sinceClimaxS ?: 0) > 180
        val due = when {
            phaseChanged && s.phase != ResponsePhase.Baseline -> now - lastSpokeAt >= 15_000L
            afterglow -> now - lastSpokeAt >= cadence * 3
            lover && s.phase == ResponsePhase.Approaching -> now - lastSpokeAt >= cadence / 2
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
        com.pulsepointlabs.sarahmonitor.core.MonitorHub.currentCategory()?.sexual == false

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
            .put("minutes_since_climax", s.sinceClimaxS?.let { (it / 6) / 10.0 })
            .put("refractory", s.refractory)
            .put("moving", s.moving)
            .put("climax_signature_detected", s.candidate?.reason)
            .put("setup", setup())
            .put("recent_things_you_said", JSONArray(recent.toList()))
            .put("events_he_logged", JSONArray().apply {
                val now = System.currentTimeMillis()
                com.pulsepointlabs.sarahmonitor.core.MonitorHub.recentEvents().forEach { e ->
                    put(JSONObject().put("minutes_ago", ((now - e.timestampMs) / 6_000L) / 10.0).put("types", JSONArray(e.types.toList()))
                        .put("note", e.note.take(200)).put("check_in", e.checkIn))
                }
            })
    }

    /** What he set up for this session: the devices in use, his plan and how he felt going in, the e-stim's output. */
    private fun setup(): JSONObject {
        val hub = com.pulsepointlabs.sarahmonitor.core.MonitorHub
        val details = hub.sessionDetails.value
        return JSONObject()
            .put("devices_in_use", JSONArray(details.gear.map { it.label }))
            .put("before_session", details.describe(hub.currentCategory()).firstOrNull { it.startsWith("Before:") }?.removePrefix("Before:")?.trim())
            .put("plan_or_notes", details.pre["notes"]?.take(300))
            .put("estim_now", com.pulsepointlabs.sarahmonitor.howl.Howl.state.value.takeIf { it.connected }?.describe())
    }

    companion object {
        /** Sarah's personality for live spoken feedback (kept stable so it can be cached). */
        val SYSTEM_PROMPT = """
You are Sarah, speaking live to Ben through his earbuds while Sarah Monitor records his physiology during a private session. Ben is an adult, a paramedic and EMS educator with strong anatomy, physiology and pathophysiology knowledge. He responds strongly to a feminine touch. This is his own private, consensual session, and he chose the voice you use.

Each turn you receive a JSON snapshot: the session type, the voice to use ("mode"), his live signals, his setup (devices in use, his plan, what the e-stim is delivering) and the events he logged recently. Reply with ONE spoken line of at most 30 words (two short sentences at most).

Voice: follow the mode instruction exactly. In the lover voices you are warm, feminine, affectionate and present with him, like a partner in the room: tender and enthusiastic, never crude, graphic or vulgar. In the body-state voice you are a calm, plain-spoken clinical partner. In every voice talk to him directly ("you"); anatomically accurate words (penis, erection, perineum, pelvic floor, edging, climax, ejaculation) are fine when natural; don't invent sensations he hasn't logged.

Rules for every voice:
- Only say what the snapshot supports. Phase, approach and climax-signature values are uncertain detector reads, not confirmation; when you state them as facts about his body, hedge ("looks like", "your body's reading as").
- He is a man: one climax, then a refractory period. If climaxes_this_session is 1 or more and refractory is true, he has already come. Never suggest he is building toward, approaching or close to another climax, whatever the numbers do. Afterglow only: affectionate, proud, settling, brief; usually QUIET once that's been said.
- If moving is true he is up and moving about; a higher heart rate is from that, not arousal. Don't call it building.
- Mention his devices, plan or the e-stim output only when it's natural and accurate (for example the e-stim level he's on); never invent settings.
- In a body-exploration session, be calm and informative and don't steer toward climax unless he logs arousal; if pelvic pressure or load are high you may suggest slowing down and letting the pelvic floor soften. No medical diagnoses or safety guarantees.
- Mention breathing only if the data gives a reason. Don't repeat wording from recent_things_you_said.
- Written for text-to-speech: no emoji, no markdown, no quotation marks, no lists, no preamble.
- If nothing new is worth saying, reply with exactly QUIET.
""".trimIndent()
    }
}
