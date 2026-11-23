package com.pulsepointlabs.sarahmonitor.session

import java.util.Locale

internal object VoiceTextNormalizer {
    private val COMMON = setOf(
        "and", "but", "so", "or", "because", "then", "which", "that", "with", "to", "of", "like", "maybe", "also", "if", "when", "while",
        "just", "as", "about", "for", "into", "in", "on", "at", "the", "a", "an", "it", "its", "it's", "we", "we're", "you", "they", "he", "she",
        "my", "your", "our", "this", "is", "was", "are", "be", "been", "not", "now", "there", "here", "what", "how", "some", "very", "still"
    )

    fun normalize(text: String, fixStrayCaps: Boolean = true): String =
        normalizeSentenceCaps(fixStrayCaps,
            text
                .trim()
                .replace(Regex("\\s+"), " ")
                .replace(Regex("\\s+([,.!?;:])"), "$1")
                .replace(Regex("([,.!?;:])(?=\\S)"), "$1 ")
                .replace(Regex("\\s+('(?:m|re|ve|ll|d|s|t)\\b)", RegexOption.IGNORE_CASE), "$1")
        )

    private fun normalizeSentenceCaps(fixStrayCaps: Boolean, text: String): String {
        val protectedWords = setOf("Sarah", "SarahVS", "CIVET", "Google", "Samsung", "OpenAI", "ChatGPT", "H10", "HR", "HRV", "RR", "ECG", "BP", "THC", "THCA", "CBD")
        val result = StringBuilder(text.length)
        var sentenceStart = true
        var i = 0
        while (i < text.length) {
            val char = text[i]
            if (char.isLetter()) {
                val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '\'')) i++
                val word = text.substring(start, i)
                val normalized = when {
                    word in protectedWords -> word
                    word.length == 1 && word.equals("i", ignoreCase = true) -> "I"
                    word.any(Char::isDigit) -> word
                    word.all { !it.isLetter() || it.isUpperCase() } -> word
                    sentenceStart -> word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                    // A stray capital on an everyday word ("…, But we") is always fixed. Other mid-sentence capitals are
                    // fixed only for Android's recognizer, which adds them at random; OpenAI's are real names (Foley, Howl).
                    word.lowercase(Locale.getDefault()) in COMMON -> word.lowercase(Locale.getDefault())
                    fixStrayCaps && word.first().isUpperCase() && word.drop(1).all { !it.isLetter() || it.isLowerCase() } -> word.lowercase(Locale.getDefault())
                    else -> word
                }
                result.append(normalized)
                sentenceStart = false
                continue
            }
            result.append(char)
            // "…" is a pause, not the end of a sentence, so it isn't in this list.
            if (char == '.' || char == '!' || char == '?') {
                sentenceStart = true
            } else if (!char.isWhitespace()) {
                sentenceStart = false
            }
            i++
        }
        return result.toString()
    }
}
