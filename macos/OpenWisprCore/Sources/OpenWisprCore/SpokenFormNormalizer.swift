import Foundation

/// Converts spoken punctuation and symbol names to their written forms. Swift port of
/// the Android `SpokenFormNormalizer`.
enum SpokenFormNormalizer {

    static func normalize(_ text: String, unambiguousOnly: Bool = false) -> String {
        if text.isEmpty { return text }
        var r = text

        if !unambiguousOnly { r = normalizeURLsAndPaths(r) }   // composite structures first
        r = normalizeUnambiguous(r)                            // always safe
        r = normalizeEllipsis(r)                               // "dot dot dot" -> "..."
        // "dash dash force" -> "--force" is unambiguous.
        r = KRegex("\\bdash\\s+dash\\s+(\\w+)\\b", ignoreCase: true).replace(r, "--$1")
        if !unambiguousOnly {
            r = normalizeLabelColons(r)                        // "re colon ..." -> "Re: ..."
            r = normalizeSpokenPunctuation(r)                  // "hello comma world" -> "hello, world"
            r = normalizeCommandPatterns(r)                    // single-dash flags
        }
        r = cleanupSymbolSpacing(r)
        r = KRegex("[ \\t]{2,}").replace(r, " ").ktTrim()      // collapse spaces, preserve newlines
        return r
    }

    // ---- spoken sentence punctuation (gated to prose) ----

    private static let determinerBeforeMark: Set<String> = [
        "a", "an", "the", "this", "that", "these", "those", "its", "his", "her",
        "their", "our", "my", "your", "of", "in", "during", "each", "every",
        "another", "no", "one", "semi",
    ]

    private static func normalizeSpokenPunctuation(_ text: String) -> String {
        let re = KRegex("\\b(\\w+)\\s+(full stop|period|comma|semicolon|colon)\\b", ignoreCase: true)
        return re.replace(text) { m in
            let prev = m.groupValues[1]
            if determinerBeforeMark.contains(prev.lowercased()) { return m.groupValues[0] }
            let mark: String
            switch m.groupValues[2].lowercased() {
            case "full stop", "period": mark = "."
            case "comma": mark = ","
            case "semicolon": mark = ";"
            case "colon": mark = ":"
            default: return m.groupValues[0]
            }
            return "\(prev)\(mark)"
        }
    }

    // ---- symbol spacing ----

    private static func cleanupSymbolSpacing(_ text: String) -> String {
        var r = text
        r = KRegex("\\s+([.,?!);:\\]%])").replace(r, "$1")    // no space before trailing punctuation
        r = KRegex("([(@\\[#$])\\s+").replace(r, "$1")         // no space after opening punctuation
        r = KRegex("\\s*_\\s*").replace(r, "_")                // join around underscore
        return r
    }

    // ---- unambiguous patterns ----

    private static let unambiguousPatterns: [(String, String)] = [
        ("question mark", "?"),
        ("exclamation point", "!"), ("exclamation mark", "!"),
        ("open parenthesis", "("), ("close parenthesis", ")"), ("closed parenthesis", ")"),
        ("left parenthesis", "("), ("right parenthesis", ")"),
        ("start parenthesis", "("), ("end parenthesis", ")"),
        ("parenthesis start", "("), ("parenthesis end", ")"),
        ("parenthesis open", "("), ("parenthesis close", ")"), ("parenthesis closed", ")"),
        ("open paren", "("), ("close paren", ")"), ("closed paren", ")"),
        ("left paren", "("), ("right paren", ")"), ("start paren", "("), ("end paren", ")"),
        ("open bracket", "["), ("close bracket", "]"),
        ("left bracket", "["), ("right bracket", "]"),
        ("open curly brace", "{"), ("close curly brace", "}"),
        ("left curly brace", "{"), ("right curly brace", "}"),
        ("open brace", "{"), ("close brace", "}"),
        ("underscore", "_"), ("ampersand", "&"), ("at sign", "@"),
        ("percent sign", "%"), ("dollar sign", "$"), ("equals sign", "="),
        ("hash sign", "#"), ("number sign", "#"), ("pound sign", "#"),
        ("plus sign", "+"), ("forward slash", "/"),
        ("backslash", "\\"), ("back slash", "\\"),
        ("pipe sign", "|"), ("pipe symbol", "|"),
        ("tilde sign", "~"), ("caret sign", "^"),
    ]

    private static func normalizeUnambiguous(_ text: String) -> String {
        var r = text
        for (spoken, written) in unambiguousPatterns {
            let regex = KRegex("\\b\(KRegex.escape(spoken))\\b", ignoreCase: true)
            // Literal replacement (written can be "$", "\", "^") — use the closure form.
            r = regex.replace(r) { _ in written }
        }
        return r
    }

    // ---- URL / email / path ----

    private static func normalizeURLsAndPaths(_ text: String) -> String {
        var r = text
        r = normalizeURLs(r)
        r = normalizeBareUrlPath(r)
        r = normalizeEmails(r)
        r = normalizePaths(r)
        r = normalizeDottedNames(r)
        return r
    }

    private static func normalizeBareUrlPath(_ text: String) -> String {
        let regex = KRegex(
            "\\b(\\w+(?:\\s+dot\\s+\\w+)+)\\s+slash\\s+(\\w+(?:\\s+slash\\s+\\w+)*)\\b",
            ignoreCase: true
        )
        return regex.replace(text) { m in
            let domain = KRegex("\\s+dot\\s+").replace(m.groupValues[1], ".")
            let path = KRegex("\\s+slash\\s+").replace(m.groupValues[2], "/")
            return "\(domain)/\(path)"
        }
    }

    private static let emailLocalStop: Set<String> = [
        "is", "at", "be", "am", "are", "was", "were", "the", "a", "an", "to", "in",
        "on", "of", "it", "and", "or", "but", "so", "we", "he", "she", "they",
        "you", "i", "me", "us", "this", "that", "here", "there",
    ]

    private static func normalizeURLs(_ text: String) -> String {
        let regex = KRegex(
            "\\b(https?|ftp|ssh|git)\\s+colon\\s+slash\\s+slash\\s+(\\S+(?:\\s+dot\\s+\\S+)+(?:\\s+slash\\s+\\S+)*)\\b",
            ignoreCase: true
        )
        return regex.replace(text) { m in
            let proto = m.groupValues[1]
            var rest = m.groupValues[2]
            rest = KRegex("\\s+dot\\s+").replace(rest, ".")
            rest = KRegex("\\s+slash\\s+").replace(rest, "/")
            return "\(proto)://\(rest)"
        }
    }

    private static func normalizeEmails(_ text: String) -> String {
        let regex = KRegex(
            "\\b((?:\\w+\\s+dot\\s+)*\\w+)\\s+at\\s+(\\w+(?:\\s+dot\\s+\\w+)+)\\b",
            ignoreCase: true
        )
        return regex.replace(text) { m in
            let localRaw = m.groupValues[1]
            // Skip if the local-part is a common word ("is at X" is not an email).
            if let lastWord = localRaw.ktSplitWhitespace().last,
               emailLocalStop.contains(lastWord.lowercased()) {
                return m.groupValues[0]
            }
            let local = KRegex("\\s+dot\\s+").replace(localRaw, ".")
            let domain = KRegex("\\s+dot\\s+").replace(m.groupValues[2], ".")
            return "\(local)@\(domain)"
        }
    }

    private static func normalizePaths(_ text: String) -> String {
        let regex = KRegex(
            "\\bslash\\s+(\\w+(?:\\s+dot\\s+\\w+)?(?:\\s+slash\\s+\\w+(?:\\s+dot\\s+\\w+)?)+)\\b",
            ignoreCase: true
        )
        return regex.replace(text) { m in
            var content = KRegex("\\s+slash\\s+").replace(m.groupValues[1], "/")
            content = KRegex("\\s+dot\\s+").replace(content, ".")
            return "/\(content)"
        }
    }

    private static let dottedExtensions: Set<String> = [
        "js", "ts", "jsx", "tsx", "py", "rb", "rs", "go", "swift", "java",
        "kt", "c", "cpp", "h", "cs", "php", "html", "css", "scss", "json",
        "xml", "yaml", "yml", "toml", "md", "txt", "pdf", "doc", "docx",
        "xls", "xlsx", "ppt", "pptx", "csv", "log", "env", "sh", "bash",
        "zsh", "fish", "conf", "cfg", "ini", "lock", "png", "jpg", "jpeg",
        "gif", "svg", "mp3", "mp4", "wav", "mov", "zip", "tar", "gz",
        "com", "org", "net", "io", "dev", "app", "ai", "co", "edu", "gov",
        "me", "us", "uk",
    ]

    private static func normalizeDottedNames(_ text: String) -> String {
        let regex = KRegex("\\b(\\w+)\\s+dot\\s+(\\w+)\\b", ignoreCase: true)
        return regex.replace(text) { m in
            let name = m.groupValues[1]
            let ext = m.groupValues[2]
            return dottedExtensions.contains(ext.lowercased()) ? "\(name).\(ext)" : m.groupValues[0]
        }
    }

    // ---- label colons ----

    private static let labelWords: Set<String> = [
        "re", "subject", "bug", "bug report", "feature", "feature request",
        "todo", "note", "warning", "error", "info", "important",
        "from", "to", "cc", "bcc", "date", "regarding",
        "step", "example", "output", "input", "result", "summary",
        "action", "action item", "title", "description",
    ]

    private static let sentenceStartLabels: Set<String> = [
        "re", "subject", "bug", "bug report", "feature", "feature request",
        "todo", "note", "warning", "error", "info", "important", "regarding",
        "step", "example", "summary",
        "action", "action item", "title", "description",
    ]

    private static func normalizeLabelColons(_ text: String) -> String {
        let regex = KRegex("\\b(\\w+(?:\\s+\\w+)?)\\s+colon(?:\\s+(\\S+))?", ignoreCase: true)
        return regex.replace(text) { m in
            let label = m.groupValues[1]
            if !labelWords.contains(label.lowercased()) { return m.groupValues[0] }
            let capLabel = label.ktReplaceFirstCharUppercase()
            let next = m.groupValues[2]
            if next.isEmpty { return "\(capLabel):" }
            if sentenceStartLabels.contains(label.lowercased()) {
                let skip = next.contains("@") || next.hasPrefix("/") ||
                    next.hasPrefix("http") || next.hasPrefix("www.") || next.hasPrefix("--")
                let capNext = (!skip && (next.first?.isLowercase ?? false))
                    ? next.ktReplaceFirstCharUppercase() : next
                return "\(capLabel): \(capNext)"
            } else {
                return "\(capLabel): \(next)"
            }
        }
    }

    // ---- command-line ----

    private static func normalizeCommandPatterns(_ text: String) -> String {
        KRegex("\\bdash\\s+([a-zA-Z])\\b", ignoreCase: true).replace(text, "-$1")
    }

    // ---- ellipsis ----

    private static func normalizeEllipsis(_ text: String) -> String {
        KRegex("\\bdot\\s+dot\\s+dot\\b", ignoreCase: true).replace(text, "...")
    }
}
