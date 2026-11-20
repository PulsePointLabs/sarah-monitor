package com.pulsepointlabs.sarahmonitor.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

class VoiceNoteRecognizer(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var committedText = ""

    fun start(
        existingText: String,
        onText: (String) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is not available")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Enable microphone permission for voice notes")
            return
        }
        stop()
        committedText = existingText.trimEnd()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onStatus("Listening...")
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = onStatus("Processing...")
                override fun onError(error: Int) {
                    onStatus("Optional note")
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No voice note captured"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Enable microphone permission for voice notes"
                        else -> "Voice note stopped"
                    }
                    stop()
                    onError(message)
                }

                override fun onResults(results: Bundle?) {
                    bestText(results)?.let { commit(it, onText) }
                    onStatus("Optional note")
                    stop()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = bestText(partialResults) ?: return
                    val next = VoiceTextNormalizer.normalize(append(committedText, partial))
                    if (next.isNotBlank()) onText(next)
                }

                override fun onSegmentResults(segmentResults: Bundle) {
                    bestText(segmentResults)?.let { commit(it, onText) }
                }

                override fun onEndOfSegmentedSession() {
                    onStatus("Optional note")
                    stop()
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(recognizerIntent())
        }
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun commit(recognized: String, onText: (String) -> Unit) {
        val normalized = VoiceTextNormalizer.normalize(recognized)
        if (normalized.isBlank()) return
        committedText = VoiceTextNormalizer.normalize(
            when {
                committedText.isBlank() -> normalized
                committedText.endsWith(normalized, ignoreCase = true) -> committedText
                normalized.startsWith(committedText, ignoreCase = true) -> normalized
                else -> append(committedText, normalized)
            }
        )
        onText(committedText)
    }

    private fun bestText(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim()?.takeIf(String::isNotBlank)

    private fun append(prefix: String, suffix: String): String =
        if (prefix.isBlank()) suffix else "$prefix $suffix"

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2_400)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5_000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true)
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, false)
            putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
            )
        }
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak event note")
    }
}
