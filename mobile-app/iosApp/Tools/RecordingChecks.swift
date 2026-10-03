import Foundation

/// Harmless PCM fixtures only: no audio session, microphone, network, or stored credentials.
@main
struct RecordingChecks {
    private enum Failure: Error { case check(String) }
    private static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw Failure.check(message) }
    }

    static func main() throws {
        var pcm = RecordingPCM()
        do {
            _ = try pcm.wav()
            throw Failure.check("Empty PCM must fail")
        } catch RecordingPCMError.empty { }
        do {
            try pcm.append(Data([0xff]))
            throw Failure.check("Odd PCM must fail")
        } catch RecordingPCMError.malformedPCM { }
        try require(pcm.bytes.isEmpty, "Rejected PCM cannot modify the recording")

        let signedSamples = Data([0, 0x80, 0xff, 0xff, 0, 0, 0xff, 0x7f])
        try require(try !pcm.append(signedSamples), "A short fixture cannot reach the ceiling")
        let wav = try pcm.wav()
        let header: [UInt8] = [
            0x52, 0x49, 0x46, 0x46, 44, 0, 0, 0, 0x57, 0x41, 0x56, 0x45,
            0x66, 0x6d, 0x74, 0x20, 16, 0, 0, 0, 1, 0, 1, 0,
            0x80, 0x3e, 0, 0, 0, 0x7d, 0, 0, 2, 0, 16, 0,
            0x64, 0x61, 0x74, 0x61, 8, 0, 0, 0
        ]
        try require(Array(wav.prefix(44)) == header, "RIFF/PCM16 mono 16 kHz header must be byte-exact")
        try require(wav.dropFirst(44) == signedSamples, "Signed little-endian samples must stay exact")

        pcm = RecordingPCM()
        let first = Data(repeating: 0x11, count: RecordingPCM.snapshotIntervalBytes)
        let second = Data(repeating: 0x22, count: RecordingPCM.snapshotIntervalBytes)
        try pcm.append(first.dropLast(2))
        try require(try pcm.takeSnapshotIfDue() == nil, "Do not emit before two seconds of PCM")
        try pcm.append(Data([0x11, 0x11]))
        guard let initial = try pcm.takeSnapshotIfDue() else { throw Failure.check("Two seconds must emit") }
        try require(initial.dropFirst(44) == first, "Initial snapshot must contain actual samples")
        try require(try pcm.takeSnapshotIfDue() == nil, "An unchanged recording must not emit again")
        try pcm.append(second)
        guard let next = try pcm.takeSnapshotIfDue() else { throw Failure.check("Four seconds must emit") }
        try require(next.dropFirst(44) == first + second, "Snapshots must accumulate from sample zero")
        try pcm.append(signedSamples)
        try require(try pcm.wav().dropFirst(44) == first + second + signedSamples,
                    "Manual stop must include the last incomplete snapshot interval")
        try require(initial.dropFirst(44) == first, "Emitted snapshot bytes must remain immutable")
        try require(readLE32(next, at: 4) == UInt32(next.count - 8), "Each snapshot has its own finalized RIFF size")
        try require(readLE32(next, at: 40) == UInt32(next.count - 44), "Each data chunk size matches its payload")

        pcm = RecordingPCM()
        let silence = Data(repeating: 0, count: RecordingPCM.snapshotIntervalBytes)
        for _ in 0..<10 {
            try require(try !pcm.append(silence), "Silence must never stop capture")
            try require(try pcm.takeSnapshotIfDue() != nil, "Silence is real audio and must emit snapshots")
        }
        try require(try pcm.wav().count == 44 + silence.count * 10, "Keep all silent samples")
        pcm = RecordingPCM()
        try require(try pcm.append(Data(repeating: 0x55, count: RecordingPCM.maximumPCMBytes + 100)),
                    "The time limit must signal completion")
        let capped = try pcm.wav()
        try require(capped.count == 44 + 16_000 * 600 * 2, "Ten-minute PCM duration must be exact")
        try require(capped.count <= 20 * 1024 * 1024, "The WAV header also counts toward the byte cap")
        try require(readLE32(capped, at: 40) == 19_200_000, "Maximum WAV size must not overflow its header")
        try pcm.append(signedSamples)
        try require(try pcm.wav() == capped, "Late buffers must never exceed the ceiling")

        var queue: [() -> Void] = []
        var received: [RecordingUpdates.Value] = []
        let updates = RecordingUpdates(enqueue: { queue.append($0) }, deliver: { received.append($0) })
        for index in 0..<10_000 {
            updates.publish(amplitude: Float(index), wav: Data([UInt8(truncatingIfNeeded: index)]))
        }
        try require(queue.count == 1, "Blocked UI must have only one pending delivery")
        queue.removeFirst()()
        try require(received.count == 1 && received[0].amplitude == 9_999,
                    "Deliver the newest meter instead of a stale backlog")
        try require(received[0].wav == Data([UInt8(truncatingIfNeeded: 9_999)]),
                    "Deliver only the newest cumulative snapshot")
        updates.publish(end: .limit)
        queue.removeFirst()()
        guard case .limit? = received.last?.end else { throw Failure.check("Explicit limit must survive coalescing") }
        updates.publish(amplitude: 1)
        updates.invalidate()
        queue.removeFirst()()
        updates.publish(amplitude: 2)
        try require(queue.isEmpty && received.count == 2, "Invalidated generations cannot deliver queued updates")
        print("PASS: exact PCM WAV, cumulative 2s snapshots, final tail, silence, 600s/20MiB, coalescing/invalidation")
    }

    private static func readLE32(_ bytes: Data, at offset: Int) -> UInt32 {
        (0..<4).reduce(UInt32(0)) { $0 | UInt32(bytes[offset + $1]) << (8 * $1) }
    }
}
