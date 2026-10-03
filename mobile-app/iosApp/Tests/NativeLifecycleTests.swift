import XCTest
@testable import ClaudeBotApp

final class NativeLifecycleTests: XCTestCase {
    func testQRInvalidationDiscardsAnAlreadyQueuedResult() {
        let unexpected = expectation(description: "Invalidated hosts must not receive a stale QR result")
        unexpected.isInverted = true
        DispatchQueue.main.async {
            let scanner = QRScanController()
            scanner.onResult = { _ in unexpected.fulfill() }
            // Cancel queues serial teardown; invalidation happens before its main-queue delivery.
            scanner.cancel()
            scanner.invalidate()
        }
        wait(for: [unexpected], timeout: 0.25)
    }

    func testRepeatedQRCancelReturnsNilExactlyOnce() {
        let completed = expectation(description: "Cancellation returns after capture teardown")
        completed.assertForOverFulfill = true
        DispatchQueue.main.async {
            let scanner = QRScanController()
            scanner.onResult = { value in
                XCTAssertNil(value)
                XCTAssertTrue(Thread.isMainThread)
                completed.fulfill()
            }
            scanner.cancel()
            scanner.cancel()
        }
        wait(for: [completed], timeout: 2)
    }
}
