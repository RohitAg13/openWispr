package com.voicerewriter.textproc

/**
 * One personal-vocabulary term. The recognizer mishears proper nouns and jargon
 * ("Rohit" -> "row hit", "Silero" -> "Silyro"); no cleanup can recover that, so we
 * snap near-misses back to [canonical] after transcription (see [VocabCorrector]).
 *
 * - [canonical]: the correct spelling to insert ("Rohit", "Kubernetes").
 * - [aliases]: explicit known mishearings ("row hit", "silyro") — always matched.
 *   Fuzzy/phonetic matching also catches unlisted mishearings.
 *
 * Part of Phase 2 of docs/names-emails-plan.md.
 */
data class VocabEntry(
    val canonical: String,
    val aliases: List<String> = emptyList(),
) {
    /** All spoken forms to match against, longest (most tokens) first. */
    fun matchPhrases(): List<String> =
        (listOf(canonical) + aliases)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
