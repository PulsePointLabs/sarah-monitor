package com.pulsepointlabs.sarahmonitor.response

import com.pulsepointlabs.sarahmonitor.core.ClaudeText
import com.pulsepointlabs.sarahmonitor.core.MonitorHub

/**
 * One tap, on his phone, with his real keys: makes each kind of Claude call Sarah uses (a few cents' worth at most)
 * and reports each as passed or failed in plain words. The emulator and unit tests can't do this (the keys live only
 * on his phone), which is how a 400 on Opus once shipped unnoticed.
 */
object SarahSelfCheck {
    data class Result(val what: String, val ok: Boolean, val detail: String)

    suspend fun run(openAiKey: String): List<Result> {
        val out = mutableListOf<Result>()
        val model = ResponseSettings.model.value
        if (ResponseSettings.apiKey.value.isBlank()) {
            out += Result("Anthropic key", false, "Not saved. Paste it under Recording & keys → API keys.")
        } else {
            val sort = ClaudeText.ask("Reply with the single word OK.", "Check.", maxTokens = 50)
            out += Result("Sorting your notes (Claude Haiku)", sort != null, if (sort != null) "Working" else plain(ClaudeText.lastProblem))
            val reply = SarahChat.reply("Just checking that you can hear me.", "note", "How I feel", null)
            out += Result("Her replies to your notes (${model.label.substringBefore(" (")})", reply != null, reply ?: plain(SarahChat.problem))
            val answer = MonitorHub.coach.answer("Quick check: can you hear me? Answer in a few words.")
            out += Result("Answering your questions (${model.label.substringBefore(" (")})", answer != null, answer ?: plain(MonitorHub.coach.problem))
        }
        out += Result("OpenAI key (her voice, voice notes)", openAiKey.isNotBlank(), if (openAiKey.isNotBlank()) "Saved (tap Test voice under Sarah → Voice to hear it)" else "Not saved. Paste it under API keys.")
        return out
    }

    /** API errors in plain words. Pure; unit-tested. */
    fun plain(problem: String?): String {
        val p = problem ?: return "No reply"
        return when {
            p.contains("401") || p.contains("authentication", true) || p.contains("invalid x-api-key", true) -> "The key was rejected. Copy it again from console.anthropic.com and paste it in."
            p.contains("429") || p.contains("rate limit", true) || p.contains("rate_limit", true) -> "Too many requests right now. Wait a minute and try again."
            p.contains("529") || p.contains("overloaded", true) -> "Claude is overloaded right now. Try again in a minute."
            p.contains("timeout", true) || p.contains("Unable to resolve host", true) || p.contains("network", true) -> "No internet connection reached Claude."
            p.contains("credit", true) || p.contains("billing", true) -> "The Anthropic account is out of credit."
            p.contains("400") -> "Claude refused what the app sent (an app bug; send me a screenshot): $p"
            else -> p
        }
    }
}
