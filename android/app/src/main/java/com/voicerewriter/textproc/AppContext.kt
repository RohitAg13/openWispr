package com.voicerewriter.textproc

/**
 * Classifies the focused app into a context category so dictation can adapt its
 * tone (formal in email/office, casual in chat). Builds on [CodeContext] (which
 * decides code/terminal handling for the deterministic stages); this adds the
 * broader category used to tune the optional LLM-polish prompt.
 *
 * The package map is partial/substring-based for robustness across OEM variants.
 * Validated against this user's real usage in docs/wispr-learnings.md.
 */
object AppContext {

    enum class Category(val key: String, val label: String) {
        GENERIC("generic", "Other apps"),
        CODE("code", "Code & terminals"),
        EMAIL("email", "Email & docs"),
        CHAT("chat", "Chat & messaging"),
        SOCIAL("social", "Social"),
        NOTES("notes", "Notes"),
    }

    /** Default tone fragment appended to the LLM-polish prompt per category. */
    val DEFAULT_TONE: Map<Category, String> = mapOf(
        Category.EMAIL to "Write this for a professional, work context: clear and polite, " +
            "complete sentences, no slang or emoji.",
        Category.CHAT to "Keep it casual and conversational, like a chat message: relaxed " +
            "phrasing and contractions, concise.",
        Category.SOCIAL to "Keep it casual, natural and a little punchy.",
        Category.NOTES to "",
        Category.GENERIC to "",
        Category.CODE to "",
    )

    private val emailHints = listOf(
        "gmail", "com.google.android.gm", "outlook", "office.word", "office.outlook",
        "apps.docs", "yahoo.mobile.client.android.mail", "fastmail", "spark", "notion",
        "superhuman", "proton.android.mail", "protonmail",
    )
    private val chatHints = listOf(
        "whatsapp", "telegram", "org.thoughtcrime.securesms", "messaging", "messenger",
        "facebook.orca", "slack", "discord", "com.google.android.apps.messaging",
        "samsung.android.messaging", "wechat", "viber", "skype", "teams", "google.android.talk",
    )
    private val socialHints = listOf(
        "twitter", "com.twitter", "x.android", "reddit", "instagram", "threads", "mastodon",
        "bluesky", "bsky", "linkedin", "snapchat", "tiktok",
    )
    private val notesHints = listOf(
        "keep", "samsung.android.app.notes", "obsidian", "bear", "standardnotes",
        "simplenote", "joplin", "evernote", "onenote",
    )

    /** Category of [text] dictated into [pkg]; code/terminal is decided by content. */
    fun categoryFor(pkg: String?, text: String): Category {
        if (CodeContext.useCodeMode(pkg, text)) return Category.CODE
        val p = pkg?.lowercase() ?: return Category.GENERIC
        return when {
            emailHints.any { p.contains(it) } -> Category.EMAIL
            chatHints.any { p.contains(it) } -> Category.CHAT
            socialHints.any { p.contains(it) } -> Category.SOCIAL
            notesHints.any { p.contains(it) } -> Category.NOTES
            else -> Category.GENERIC
        }
    }
}
