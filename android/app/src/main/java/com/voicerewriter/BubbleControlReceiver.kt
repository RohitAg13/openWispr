package com.voicerewriter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Lets the bubble be started/stopped without the UI — handy for testing
 * (`adb shell am broadcast -a com.voicerewriter.START_BUBBLE -n com.voicerewriter/.BubbleControlReceiver`)
 * and reusable later for a Quick Settings tile or automation.
 */
class BubbleControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.voicerewriter.STOP_BUBBLE" ->
                context.stopService(Intent(context, BubbleService::class.java))
            else ->
                ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
        }
    }
}
