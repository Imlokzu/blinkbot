import AVFoundation
import PhotosUI
import UniformTypeIdentifiers
import UIKit
import ClaudeBot

final class NativePicker: NSObject, NativeOperation, UIDocumentPickerDelegate,
                          PHPickerViewControllerDelegate, UIImagePickerControllerDelegate,
                          UINavigationControllerDelegate, UIAdaptivePresentationControllerDelegate {
    var onResult: ((PickedFile?) -> Void)?
    var onFilesResult: (([PickedFile]) -> Void)?
    var onError: ((String) -> Void)?
    private weak var presenter: UIViewController?
    private var controller: UIViewController?
    private var finished = false
    private var loading = false
    private var progress: Progress?
    private let readCancellation = Progress(totalUnitCount: 1)
    private var multiple = false
    private var files: [PickedFile] = []
    private var remainingBytes = BoundedData.maximumBytes
    private let readQueue = DispatchQueue(label: "me.waveio.claudebot.file-reader", qos: .userInitiated)

    init(presenter: UIViewController) {
        self.presenter = presenter
    }

    func start(kind: String, multiple: Bool = false) {
        guard !finished else { return }
        self.multiple = multiple && (kind == "photo" || kind == "document")
        switch kind {
        case "photo", "wallpaper":
            var configuration = PHPickerConfiguration()
            configuration.filter = .images
            configuration.selectionLimit = self.multiple ? BoundedData.maximumSelection : 1
            configuration.preferredAssetRepresentationMode = .current
            let picker = PHPickerViewController(configuration: configuration)
            picker.delegate = self
            present(picker)
        case "document":
            // Copy mode avoids retaining external provider files or bookmarks in the app.
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
            picker.allowsMultipleSelection = self.multiple
            picker.delegate = self
            present(picker)
        case "camera":
            guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
                fail("error.camera.unavailable"); return
            }
            AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
                onMain {
                    guard let self, !self.finished else { return }
                    guard allowed else { self.fail("error.camera.permission"); return }
                    guard UIApplication.shared.applicationState == .active else { self.cancel(); return }
                    let picker = UIImagePickerController()
                    picker.sourceType = .camera
                    picker.mediaTypes = [UTType.image.identifier]
                    picker.cameraCaptureMode = .photo
                    picker.delegate = self
                    self.present(picker)
                }
            }
        default:
            fail("error.picker.kind")
        }
    }

    private func present(_ controller: UIViewController) {
        guard !finished, UIApplication.shared.applicationState == .active,
              let presenter, presenter.viewIfLoaded?.window != nil,
              presenter.presentedViewController == nil else { cancel(); return }
        self.controller = controller
        presenter.present(controller, animated: true)
        controller.presentationController?.delegate = self
    }

    func cancel() { finish(nil) }
    func invalidate() {
        onResult = nil
        onFilesResult = nil
        onError = nil
        finish(nil)
    }

    private func fail(_ key: String) {
        onError?(key)
        finish(nil)
    }

    private func finish(_ result: PickedFile?) {
        finishFiles(result.map { [$0] } ?? [])
    }

    private func finishFiles(_ results: [PickedFile]) {
        guard !finished else { return }
        finished = true
        progress?.cancel()
        progress = nil
        readCancellation.cancel()
        files.removeAll()
        let presented = controller
        controller = nil
        // Keep the coordinator alive until dismissal completes; the bridge owns it until callback.
        if let presented, presented.presentingViewController != nil {
            presented.dismiss(animated: true) { [self] in complete(results) }
        } else {
            complete(results)
        }
    }

    private func complete(_ results: [PickedFile]) {
        // Invalidation during dismissal must still be able to discard this result.
        let callback = onResult
        let filesCallback = onFilesResult
        onResult = nil
        onFilesResult = nil
        onError = nil
        if let filesCallback { filesCallback(results) } else { callback?(results.first) }
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        // A selected document may dismiss before its coordinated read completes.
        if !loading { cancel() }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { cancel() }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard !finished, !urls.isEmpty else { cancel(); return }
        loading = true
        let selected = Array(urls.prefix(multiple ? BoundedData.maximumSelection : 1))
        let cancellation = readCancellation
        let multiple = self.multiple
        readQueue.async { [weak self] in
            // Copy mode owns all returned imports, including entries beyond our selection cap.
            defer { urls.forEach { NativeTemporaryFiles.removeImport($0) } }
            var remaining = BoundedData.maximumBytes
            var loaded: [(Data, String, String)] = []
            var failure: Error?
            for url in selected {
                if cancellation.isCancelled || remaining == 0 { break }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                var data: Data?
                var readError: Error?
                var coordinationError: NSError?
                NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { file in
                    do {
                        data = try BoundedData.read(file, maximumBytes: remaining) {
                            if cancellation.isCancelled { throw NativeFileError.unreadable }
                        }
                    } catch { readError = error }
                }
                if let data, readError == nil, coordinationError == nil {
                    let mime = (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType)?
                        .preferredMIMEType ?? "application/octet-stream"
                    loaded.append((data, url.lastPathComponent, mime))
                    remaining -= data.count
                } else { failure = readError ?? coordinationError ?? NativeFileError.unreadable }
            }
            onMain { [weak self] in
                guard let self, !self.finished else { return }
                if !multiple, let failure {
                    self.deliver(data: nil, name: "", mime: "", error: failure); return
                }
                let files = loaded.compactMap { IosBridgeKt.iosPickedFile(name: $0.1, mimeType: $0.2, data: $0.0) }
                self.finishFiles(files)
            }
        }
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        guard !finished, !results.isEmpty else { cancel(); return }
        loading = true
        loadPhotos(Array(results.prefix(multiple ? BoundedData.maximumSelection : 1)), index: 0)
    }

    private func loadPhotos(_ results: [PHPickerResult], index: Int) {
        guard !finished else { return }
        guard index < results.count, remainingBytes > 0 else { finishFiles(files); return }
        let item = results[index].itemProvider
        guard let identifier = item.registeredTypeIdentifiers.first(where: {
            UTType($0)?.conforms(to: .image) == true
        }) else {
            if multiple { loadPhotos(results, index: index + 1) } else { fail("error.file.invalid") }
            return
        }
        let type = UTType(identifier)
        let mime = type?.preferredMIMEType ?? "application/octet-stream"
        let limit = remainingBytes
        let cancellation = readCancellation
        let loadingProgress = item.loadFileRepresentation(forTypeIdentifier: identifier) { [weak self] url, error in
            // This URL is only valid for this callback; read its bounded data before returning.
            var data: Data?
            var failure: Error?
            do {
                guard let url, error == nil else { throw error ?? NativeFileError.unreadable }
                data = try BoundedData.read(url, maximumBytes: limit) {
                    if cancellation.isCancelled { throw NativeFileError.unreadable }
                }
            } catch { failure = error }
            let name = url?.lastPathComponent ?? "attachment"
            // Always enqueue to avoid synchronous provider callbacks replacing the next Progress.
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.finished else { return }
                self.progress = nil
                if let data, failure == nil,
                   let file = IosBridgeKt.iosPickedFile(name: name, mimeType: mime, data: data) {
                    self.files.append(file)
                    self.remainingBytes -= data.count
                } else if !self.multiple {
                    self.deliver(data: nil, name: name, mime: mime, error: failure ?? NativeFileError.unreadable)
                    return
                }
                self.loadPhotos(results, index: index + 1)
            }
        }
        if finished { loadingProgress.cancel() } else { progress = loadingProgress }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { cancel() }

    func imagePickerController(_ picker: UIImagePickerController,
                               didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        guard !finished, let image = info[.originalImage] as? UIImage else {
            fail("error.file.invalid"); return
        }
        loading = true
        // Bound the camera JPEG's pixel count before allocating its encoded output.
        let width = image.size.width * image.scale
        let height = image.size.height * image.scale
        guard width > 0, height > 0, width.isFinite, height.isFinite else {
            fail("error.file.invalid"); return
        }
        let factor = min(1, 4096 / max(width, height))
        let size = CGSize(width: width * factor, height: height * factor)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let reduced = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        let data = reduced.jpegData(compressionQuality: 0.85)
        deliver(data: data, name: "camera-" + UUID().uuidString + ".jpg", mime: "image/jpeg",
                error: data == nil ? NativeFileError.unreadable : nil)
    }

    private func deliver(data: Data?, name: String, mime: String, error: Error?) {
        onMain { [weak self] in
            guard let self, !self.finished else { return }
            if let error {
                if case NativeFileError.tooLarge = error { self.fail("error.file.tooLarge") }
                else { self.fail("error.file.invalid") }
                return
            }
            guard let data, !data.isEmpty else { self.fail("error.file.invalid"); return }
            guard data.count <= BoundedData.maximumBytes else { self.fail("error.file.tooLarge"); return }
            guard let file = IosBridgeKt.iosPickedFile(name: name, mimeType: mime, data: data) else {
                self.fail("error.file.invalid"); return
            }
            self.finish(file)
        }
    }
}
