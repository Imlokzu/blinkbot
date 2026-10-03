import AVFoundation
import UIKit

final class QRScanController: UIViewController, NativeOperation, AVCaptureMetadataOutputObjectsDelegate,
                              UIAdaptivePresentationControllerDelegate {
    var onResult: ((String?) -> Void)?
    var onError: ((String) -> Void)?
    private let session = AVCaptureSession()
    private let captureQueue = DispatchQueue(label: "me.waveio.claudebot.qr-capture")
    private var preview: AVCaptureVideoPreviewLayer?
    private var started = false
    private var finished = false
    private var observers: [NSObjectProtocol] = []

    init() {
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }
    required init?(coder: NSCoder) { return nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer

        let title = UILabel()
        title.text = nativeString("qr.title")
        title.textColor = .white
        title.font = .preferredFont(forTextStyle: .headline)
        title.adjustsFontForContentSizeCategory = true
        title.numberOfLines = 0
        title.textAlignment = .center
        let instruction = UILabel()
        instruction.text = nativeString("qr.instruction")
        instruction.textColor = .white
        instruction.font = .preferredFont(forTextStyle: .body)
        instruction.adjustsFontForContentSizeCategory = true
        instruction.numberOfLines = 0
        instruction.textAlignment = .center
        let cancel = UIButton(type: .system)
        var configuration = UIButton.Configuration.filled()
        configuration.title = nativeString("action.cancel")
        cancel.configuration = configuration
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [title, instruction, cancel])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24)
        ])
        let center = NotificationCenter.default
        for name in [AVCaptureSession.runtimeErrorNotification, AVCaptureSession.wasInterruptedNotification] {
            observers.append(center.addObserver(forName: name, object: session, queue: .main) { [weak self] _ in
                guard let self, !self.finished else { return }
                self.onError?("error.camera.interrupted")
                self.finish(nil)
            })
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        presentationController?.delegate = self
        guard !started, !finished else { return }
        started = true
        AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
            onMain {
                guard let self, !self.finished else { return }
                guard allowed else {
                    self.onError?("error.camera.permission"); self.finish(nil); return
                }
                guard UIApplication.shared.applicationState == .active else { self.finish(nil); return }
                self.configure()
            }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
        if let connection = preview?.connection, connection.isVideoOrientationSupported {
            switch view.window?.windowScene?.interfaceOrientation {
            case .landscapeLeft: connection.videoOrientation = .landscapeLeft
            case .landscapeRight: connection.videoOrientation = .landscapeRight
            case .portraitUpsideDown: connection.videoOrientation = .portraitUpsideDown
            default: connection.videoOrientation = .portrait
            }
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if !finished { finish(nil) }
    }

    private func configure() {
        // Session mutation/start/stop all use one serial queue, never the UI thread.
        captureQueue.async { [self] in
            do {
                guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
                    ?? AVCaptureDevice.default(for: .video) else { throw NativeFileError.unreadable }
                let input = try AVCaptureDeviceInput(device: camera)
                let output = AVCaptureMetadataOutput()
                session.beginConfiguration()
                defer { session.commitConfiguration() }
                guard session.canAddInput(input), session.canAddOutput(output) else {
                    throw NativeFileError.unreadable
                }
                session.addInput(input)
                session.addOutput(output)
                guard output.availableMetadataObjectTypes.contains(.qr) else { throw NativeFileError.unreadable }
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
            } catch {
                onMain { [weak self] in
                    guard let self, !self.finished else { return }
                    self.onError?("error.camera.unavailable")
                    self.finish(nil)
                }
                return
            }
            // A cancelled configuration queues teardown after this block. Check on main
            // before enqueueing start so cancellation can never restart a released session.
            onMain { [weak self] in
                guard let self, !self.finished else { return }
                self.captureQueue.async { [self] in session.startRunning() }
                self.view.setNeedsLayout()
            }
        }
    }

    @objc private func cancelTapped() { cancel() }
    func cancel() { finish(nil) }
    func invalidate() {
        onResult = nil
        onError = nil
        finish(nil)
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { cancel() }

    private func finish(_ value: String?) {
        guard !finished else { return }
        finished = true
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        preview?.removeFromSuperlayer()
        preview = nil
        captureQueue.async { [self] in
            if session.isRunning { session.stopRunning() }
            session.beginConfiguration()
            for output in session.outputs {
                (output as? AVCaptureMetadataOutput)?.setMetadataObjectsDelegate(nil, queue: nil)
                session.removeOutput(output)
            }
            for input in session.inputs { session.removeInput(input) }
            session.commitConfiguration()
            onMain { [self] in
                if presentingViewController != nil {
                    dismiss(animated: true) { [self] in complete(value) }
                } else { complete(value) }
            }
        }
    }

    private func complete(_ value: String?) {
        // Keep this cancellable until serial teardown and UIKit dismissal both finish.
        let callback = onResult
        onResult = nil
        onError = nil
        callback?(value)
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !finished else { return }
        if let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject })
            .first(where: { $0.type == .qr && $0.stringValue != nil }), let value = code.stringValue {
            finish(value)
        }
    }
}
