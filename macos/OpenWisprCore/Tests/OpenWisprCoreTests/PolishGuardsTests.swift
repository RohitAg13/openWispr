import XCTest
@testable import OpenWisprCore

/// Pins the on-device-LLM over-edit guards (port of Android `RewriteEngine`) and the polish
/// prompt builder. These behaviors are what keep a tiny looping/inventing model from wrecking
/// a clean transcript, so they must not drift.
final class PolishGuardsTests: XCTestCase {

    // MARK: - hasSelfCorrection

    func testSelfCorrectionDetected() {
        XCTAssertTrue(PolishGuards.hasSelfCorrection("send it monday, actually tuesday"))
        XCTAssertTrue(PolishGuards.hasSelfCorrection("buy milk, scratch that, oat milk"))
        XCTAssertTrue(PolishGuards.hasSelfCorrection("I MEAN the other one")) // case-insensitive
    }

    func testNoSelfCorrection() {
        XCTAssertFalse(PolishGuards.hasSelfCorrection("let's meet at three"))
    }

    // MARK: - preservesContent

    func testPreservesContentKeepsCleanup() {
        // A faithful cleanup keeps essentially all words.
        XCTAssertTrue(PolishGuards.preservesContent(
            input: "um so i think we should ship it today",
            output: "I think we should ship it today.",
            relaxed: false))
    }

    func testPreservesContentRejectsBalloon() {
        // Short fragment → long invented message.
        let invented = String(repeating: "additionally the team will reconvene next week ", count: 6)
        XCTAssertFalse(PolishGuards.preservesContent(
            input: "ship it", output: invented, relaxed: false))
    }

    func testPreservesContentRejectsHeavyDrop() {
        // Dropped most of the words, not a self-correction → reject at the 60% gate.
        XCTAssertFalse(PolishGuards.preservesContent(
            input: "alpha bravo charlie delta echo foxtrot golf hotel",
            output: "alpha bravo.",
            relaxed: false))
    }

    func testPreservesContentRelaxedAllowsMoreDrop() {
        // Same drop is allowed when relaxed (speaker self-corrected).
        XCTAssertTrue(PolishGuards.preservesContent(
            input: "send it monday actually no tuesday",
            output: "Send it Tuesday.",
            relaxed: true))
    }

    func testPreservesContentEmptyInput() {
        XCTAssertTrue(PolishGuards.preservesContent(input: "", output: "anything", relaxed: false))
    }

    // MARK: - looksRepeating

    func testLooksRepeatingDetectsLoop() {
        let looped = "The meeting is at three today. The meeting is at three today. The meeting is"
        XCTAssertTrue(PolishGuards.looksRepeating(looped))
    }

    func testLooksNotRepeatingOnNormalText() {
        let normal = "The meeting is at three today. Bring the slides and the budget."
        XCTAssertFalse(PolishGuards.looksRepeating(normal))
    }

    func testLooksRepeatingShortTextSafe() {
        XCTAssertFalse(PolishGuards.looksRepeating("hi there"))
    }

    // MARK: - cleanOutput

    func testCleanOutputStripsThinkBlock() {
        let s = "<think>let me clean this</think>The plan is ready."
        XCTAssertEqual(PolishGuards.cleanOutput(s), "The plan is ready.")
    }

    func testCleanOutputStripsUnclosedThink() {
        let s = "The plan is ready.\n<think>now I should also"
        XCTAssertEqual(PolishGuards.cleanOutput(s), "The plan is ready.")
    }

    func testCleanOutputStripsCodeFence() {
        let s = "```\nlet x = 1\n```"
        XCTAssertEqual(PolishGuards.cleanOutput(s), "let x = 1")
    }

    func testCleanOutputStripsWrappingQuotes() {
        XCTAssertEqual(PolishGuards.cleanOutput("\"Hello there.\""), "Hello there.")
    }

    func testCleanOutputCutsAtMarker() {
        let s = "Clean text here. <<<TEXT echoed junk"
        XCTAssertEqual(PolishGuards.cleanOutput(s), "Clean text here.")
    }

    func testCleanOutputCollapsesExactLoop() {
        let unit = "the report is due friday. "
        let looped = String(repeating: unit, count: 4)
        let cleaned = PolishGuards.cleanOutput(looped)
        XCTAssertEqual(cleaned, "the report is due friday.")
    }

    func testCleanOutputLeavesGoodTextAlone() {
        let s = "Let's meet at three. Bring the slides."
        XCTAssertEqual(PolishGuards.cleanOutput(s), s)
    }

    // MARK: - Hallucinated scaffold tail

    func testCleanOutputStripsHallucinatedScaffoldWithLabel() {
        let s = "Send it to John tomorrow at 2.\nCapitalization responses:\n\u{2610} [] [] [] [] [] []"
        XCTAssertEqual(PolishGuards.cleanOutput(s), "Send it to John tomorrow at 2.")
    }

    func testCleanOutputStripsBracketRunWithoutLabel() {
        let s = "The plan is ready. [] [] [] [] []"
        XCTAssertEqual(PolishGuards.cleanOutput(s), "The plan is ready.")
    }

    func testCleanOutputStripsCheckboxRun() {
        let s = "Buy milk and eggs.\n\u{2610} \u{2610} \u{2610} \u{2610}"
        XCTAssertEqual(PolishGuards.cleanOutput(s), "Buy milk and eggs.")
    }

    func testCleanOutputKeepsLegitCitationBracket() {
        // A single bracket with content (citation) is not a scaffold cell.
        let s = "See reference [1] for details."
        XCTAssertEqual(PolishGuards.cleanOutput(s), s)
    }

    func testLooksLikeScaffoldDetectsRun() {
        XCTAssertTrue(PolishGuards.looksLikeScaffold("good text [] []"))
        XCTAssertTrue(PolishGuards.looksLikeScaffold("good text \u{2610} \u{2610}"))
    }

    func testLooksLikeScaffoldIgnoresNormalText() {
        XCTAssertFalse(PolishGuards.looksLikeScaffold("Let's meet at three. Bring the slides."))
        XCTAssertFalse(PolishGuards.looksLikeScaffold("See reference [1] for details."))
    }

    // MARK: - Prompt builder

    func testOffLevelHasNoPrompt() {
        XCTAssertEqual(LlmPolish.systemPrompt(level: .off, category: .generic), "")
        XCTAssertFalse(PolishLevel.off.usesLLM)
    }

    func testLightPromptHasPreservationNoTone() {
        let p = LlmPolish.systemPrompt(level: .light, category: .email)
        XCTAssertTrue(p.contains("EXACTLY"))
        XCTAssertTrue(p.contains("punctuation"))
        // Light stays minimal — no email tone fragment.
        XCTAssertFalse(p.contains("professional, work context"))
    }

    func testFullPromptFoldsInTone() {
        let p = LlmPolish.systemPrompt(level: .full, category: .email)
        XCTAssertTrue(p.contains("Rewrite"))
        XCTAssertTrue(p.contains("professional, work context"))
    }

    func testMediumGenericHasNoToneLine() {
        let p = LlmPolish.systemPrompt(level: .medium, category: .generic)
        XCTAssertTrue(p.contains("filler"))
        // generic tone is empty → no trailing tone line beyond preservation.
        XCTAssertTrue(p.contains("EXACTLY"))
    }
}
