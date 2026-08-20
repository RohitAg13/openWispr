package com.voicerewriter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the floating bubble back after a reboot or an app update.
 *
 * Without this the bubble was gone until the user opened Settings and toggled it on again, which
 * they only discovered when they went to dictate and the button wasn't there. `START_STICKY` on
 * [BubbleService] doesn't help: it covers a low-memory kill, not a reboot.
 *
 * `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are both on Android's exemption list for starting a
 * foreground service from the background, so the `startForegroundService` here is legal. The
 * overlay check is not optional though — the grant can be revoked while the app isn't running,
 * and starting the service without it would put up a notification for a bubble that can never
 * call `addView`.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        if (!BubblePrefs.enabled(context)) return
        if (!SetupUtils.canDrawOverlays(context)) {
            // Nothing to do but leave it off; the Settings screen re-checks and re-offers the
            // grant on resume.
            Log.i("BootReceiver", "bubble was on, but the overlay grant is gone; not starting")
            return
        }
        try {
            SetupUtils.startBubble(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "couldn't restart the bubble", e)
        }
    }
}
