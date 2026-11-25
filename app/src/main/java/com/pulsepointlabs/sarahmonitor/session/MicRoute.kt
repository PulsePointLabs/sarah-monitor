package com.pulsepointlabs.sarahmonitor.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Which microphone voice notes use. With a Bluetooth headset that has a mic (e.g. bone-conduction headphones),
 * Android keeps recording from the phone's own mic unless the headset's call (SCO) link is switched on; this does
 * that for the length of the note and switches it back afterwards. The catch: while the call link is on, the
 * headset drops to call-quality audio for everything (music, Sarah's voice). So the phone's mic is the default
 * for anything long-running, and it's asked for explicitly so a headset mic is never picked up by accident.
 */
object MicRoute {
    data class Route(val input: AudioDeviceInfo?, val label: String, val headset: Boolean)

    private val headsetTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26 /* TYPE_BLE_HEADSET */)

    // While any mic is open, other apps' media is paused (YouTube, music…): otherwise the speaker is transcribed
    // along with you. Taken once for all open mics and given back when the last one closes. Sarah's own voice
    // and beeps still play (they take short focus of their own and hand it back to this).
    private val openRoutes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Route, Boolean>())
    private var hush: AudioFocusRequest? = null

    @Synchronized
    private fun hold(context: Context, route: Route) {
        if (com.pulsepointlabs.sarahmonitor.audio.OtherAudio.now(context) != null) return // never grab focus from a call or voice chat
        if (!openRoutes.add(route) || openRoutes.size > 1 || Build.VERSION.SDK_INT < 26) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        hush = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build().also { runCatching { am.requestAudioFocus(it) } }
    }

    @Synchronized
    private fun letGo(context: Context, route: Route?) {
        if (route == null || !openRoutes.remove(route) || openRoutes.isNotEmpty()) return
        hush?.let { h -> runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(h) } }
        hush = null
    }

    /** [holdMedia]: pause other apps' media while this mic is open (dictation yes; always-on wake listening no). */
    fun open(context: Context, useHeadset: Boolean = VoiceSettings.preferHeadsetMic(context), holdMedia: Boolean = true): Route =
        pick(context, useHeadset).also { if (holdMedia) hold(context, it) }

    private fun pick(context: Context, useHeadset: Boolean): Route {
        val am = context.getSystemService(AudioManager::class.java) ?: return Route(null, "Phone mic", false)
        if (useHeadset && Build.VERSION.SDK_INT >= 31) {
            val comm = runCatching { am.availableCommunicationDevices.firstOrNull { it.type in headsetTypes } }.getOrNull()
            if (comm != null && runCatching { am.setCommunicationDevice(comm) }.getOrDefault(false)) {
                val input = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == comm.type }
                return Route(input, "Headset mic · ${comm.productName}", true)
            }
        }
        val builtIn = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } }.getOrNull()
        return Route(builtIn, "Phone mic", false)
    }

    fun close(context: Context, route: Route?) {
        letGo(context, route)
        if (route?.headset != true || Build.VERSION.SDK_INT < 31) return
        runCatching { context.getSystemService(AudioManager::class.java)?.clearCommunicationDevice() }
    }
}
