package com.voicerewriter

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

/**
 * Pulls candidate vocabulary terms from the phone's contacts. Contact display
 * names are messy ("Clootrack Mohit", "HIT Rohan", "Shashank PG") — companies,
 * institutions and roles get mixed into the name — so we split into individual
 * proper-noun tokens and let the user pick which to keep (see ContactsImportActivity).
 * On-device only; nothing leaves the phone.
 */
object ContactsImporter {

    /** A candidate term and how many contacts it appears in (frequency = more useful). */
    data class Candidate(val token: String, val count: Int)

    // Org/role/title noise we don't propose as names.
    private val stop = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sir", "the", "and", "for",
        "llc", "inc", "ltd", "pvt", "llp", "co", "corp", "team", "office", "home", "work",
    )

    /** Read contacts and return deduped candidate tokens not already in [existing]. */
    fun candidates(context: Context, existing: Set<String>): List<Candidate> {
        val counts = HashMap<String, Int>()      // lowercased token -> count
        val display = HashMap<String, String>()  // lowercased -> best-cased form
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null, null, null,
            )?.use { c ->
                val col = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                if (col < 0) return emptyList()
                while (c.moveToNext()) {
                    val name = c.getString(col) ?: continue
                    for (raw in name.split(Regex("\\s+"))) {
                        val tok = raw.trim().trim('.', ',', '(', ')', '-', '_', '"', '\'')
                        if (!isCandidate(tok)) continue
                        val key = tok.lowercase()
                        if (key in existing) continue
                        counts[key] = (counts[key] ?: 0) + 1
                        // Prefer a mixed-case form (e.g. "Mohit") over ALL-CAPS if both seen.
                        val prev = display[key]
                        if (prev == null || (prev == prev.uppercase() && tok != tok.uppercase())) {
                            display[key] = tok
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactsImporter", "contacts query failed", e)
            return emptyList()
        }
        return counts.entries
            .map { Candidate(display[it.key] ?: it.key, it.value) }
            .sortedWith(compareByDescending<Candidate> { it.count }.thenBy { it.token.lowercase() })
    }

    /** A token is a name candidate if it's a 3+ letter word, not a number or noise word. */
    private fun isCandidate(tok: String): Boolean {
        if (tok.length < 3) return false
        if (tok.lowercase() in stop) return false
        if (!tok.any { it.isLetter() }) return false
        if (tok.all { it.isDigit() }) return false
        return true
    }
}
