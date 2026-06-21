package com.voicerewriter

import android.content.Context
import android.util.Log
import com.voicerewriter.textproc.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Per-category tone overrides for the LLM-polish prompt, stored on-device in
 * filesDir/app_tone.json. When a category has no override, the built-in default
 * from [AppContext.DEFAULT_TONE] is used. Small map → read/write the whole file.
 */
class AppToneRepository(context: Context) {

    private val file = File(context.applicationContext.filesDir, "app_tone.json")

    suspend fun overrides(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        try {
            val o = JSONObject(file.readText())
            o.keys().asSequence().associateWith { o.optString(it).trim() }.filterValues { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e("AppToneRepository", "read failed", e); emptyMap()
        }
    }

    suspend fun save(overridesByKey: Map<String, String>) = withContext(Dispatchers.IO) {
        val o = JSONObject()
        for ((k, v) in overridesByKey) if (v.isNotBlank()) o.put(k, v.trim())
        file.writeText(o.toString())
    }

    /** The effective tone fragment for [category]: user override, else the default. */
    suspend fun toneFor(category: AppContext.Category): String {
        val ov = overrides()[category.key]
        return ov ?: AppContext.DEFAULT_TONE[category].orEmpty()
    }
}
