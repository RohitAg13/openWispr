import XCTest
@testable import OpenWisprCore

final class CapitalizerTests: XCTestCase {

    func testCapitalizesFirstAndAfterPeriod() {
        XCTAssertEqual(Capitalizer.capitalizeSentences("hello. world"), "Hello. World")
    }

    func testCapitalizesAfterNewline() {
        XCTAssertEqual(Capitalizer.capitalizeSentences("hello\nworld"), "Hello\nWorld")
    }

    func testFastAiIsNotASentenceBoundary() {
        XCTAssertEqual(Capitalizer.capitalizeSentences("fast.ai is great"), "Fast.ai is great")
    }

    func testDecimalIsNotASentenceBoundary() {
        XCTAssertEqual(Capitalizer.capitalizeSentences("pi is 3.14 ok"), "Pi is 3.14 ok")
    }

    func testPreservesMidSentenceCasing() {
        XCTAssertEqual(Capitalizer.capitalizeSentences("the new iPhone is here"), "The new iPhone is here")
    }

    func testSkipsLeadingQuote() {
        XCTAssertEqual(Capitalizer.capitalizeSentences("\"hello\" world"), "\"Hello\" world")
    }
}
