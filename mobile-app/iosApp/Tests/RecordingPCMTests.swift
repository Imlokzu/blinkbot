import XCTest
@testable import ClaudeBotApp

final class RecordingPCMTests: XCTestCase {
    func testWAVHeaderAndSignedLittleEndianSamplesAreExact() throws {
        var pcm = RecordingPCM()
        let samples = Data([0x00, 0x80, 0xff, 0xff, 0x00, 0x00, 0xff, 0x7f])
        XCTAssertFalse(try pcm.append(samples))
        let wav = try pcm.wav()
        XCTAssertEqual(Array(wav.prefix(44)), [
            0x52, 0x49, 0x46, 0x46, 44, 0, 0, 0, 0x57, 0x41, 0x56, 0x45,
            0x66, 0x6d, 0x74, 0x20, 16, 0, 0, 0, 1, 0, 1, 0,
            0x80, 0x3e, 0, 0, 0, 0x7d, 0, 0, 2, 0, 16, 0,
            0x64, 0x61, 0x74, 0x61, 8, 0, 0, 0
        ])
        XCTAssertEqual(wav.dropFirst(44), samples)
    }

    func testRollingSnapshotsAndFinalIncludeEveryEarlierSample() throws {
        var pcm = RecordingPCM()
        let first = Data(repeating: 0x11, count: RecordingPCM.snapshotIntervalBytes)
        let second = Data(repeating: 0x22, count: RecordingPCM.snapshotIntervalBytes)
        try pcm.append(first.dropLast(2))
        XCTAssertNil(try pcm.takeSnapshotIfDue())
        try pcm.append(Data([0x11, 0x11]))
        let firstWAV = try XCTUnwrap(pcm.takeSnapshotIfDue())
        XCTAssertEqual(firstWAV.dropFirst(44), first)
        XCTAssertNil(try pcm.takeSnapshotIfDue())
        try pcm.append(second)
        let secondWAV = try XCTUnwrap(pcm.takeSnapshotIfDue())
        XCTAssertEqual(secondWAV.dropFirst(44), first + second)
        try pcm.append(Data([0x33, 0x44]))
        XCTAssertEqual(try pcm.wav().dropFirst(44), first + second + Data([0x33, 0x44]))
        XCTAssertEqual(firstWAV.dropFirst(44), first, "Later appends cannot mutate an emitted snapshot")
    }

    func testSilenceNeverStopsBeforeTheExplicitLimit() throws {
        var pcm = RecordingPCM()
        let silence = Data(repeating: 0, count: RecordingPCM.snapshotIntervalBytes)
        for _ in 0..<10 {
            XCTAssertFalse(try pcm.append(silence))
            XCTAssertNotNil(try pcm.takeSnapshotIfDue())
            XCTAssertFalse(pcm.atLimit)
        }
        XCTAssertEqual(try pcm.wav().count, 44 + silence.count * 10)
    }

    func testTenMinuteCeilingIsFrameAlignedAndBelowTwentyMiB() throws {
        var pcm = RecordingPCM()
        XCTAssertTrue(try pcm.append(Data(repeating: 0x55, count: RecordingPCM.maximumPCMBytes + 100)))
        let final = try pcm.wav()
        XCTAssertEqual(final.count, 44 + 16_000 * 600 * 2)
        XCTAssertLessThanOrEqual(final.count, 20 * 1024 * 1024)
        XCTAssertEqual(pcm.bytes.count % 2, 0)
        XCTAssertTrue(try pcm.append(Data([0x00, 0x00])))
        XCTAssertEqual(try pcm.wav(), final)
    }

    func testEmptyAndOddPCMFailWithoutFabricatingAudio() {
        var pcm = RecordingPCM()
        XCTAssertThrowsError(try pcm.wav())
        XCTAssertThrowsError(try pcm.append(Data([0xff])))
        XCTAssertTrue(pcm.bytes.isEmpty)
    }

    func testBlockedUIDeliveriesCoalesceAndInvalidationDropsQueuedWork() {
        var queue: [() -> Void] = []
        var received: [RecordingUpdates.Value] = []
        let updates = RecordingUpdates(enqueue: { queue.append($0) }, deliver: { received.append($0) })
        for index in 0..<10_000 {
            updates.publish(amplitude: Float(index), wav: Data([UInt8(truncatingIfNeeded: index)]))
        }
        XCTAssertEqual(queue.count, 1, "Capture must never create a per-buffer UI backlog")
        queue.removeFirst()()
        XCTAssertEqual(received.count, 1)
        XCTAssertEqual(received[0].amplitude, 9_999)
        XCTAssertEqual(received[0].wav, Data([UInt8(truncatingIfNeeded: 9_999)]))
        updates.publish(end: .limit)
        updates.invalidate()
        queue.removeFirst()()
        updates.publish(amplitude: 1)
        XCTAssertTrue(queue.isEmpty)
        XCTAssertEqual(received.count, 1, "Queued work cannot outlive its recording generation")
    }
}
