package com.voicerewriter.textproc

/**
 * Classifies a target app package as a code/terminal context. In those fields the
 * spoken-form normalizer runs in unambiguous-only mode so "dot", "slash", and
 * "dash" survive as literal words (you're dictating identifiers and commands, not
 * prose). Best-effort: an unknown package is treated as prose.
 */
object CodeContext {

    // Exact packages known to be terminals or code editors.
    private val codePackages = setOf(
        "com.termux", "com.termux.styling",
        "com.server.auditor.ssh.client",   // Termius
        "org.connectbot",
        "com.itsaky.androidide",            // AndroidIDE
        "com.foxdebug.acode",               // Acode
        "com.spck.editor",                  // Spck
        "com.rhmsoft.code",                 // Quoda
        "com.github.android",               // GitHub mobile (code views/comments)
        "com.sketchware.remod",
    )

    // Substrings that strongly imply a developer tool.
    private val codeHints = listOf("termux", "terminal", "ssh", "androidide", "code", "ide", "shell")

    fun isCode(packageName: String?): Boolean {
        val pkg = packageName?.lowercase() ?: return false
        if (codePackages.contains(pkg)) return true
        return codeHints.any { pkg.contains(it) }
    }
}
