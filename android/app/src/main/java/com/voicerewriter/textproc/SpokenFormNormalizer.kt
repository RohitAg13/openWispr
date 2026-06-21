package com.voicerewriter.textproc

/**
 * Converts spoken punctuation and symbol names to their written forms. Kotlin
 * port of the cleanup pipeline's SpokenFormNormalizer (github.com/openwispr).
 *
 * Handles unambiguous patterns ("question mark" -> "?") always, and context-
 * dependent ones (URLs, paths, email, command flags, label colons) only when
 * [unambiguousOnly] is false. In code/terminal fields pass unambiguousOnly=true
 * so "dot", "slash", "dash" survive as words.
 */
object SpokenFormNormalizer {

    fun normalize(text: String, unambiguousOnly: Boolean = false): String {
        if (text.isEmpty()) return text
        var r = text

        if (!unambiguousOnly) r = normalizeURLsAndPaths(r)   // composite structures first
        r = normalizeUnambiguous(r)                          // always safe
        r = normalizeEllipsis(r)                             // "dot dot dot" -> "..."
        // "dash dash force" -> "--force" is unambiguous, so it runs even in code/
        // terminal fields (where you most want CLI flags). Single-dash stays gated.
        r = Regex("\\bdash\\s+dash\\s+(\\w+)\\b", RegexOption.IGNORE_CASE).replace(r, "--$1")
        if (!unambiguousOnly) {
            r = normalizeLabelColons(r)                      // "re colon ..." -> "Re: ..."
            r = normalizeSpokenPunctuation(r)                // "hello comma world" -> "hello, world"
            r = normalizeCommandPatterns(r)                  // single-dash flags
        }
        r = cleanupSymbolSpacing(r)
        r = r.replace(Regex("[ \\t]{2,}"), " ").trim()       // collapse spaces, preserve newlines
        return r
    }

    // ---- spoken sentence punctuation (gated to prose) ----

    /** Words that signal the *noun* sense, so we don't punctuate ("a comma", "the period"). */
    private val determinerBeforeMark = setOf(
        "a", "an", "the", "this", "that", "these", "those", "its", "his", "her",
        "their", "our", "my", "your", "of", "in", "during", "each", "every",
        "another", "no", "one", "semi",
    )

    /**
     * Convert an explicitly dictated mark that follows a real word into the symbol:
     * "send it comma then wait" -> "send it, then wait", "that's all period" -> "that's all."
     * Skipped when the preceding word marks the noun sense ("a comma", "the period").
     */
    private fun normalizeSpokenPunctuation(text: String): String {
        val re = Regex("\\b(\\w+)\\s+(full stop|period|comma|semicolon|colon)\\b", RegexOption.IGNORE_CASE)
        return re.replace(text) { m ->
            val prev = m.groupValues[1]
            if (prev.lowercase() in determinerBeforeMark) return@replace m.value
            val mark = when (m.groupValues[2].lowercase()) {
                "full stop", "period" -> "."
                "comma" -> ","
                "semicolon" -> ";"
                "colon" -> ":"
                else -> return@replace m.value
            }
            "$prev$mark"
        }
    }

    // ---- symbol spacing ----

    private fun cleanupSymbolSpacing(text: String): String {
        var r = text
        r = r.replace(Regex("\\s+([.,?!);:\\]%])"), "$1")    // no space before trailing punctuation
        r = r.replace(Regex("([(@\\[#$])\\s+"), "$1")         // no space after opening punctuation
        r = r.replace(Regex("\\s*_\\s*"), "_")                // join around underscore
        return r
    }

    // ---- unambiguous patterns ----

    private val unambiguousPatterns: List<Pair<String, String>> = listOf(
        "question mark" to "?",
        "exclamation point" to "!", "exclamation mark" to "!",
        "open parenthesis" to "(", "close parenthesis" to ")", "closed parenthesis" to ")",
        "left parenthesis" to "(", "right parenthesis" to ")",
        "start parenthesis" to "(", "end parenthesis" to ")",
        "parenthesis start" to "(", "parenthesis end" to ")",
        "parenthesis open" to "(", "parenthesis close" to ")", "parenthesis closed" to ")",
        "open paren" to "(", "close paren" to ")", "closed paren" to ")",
        "left paren" to "(", "right paren" to ")", "start paren" to "(", "end paren" to ")",
        "open bracket" to "[", "close bracket" to "]",
        "left bracket" to "[", "right bracket" to "]",
        "open curly brace" to "{", "close curly brace" to "}",
        "left curly brace" to "{", "right curly brace" to "}",
        "open brace" to "{", "close brace" to "}",
        "underscore" to "_", "ampersand" to "&", "at sign" to "@",
        "percent sign" to "%", "dollar sign" to "$", "equals sign" to "=",
        "hash sign" to "#", "number sign" to "#", "pound sign" to "#",
        "plus sign" to "+", "forward slash" to "/",
        "backslash" to "\\", "back slash" to "\\",
        "pipe sign" to "|", "pipe symbol" to "|",
        "tilde sign" to "~", "caret sign" to "^",
    )

    private fun normalizeUnambiguous(text: String): String {
        var r = text
        for ((spoken, written) in unambiguousPatterns) {
            val regex = Regex("\\b${Regex.escape(spoken)}\\b", RegexOption.IGNORE_CASE)
            // Literal replacement (written can be "$", "\", "^") — use the lambda form.
            r = regex.replace(r) { written }
        }
        return r
    }

    // ---- URL / email / path ----

    private fun normalizeURLsAndPaths(text: String): String {
        var r = text
        r = normalizeURLs(r)
        r = normalizeBareUrlPath(r)  // "github dot com slash x" -> github.com/x (before email, so "is at github..." isn't misread as an email)
        r = normalizeEmails(r)
        r = normalizePaths(r)
        r = normalizeDottedNames(r)
        return r
    }

    /** Bare domain+path (no protocol): "github dot com slash a slash b" -> "github.com/a/b". */
    private fun normalizeBareUrlPath(text: String): String {
        val regex = Regex(
            "\\b(\\w+(?:\\s+dot\\s+\\w+)+)\\s+slash\\s+(\\w+(?:\\s+slash\\s+\\w+)*)\\b",
            RegexOption.IGNORE_CASE,
        )
        return regex.replace(text) { m ->
            val domain = m.groupValues[1].replace(Regex("\\s+dot\\s+"), ".")
            val path = m.groupValues[2].replace(Regex("\\s+slash\\s+"), "/")
            "$domain/$path"
        }
    }

    /**
     * Common function words that are never an email local-part. Guards against
     * "the repo is at github dot com" becoming "is@github.com".
     */
    private val emailLocalStop: Set<String> = setOf(
        "is", "at", "be", "am", "are", "was", "were", "the", "a", "an", "to", "in",
        "on", "of", "it", "and", "or", "but", "so", "we", "he", "she", "they",
        "you", "i", "me", "us", "this", "that", "here", "there",
    )

    /** "https colon slash slash github dot com slash x" -> "https://github.com/x" */
    private fun normalizeURLs(text: String): String {
        val regex = Regex(
            "\\b(https?|ftp|ssh|git)\\s+colon\\s+slash\\s+slash\\s+(\\S+(?:\\s+dot\\s+\\S+)+(?:\\s+slash\\s+\\S+)*)\\b",
            RegexOption.IGNORE_CASE,
        )
        return regex.replace(text) { m ->
            val proto = m.groupValues[1]
            var rest = m.groupValues[2]
            rest = rest.replace(Regex("\\s+dot\\s+"), ".").replace(Regex("\\s+slash\\s+"), "/")
            "$proto://$rest"
        }
    }

    /** "john dot smith at example dot com" -> "john.smith@example.com" */
    private fun normalizeEmails(text: String): String {
        val regex = Regex(
            "\\b((?:\\w+\\s+dot\\s+)*\\w+)\\s+at\\s+(\\w+(?:\\s+dot\\s+\\w+)+)\\b",
            RegexOption.IGNORE_CASE,
        )
        return regex.replace(text) { m ->
            val localRaw = m.groupValues[1]
            // Skip if the local-part is a common word ("is at X" is not an email).
            if (localRaw.split(Regex("\\s+")).last().lowercase() in emailLocalStop) return@replace m.value
            val local = localRaw.replace(Regex("\\s+dot\\s+"), ".")
            val domain = m.groupValues[2].replace(Regex("\\s+dot\\s+"), ".")
            "$local@$domain"
        }
    }

    /** "slash api slash v2 slash users" -> "/api/v2/users" (needs >=2 segments). */
    private fun normalizePaths(text: String): String {
        val regex = Regex(
            "\\bslash\\s+(\\w+(?:\\s+dot\\s+\\w+)?(?:\\s+slash\\s+\\w+(?:\\s+dot\\s+\\w+)?)+)\\b",
            RegexOption.IGNORE_CASE,
        )
        return regex.replace(text) { m ->
            val content = m.groupValues[1]
                .replace(Regex("\\s+slash\\s+"), "/").replace(Regex("\\s+dot\\s+"), ".")
            "/$content"
        }
    }

    private val dottedExtensions: Set<String> = setOf(
        "js", "ts", "jsx", "tsx", "py", "rb", "rs", "go", "swift", "java",
        "kt", "c", "cpp", "h", "cs", "php", "html", "css", "scss", "json",
        "xml", "yaml", "yml", "toml", "md", "txt", "pdf", "doc", "docx",
        "xls", "xlsx", "ppt", "pptx", "csv", "log", "env", "sh", "bash",
        "zsh", "fish", "conf", "cfg", "ini", "lock", "png", "jpg", "jpeg",
        "gif", "svg", "mp3", "mp4", "wav", "mov", "zip", "tar", "gz",
        "com", "org", "net", "io", "dev", "app", "ai", "co", "edu", "gov",
        "me", "us", "uk",
    )

    /** "next dot js" -> "next.js" only when the word after "dot" is a known ext/TLD. */
    private fun normalizeDottedNames(text: String): String {
        val regex = Regex("\\b(\\w+)\\s+dot\\s+(\\w+)\\b", RegexOption.IGNORE_CASE)
        return regex.replace(text) { m ->
            val name = m.groupValues[1]
            val ext = m.groupValues[2]
            if (dottedExtensions.contains(ext.lowercase())) "$name.$ext" else m.value
        }
    }

    // ---- label colons ----

    private val labelWords: Set<String> = setOf(
        "re", "subject", "bug", "bug report", "feature", "feature request",
        "todo", "note", "warning", "error", "info", "important",
        "from", "to", "cc", "bcc", "date", "regarding",
        "step", "example", "output", "input", "result", "summary",
        "action", "action item", "title", "description",
    )

    private val sentenceStartLabels: Set<String> = setOf(
        "re", "subject", "bug", "bug report", "feature", "feature request",
        "todo", "note", "warning", "error", "info", "important", "regarding",
        "step", "example", "summary",
        "action", "action item", "title", "description",
    )

    private fun normalizeLabelColons(text: String): String {
        val regex = Regex("\\b(\\w+(?:\\s+\\w+)?)\\s+colon(?:\\s+(\\S+))?", RegexOption.IGNORE_CASE)
        return regex.replace(text) { m ->
            val label = m.groupValues[1]
            if (!labelWords.contains(label.lowercase())) return@replace m.value
            val capLabel = label.replaceFirstChar { it.uppercase() }
            val next = m.groupValues[2]
            if (next.isEmpty()) return@replace "$capLabel:"
            if (sentenceStartLabels.contains(label.lowercase())) {
                val skip = next.contains("@") || next.startsWith("/") ||
                    next.startsWith("http") || next.startsWith("www.") || next.startsWith("--")
                val capNext = if (!skip && next.firstOrNull()?.isLowerCase() == true)
                    next.replaceFirstChar { it.uppercase() } else next
                "$capLabel: $capNext"
            } else {
                "$capLabel: $next"
            }
        }
    }

    // ---- command-line ----

    private fun normalizeCommandPatterns(text: String): String =
        // Double-dash is handled earlier (always-safe). Single-dash before one char
        // is gated to non-code prose contexts only.
        Regex("\\bdash\\s+([a-zA-Z])\\b", RegexOption.IGNORE_CASE).replace(text, "-$1")

    // ---- ellipsis ----

    private fun normalizeEllipsis(text: String): String =
        Regex("\\bdot\\s+dot\\s+dot\\b", RegexOption.IGNORE_CASE).replace(text, "...")
}
