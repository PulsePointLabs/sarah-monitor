package com.pulsepointlabs.sarahmonitor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Is something else in a live conversation right now: a phone call, a voice chat (Claude or ChatGPT voice mode,
 * WhatsApp, Meet…), or another app listening through the mic (live dictation)? Then Sarah stays out of it: she
 * doesn't speak, beep over it, take audio focus, or keep her own mic open, until it's over. Pausing music and
 * video is fine; interrupting a conversation is never fine (her stress line once cut off a Claude voice chat).
 */
object OtherAudio {
    private val _busy = MutableStateFlow<String?>(null)
    /** Why Sarah is staying quiet ("phone call", "voice chat", "another app is using the mic"), or null. */
    val busy: StateFlow<String?> = _busy

    /** Our own recordings (audio session ids), so Sarah's listening never counts as "another app". */
    private val ours = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    fun ownRecording(sessionId: Int, open: Boolean) { if (open) ours.add(sessionId) else ours.remove(sessionId) }

    private var started = false

    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        val am = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        scope.launch {
            while (true) {
                _busy.value = runCatching { check(am) }.getOrNull()
                delay(1_000L)
            }
        }
    }

    /** Right now, not waiting for the next poll (before speaking or grabbing focus). */
    fun now(context: Context): String? = runCatching {
        context.applicationContext.getSystemService(AudioManager::class.java)?.let(::check)
    }.getOrNull().also { _busy.value = it }

    private val CONVERSATION_USAGES = setOf(
        AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    )

    private fun check(am: AudioManager): String? {
        when (am.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_RINGTONE -> return "phone call"
            AudioManager.MODE_IN_COMMUNICATION -> return "voice chat"
            4 /* MODE_CALL_SCREENING */, 5 /* MODE_CALL_REDIRECT */, 6 /* MODE_COMMUNICATION_REDIRECT */ -> return "phone call"
        }
        // Sarah never plays as voice communication, so any such playback is someone else's conversation.
        if (Build.VERSION.SDK_INT >= 26 && am.activePlaybackConfigurations.any { it.audioAttributes.usage in CONVERSATION_USAGES }) return "voice chat"
        // Another app recording (voice mode, live dictation): anything that isn't one of our own mics. Only real capture
        // sources (0..10: mic, call, camcorder, recognition, communication…); the system's always-on hotword listener
        // ("Hi Bixby", source 1999) and echo-reference taps don't count.
        if (Build.VERSION.SDK_INT >= 24 && am.activeRecordingConfigurations.any { it.clientAudioSessionId !in ours && it.clientAudioSource in 0..10 }) return "another app is using the mic"
        return null
    }
}
