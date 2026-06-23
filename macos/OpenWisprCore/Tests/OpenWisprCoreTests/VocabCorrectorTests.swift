import XCTest
@testable import OpenWisprCore

final class VocabCorrectorTests: XCTestCase {

    // ---- correct ----

    func testExactAliasMapsToCanonical() {
        let vocab = [VocabEntry("Rohit", aliases: ["row hit"])]
        XCTAssertEqual(
            VocabCorrector.correct("tell row hit i said hi", vocab),
            "tell Rohit i said hi"
        )
    }

    func testCanonicalCasingFixedViaFuzzyWhenAliasPresent() {
        // The entry carries an alias, so fuzzy is enabled; an exact (lowercase)
        // canonical match scores 1.0 and snaps to the canonical casing.
        let vocab = [VocabEntry("Rohit", aliases: ["row hit"])]
        XCTAssertEqual(VocabCorrector.correct("tell rohit hi", vocab), "tell Rohit hi")
    }

    func testBareCanonicalContactMatchesExactlyOnly() {
        // Contact import with no alias => fuzzy off => exact match snaps casing,
        let vocab = [VocabEntry("Gita", source: "contact")]
        XCTAssertEqual(VocabCorrector.correct("meet gita", vocab), "meet Gita")
        // but a phonetic near-miss is left untouched (no fuzzy without an alias).
        XCTAssertEqual(VocabCorrector.correct("meet gate", vocab), "meet gate")
    }

    func testCommonWordsGuardSingleToken() {
        let vocab = [VocabEntry("Mark", aliases: ["marc"])]
        // "marc" is the alias -> matches, snaps to "Mark".
        XCTAssertEqual(VocabCorrector.correct("send it to marc", vocab), "send it to Mark")
        // "mark" is a single-token COMMON_WORD -> never matched, even though it is
        // both the canonical and would fuzzy-equal "marc".
        XCTAssertEqual(VocabCorrector.correct("i will mark it", vocab), "i will mark it")
    }

    func testSnippetExpansionExactOnly() {
        let vocab = [VocabEntry("my email", expansion: "me@example.com")]
        XCTAssertEqual(
            VocabCorrector.correct("send my email please", vocab),
            "send me@example.com please"
        )
    }

    func testEmptyVocabUnchanged() {
        XCTAssertEqual(VocabCorrector.correct("tell row hit hi", []), "tell row hit hi")
    }

    func testBlankTextUnchanged() {
        let vocab = [VocabEntry("Rohit", aliases: ["row hit"])]
        XCTAssertEqual(VocabCorrector.correct("   ", vocab), "   ")
        XCTAssertEqual(VocabCorrector.correct("", vocab), "")
    }

    // ---- soundex ----

    func testSoundexClassicRobertRupert() {
        XCTAssertEqual(VocabCorrector.soundex("Robert"), VocabCorrector.soundex("Rupert"))
        XCTAssertEqual(VocabCorrector.soundex("Robert"), "R163")
    }

    func testSoundexRohitStable() {
        // Stable across calls; R-h(0)-t(3) -> "R300" (vowels/H ignored).
        XCTAssertEqual(VocabCorrector.soundex("Rohit"), VocabCorrector.soundex("Rohit"))
        XCTAssertEqual(VocabCorrector.soundex("Rohit"), "R300")
    }

    // ---- biasPrompt ----

    func testBiasPromptEmpty() {
        XCTAssertEqual(VocabCorrector.biasPrompt([]), "")
    }

    func testBiasPromptRankingAndFormat() {
        // Provide in an order that is NOT the ranked order, to prove sorting.
        let contact = VocabEntry("Carol", source: "contact")
        let manual = VocabEntry("Bob", source: "manual")
        let learned = VocabEntry("Alice", source: "manual", learnedAliases: ["a liss"])
        let out = VocabCorrector.biasPrompt([contact, manual, learned])
        // learned-alias canonical first, then manual, then contact.
        XCTAssertEqual(out, "Glossary: Alice, Bob, Carol.")
        XCTAssertTrue(out.hasPrefix("Glossary: "))
        XCTAssertTrue(out.hasSuffix("."))
        // Aliases never appear.
        XCTAssertFalse(out.contains("a liss"))
    }

    func testBiasPromptExcludesSnippets() {
        let snippet = VocabEntry("my email", expansion: "me@example.com")
        let name = VocabEntry("Rohit")
        let out = VocabCorrector.biasPrompt([snippet, name])
        XCTAssertEqual(out, "Glossary: Rohit.")
    }
}
