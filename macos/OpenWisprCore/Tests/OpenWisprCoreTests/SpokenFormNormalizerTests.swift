import XCTest
@testable import OpenWisprCore

final class SpokenFormNormalizerTests: XCTestCase {

    func testExplicitPunctuation() {
        XCTAssertEqual(
            SpokenFormNormalizer.normalize("send it to bob comma then wait period see you tomorrow"),
            "send it to bob, then wait. see you tomorrow"
        )
    }

    func testPeriodAsNounIsNotPunctuation() {
        XCTAssertEqual(
            SpokenFormNormalizer.normalize("during that period I was calm"),
            "during that period I was calm"
        )
    }

    func testQuestionMark() {
        XCTAssertEqual(SpokenFormNormalizer.normalize("is it done question mark"), "is it done?")
    }

    func testDottedFilename() {
        XCTAssertEqual(SpokenFormNormalizer.normalize("check out next dot js today"), "check out next.js today")
    }

    func testEllipsis() {
        XCTAssertEqual(SpokenFormNormalizer.normalize("wait dot dot dot then go"), "wait... then go")
    }

    func testPathInProse() {
        XCTAssertEqual(SpokenFormNormalizer.normalize("cd slash usr slash bin"), "cd /usr/bin")
    }

    func testCodeModeKeepsSlashAsWord() {
        XCTAssertEqual(
            SpokenFormNormalizer.normalize("cd slash usr slash bin", unambiguousOnly: true),
            "cd slash usr slash bin"
        )
    }
}
