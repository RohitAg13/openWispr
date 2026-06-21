package com.voicerewriter.textproc

import android.util.Log

/** Config for the deterministic pipeline. Inspired by the cleanup pipeline's TextProcessingConfig. */
data class TextProcessingConfig(
    val selfCorrectionEnabled: Boolean = true,
    val fillerRemovalEnabled: Boolean = true,
    val listFormattingEnabled: Boolean = true,
    val capitalizationEnabled: Boolean = true,
    val fillerWords: List<String> = DEFAULT_FILLER_WORDS,
) {
    companion object {
        /**
         * Phrase fillers safe for word-boundary removal. Hesitation sounds (um/uh/
         * hmm + variants) are handled by a regex pass in FillerWordRemover, so they're
         * not listed here. Excludes high-false-positive words ("like" as a verb,
         * "right" as direction, "so" as connector) — the LLM polish layer handles
         * those nuances better when it's enabled.
         */
        val DEFAULT_FILLER_WORDS = listOf(
            "you know", "basically", "literally",
        )
    }
}

/** One sub-stage's before/after, for debug logging. */
data class StageResult(val name: String, val input: String, val output: String) {
    val changed: Boolean get() = input != output
}

data class TextProcessingResult(val output: String, val stages: List<StageResult>)

/**
 * Orchestrates the deterministic, zero-latency cleanup pipeline that runs on a raw
 * Whisper transcript before (and independently of) any LLM. Kotlin port of the cleanup pipeline's
 * TextProcessor (github.com/openwispr). Order matters:
 *
 *   1. self-correction  (runs first, before filler removal eats correction markers)
 *   2. filler removal
 *   3. spoken-form normalization  (skips ambiguous rules in code/terminal fields)
 *   4. number normalization
 *   5. structure: "new line"/"new paragraph" + numbered lists  (introduces newlines)
 *   6. sentence capitalization  (newline-aware; skipped in code/terminal fields)
 */
object TextProcessor {

    private const val TAG = "TextProcessor"

    fun process(rawText: String, config: TextProcessingConfig = TextProcessingConfig(), isCodeContext: Boolean = false): String =
        processWithDetails(rawText, config, isCodeContext).output

    fun processWithDetails(
        rawText: String,
        config: TextProcessingConfig = TextProcessingConfig(),
        isCodeContext: Boolean = false,
    ): TextProcessingResult {
        var text = rawText
        val stages = ArrayList<StageResult>()

        // 0. Spelled-out entities ("spelled k a y l a" -> "Kayla") — runs first so
        //    downstream stages treat the assembled name as one token.
        run {
            val before = text
            text = EntityNormalizer.joinSpelledLetters(text)
            stages.add(StageResult("spelled entities", before, text))
        }

        // 1. Self-correction.
        if (config.selfCorrectionEnabled) {
            val before = text
            text = SelfCorrectionDetector.detectAndResolve(text)
            // If a correction cut the text in half, recapitalize the survivor
            // ("send to mark, scratch that, to john" -> "To john"). Skip for code.
            if (!isCodeContext) {
                val bw = before.split(Regex("\\s+")).count { it.isNotEmpty() }
                val aw = text.split(Regex("\\s+")).count { it.isNotEmpty() }
                if (bw > 0 && aw > 0 && aw.toDouble() / bw <= 0.5 &&
                    text.firstOrNull()?.isLowerCase() == true) {
                    text = text.replaceFirstChar { it.uppercase() }
                }
            }
            stages.add(StageResult("self-correction", before, text))
        }

        // 2. Filler removal.
        if (config.fillerRemovalEnabled) {
            val before = text
            text = FillerWordRemover.removeFillers(text, config.fillerWords)
            stages.add(StageResult("filler removal", before, text))
        }

        // 3. Spoken forms (unambiguous-only in code/terminal).
        run {
            val before = text
            text = SpokenFormNormalizer.normalize(text, unambiguousOnly = isCodeContext)
            stages.add(StageResult("spoken forms", before, text))
        }

        // 4. Numbers.
        run {
            val before = text
            text = NumberNormalizer.normalize(text)
            stages.add(StageResult("number normalization", before, text))
        }

        // 5. Structure: spoken "new line"/"new paragraph" + explicit numbered lists.
        //    Runs after numbers so digits are settled and no earlier stage flattens
        //    the newlines it introduces.
        if (config.listFormattingEnabled) {
            val before = text
            text = ListFormatter.format(text)
            stages.add(StageResult("list formatting", before, text))
        }

        // 6. Sentence capitalization (newline-aware). Skipped in code/terminal fields.
        if (config.capitalizationEnabled && !isCodeContext) {
            val before = text
            text = Capitalizer.capitalizeSentences(text)
            stages.add(StageResult("capitalization", before, text))
        }

        if (stages.any { it.changed }) {
            Log.i(TAG, "cleaned: " + stages.filter { it.changed }.joinToString(", ") { it.name })
        }
        return TextProcessingResult(text, stages)
    }
}
