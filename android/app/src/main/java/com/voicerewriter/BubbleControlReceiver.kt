package com.voicerewriter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets the bubble be started/stopped without the UI — handy for testing
 * (`adb shell am broadcast -a com.voicerewriter.START_BUBBLE -n com.voicerewriter/.BubbleControlReceiver`)
 * and reusable later for a Quick Settings tile or automation.
 */
class BubbleControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Routed through SetupUtils so these behave exactly like the in-app toggle, including
        // persisting the on/off state that BootReceiver reads.
        when (intent.action) {
            "com.voicerewriter.STOP_BUBBLE" -> SetupUtils.stopBubble(context)
            else -> SetupUtils.startBubble(context)
        }
    }
}
