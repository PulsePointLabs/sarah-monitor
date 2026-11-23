package com.pulsepointlabs.sarahmonitor.session

import android.content.Context
import android.media.AudioDeviceInfo
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

    fun open(context: Context, useHeadset: Boolean = VoiceSettings.preferHeadsetMic(context)): Route {
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
        if (route?.headset != true || Build.VERSION.SDK_INT < 31) return
        runCatching { context.getSystemService(AudioManager::class.java)?.clearCommunicationDevice() }
    }
}
