import XCTest
@testable import ClaudeBotApp

final class BoundedDataTests: XCTestCase {
    private func withFile(_ data: Data, test: (URL) throws -> Void) throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        try test(url)
    }

    func testExactLimitIsAcceptedWithoutTruncatingBytes() throws {
        let bytes = Data((0..<65_536).map { UInt8(truncatingIfNeeded: $0) })
        try withFile(bytes) { url in
            XCTAssertEqual(try BoundedData.read(url, maximumBytes: bytes.count), bytes)
        }
    }

    func testOversizedFileIsRejectedRatherThanTruncated() throws {
        try withFile(Data(repeating: 1, count: 129)) { url in
            XCTAssertThrowsError(try BoundedData.read(url, maximumBytes: 128)) { error in
                guard case NativeFileError.tooLarge = error else { return XCTFail("Unexpected error") }
            }
        }
    }

    func testEmptyAndNonFileInputsAreRejected() throws {
        try withFile(Data()) { url in XCTAssertThrowsError(try BoundedData.read(url)) }
        XCTAssertThrowsError(try BoundedData.read(FileManager.default.temporaryDirectory))
    }
}
