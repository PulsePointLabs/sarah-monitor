package com.pulsepointlabs.sarahmonitor.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import com.pulsepointlabs.sarahmonitor.response.ResponseSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/** Small, quick Claude calls (tidying dictation, reading spoken commands). Uses the saved Anthropic key. */
object ClaudeText {
    /** Fast model for short text jobs. */
    const val FAST_MODEL = "claude-haiku-5-5"
    private var client: AnthropicClient? = null
    private var clientKey = ""

    val available: Boolean get() = ResponseSettings.apiKey.value.isNotBlank()
    /** Why the last call returned nothing (shown in status lines instead of failing silently). */
    @Volatile var lastProblem: String? = null
        private set

    @Synchronized
    private fun client(key: String): AnthropicClient {
        if (client == null || clientKey != key) {
            client = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(15)).maxRetries(1).build()
            clientKey = key
        }
        return client!!
    }

    /** One system + user turn; returns the text, or null on no key / refusal / error. */
    suspend fun ask(system: String, user: String, maxTokens: Long = 1_500L, model: String = FAST_MODEL): String? = withContext(Dispatchers.IO) {
        val key = ResponseSettings.apiKey.value
        if (key.isBlank()) { lastProblem = "no Anthropic key"; return@withContext null }
        runCatching {
            val params = MessageCreateParams.builder()
                .model(model)
                // Room for the answer even if the model writes more than expected (short jobs, so it stays cheap).
                .maxTokens(maxOf(maxTokens, 1_500L))
                // Short mechanical jobs: no thinking (it could otherwise use up the budget and leave no answer).
                .thinking(com.anthropic.models.beta.messages.BetaThinkingConfigDisabled.builder().build())
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(system).cacheControl(BetaCacheControlEphemeral.builder().build()).build()))
                .addUserMessage(user)
                .build()
            val response = client(key).beta().messages().create(params)
            val stop = response.stopReason().orElse(null)
            if (stop == BetaStopReason.REFUSAL) { lastProblem = "declined"; return@runCatching null }
            val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim().ifBlank { null }
            lastProblem = if (text == null) "empty reply ($stop)" else null
            text
        }.onFailure { lastProblem = it.message?.take(120) ?: it.javaClass.simpleName }.getOrNull()
    }
}

/** Claude tidies dictated text: punctuation and capitals only, the same words in the same order. */
object TranscriptTidy {
    private const val SYSTEM = """You fix the punctuation and capitalization of dictated notes from a physiology lab (a paramedic logging his own body during sessions).
The text was transcribed in pieces at every pause, so it has full stops and capitals where the speaker only paused for breath. Punctuate it the way the speaker meant it, as natural flowing speech: join pieces that belong to one thought into one sentence; start a new sentence only where a new thought starts.
Rules: keep every word and their order; you may remove only filler sounds (um, uh, er); fix obvious split-word errors; keep "…" only for a real trailing-off or hesitation; use commas, full stops and question marks naturally; keep medical and device terms (Foley, French, glans, meatus, sphincter, HRV, Howl, OBS) as written.
Reply with the corrected text only. No quotes, no comments."""

    private val FILLERS = setOf("um", "uh", "er", "erm", "hmm")
    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() && it !in FILLERS }

    /** True when [tidied] keeps the same words (allowing small fixes), so a bad reply never replaces the note. */
    fun sameWords(original: String, tidied: String): Boolean {
        val a = words(original); val b = words(tidied)
        if (a.isEmpty()) return false
        if (b.size < a.size * 0.9 || b.size > a.size * 1.1 + 1) return false
        val bag = b.groupingBy { it }.eachCount().toMutableMap()
        val kept = a.count { w -> (bag[w] ?: 0).let { if (it > 0) { bag[w] = it - 1; true } else false } }
        return kept >= a.size * 0.9
    }

    suspend fun tidy(text: String): String? {
        if (text.isBlank() || !ClaudeText.available) return null
        val out = ClaudeText.ask(SYSTEM, text, maxTokens = (text.length / 2 + 200).toLong()) ?: return null
        return out.trim().trim('"').takeIf { sameWords(text, it) }
    }
}
