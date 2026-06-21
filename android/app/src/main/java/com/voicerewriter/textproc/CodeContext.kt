package com.voicerewriter.textproc

/**
 * Decides whether to treat dictation as a code/terminal context, where the
 * spoken-form normalizer runs in unambiguous-only mode so "dot", "slash", and
 * single "dash" survive as literal words.
 *
 * Learned from real Wispr Flow usage: ~70% of this user's dictations go into a
 * terminal (iTerm), but they're natural-language *prompts to an AI agent*, not
 * shell commands. So a terminal is NOT automatically a code context — we look at
 * the content. Dedicated code editors are still treated as code. Best-effort: an
 * unknown package with prose-looking text is treated as prose.
 */
object CodeContext {

    // Dedicated code editors — content here is overwhelmingly code.
    private val codeEditors = setOf(
        "com.microsoft.vscode", "com.itsaky.androidide", "com.foxdebug.acode",
        "com.spck.editor", "com.rhmsoft.code", "com.sketchware.remod",
        "com.jetbrains.intellij",
    )

    // Terminals — could be commands OR (often) AI prompts; decide by content.
    private val terminals = setOf(
        "com.googlecode.iterm2", "com.apple.terminal", "com.termux", "com.termux.styling",
        "com.server.auditor.ssh.client", "org.connectbot", "dev.warp.warp",
    )

    private val terminalHints = listOf("term", "terminal", "iterm", "tmux", "ssh", "shell", "console")

    // Common command leaders — a strong "this is a shell command" signal.
    private val commandVerbs = setOf(
        "cd", "ls", "pwd", "git", "npm", "npx", "node", "yarn", "pnpm", "python", "python3",
        "pip", "pip3", "brew", "docker", "kubectl", "ssh", "scp", "sudo", "cat", "less",
        "tail", "head", "grep", "rg", "awk", "sed", "curl", "wget", "make", "cargo", "go",
        "rustc", "tmux", "chmod", "chown", "mkdir", "rm", "cp", "mv", "touch", "export",
        "source", "kill", "ps", "top", "vim", "nvim", "nano", "code", "open", "echo",
    )

    /** Whether [text] dictated into [pkg] should use code/terminal normalization. */
    fun useCodeMode(pkg: String?, text: String): Boolean {
        val p = pkg?.lowercase() ?: return false
        if (p in codeEditors) return true
        if (p in terminals || terminalHints.any { p.contains(it) }) return looksLikeCommand(text)
        return false
    }

    /** Conservative: commands are short and start with a known verb / flag / path. */
    private fun looksLikeCommand(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val words = t.split(Regex("\\s+"))
        if (words.size > 10) return false // long => natural-language prompt, not a command
        val first = words[0].lowercase().trim('$', '>', '#')
        if (first in commandVerbs) return true
        if (t.startsWith("/") || t.startsWith("~/") || t.startsWith("./")) return true
        if (Regex("\\s-{1,2}[A-Za-z]").containsMatchIn(t)) return true // has a CLI flag
        return false
    }
}
