import XCTest
@testable import OpenWisprCore

final class CodeContextTests: XCTestCase {

    func testCodeEditorIsAlwaysCode() {
        XCTAssertTrue(CodeContext.useCodeMode("com.microsoft.vscode", "hello world this is prose"))
        XCTAssertTrue(CodeContext.useCodeMode("com.microsoft.vscode", ""))
    }

    func testTerminalCommandIsCode() {
        XCTAssertTrue(CodeContext.useCodeMode("com.googlecode.iterm2", "git push origin main"))
    }

    func testTerminalProseIsNotCode() {
        XCTAssertFalse(CodeContext.useCodeMode(
            "com.googlecode.iterm2",
            "can you refactor the auth module to use signed tokens please"
        ))
    }

    func testTerminalPathIsCode() {
        XCTAssertTrue(CodeContext.useCodeMode("com.googlecode.iterm2", "/usr/local/bin"))
    }

    func testTerminalFlagIsCode() {
        XCTAssertTrue(CodeContext.useCodeMode("com.googlecode.iterm2", "ls -la"))
    }

    func testUnknownPackageIsNotCode() {
        XCTAssertFalse(CodeContext.useCodeMode("com.example.random", "git push origin main"))
    }

    func testNilPackageIsNotCode() {
        XCTAssertFalse(CodeContext.useCodeMode(nil, "git push origin main"))
    }
}
