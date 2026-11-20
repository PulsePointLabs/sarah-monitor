package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.session.WhisperTranscriber
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperMultipartTest {
    @Test
    fun bodyHasFieldsFileAndClosingBoundary() {
        val audio = byteArrayOf(1, 2, 3, 4)
        val body = WhisperTranscriber.multipartBody(
            "BOUND", listOf("model" to "whisper-1", "response_format" to "text"),
            "file", "note.m4a", "audio/mp4", audio
        )
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("--BOUND\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n"))
        assertTrue(text.contains("name=\"response_format\"\r\n\r\ntext\r\n"))
        assertTrue(text.contains("name=\"file\"; filename=\"note.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n\u0001\u0002\u0003\u0004\r\n"))
        assertTrue(text.endsWith("--BOUND--\r\n"))
    }

    @Test
    fun promptCarriesTheAppVocabulary() {
        listOf("RMSSD", "CIVET", "pelvic floor").forEach { assertTrue(WhisperTranscriber.PROMPT.contains(it)) }
    }
}
