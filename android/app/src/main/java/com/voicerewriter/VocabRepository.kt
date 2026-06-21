package com.voicerewriter

import android.content.Context
import android.util.Log
import com.voicerewriter.textproc.VocabEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores the personal vocabulary as JSON in filesDir/vocab.json. On-device only —
 * never uploaded. Small list, so we read/write the whole file each time.
 */
class VocabRepository(context: Context) {

    private val file = File(context.applicationContext.filesDir, "vocab.json")

    suspend fun get(): List<VocabEntry> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val canonical = o.optString("canonical").trim()
                if (canonical.isEmpty()) return@mapNotNull null
                val aliasArr = o.optJSONArray("aliases")
                val aliases = if (aliasArr != null)
                    (0 until aliasArr.length()).map { aliasArr.optString(it).trim() }.filter { it.isNotEmpty() }
                else emptyList()
                val expansion = o.optString("expansion").trim().ifEmpty { null }
                val source = o.optString("source").trim().ifEmpty { "manual" }
                VocabEntry(canonical, aliases, expansion, source)
            }
        } catch (e: Exception) {
            Log.e("VocabRepository", "failed to read $file", e); emptyList()
        }
    }

    suspend fun save(entries: List<VocabEntry>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        for (e in entries) {
            if (e.canonical.isBlank()) continue
            arr.put(JSONObject()
                .put("canonical", e.canonical.trim())
                .put("aliases", JSONArray(e.aliases.map { it.trim() }.filter { it.isNotEmpty() }))
                .apply { if (!e.expansion.isNullOrBlank()) put("expansion", e.expansion.trim()) }
                .put("source", e.source))
        }
        file.writeText(arr.toString())
    }
}
