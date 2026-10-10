import AVFoundation
import UIKit
import ClaudeBot

/// Native state and Kotlin callbacks are main-thread owned; the tap owns no UI state.
final class NativeRecording: NSObject {
    var onError: ((String) -> Void)?
    private var engine: AVAudioEngine?
    private var tapInstalled = false
    private var capture: RecordingCapture?
    private var snapshotTimer: DispatchSourceTimer?
    private var updates: RecordingUpdates?
    private var token: UUID?
    private var generation: UUID?
    private var amplitude: ((Float) -> Void)?
    private var result: ((PickedFile?) -> Void)?
    private var partial: ((PickedFile) -> Void)?
    private var observers: [NSObjectProtocol] = []
    private var previousCategory: AVAudioSession.Category?
    private var previousMode: AVAudioSession.Mode?
    private var previousOptions: AVAudioSession.CategoryOptions = []
    private var ownsAudioSession = false

    override init() {
        super.init()
        let center = NotificationCenter.default
        for name in [AVAudioSession.interruptionNotification, AVAudioSession.mediaServicesWereResetNotification,
                     Notification.Name.AVAudioEngineConfigurationChange] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                if note.name == AVAudioSession.interruptionNotification,
                   let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                   raw != AVAudioSession.InterruptionType.began.rawValue { return }
                guard let self, let request = self.token else { return }
                if note.name == Notification.Name.AVAudioEngineConfigurationChange,
                   let changed = note.object as? AVAudioEngine, changed !== self.engine { return }
                // A notification can be posted synchronously inside engine/session mutation.
                // Complete that operation before teardown, and discard notifications from an old generation.
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.token == request else { return }
                    self.finish(success: false, errorKey: "error.recording.interrupted")
                }
            })
        }
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        updates?.invalidate()
        snapshotTimer?.cancel()
        capture?.cancel()
        engine?.stop()
        if tapInstalled { engine?.inputNode.removeTap(onBus: 0) }
        releaseAudioSession()
    }

    func start(amplitude: @escaping (Float) -> Void, result: @escaping (PickedFile?) -> Void,
               partial: @escaping (PickedFile) -> Void) {
        // Preserve the in-flight request; a repeated start must not steal its callback.
        guard token == nil else { result(nil); return }
        let request = UUID()
        token = request
        generation = request
        self.amplitude = amplitude
        self.result = result
        self.partial = partial
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] allowed in
            onMain {
                guard let self, self.token == request else { return }
                guard allowed, UIApplication.shared.applicationState == .active else {
                    self.finish(success: false, errorKey: allowed ? nil : "error.microphone.permission")
                    return
                }
                self.begin(request: request)
            }
        }
    }

    private func begin(request: UUID) {
        let session = AVAudioSession.sharedInstance()
        previousCategory = session.category
        previousMode = session.mode
        previousOptions = session.categoryOptions
        do {
            try session.setCategory(.record, mode: .measurement, options: [])
            // An activation failure still needs deactivation/category restoration.
            ownsAudioSession = true
            try session.setActive(true)
            let engine = AVAudioEngine()
            self.engine = engine
            let input = engine.inputNode
            let hardwareFormat = input.outputFormat(forBus: 0)
            let updates = RecordingUpdates { [weak self] value in
                guard let self, self.token == request else { return }
                if let end = value.end {
                    switch end {
                    case .limit: self.finish(success: true)
                    case .failure: self.finish(success: false, errorKey: "error.recording")
                    }
                    return
                }
                if let value = value.amplitude { self.amplitude?(value) }
                // A callback can synchronously cancel, stop, or start another generation.
                guard self.token == request else { return }
                if let wav = value.wav {
                    guard let file = IosBridgeKt.iosPickedFile(name: "recording-partial.wav",
                                                              mimeType: "audio/wav", data: wav) else {
                        self.finish(success: false, errorKey: "error.recording")
                        return
                    }
                    self.partial?(file)
                }
            }
            self.updates = updates
            let capture = try RecordingCapture(format: hardwareFormat, updates: updates)
            self.capture = capture
            // Never force 16 kHz onto a hardware bus. The converter handles rate/channel/PCM changes.
            input.installTap(onBus: 0, bufferSize: 1024, format: hardwareFormat) { buffer, _ in
                capture.consume(buffer)
            }
            tapInstalled = true
            engine.prepare()
            try engine.start()
            let timer = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "me.waveio.claudebot.audio.snapshots",
                                                                          qos: .utility))
            timer.schedule(deadline: .now() + 2, repeating: 0.25, leeway: .milliseconds(50))
            timer.setEventHandler { capture.pollSnapshot() }
            snapshotTimer = timer
            timer.resume()
        } catch {
            finish(success: false, errorKey: "error.recording")
        }
    }

    func stop() { finish(success: capture != nil) }
    func cancel() {
        if token == nil { generation = nil }
        finish(success: false)
    }
    func invalidate() {
        generation = nil
        onError = nil
        result = nil
        amplitude = nil
        partial = nil
        finish(success: false)
    }

    private func finish(success: Bool, errorKey: String? = nil) {
        guard let request = token else { return }
        // Keep this token until teardown finishes, preventing any replacement from sharing the session.
        updates?.invalidate()
        updates = nil
        snapshotTimer?.cancel()
        snapshotTimer = nil
        let captured = capture
        capture = nil
        // Prevent a late tap from appending after stop, then remove it outside the capture lock.
        captured?.stopAccepting()
        engine?.stop()
        if tapInstalled { engine?.inputNode.removeTap(onBus: 0) }
        tapInstalled = false
        engine = nil
        releaseAudioSession()
        var picked: PickedFile?
        var failure = errorKey
        if success, let captured {
            do {
                let wav = try captured.finishWAV()
                picked = IosBridgeKt.iosPickedFile(name: "recording.wav", mimeType: "audio/wav", data: wav)
                if picked == nil { failure = "error.recording" }
            } catch { failure = "error.recording" }
        }
        captured?.cancel()
        let callback = result
        let lastAmplitude = amplitude
        result = nil
        amplitude = nil
        partial = nil
        token = nil
        if let failure { onError?(failure) }
        guard generation == request else { return }
        lastAmplitude?(0)
        guard generation == request else { return }
        callback?(picked)
    }

    private func releaseAudioSession() {
        let session = AVAudioSession.sharedInstance()
        if ownsAudioSession { try? session.setActive(false, options: .notifyOthersOnDeactivation) }
        ownsAudioSession = false
        if let category = previousCategory, let mode = previousMode {
            try? session.setCategory(category, mode: mode, options: previousOptions)
        }
        previousCategory = nil
        previousMode = nil
    }
}

/// Conversion/PCM state is serialized with a short lock. Tap buffers are consumed before returning;
/// no captured hardware buffers or unbounded dispatch work escape their lifetime.
private final class RecordingCapture {
    private let lock = NSLock()
    private let converter: AVAudioConverter
    private let output: AVAudioPCMBuffer
    private let updates: RecordingUpdates
    private var pcm = RecordingPCM()
    private var accepting = true
    private var failed = false
    private var receivedInput = false
    private let started = DispatchTime.now().uptimeNanoseconds
    private var lastMeter: UInt64 = 0

    init(format: AVAudioFormat, updates: RecordingUpdates) throws {
        guard format.sampleRate.isFinite, format.sampleRate > 0, format.channelCount > 0,
              let target = AVAudioFormat(commonFormat: .pcmFormatInt16,
                                        sampleRate: Double(RecordingPCM.sampleRate), channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: format, to: target),
              let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: 4096) else {
            throw NativeFileError.unreadable
        }
        self.converter = converter
        self.output = output
        self.updates = updates
    }

    func consume(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        defer { lock.unlock() }
        guard accepting, buffer.frameLength > 0 else { return }
        guard buffer.format.isEqual(converter.inputFormat) else { fail(); return }
        do {
            receivedInput = true
            let amplitude = try convert(buffer, final: false)
            let now = DispatchTime.now().uptimeNanoseconds
            if now - lastMeter >= 50_000_000 {
                lastMeter = now
                updates.publish(amplitude: amplitude)
            }
            if pcm.atLimit || now - started >= UInt64(RecordingPCM.maximumSeconds) * 1_000_000_000 {
                accepting = false
                updates.publish(end: .limit)
            }
        } catch { fail() }
    }

    func pollSnapshot() {
        lock.lock()
        defer { lock.unlock() }
        guard accepting else { return }
        if DispatchTime.now().uptimeNanoseconds - started >= UInt64(RecordingPCM.maximumSeconds) * 1_000_000_000 {
            accepting = false
            updates.publish(end: .limit)
            return
        }
        do {
            // WAV serialization is off the tap thread, with one bounded snapshot retained by the mailbox.
            if let wav = try pcm.takeSnapshotIfDue() { updates.publish(wav: wav) }
        } catch { fail() }
    }

    func stopAccepting() {
        lock.lock()
        accepting = false
        lock.unlock()
    }

    func finishWAV() throws -> Data {
        lock.lock()
        defer { lock.unlock() }
        guard !failed else { throw NativeFileError.unreadable }
        if receivedInput, !pcm.atLimit { _ = try convert(nil, final: true) }
        return try pcm.wav()
    }

    func cancel() {
        lock.lock()
        accepting = false
        pcm = RecordingPCM()
        converter.reset()
        lock.unlock()
    }

    private func fail() {
        failed = true
        accepting = false
        updates.publish(end: .failure)
    }

    private func convert(_ input: AVAudioPCMBuffer?, final: Bool) throws -> Float {
        var supplied = false
        var sum = 0.0
        var samples = 0
        // Hardware buffer sizes are advisory. Drain larger buffers too, with a finite safety ceiling.
        let estimatedFrames = Double(input?.frameLength ?? 0) * converter.outputFormat.sampleRate / converter.inputFormat.sampleRate
        let attempts = Int(ceil(estimatedFrames / Double(output.frameCapacity))) + 16
        for _ in 0..<attempts {
            output.frameLength = 0
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, inputStatus in
                if let input, !supplied {
                    supplied = true
                    inputStatus.pointee = .haveData
                    return input
                }
                inputStatus.pointee = final ? .endOfStream : .noDataNow
                return nil
            }
            guard status != .error, error == nil else { throw NativeFileError.unreadable }
            if output.frameLength > 0 {
                guard let channel = output.int16ChannelData?[0] else { throw NativeFileError.unreadable }
                let count = Int(output.frameLength)
                // iOS hardware is little-endian; this converted mono buffer is already WAV PCM16LE.
                try pcm.append(Data(bytes: channel, count: count * MemoryLayout<Int16>.size))
                for index in 0..<count {
                    let value = Double(channel[index]) / 32768
                    sum += value * value
                }
                samples += count
            }
            if pcm.atLimit || status == .inputRanDry || status == .endOfStream {
                return samples > 0 ? Float(min(1, sqrt(sum / Double(samples)))) : 0
            }
            guard status == .haveData, output.frameLength > 0 else { throw NativeFileError.unreadable }
        }
        throw NativeFileError.unreadable
    }
}
