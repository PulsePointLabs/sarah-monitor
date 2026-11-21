package com.pulsepointlabs.sarahmonitor.overlay

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pulsepointlabs.sarahmonitor.core.MonitorService

/** Quick Settings tile: turns the floating overlays on or off for the screen they're on (phone or DeX). */
class OverlayTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        OverlaySettings.load(this)
        update()
    }

    override fun onClick() {
        super.onClick()
        OverlaySettings.load(this)
        OverlaySettings.toggle(this)
        MonitorService.start(this)
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = OverlaySettings.on.value
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Sarah overlays"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "${OverlaySettings.profile.value.label} · ${if (on) "on" else "off"}"
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, OverlayTile::class.java)) }
        }
    }
}
