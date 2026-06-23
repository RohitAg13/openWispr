import XCTest
@testable import OpenWisprCore

final class SelfCorrectionDetectorTests: XCTestCase {

    func testNumericBacktrack() {
        XCTAssertEqual(SelfCorrectionDetector.detectAndResolve("let's meet at 2 actually 3"), "let's meet at 3")
    }

    func testNumericBacktrackWithPm() {
        XCTAssertEqual(SelfCorrectionDetector.detectAndResolve("the meeting is at 2pm no 3pm"), "the meeting is at 3pm")
    }

    func testStandaloneRestartDropsPrefix() {
        XCTAssertEqual(
            SelfCorrectionDetector.detectAndResolve("send it to mark, scratch that, send it to john"),
            "send it to john"
        )
    }

    func testInlineIMeanCorrectsTheFragment() {
        XCTAssertEqual(SelfCorrectionDetector.detectAndResolve("send it to mark, I mean john"), "send it to john")
    }

    func testGatedMarkerDoesNotFalseTrigger() {
        let s = "I'm sorry to hear that"
        XCTAssertEqual(SelfCorrectionDetector.detectAndResolve(s), s)
    }

    func testProseIsNotSwapped() {
        let s = "I actually like this plan"
        XCTAssertEqual(SelfCorrectionDetector.detectAndResolve(s), s)
    }
}
