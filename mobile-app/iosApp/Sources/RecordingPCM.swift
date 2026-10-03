import Foundation

enum RecordingPCMError: Error { case malformedPCM, empty }

/// All snapshots contain the recording from sample zero, matching cumulative ASR text.
/// This type is deliberately Foundation-only so real WAV bytes can be checked without a microphone.
struct RecordingPCM {
    static let sampleRate = 16_000
    static let headerBytes = 44
    static let maximumSeconds = 600
    static let maximumBytes = 20 * 1024 * 1024
    static let maximumPCMBytes = min(sampleRate * maximumSeconds * 2, maximumBytes - headerBytes) & ~1
    static let snapshotIntervalBytes = sampleRate * 2 * 2

    private(set) var bytes = Data()
    private var lastSnapshotBytes = 0
    var atLimit: Bool { bytes.count >= Self.maximumPCMBytes }

    /// Input is little-endian, signed 16-bit mono PCM, after hardware-format conversion.
    /// Only the last hardware buffer may be trimmed, at the explicit duration/byte ceiling.
    @discardableResult
    mutating func append(_ pcm: Data) throws -> Bool {
        guard pcm.count % 2 == 0 else { throw RecordingPCMError.malformedPCM }
        bytes.append(pcm.prefix(Self.maximumPCMBytes - bytes.count))
        return atLimit
    }

    mutating func takeSnapshotIfDue() throws -> Data? {
        guard bytes.count - lastSnapshotBytes >= Self.snapshotIntervalBytes else { return nil }
        let snapshot = try wav()
        lastSnapshotBytes = bytes.count
        return snapshot
    }

    func wav() throws -> Data {
        guard !bytes.isEmpty else { throw RecordingPCMError.empty }
        var data = Data()
        data.reserveCapacity(Self.headerBytes + bytes.count)
        data.append(contentsOf: "RIFF".utf8)
        appendLE(UInt32(36 + bytes.count), to: &data)
        data.append(contentsOf: "WAVEfmt ".utf8)
        appendLE(UInt32(16), to: &data)
        appendLE(UInt16(1), to: &data) // Linear PCM.
        appendLE(UInt16(1), to: &data) // Mono.
        appendLE(UInt32(Self.sampleRate), to: &data)
        appendLE(UInt32(Self.sampleRate * 2), to: &data)
        appendLE(UInt16(2), to: &data) // Block alignment.
        appendLE(UInt16(16), to: &data)
        data.append(contentsOf: "data".utf8)
        appendLE(UInt32(bytes.count), to: &data)
        data.append(bytes)
        return data
    }

    private func appendLE<T: FixedWidthInteger>(_ value: T, to data: inout Data) {
        var littleEndian = value.littleEndian
        withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
    }
}

/// A single queued delivery holds only the newest meter/snapshot, even if the UI is blocked.
/// Each recording owns one mailbox; invalidation also discards work already queued on main.
final class RecordingUpdates {
    enum End { case limit, failure }
    struct Value {
        var amplitude: Float?
        var wav: Data?
        var end: End?
    }

    private let lock = NSLock()
    private var active = true
    private var scheduled = false
    private var latest = Value()
    private let enqueue: (@escaping () -> Void) -> Void
    private let deliver: (Value) -> Void

    init(enqueue: @escaping (@escaping () -> Void) -> Void = { DispatchQueue.main.async(execute: $0) },
         deliver: @escaping (Value) -> Void) {
        self.enqueue = enqueue
        self.deliver = deliver
    }

    func publish(amplitude: Float? = nil, wav: Data? = nil, end: End? = nil) {
        lock.lock()
        guard active else { lock.unlock(); return }
        if let amplitude { latest.amplitude = amplitude }
        if let wav { latest.wav = wav }
        if let end { latest.end = end }
        let needsDelivery = !scheduled
        scheduled = true
        lock.unlock()
        if needsDelivery { enqueue { [weak self] in self?.drain() } }
    }

    func invalidate() {
        lock.lock()
        active = false
        latest = Value()
        lock.unlock()
    }

    private func drain() {
        lock.lock()
        guard active else { lock.unlock(); return }
        let value = latest
        latest = Value()
        scheduled = false
        lock.unlock()
        // The owner additionally checks its generation before every callback.
        deliver(value)
    }
}
