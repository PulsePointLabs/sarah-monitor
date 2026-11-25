package com.pulsepointlabs.sarahmonitor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Pauses whatever is playing (music, a video) while you talk to Sarah, and lets it carry on afterwards.
 *
 * It takes Android's exclusive "transient" audio focus: players pause on that and resume by themselves when it's
 * handed back, so only what was actually playing starts again (nothing that was already paused). Holds are
 * counted, so hands-free and the Mark event dictation can overlap; media resumes when the last one lets go.
 */
object MediaHold {
    private var manager: AudioManager? = null
    private var request: AudioFocusRequest? = null
    private val holders = HashSet<String>()
    private val listener = AudioManager.OnAudioFocusChangeListener { }

    fun init(c: Context) {
        if (manager == null) manager = c.applicationContext.getSystemService(AudioManager::class.java)
    }

    val held: Boolean @Synchronized get() = holders.isNotEmpty()

    /** Pauses media for [who] (no-op if [who] already holds it). */
    @Synchronized
    fun acquire(who: String) {
        val am = manager ?: return
        val first = holders.isEmpty()
        if (!holders.add(who) || !first) return
        // Pausing music is fine; taking focus from a call or a voice chat is not.
        if (OtherAudio.busy.value != null) return
        if (Build.VERSION.SDK_INT >= 26) {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(listener)
                .build()
            request = r
            am.requestAudioFocus(r)
        } else @Suppress("DEPRECATION") am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    }

    /** [who] is done; media resumes once nobody holds it. */
    @Synchronized
    fun release(who: String) {
        val am = manager ?: return
        if (!holders.remove(who) || holders.isNotEmpty()) return
        if (Build.VERSION.SDK_INT >= 26) request?.let(am::abandonAudioFocusRequest)
        else @Suppress("DEPRECATION") am.abandonAudioFocus(listener)
        request = null
    }
}
