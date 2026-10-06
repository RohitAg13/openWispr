package com.voicerewriter.textproc

import kotlin.math.max

/**
 * Snaps mis-transcribed names/terms back to their canonical spelling using a
 * personal vocabulary. Runs after STT, before the cleanup pipeline. Pure and
 * deterministic so it's JVM-testable and never hallucinates outside the list.
 *
 * Matching, conservative by design:
 *  - Exact alias/canonical match (case-insensitive, whole word) -> always replace.
 *  - Fuzzy match -> Soundex phonetic equality + small edit distance, gated against
 *    common English words and short tokens, so "right"/"to"/"one" are never touched.
 */
object VocabCorrector {

    private const val FUZZY_THRESHOLD = 0.84
    private const val MIN_FUZZY_LEN = 4

    private data class Target(val replacement: String, val tokens: List<String>, val fuzzy: Boolean)
    private data class Tok(val norm: String, val start: Int, val end: Int)
    private data class Hit(val start: Int, val end: Int, val replacement: String, val score: Double, val span: Int)

    /**
     * A compact glossary string to bias Whisper's decoding (initial_prompt). Only
     * canonical spellings — never aliases (those are mishearings we don't want
     * emitted). Capped to stay within Whisper's prompt-token budget (~224 tokens).
     */
    fun biasPrompt(vocab: List<VocabEntry>, maxChars: Int = 200): String {
        if (vocab.isEmpty()) return ""
        val sb = StringBuilder("Glossary: ")
        // Names only. Priority for the capped budget: terms the user actually had to correct
        // (learnedAliases — proven mishearings) first, then manual, then imported contacts.
        val ranked = vocab.filter { !it.isSnippet }.sortedWith(
            compareByDescending<VocabEntry> { it.learnedAliases.isNotEmpty() }.thenBy { it.source != "manual" },
        )
        for (e in ranked) {
            val c = e.canonical.trim()
            if (c.isEmpty()) continue
            if (sb.length + c.length + 2 > maxChars) break
            if (!sb.endsWith(": ")) sb.append(", ")
            sb.append(c)
        }
        return if (sb.endsWith(": ")) "" else sb.append(".").toString()
    }

    /**
     * [guardRomanizedHindi] protects romanized Hindi function words from being rewritten at all.
     *
     * It exists because a short learned entry can hijack a similar-sounding word, and on
     * romanized Hindi that is ruinous rather than merely wrong. Measured 2026-10-06 against
     * this author's own 708-entry vocab: learned entries `mean` (alias "Mani"), `by` (alias
     * "back"), `honey` (alias "home") and `home` ate `main`, `mein`, `bhai`, `hoon` and `thik`
     * — five of the commonest words in the language. Some went phonetically (Soundex cannot
     * tell `main` from `mean`) and one by an exact alias, so guarding only the fuzzy path was
     * not enough. The same clips through whisper.cpp with no vocab were clean, which is how we
     * know the corrector and not the model was doing the damage.
     *
     * A length floor was tried first and rejected: it protected the function words but stopped
     * correcting short *names*, so "Sam aur Mama" became "Samp aur maama" — landing the cost on
     * exactly what vocab correction is for. Length was only ever a proxy. This names the words
     * instead, which lets `srshti` -> `Srushti` and `Samp` -> `Sam` both work.
     *
     * Single tokens only: a deliberate multi-word phrase is not going to collide by accident,
     * and exempting them keeps snippet triggers working.
     *
     * It also restricts single-token corrections to *proper nouns* — a replacement starting
     * with a capital. The stoplist alone could not be enough, because it can only name
     * spellings we have seen: the recogniser emitted `mani` for `main`, which is not a word in
     * any list, and the exact alias `Mani` -> `mean` fired on it. The two rules cover each
     * other. Naming the words catches the common case robustly; requiring a proper noun catches
     * every mis-spelling of them we have not thought of, because rewriting a romanized Hindi
     * word into a lowercase English one is wrong almost by definition, while rewriting it into
     * a name is the entire point of the feature.
     */
    fun correct(text: String, vocab: List<VocabEntry>, guardRomanizedHindi: Boolean = false): String {
        if (text.isBlank() || vocab.isEmpty()) return text
        val toks = tokenize(text)
        if (toks.isEmpty()) return text

        // Build match targets, longest phrase first so "row hit" beats "hit".
        val targets = ArrayList<Target>()
        for (e in vocab) {
            // Snippets expand to their text; names correct to the canonical spelling.
            val replacement = if (e.isSnippet) e.expansion!!.trim() else e.canonical.trim()
            if (replacement.isEmpty()) continue
            // Fuzzy matching only applies to entries that carry an explicit mis-hearing
            // alias (typed by the user, or recorded by learn-from-edits). A bare canonical
            // with no alias — every contact import — matches EXACTLY only, so the 600+
            // noisy contacts can't phonetically grab ordinary words (Check→Chachi,
            // Gate→Gita, mishearing→Masi). Contacts still bias Whisper via biasPrompt().
            val fuzzy = !e.isSnippet && e.aliases.isNotEmpty()
            for (phrase in e.matchPhrases()) {
                val ptoks = phrase.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (ptoks.isNotEmpty()) targets.add(Target(replacement, ptoks, fuzzy = fuzzy))
            }
        }
        targets.sortByDescending { it.tokens.size }

        val hits = ArrayList<Hit>()
        for (t in targets) {
            val span = t.tokens.size
            var i = 0
            while (i + span <= toks.size) {
                val window = toks.subList(i, i + span)
                // A romanized Hindi function word is never a vocab term, however it scores;
                // and a lone token may only ever be corrected into a proper noun.
                if (guardRomanizedHindi && span == 1 &&
                    (window.first().norm in HINDI_FUNCTION_WORDS || !t.replacement.first().isUpperCase())
                ) {
                    i++; continue
                }
                val score = matchScore(window.map { it.norm }, t.tokens)
                // Snippets and contacts fire on an exact match only; deliberate names fuzzy.
                val ok = if (t.fuzzy) score >= FUZZY_THRESHOLD else score >= 0.999
                if (ok) hits.add(Hit(window.first().start, window.last().end, t.replacement, score, span))
                i++
            }
        }
        if (hits.isEmpty()) return text

        // Greedy: prefer higher score, then longer span; apply non-overlapping.
        hits.sortWith(compareByDescending<Hit> { it.score }.thenByDescending { it.span })
        val taken = ArrayList<Hit>()
        for (h in hits) {
            if (taken.none { it.start < h.end && h.start < it.end }) taken.add(h)
        }
        taken.sortByDescending { it.start } // apply right-to-left so offsets stay valid

        val sb = StringBuilder(text)
        for (h in taken) sb.replace(h.start, h.end, h.replacement)
        return sb.toString()
    }

    // ---- matching ----

    /** 0..1 similarity of an input window to a target phrase (token-wise). */
    private fun matchScore(window: List<String>, phrase: List<String>): Double {
        if (window.size != phrase.size) return 0.0
        // A single token that's a common English word is never a vocab match — even
        // exactly — so a contact named "Will"/"Mark" can't corrupt "i will mark it".
        // Multi-word phrases (deliberate snippets/aliases) are exempt.
        if (window.size == 1 && window[0] in COMMON_WORDS) return 0.0
        if (window == phrase) return 1.0 // exact alias/canonical
        var sum = 0.0
        for (k in window.indices) {
            val a = window[k]; val b = phrase[k]
            if (a == b) { sum += 1.0; continue }
            // Guard: don't fuzzy-correct common words or short tokens.
            if (a in COMMON_WORDS || a.length < MIN_FUZZY_LEN || b.length < MIN_FUZZY_LEN) return 0.0
            val dist = levenshtein(a, b)
            val editSim = 1.0 - dist.toDouble() / max(a.length, b.length)
            val s = if (soundex(a) == soundex(b)) 0.7 + 0.3 * editSim else editSim
            if (s < FUZZY_THRESHOLD) return 0.0
            sum += s
        }
        return sum / window.size
    }

    private fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        for (m in Regex("[\\p{L}\\p{N}'’]+").findAll(text)) {
            out.add(Tok(m.value.lowercase().trim('\'', '’'), m.range.first, m.range.last + 1))
        }
        return out
    }

    // ---- phonetics / distance ----

    /** Classic Soundex (first letter + 3 digits). Good enough for name matching. */
    fun soundex(s: String): String {
        val up = s.uppercase().filter { it in 'A'..'Z' }
        if (up.isEmpty()) return "0000"
        fun code(c: Char): Char = when (c) {
            'B', 'F', 'P', 'V' -> '1'
            'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2'
            'D', 'T' -> '3'
            'L' -> '4'
            'M', 'N' -> '5'
            'R' -> '6'
            else -> '0' // vowels + H,W,Y
        }
        val sb = StringBuilder().append(up[0])
        var prev = code(up[0])
        for (i in 1 until up.length) {
            val c = code(up[i])
            if (c != '0' && c != prev) sb.append(c)
            // H and W don't reset the "previous code" gate; vowels do.
            if (up[i] != 'H' && up[i] != 'W') prev = c
            if (sb.length >= 4) break
        }
        return (sb.toString() + "000").substring(0, 4)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val cur = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
        }
        return dp[b.length]
    }

    /**
     * Frequent words we never match as a single-token vocab term. Includes common
     * English words AND words that double as first names ("Will", "Mark", "Rose"),
     * so importing such contacts can't corrupt ordinary speech.
     */
    private val COMMON_WORDS = setOf(
        "the", "and", "for", "are", "you", "your", "with", "this", "that", "have",
        "from", "they", "what", "when", "will", "would", "there", "their", "right",
        "write", "here", "hear", "one", "two", "ten", "now", "not", "but", "can",
        "all", "any", "out", "our", "was", "his", "her", "him", "she", "who", "how",
        "why", "yes", "let", "set", "get", "got", "see", "way", "day", "new", "use",
        "make", "like", "just", "need", "want", "into", "over", "then", "than",
        // common English words that are also names
        "mark", "rose", "grace", "hope", "bill", "art", "max", "ray", "dawn", "jack",
        "drew", "rich", "faith", "joy", "may", "june", "guy", "frank", "grant", "page",
        "lane", "reed", "hunter", "chase", "cash", "dale", "rob", "bob", "sunny", "victor",
    )

    /**
     * Romanized Hindi function words, never matched as a single-token vocab term.
     *
     * The same job [COMMON_WORDS] does for English, for the other language in the sentence.
     * Hinglish has no standard orthography, so the frequent variants are all listed rather
     * than normalized — `hoon`/`hun`/`hu`, `yeh`/`ye`/`yah`, `nahi`/`nahin` are all current.
     * Only consulted when the caller asks (see `guardRomanizedHindi`), so English dictation
     * is unaffected.
     */
    private val HINDI_FUNCTION_WORDS = setOf(
        // pronouns and demonstratives
        // "mani" is not a spelling anyone chooses, but it is what the recogniser hands us
        // for मैं, so it belongs here with the word it means.
        "main", "mai", "mani", "mein", "mera", "meri", "mere", "hum", "hamara", "humara",
        "tum", "tera", "teri", "tere", "tu", "aap", "apna", "apne", "apni",
        "yeh", "ye", "yah", "voh", "vo", "vah", "woh", "iska", "uska", "inka", "unka",
        "isko", "usko", "inko", "unko", "ise", "use", "jo", "jis", "jise",
        // postpositions and the wala family
        "ka", "ki", "ke", "ko", "se", "par", "pe", "tak", "liye", "lie",
        "wala", "vala", "waala", "vaala", "wali", "vali", "wale", "vale",
        // auxiliaries and very common verbs
        "hai", "hain", "hu", "hun", "hoon", "ho", "hota", "hoti", "hote",
        "tha", "thi", "the", "hoga", "hogi", "honge", "raha", "rahi", "rahe",
        "kar", "karna", "karne", "karo", "kiya", "kiye", "karu", "karoon", "karunga",
        "ja", "jaa", "jaega", "jaunga", "jaoonga", "gaya", "gayi", "gaye",
        "de", "dena", "diya", "di", "le", "lena", "liya", "lo", "mil", "mila",
        // conjunctions and particles
        "aur", "ya", "lekin", "magar", "kyunki", "kyonki", "to", "toh", "phir",
        "bhi", "bas", "agar", "warna", "varna", "matlab", "jaise", "waise", "vaise",
        // negation and question words
        "nahi", "nahin", "na", "mat", "kya", "kyun", "kyon", "kaise", "kaisa", "kaisi",
        "kab", "kahan", "kaun", "kitna", "kitne", "kitni",
        // quantity and degree
        "kuch", "kuchh", "sab", "sabhi", "bahut", "thoda", "thodi", "zyada", "jyada",
        "sirf", "sirph", "bilkul", "itna", "itne", "utna",
        // time
        "kal", "aaj", "aj", "abhi", "ab", "parso", "subah", "shaam", "raat",
        // vocatives and discourse words -- the ones actually observed being eaten
        "bhai", "yaar", "acha", "achha", "achchha", "accha", "thik", "theek", "sahi",
        "haan", "han", "arre", "arey", "chalo", "dekh", "dekho", "bol", "bolo", "batao",
    )
}
