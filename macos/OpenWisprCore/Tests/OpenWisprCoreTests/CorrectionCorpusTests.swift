import XCTest
@testable import OpenWisprCore

/// Pins the pure correction-corpus ranking + few-shot + learn-from-edit diff (port of Android
/// `CorrectionCorpus` / `VocabRepository.learnFromEdit`).
final class CorrectionCorpusTests: XCTestCase {

    private func sample(_ cleaned: String, _ kept: String, category: String = "generic", edited: Bool = false) -> CorrectionSample {
        CorrectionSample(ts: 0, category: category, cleaned: cleaned, kept: kept, edited: edited)
    }

    // MARK: - rank / similar

    func testRankFindsOverlappingSample() {
        let samples = [
            sample("let's ship the kubernetes deployment today", "Let's ship the Kubernetes deployment today."),
            sample("buy milk and eggs", "Buy milk and eggs."),
        ]
        let top = CorrectionCorpus.rank(samples, query: "ship the kubernetes cluster", category: "generic", k: 1)
        XCTAssertEqual(top.count, 1)
        XCTAssertTrue(top[0].cleaned.contains("kubernetes"))
    }

    func testRankIgnticesStopwordOnlyOverlap() {
        let samples = [sample("the the the and a", "the the the and a")]
        // Only stopword/short-token overlap → below MIN_JACCARD → no match.
        let top = CorrectionCorpus.rank(samples, query: "kubernetes deployment pipeline", category: "generic", k: 2)
        XCTAssertTrue(top.isEmpty)
    }

    func testRankPrefersSameCategoryAndEdited() {
        let a = sample("deploy the staging server now", "deploy the staging server now", category: "generic", edited: false)
        let b = sample("deploy the staging server now", "Deploy the staging server now.", category: "code", edited: true)
        let top = CorrectionCorpus.rank([a, b], query: "deploy the staging server", category: "code", k: 1)
        XCTAssertEqual(top.first?.category, "code") // same-category + edited boosts win
    }

    func testRankEmptyQuery() {
        XCTAssertTrue(CorrectionCorpus.rank([sample("a b c", "a b c")], query: "", category: "generic", k: 2).isEmpty)
    }

    // MARK: - fewShotBlock

    func testFewShotBlockFormatsPairs() {
        let block = CorrectionCorpus.fewShotBlock([sample("raw text here", "Raw text here.")])
        XCTAssertTrue(block.contains("Raw: raw text here"))
        XCTAssertTrue(block.contains("Kept: Raw text here."))
    }

    func testFewShotBlockEmptyForNoSamples() {
        XCTAssertEqual(CorrectionCorpus.fewShotBlock([]), "")
    }

    // MARK: - learnFromEditPairs

    func testLearnFromEditDetectsNameFix() {
        // A real mishearing ("rowhit" → "Rohit") — differs beyond case, like Android.
        let pairs = CorrectionCorpus.learnFromEditPairs(
            original: "tell rowhit about the plan",
            edited: "tell Rohit about the plan"
        )
        XCTAssertEqual(pairs.count, 1)
        XCTAssertEqual(pairs[0].wrong, "rowhit")
        XCTAssertEqual(pairs[0].right, "Rohit")
    }

    func testLearnFromEditIgnoresPureCaseChange() {
        // Pure case fix is not learned (matches Android's ignoreCase equality guard).
        XCTAssertTrue(CorrectionCorpus.learnFromEditPairs(
            original: "tell rohit hi", edited: "tell Rohit hi").isEmpty)
    }

    func testLearnFromEditRejectsLengthMismatch() {
        // Different token counts → not a position-aligned fix.
        XCTAssertTrue(CorrectionCorpus.learnFromEditPairs(
            original: "hello there", edited: "hello there friend").isEmpty)
    }

    func testLearnFromEditRejectsBigRewrite() {
        // > 5 substitutions → a rewrite, don't pollute vocab.
        let pairs = CorrectionCorpus.learnFromEditPairs(
            original: "aa bb cc dd ee ff",
            edited: "az bz cz dz ez fz"
        )
        XCTAssertTrue(pairs.isEmpty)
    }

    func testLearnFromEditIgnoresNumbersAndEmails() {
        // Non-name-like tokens (numbers / emails) are skipped.
        let pairs = CorrectionCorpus.learnFromEditPairs(
            original: "call 555 now", edited: "call 556 now")
        XCTAssertTrue(pairs.isEmpty)
    }
}
