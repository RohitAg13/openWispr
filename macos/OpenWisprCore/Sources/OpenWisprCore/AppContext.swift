import Foundation

/// Classifies the focused app into a context category so dictation can adapt its
/// tone (formal in email/office, casual in chat). Builds on `CodeContext` (which
/// decides code/terminal handling for the deterministic stages); this adds the
/// broader category used to tune the optional LLM-polish prompt.
///
/// The package map is partial/substring-based for robustness across OEM variants.
/// Faithful port of the Kotlin `AppContext` object.
public enum AppContext {

    public enum Category: String, CaseIterable {
        case generic = "generic"
        case code
        case email
        case chat
        case social
        case notes

        /// Kotlin `key` (= the raw value).
        public var key: String { rawValue }

        /// Kotlin `label`.
        public var label: String {
            switch self {
            case .generic: return "Other apps"
            case .code: return "Code & terminals"
            case .email: return "Email & docs"
            case .chat: return "Chat & messaging"
            case .social: return "Social"
            case .notes: return "Notes"
            }
        }
    }

    /// Default tone fragment appended to the LLM-polish prompt per category.
    public static let DEFAULT_TONE: [Category: String] = [
        .email: "Write this for a professional, work context: clear and polite, " +
            "complete sentences, no slang or emoji.",
        .chat: "Keep it casual and conversational, like a chat message: relaxed " +
            "phrasing and contractions, concise.",
        .social: "Keep it casual, natural and a little punchy.",
        .notes: "",
        .generic: "",
        .code: "",
    ]

    private static let emailHints = [
        "gmail", "com.google.android.gm", "outlook", "office.word", "office.outlook",
        "apps.docs", "yahoo.mobile.client.android.mail", "fastmail", "spark", "notion",
        "superhuman", "proton.android.mail", "protonmail",
    ]
    private static let chatHints = [
        "whatsapp", "telegram", "org.thoughtcrime.securesms", "messaging", "messenger",
        "facebook.orca", "slack", "discord", "com.google.android.apps.messaging",
        "samsung.android.messaging", "wechat", "viber", "skype", "teams", "google.android.talk",
    ]
    private static let socialHints = [
        "twitter", "com.twitter", "x.android", "reddit", "instagram", "threads", "mastodon",
        "bluesky", "bsky", "linkedin", "snapchat", "tiktok",
    ]
    private static let notesHints = [
        "keep", "samsung.android.app.notes", "obsidian", "bear", "standardnotes",
        "simplenote", "joplin", "evernote", "onenote",
    ]

    /// Category of `text` dictated into `pkg`; code/terminal is decided by content.
    public static func categoryFor(_ pkg: String?, _ text: String) -> Category {
        if CodeContext.useCodeMode(pkg, text) { return .code }
        guard let p = pkg?.lowercased() else { return .generic }
        if emailHints.contains(where: { p.contains($0) }) { return .email }
        if chatHints.contains(where: { p.contains($0) }) { return .chat }
        if socialHints.contains(where: { p.contains($0) }) { return .social }
        if notesHints.contains(where: { p.contains($0) }) { return .notes }
        return .generic
    }
}
