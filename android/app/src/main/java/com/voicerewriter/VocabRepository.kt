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

    /** Add [wrong] as an alias of [right], merging into an existing canonical entry. */
    suspend fun learnAlias(wrong: String, right: String, source: String = "learned") {
        val w = wrong.trim(); val r = right.trim()
        if (w.isEmpty() || r.isEmpty() || w.equals(r, ignoreCase = true)) return
        val list = get().toMutableList()
        val idx = list.indexOfFirst { it.canonical.equals(r, ignoreCase = true) && !it.isSnippet }
        if (idx >= 0) {
            val e = list[idx]
            if (e.aliases.any { it.equals(w, ignoreCase = true) }) return
            list[idx] = e.copy(aliases = e.aliases + w)
        } else {
            list.add(0, VocabEntry(r, listOf(w), source = source))
        }
        save(list)
    }

    /**
     * Learn name/word corrections from an inline edit. If the user changed a few
     * individual words (and the token count lines up), remember each wrong→right so
     * it's right next time. Conservative: skips big rewrites, numbers, emails, URLs.
     * Returns how many corrections were learned.
     */
    suspend fun learnFromEdit(original: String, edited: String): Int {
        val re = Regex("[\\p{L}\\p{N}'’.@/-]+")
        val a = re.findAll(original).map { it.value }.toList()
        val b = re.findAll(edited).map { it.value }.toList()
        if (a.isEmpty() || a.size != b.size) return 0 // only position-aligned word fixes
        val nameLike = Regex("^[\\p{L}][\\p{L}'’-]+$") // alphabetic, len ≥ 2 (no numbers/@/.)
        val subs = a.indices.mapNotNull { i ->
            val x = a[i]; val y = b[i]
            if (!x.equals(y, ignoreCase = true) && nameLike.matches(x) && nameLike.matches(y)) x to y else null
        }
        if (subs.isEmpty() || subs.size > 3) return 0 // nothing, or a big rewrite — don't pollute
        for ((wrong, right) in subs) learnAlias(wrong, right)
        return subs.size
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
