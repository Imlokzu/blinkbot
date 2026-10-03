import XCTest
import ClaudeBot
@testable import ClaudeBotApp

final class IncomingPairingTests: XCTestCase {
    private let valid = "claudebot://pair?server=https%3A%2F%2Fexample.invalid&code=fixture-code"

    func testValidatedPairingIsDeliveredThroughKotlinState() {
        let bridge = IosBridge(delegate: NativeBridge())
        bridge.deliverIncomingPairing(value: valid)
        XCTAssertEqual(bridge.incomingPairing.value as? String, valid)
    }

    func testMalformedOrUnboundedPairingNeverReplacesPendingValue() {
        let bridge = IosBridge(delegate: NativeBridge())
        bridge.deliverIncomingPairing(value: valid)
        let invalid = [
            "https://pair?server=https%3A%2F%2Fexample.invalid&code=fixture-code",
            "claudebot://pair.example?server=https%3A%2F%2Fexample.invalid&code=fixture-code",
            "claudebot://pair/path?server=https%3A%2F%2Fexample.invalid&code=fixture-code",
            "claudebot://pair?server=http%3A%2F%2Fexample.invalid&code=fixture-code",
            valid + "&code=duplicate", valid + "&extra=value", valid + "#fragment",
            "claudebot://pair?server=https%3A%2F%2Fexample.invalid&code=",
            "claudebot://pair?server=https%3A%2F%2Fexample.invalid&code=" + String(repeating: "x", count: 4096),
            valid + "\n"
        ]
        for value in invalid {
            bridge.deliverIncomingPairing(value: value)
            XCTAssertEqual(bridge.incomingPairing.value as? String, valid)
        }
    }
}
