import AVFoundation
import PhotosUI
import UniformTypeIdentifiers
import UIKit
import ClaudeBot

final class NativePicker: NSObject, NativeOperation, UIDocumentPickerDelegate,
                          PHPickerViewControllerDelegate, UIImagePickerControllerDelegate,
                          UINavigationControllerDelegate, UIAdaptivePresentationControllerDelegate {
    var onResult: ((PickedFile?) -> Void)?
    var onError: ((String) -> Void)?
    private weak var presenter: UIViewController?
    private var controller: UIViewController?
    private var finished = false
    private var loading = false
    private var progress: Progress?
    private let readQueue = DispatchQueue(label: "me.waveio.claudebot.file-reader", qos: .userInitiated)

    init(presenter: UIViewController) {
        self.presenter = presenter
    }

    func start(kind: String) {
        guard !finished else { return }
        switch kind {
        case "photo", "wallpaper":
            var configuration = PHPickerConfiguration()
            configuration.filter = .images
            configuration.selectionLimit = 1
            configuration.preferredAssetRepresentationMode = .current
            let picker = PHPickerViewController(configuration: configuration)
            picker.delegate = self
            present(picker)
        case "document":
            // Copy mode avoids retaining external provider files or bookmarks in the app.
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
            picker.allowsMultipleSelection = false
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
        onError = nil
        finish(nil)
    }

    private func fail(_ key: String) {
        onError?(key)
        finish(nil)
    }

    private func finish(_ result: PickedFile?) {
        guard !finished else { return }
        finished = true
        progress?.cancel()
        progress = nil
        let presented = controller
        controller = nil
        // Keep the coordinator alive until dismissal completes; the bridge owns it until callback.
        if let presented, presented.presentingViewController != nil {
            presented.dismiss(animated: true) { [self] in complete(result) }
        } else {
            complete(result)
        }
    }

    private func complete(_ result: PickedFile?) {
        // Invalidation during dismissal must still be able to discard this result.
        let callback = onResult
        onResult = nil
        onError = nil
        callback?(result)
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        // A selected document may dismiss before its coordinated read completes.
        if !loading { cancel() }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { cancel() }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard !finished, let url = urls.first else { cancel(); return }
        loading = true
        readQueue.async { [weak self] in
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped { url.stopAccessingSecurityScopedResource() }
                // asCopy owns this temporary import; remove it after consuming or rejecting it.
                NativeTemporaryFiles.removeImport(url)
            }
            var data: Data?
            var readError: Error?
            var coordinationError: NSError?
            NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { file in
                do { data = try BoundedData.read(file) } catch { readError = error }
            }
            let mime = (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType)?
                .preferredMIMEType ?? "application/octet-stream"
            self?.deliver(data: data, name: url.lastPathComponent, mime: mime,
                          error: readError ?? coordinationError)
        }
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        guard !finished, let item = results.first?.itemProvider else { cancel(); return }
        loading = true
        guard let identifier = item.registeredTypeIdentifiers.first(where: {
            UTType($0)?.conforms(to: .image) == true
        }) else { fail("error.file.invalid"); return }
        let type = UTType(identifier)
        let mime = type?.preferredMIMEType ?? "application/octet-stream"
        let loadingProgress = item.loadFileRepresentation(forTypeIdentifier: identifier) { [weak self] url, error in
            // This URL is only valid for this callback; read its bounded data before returning.
            do {
                guard let url, error == nil else { throw error ?? NativeFileError.unreadable }
                let data = try BoundedData.read(url)
                self?.deliver(data: data, name: url.lastPathComponent, mime: mime, error: nil)
            } catch {
                self?.deliver(data: nil, name: "", mime: mime, error: error)
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
