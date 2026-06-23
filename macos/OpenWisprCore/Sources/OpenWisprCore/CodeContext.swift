import Foundation

/// Decides whether to treat dictation as a code/terminal context, where the
/// spoken-form normalizer runs in unambiguous-only mode so "dot", "slash", and
/// single "dash" survive as literal words.
///
/// A terminal is NOT automatically a code context — we look at the content, since
/// terminals often receive natural-language prompts to an AI agent. Dedicated code
/// editors are still treated as code. Faithful port of the Kotlin `CodeContext` object.
public enum CodeContext {

    // Dedicated code editors — content here is overwhelmingly code.
    private static let codeEditors: Set<String> = [
        "com.microsoft.vscode", "com.itsaky.androidide", "com.foxdebug.acode",
        "com.spck.editor", "com.rhmsoft.code", "com.sketchware.remod",
        "com.jetbrains.intellij",
    ]

    // Terminals — could be commands OR (often) AI prompts; decide by content.
    private static let terminals: Set<String> = [
        "com.googlecode.iterm2", "com.apple.terminal", "com.termux", "com.termux.styling",
        "com.server.auditor.ssh.client", "org.connectbot", "dev.warp.warp",
    ]

    private static let terminalHints = ["term", "terminal", "iterm", "tmux", "ssh", "shell", "console"]

    // Common command leaders — a strong "this is a shell command" signal.
    private static let commandVerbs: Set<String> = [
        "cd", "ls", "pwd", "git", "npm", "npx", "node", "yarn", "pnpm", "python", "python3",
        "pip", "pip3", "brew", "docker", "kubectl", "ssh", "scp", "sudo", "cat", "less",
        "tail", "head", "grep", "rg", "awk", "sed", "curl", "wget", "make", "cargo", "go",
        "rustc", "tmux", "chmod", "chown", "mkdir", "rm", "cp", "mv", "touch", "export",
        "source", "kill", "ps", "top", "vim", "nvim", "nano", "code", "open", "echo",
    ]

    private static let flagRegex = KRegex("\\s-{1,2}[A-Za-z]")

    /// Whether `text` dictated into `pkg` should use code/terminal normalization.
    public static func useCodeMode(_ pkg: String?, _ text: String) -> Bool {
        guard let p = pkg?.lowercased() else { return false }
        if codeEditors.contains(p) { return true }
        if terminals.contains(p) || terminalHints.contains(where: { p.contains($0) }) {
            return looksLikeCommand(text)
        }
        return false
    }

    /// Conservative: commands are short and start with a known verb / flag / path.
    private static func looksLikeCommand(_ text: String) -> Bool {
        let t = text.ktTrim()
        if t.isEmpty { return false }
        let words = t.ktSplitWhitespace()
        if words.count > 10 { return false } // long => natural-language prompt, not a command
        let first = words[0].lowercased().ktTrim("$", ">", "#")
        if commandVerbs.contains(first) { return true }
        if t.hasPrefix("/") || t.hasPrefix("~/") || t.hasPrefix("./") { return true }
        if !flagRegex.findAll(t).isEmpty { return true } // has a CLI flag
        return false
    }
}
