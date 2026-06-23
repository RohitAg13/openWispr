import Foundation

/// Config for the deterministic pipeline.
public struct TextProcessingConfig {
    public let selfCorrectionEnabled: Bool
    public let fillerRemovalEnabled: Bool
    public let listFormattingEnabled: Bool
    public let capitalizationEnabled: Bool
    public let fillerWords: [String]

    /// Phrase fillers safe for word-boundary removal.
    public static let DEFAULT_FILLER_WORDS = [
        "you know", "basically", "literally",
    ]

    public init(
        selfCorrectionEnabled: Bool = true,
        fillerRemovalEnabled: Bool = true,
        listFormattingEnabled: Bool = true,
        capitalizationEnabled: Bool = true,
        fillerWords: [String] = TextProcessingConfig.DEFAULT_FILLER_WORDS
    ) {
        self.selfCorrectionEnabled = selfCorrectionEnabled
        self.fillerRemovalEnabled = fillerRemovalEnabled
        self.listFormattingEnabled = listFormattingEnabled
        self.capitalizationEnabled = capitalizationEnabled
        self.fillerWords = fillerWords
    }
}

/// One sub-stage's before/after, for debug logging.
public struct StageResult {
    public let name: String
    public let input: String
    public let output: String
    public var changed: Bool { input != output }
}

public struct TextProcessingResult {
    public let output: String
    public let stages: [StageResult]
}

/// Orchestrates the deterministic, zero-latency cleanup pipeline. Port of the cleanup pipeline's
/// TextProcessor.
public enum TextProcessor {

    public static func process(
        _ rawText: String,
        config: TextProcessingConfig = TextProcessingConfig(),
        isCodeContext: Bool = false
    ) -> String {
        processWithDetails(rawText, config: config, isCodeContext: isCodeContext).output
    }

    public static func processWithDetails(
        _ rawText: String,
        config: TextProcessingConfig = TextProcessingConfig(),
        isCodeContext: Bool = false
    ) -> TextProcessingResult {
        var text = rawText
        var stages: [StageResult] = []

        // 0. Spelled-out entities ("spelled k a y l a" -> "Kayla").
        do {
            let before = text
            text = EntityNormalizer.joinSpelledLetters(text)
            stages.append(StageResult(name: "spelled entities", input: before, output: text))
        }

        // 1. Self-correction.
        if config.selfCorrectionEnabled {
            let before = text
            text = SelfCorrectionDetector.detectAndResolve(text)
            // If a correction cut the text in half, recapitalize the survivor. Skip for code.
            if !isCodeContext {
                let bw = before.ktSplitWhitespace().filter { !$0.isEmpty }.count
                let aw = text.ktSplitWhitespace().filter { !$0.isEmpty }.count
                if bw > 0 && aw > 0 && Double(aw) / Double(bw) <= 0.5 &&
                    (text.first?.isLowercase ?? false) {
                    text = text.ktReplaceFirstCharUppercase()
                }
            }
            stages.append(StageResult(name: "self-correction", input: before, output: text))
        }

        // 2. Filler removal.
        if config.fillerRemovalEnabled {
            let before = text
            text = FillerWordRemover.removeFillers(text, fillerWords: config.fillerWords)
            stages.append(StageResult(name: "filler removal", input: before, output: text))
        }

        // 3. Spoken forms (unambiguous-only in code/terminal).
        do {
            let before = text
            text = SpokenFormNormalizer.normalize(text, unambiguousOnly: isCodeContext)
            stages.append(StageResult(name: "spoken forms", input: before, output: text))
        }

        // 4. Numbers.
        do {
            let before = text
            text = NumberNormalizer.normalize(text)
            stages.append(StageResult(name: "number normalization", input: before, output: text))
        }

        // 5. Structure: spoken "new line"/"new paragraph" + explicit numbered lists.
        if config.listFormattingEnabled {
            let before = text
            text = ListFormatter.format(text)
            stages.append(StageResult(name: "list formatting", input: before, output: text))
        }

        // 6. Sentence capitalization (newline-aware). Skipped in code/terminal fields.
        if config.capitalizationEnabled && !isCodeContext {
            let before = text
            text = Capitalizer.capitalizeSentences(text)
            stages.append(StageResult(name: "capitalization", input: before, output: text))
        }

        return TextProcessingResult(output: text, stages: stages)
    }
}
