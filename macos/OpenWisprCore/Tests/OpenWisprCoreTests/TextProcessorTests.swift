import XCTest
@testable import OpenWisprCore

final class TextProcessorTests: XCTestCase {

    func testBacktrackEndToEnd() {
        XCTAssertEqual(TextProcessor.process("let's meet at 2 actually 3"), "Let's meet at 3")
    }

    func testNumberedListEndToEnd() {
        XCTAssertEqual(
            TextProcessor.process("going to the store for 1. apples 2. bananas 3. oranges"),
            "Going to the store for\n1. Apples\n2. Bananas\n3. Oranges"
        )
    }

    func testNewlineMarkEndToEnd() {
        XCTAssertEqual(TextProcessor.process("hello new line world"), "Hello\nWorld")
    }

    func testPunctuationEndToEnd() {
        XCTAssertEqual(
            TextProcessor.process("send it to bob comma then wait period see you tomorrow"),
            "Send it to bob, then wait. See you tomorrow"
        )
    }

    func testCodeContextSkipsCapsAndSpokenForms() {
        XCTAssertEqual(
            TextProcessor.process("cd slash usr slash bin", isCodeContext: true),
            "cd slash usr slash bin"
        )
    }
}
