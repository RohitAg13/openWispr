import XCTest
@testable import OpenWisprCore

final class AppContextTests: XCTestCase {

    func testChat() {
        XCTAssertEqual(AppContext.categoryFor("com.whatsapp", "hey what's up"), .chat)
    }

    func testEmail() {
        XCTAssertEqual(AppContext.categoryFor("com.google.android.gm", "dear sir"), .email)
    }

    func testCode() {
        XCTAssertEqual(AppContext.categoryFor("com.microsoft.vscode", "anything"), .code)
    }

    func testSocial() {
        XCTAssertEqual(AppContext.categoryFor("com.twitter.android", "just shipped a thing"), .social)
    }

    func testNotes() {
        XCTAssertEqual(AppContext.categoryFor("com.google.android.keep", "grocery list"), .notes)
    }

    func testUnknownIsGeneric() {
        XCTAssertEqual(AppContext.categoryFor("com.example.random", "some prose"), .generic)
    }

    func testNilIsGeneric() {
        XCTAssertEqual(AppContext.categoryFor(nil, "some prose"), .generic)
    }

    func testDefaultToneNonEmptyCategories() {
        XCTAssertFalse(AppContext.DEFAULT_TONE[.chat]!.isEmpty)
        XCTAssertFalse(AppContext.DEFAULT_TONE[.email]!.isEmpty)
        XCTAssertFalse(AppContext.DEFAULT_TONE[.social]!.isEmpty)
    }

    func testDefaultToneEmptyCategories() {
        XCTAssertEqual(AppContext.DEFAULT_TONE[.code], "")
        XCTAssertEqual(AppContext.DEFAULT_TONE[.generic], "")
        XCTAssertEqual(AppContext.DEFAULT_TONE[.notes], "")
    }

    func testCategoryRawValueIsKey() {
        XCTAssertEqual(AppContext.Category.generic.rawValue, "generic")
        XCTAssertEqual(AppContext.Category.email.key, "email")
    }
}
