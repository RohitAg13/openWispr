package com.voicerewriter

import com.voicerewriter.textproc.TextProcessor

/**
 * Turns a raw transcript into a short "here's what just happened to your words" list, used by
 * the onboarding try-it step to teach the cleanup features on the user's own sentence instead
 * of on canned examples.
 *
 * The deterministic pipeline already records which of its stages actually changed the text
 * ([TextProcessor.processWithDetails]), so this reads the real result rather than inferring it
 * from a before/after diff. Re-running the pipeline here is pure and cheap, which means the
 * dictation path itself needs no changes and nothing extra has to be persisted.
 */
object PolishHighlights {

    /**
     * The stages worth showing, most striking first. Capitalization is deliberately absent:
     * it fires on nearly every sentence and "Capitalized" earns nothing next to the others.
     */
    private val ORDER = listOf(
        "self-correction", "filler removal", "spoken forms",
        "list formatting", "number normalization",
    )

    /** Pipeline stage name to what we call it in front of a user. */
    private val LABELS = mapOf(
        "self-correction" to "Caught your correction",
        "filler removal" to "Filler removed",
        "spoken forms" to "Punctuation added",
        "list formatting" to "Laid out as a list",
        "number normalization" to "Numbers formatted",
    )

    /** How to describe a capability this particular sentence didn't happen to exercise. */
    private val ALSO = mapOf(
        "self-correction" to "fixes mid-sentence corrections",
        "filler removal" to "strips filler words",
        "spoken forms" to "adds punctuation as you speak",
        "list formatting" to "lays out spoken lists",
        "number normalization" to "formats spoken numbers",
    )

    /** Stage names that actually changed [raw], in display order. */
    fun firedStages(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val changed = TextProcessor.processWithDetails(raw)
            .stages.filter { it.changed }.map { it.name }.toSet()
        return ORDER.filter { it in changed }
    }

    /** User-facing labels for what the cleanup did to [raw]. */
    fun labels(raw: String): List<String> = firedStages(raw).mapNotNull { LABELS[it] }

    /**
     * One quiet sentence naming up to two capabilities this sentence didn't show off, so the
     * feature set lands without turning into a list nobody reads. Null when the user managed
     * to trigger everything.
     */
    fun alsoHandles(raw: String): String? {
        val fired = firedStages(raw).toSet()
        val rest = ORDER.filterNot { it in fired }.mapNotNull { ALSO[it] }.take(2)
        return if (rest.isEmpty()) null else "It also ${rest.joinToString(" and ")}."
    }
}
