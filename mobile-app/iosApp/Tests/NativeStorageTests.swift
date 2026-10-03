import Security
import XCTest
@testable import ClaudeBotApp

final class NativeStorageTests: XCTestCase {
    func testMissingItemIsDistinctFromDeniedAccess() throws {
        XCTAssertNil(try NativeStorage.decodeSecret(status: errSecItemNotFound, item: nil))
        XCTAssertThrowsError(try NativeStorage.decodeSecret(status: errSecInteractionNotAllowed, item: nil)) { error in
            XCTAssertEqual((error as? KeychainError)?.status, errSecInteractionNotAllowed)
        }
    }

    func testCorruptSuccessfulResultReportsDecodeFailure() {
        // A successful OSStatus must never accompany the error for invalid stored UTF-8.
        for item: CFTypeRef? in [nil, Data([0xff]) as CFData] {
            XCTAssertThrowsError(try NativeStorage.decodeSecret(status: errSecSuccess, item: item)) { error in
                XCTAssertEqual((error as? KeychainError)?.status, errSecDecode)
            }
        }
    }
}
