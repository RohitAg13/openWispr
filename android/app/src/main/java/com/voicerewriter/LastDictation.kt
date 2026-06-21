package com.voicerewriter

import android.content.Context
import java.io.File

/**
 * Remembers the most recent dictation so the user can teach a correction after it
 * auto-inserts (see FixDictationActivity). File-backed so it survives the activity
 * finishing and the notification tap relaunching us.
 */
object LastDictation {
    @Volatile private var cached: String? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, "last_dictation.txt")

    fun set(context: Context, text: String) {
        cached = text
        runCatching { file(context).writeText(text) }
    }

    fun get(context: Context): String =
        cached ?: runCatching { file(context).readText() }.getOrNull().orEmpty().also { cached = it }
}
