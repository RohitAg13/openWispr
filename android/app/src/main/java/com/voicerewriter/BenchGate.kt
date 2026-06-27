package com.voicerewriter

/**
 * Tier-3 mirror of the research-harness gating predicate
 * (openwispr-research `bench/tier1_polish.py` `_needs_polish` / `_finish`, and the macOS
 * `BenchGate.swift`). "Gating" skips the on-device LLM polish entirely when the deterministic
 * textproc output is already clean — the single biggest honest latency lever (the LLM is the
 * dominant on-device cost). Kept byte-faithful to Tier 1/2 so `run_ondevice.sh` confirms the
 * SAME win on the real S25. Driven only by [EvalReceiver]; production gating would live at the
 * det→LLM hand-off in RewriteActivity.process.
 */
object BenchGate {
    private val fillerWords = setOf(
        "um", "uh", "erm", "uhm", "hmm", "mm", "mhm", "ah", "eh",
        "basically", "literally", "actually", "anyway", "anyways", "honestly",
        "seriously", "obviously", "essentially", "frankly", "regardless",
    )
    private val fillerPhrases = listOf(
        "i mean", "you know", "kind of", "sort of", "you see", "i guess",
        "or something", "or whatever", "i think like", "let me", "scratch that",
        "new line", "new paragraph", "full stop",
    )
    private val leadingFillers = setOf("so", "well", "like", "yeah", "okay", "ok", "right", "now", "see")
    private val spokenSymbols = setOf(
        "slash", "backslash", "dot", "at", "dash", "underscore", "colon",
        "semicolon", "asterisk", "hashtag", "ampersand",
    )
    private val numberWords = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    )
    private val vowels = setOf('a', 'e', 'i', 'o', 'u')
    private val tokenRe = Regex("[a-z0-9']+")

    /** True ⇒ the deterministic output still has disfluencies the LLM should fix (do NOT gate). */
    fun needsPolish(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val low = t.lowercase()
        val words = tokenRe.findAll(low).map { it.value }.toList()
        if (words.isEmpty()) return false
        if (words.size > 24) return true
        if (fillerPhrases.any { low.contains(it) }) return true
        if (words[0] in leadingFillers) return true
        for (i in words.indices) {
            val w = words[i]
            if (w in fillerWords) return true
            if (w in spokenSymbols) return true
            if (i + 1 < words.size) {
                val nxt = words[i + 1]
                if (w == nxt) return true
                if (w == "a" && nxt.isNotEmpty() && nxt[0] in vowels) return true
                if (w == "an" && nxt.isNotEmpty() && nxt[0] !in vowels && nxt[0].isLetter()) return true
                if (w.length == 1 && w[0].isLetter()) {
                    val nextSingleLetter = nxt.length == 1 && nxt[0].isLetter()
                    val nextDigits = nxt.isNotEmpty() && nxt.all { it.isDigit() }
                    if (nextSingleLetter || nextDigits || nxt in numberWords) return true
                }
            }
        }
        return false
    }

    /** Must-keep-safe finishing for a gated case: capitalize the first plain-lowercase word,
     *  uppercase lone "i", add a terminal period. WER-norm ignores all of this. */
    fun finish(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        val firstTok = t.split(" ").firstOrNull() ?: ""
        if (firstTok.isNotEmpty() && firstTok.all { it.isLowerCase() && it.isLetter() }) {
            t = t.substring(0, 1).uppercase() + t.substring(1)
        }
        t = t.replace(Regex("\\bi\\b"), "I")
        val last = t.lastOrNull()
        if (last != null && last.isLetterOrDigit()) t += "."
        return t
    }
}
