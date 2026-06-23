import XCTest
@testable import OpenWisprCore

final class ListFormatterTests: XCTestCase {

    func testDigitListWithLeadIn() {
        XCTAssertEqual(
            ListFormatter.format("going to the store for 1. apples 2. bananas 3. oranges"),
            "going to the store for\n1. apples\n2. bananas\n3. oranges"
        )
    }

    func testNewLineMark() {
        XCTAssertEqual(ListFormatter.format("hello new line world"), "hello\nworld")
    }

    func testNewParagraphMark() {
        XCTAssertEqual(ListFormatter.format("intro new paragraph body"), "intro\n\nbody")
    }

    func testSingleMarkerIsNotAList() {
        XCTAssertEqual(ListFormatter.format("step 1. do the thing"), "step 1. do the thing")
    }

    func testNonConsecutiveMarkersAreNotAList() {
        XCTAssertEqual(ListFormatter.format("a 1. x 3. y"), "a 1. x 3. y")
    }

    func testBareNumberIsNotAList() {
        XCTAssertEqual(ListFormatter.format("I have 1 apple"), "I have 1 apple")
    }

    func testTwoOrdinalsAreNotEnough() {
        XCTAssertEqual(ListFormatter.format("first, eat, second, sleep"), "first, eat, second, sleep")
    }

    func testThreeOrdinalsFormAList() {
        XCTAssertEqual(
            ListFormatter.format("first, eat, second, sleep, third, code"),
            "1. eat\n2. sleep\n3. code"
        )
    }
}
