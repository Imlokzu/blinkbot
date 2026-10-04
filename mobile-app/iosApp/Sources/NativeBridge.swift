import UIKit
import UserNotifications
import ClaudeBot

protocol NativeOperation: AnyObject {
    func cancel()
    func invalidate()
}

func nativeString(_ key: String) -> String {
    NSLocalizedString(key, tableName: "Localizable", bundle: .main, comment: "")
}

func onMain(_ action: @escaping () -> Void) {
    if Thread.isMainThread { action() } else { DispatchQueue.main.async(execute: action) }
}

final class NativeBridge: NSObject, IosNativeDelegate, UNUserNotificationCenterDelegate {
    private weak var controller: UIViewController?
    private weak var bridge: IosBridge?
    private var observers: [NSObjectProtocol] = []
    private weak var scene: UIWindowScene?
    private var operation: NativeOperation?
    private let recording = NativeRecording()
    private var pendingErrors: [String] = []
    private let keychainQueue = DispatchQueue(label: "me.waveio.claudebot.keychain")
    private let preferences = UserDefaults.standard
    private let preferencePrefix = "claudebot."

    var systemLanguage: String { Locale.preferredLanguages.first ?? "en" }
    var reducedMotion: Bool { UIAccessibility.isReduceMotionEnabled }

    func attach(controller: UIViewController, bridge: IosBridge) {
        self.controller = controller
        self.bridge = bridge
        UNUserNotificationCenter.current().delegate = self
        recording.onError = { [weak self] key in self?.showError(key) }
    }

    func detach() {
        // Kotlin/Native GC may retain this delegate after SwiftUI releases its host.
        bridge?.setForeground(value: false)
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        scene = nil
        recording.invalidate()
        operation?.invalidate()
        operation = nil
        controller = nil
        bridge = nil
        pendingErrors.removeAll()
        let center = UNUserNotificationCenter.current()
        if center.delegate === self { center.delegate = nil }
    }

    func observeScene(_ scene: UIWindowScene) {
        bridge?.setForeground(value: scene.activationState == .foregroundActive)
        guard self.scene !== scene else { flushErrors(); return }
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        self.scene = scene
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIScene.didActivateNotification,
                                            object: scene, queue: .main) { [weak self] _ in
            self?.bridge?.setForeground(value: true)
            self?.flushErrors()
        })
        observers.append(center.addObserver(forName: UIScene.willDeactivateNotification,
                                            object: scene, queue: .main) { [weak self] _ in
            self?.bridge?.setForeground(value: false)
        })
        observers.append(center.addObserver(forName: UIScene.didEnterBackgroundNotification,
                                            object: scene, queue: .main) { [weak self] _ in
            self?.bridge?.setForeground(value: false)
            self?.recording.cancel()
            self?.operation?.cancel()
        })
        flushErrors()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        recording.invalidate()
        operation?.invalidate()
    }

    func readPreference(key: String) -> String? {
        preferences.string(forKey: preferencePrefix + key)
    }

    func writePreference(key: String, value: String?) {
        if let value { preferences.set(value, forKey: preferencePrefix + key) }
        else { preferences.removeObject(forKey: preferencePrefix + key) }
    }

    func readSecret(key: String) -> IosSecretResult {
        do {
            return IosSecretResult(value: try keychainQueue.sync { try NativeStorage.readSecret(key) },
                                   errorMessage: nil)
        } catch {
            return IosSecretResult(value: nil, errorMessage: storageFailure())
        }
    }

    func writeSecret(key: String, value: String?) -> String? {
        do {
            try keychainQueue.sync { try NativeStorage.writeSecret(key, value: value) }
            return nil
        } catch { return storageFailure() }
    }

    private func storageFailure() -> String {
        showError("error.keychain")
        return nativeString("error.keychain")
    }

    func scanQr(onResult: @escaping (String?) -> KotlinUnit) {
        onMain { [weak self] in
            guard let self, let presenter = self.availablePresenter() else {
                _ = onResult(nil); return
            }
            let scanner = QRScanController()
            scanner.onError = { [weak self] in self?.showError($0) }
            scanner.onResult = { [weak self, weak scanner] value in
                guard let self, let scanner, self.operation === scanner else { return }
                self.operation = nil
                _ = onResult(value)
                self.flushErrors()
            }
            self.operation = scanner
            self.recording.cancel()
            guard self.operation === scanner, self.controller === presenter,
                  UIApplication.shared.applicationState == .active else { scanner.cancel(); return }
            presenter.present(scanner, animated: true)
        }
    }

    func pickFile(kind: String, onResult: @escaping (PickedFile?) -> KotlinUnit) {
        startPicker(kind: kind, multiple: false) { _ = onResult($0.first) }
    }

    func pickFiles(kind: String, onResult: @escaping ([PickedFile]) -> KotlinUnit) {
        startPicker(kind: kind, multiple: true) { _ = onResult($0) }
    }

    private func startPicker(kind: String, multiple: Bool, onResult: @escaping ([PickedFile]) -> Void) {
        onMain { [weak self] in
            guard let self, let presenter = self.availablePresenter() else {
                onResult([]); return
            }
            let picker = NativePicker(presenter: presenter)
            picker.onError = { [weak self] in self?.showError($0) }
            picker.onFilesResult = { [weak self, weak picker] result in
                guard let self, let picker, self.operation === picker else { return }
                self.operation = nil
                onResult(result)
                self.flushErrors()
            }
            self.operation = picker
            self.recording.cancel()
            guard self.operation === picker, self.controller === presenter,
                  UIApplication.shared.applicationState == .active else { picker.cancel(); return }
            picker.start(kind: kind, multiple: multiple)
        }
    }

    func saveFile(file: PickedFile, onResult: @escaping (KotlinBoolean) -> KotlinUnit) {
        exportFile(file, sharing: false, onResult: onResult)
    }

    func shareFile(file: PickedFile, onResult: @escaping (KotlinBoolean) -> KotlinUnit) {
        exportFile(file, sharing: true, onResult: onResult)
    }

    private func exportFile(_ file: PickedFile, sharing: Bool,
                            onResult: @escaping (KotlinBoolean) -> KotlinUnit) {
        onMain { [weak self] in
            guard let self, let presenter = self.availablePresenter(),
                  let data = IosBridgeKt.iosFileData(file: file) else {
                _ = onResult(KotlinBoolean(bool: false)); return
            }
            let exporter = NativeFileExport(presenter: presenter, name: file.name, mime: file.mimeType, data: data)
            exporter.onResult = { [weak self, weak exporter] success in
                guard let self, let exporter, self.operation === exporter else { return }
                self.operation = nil
                _ = onResult(KotlinBoolean(bool: success))
                self.flushErrors()
            }
            self.operation = exporter
            self.recording.cancel()
            guard self.operation === exporter, self.controller === presenter,
                  UIApplication.shared.applicationState == .active else { exporter.cancel(); return }
            exporter.start(sharing: sharing)
        }
    }

    func startRecording(onAmplitude: @escaping (KotlinFloat) -> KotlinUnit,
                        onResult: @escaping (PickedFile?) -> KotlinUnit,
                        onPartial: @escaping (PickedFile) -> KotlinUnit) {
        onMain { [weak self] in
            guard let self, self.operation == nil,
                  self.controller != nil, self.bridge != nil,
                  UIApplication.shared.applicationState == .active else { _ = onResult(nil); return }
            self.recording.start(amplitude: { [weak self] in
                guard self != nil else { return }
                _ = onAmplitude(KotlinFloat(float: $0))
            }, result: { [weak self] in
                guard self != nil else { return }
                _ = onResult($0)
            }, partial: { [weak self] in
                guard self != nil else { return }
                _ = onPartial($0)
            })
        }
    }

    func stopRecording() { onMain { [weak self] in self?.recording.stop() } }
    func cancelRecording() { onMain { [weak self] in self?.recording.cancel() } }
    func haptic() {
        onMain { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    }
    func copyText(value: String) { onMain { UIPasteboard.general.string = value } }

    func shareText(value: String) {
        onMain { [weak self] in
            guard let presenter = self?.availablePresenter() else { return }
            let sheet = UIActivityViewController(activityItems: [value], applicationActivities: nil)
            if let popover = sheet.popoverPresentationController {
                popover.sourceView = presenter.view
                popover.sourceRect = CGRect(x: presenter.view.bounds.midX,
                                            y: presenter.view.bounds.midY, width: 1, height: 1)
                popover.permittedArrowDirections = []
            }
            presenter.present(sheet, animated: true)
        }
    }

    func requestNotifications(onResult: @escaping (KotlinBoolean) -> KotlinUnit) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) {
            [weak self] allowed, error in
            onMain { [weak self] in
                guard let self else { return }
                if error != nil { self.showError("error.notifications") }
                _ = onResult(KotlinBoolean(bool: allowed && error == nil))
            }
        }
    }

    func notifyReply(title: String, body: String, conversationId: String) {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { [weak self] settings in
            guard settings.authorizationStatus == .authorized ||
                    settings.authorizationStatus == .provisional ||
                    settings.authorizationStatus == .ephemeral else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            content.userInfo = ["conversationId": conversationId]
            let request = UNNotificationRequest(identifier: "reply." + UUID().uuidString,
                                                 content: content, trigger: nil)
            center.add(request) { error in
                if error != nil { self?.showError("error.notifications") }
            }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completion: @escaping (UNNotificationPresentationOptions) -> Void) {
        completion([.banner, .list, .sound])
    }

    private func availablePresenter() -> UIViewController? {
        guard operation == nil, UIApplication.shared.applicationState == .active,
              let controller, controller.viewIfLoaded?.window != nil,
              controller.presentedViewController == nil else { return nil }
        return controller
    }

    func showError(_ key: String) {
        onMain { [weak self] in
            guard let self else { return }
            if !self.pendingErrors.contains(key) { self.pendingErrors.append(key) }
            self.flushErrors()
        }
    }

    private func flushErrors() {
        guard !pendingErrors.isEmpty, let presenter = availablePresenter() else { return }
        let key = pendingErrors.removeFirst()
        let alert = UIAlertController(title: nativeString("error.title"), message: nativeString(key),
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: nativeString("action.ok"), style: .default) { [weak self] _ in
            // UIKit dismisses the alert after the handler returns.
            DispatchQueue.main.async { self?.flushErrors() }
        })
        presenter.present(alert, animated: true)
    }
}
