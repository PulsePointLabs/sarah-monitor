package com.pulsepointlabs.sarahmonitor.overlay

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager

/**
 * Tracks which display the SarahVS UI was last shown on so floating overlays, the
 * event marker panel and HR alert toasts land on the same screen.
 *
 * On Samsung DeX (and other desktop/external-display setups) the app runs on a
 * secondary display. A Service context is bound to the phone's built-in display, so
 * overlays created from it end up on the phone screen with the phone's density.
 */
object OverlayDisplay {
    @Volatile
    private var lastActivityDisplayId: Int = Display.DEFAULT_DISPLAY

    fun recordActivityDisplay(activity: Activity) {
        val displayId = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                activity.display?.displayId
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.displayId
            }
        }.getOrNull() ?: return
        lastActivityDisplayId = displayId
    }

    /** The display overlays should use: the app's last display if it still exists. */
    fun targetDisplayId(context: Context): Int {
        val id = lastActivityDisplayId
        if (id == Display.DEFAULT_DISPLAY) return id
        val displayManager = context.getSystemService(DisplayManager::class.java)
        return if (displayManager?.getDisplay(id) != null) id else Display.DEFAULT_DISPLAY
    }

    /**
     * Context bound to [displayId], suitable for adding TYPE_APPLICATION_OVERLAY windows.
     * Falls back to [base] if the display can't be resolved.
     */
    fun overlayContext(base: Context, displayId: Int): Context {
        val displayManager = base.getSystemService(DisplayManager::class.java) ?: return base
        val display = displayManager.getDisplay(displayId)
            ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return base
        return runCatching {
            val displayContext = base.createDisplayContext(display)
            if (Build.VERSION.SDK_INT >= 30) {
                displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                displayContext
            }
        }.getOrDefault(base)
    }

    /** Plain display context (no window token), used for toasts. */
    fun displayContext(base: Context): Context {
        val displayId = targetDisplayId(base)
        if (displayId == Display.DEFAULT_DISPLAY) return base
        val display = base.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return base
        return runCatching { base.createDisplayContext(display) }.getOrDefault(base)
    }

    /** Usable bounds of the display behind [windowContext], in pixels. */
    fun displayBounds(windowContext: Context, windowManager: WindowManager): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { return Rect(windowManager.maximumWindowMetrics.bounds) }
        }
        val metrics = windowContext.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /** Launches [intent] on the display the overlay is showing on. */
    fun startActivityOnDisplay(context: Context, intent: Intent, displayId: Int) {
        val options = if (displayId != Display.DEFAULT_DISPLAY) {
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        } else {
            null
        }
        runCatching { context.startActivity(intent, options) }
            .onFailure { context.startActivity(intent) }
    }
}
