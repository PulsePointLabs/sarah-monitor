package com.pulsepointlabs.sarahmonitor.session

import java.util.Locale

internal object VoiceTextNormalizer {
    fun normalize(text: String): String =
        normalizeSentenceCaps(
            text
                .trim()
                .replace(Regex("\\s+"), " ")
                .replace(Regex("\\s+([,.!?;:])"), "$1")
                .replace(Regex("([,.!?;:])(?=\\S)"), "$1 ")
                .replace(Regex("\\s+('(?:m|re|ve|ll|d|s|t)\\b)", RegexOption.IGNORE_CASE), "$1")
        )

    private fun normalizeSentenceCaps(text: String): String {
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
                    word.first().isUpperCase() && word.drop(1).all { !it.isLetter() || it.isLowerCase() } ->
                        word.lowercase(Locale.getDefault())
                    else -> word
                }
                result.append(normalized)
                sentenceStart = false
                continue
            }
            result.append(char)
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
