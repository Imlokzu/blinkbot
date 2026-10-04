import UIKit
import UniformTypeIdentifiers

/** Own temporary bytes until the OS save/share controller finishes or is cancelled. */
final class NativeFileExport: NSObject, NativeOperation, UIDocumentPickerDelegate,
                              UIActivityItemSource, UIAdaptivePresentationControllerDelegate {
    var onResult: ((Bool) -> Void)?
    private weak var presenter: UIViewController?
    private var controller: UIViewController?
    private var directory: URL?
    private var fileURL: URL?
    private var data: Data?
    private let name: String
    private let typeIdentifier: String
    private var finished = false
    private let writeQueue = DispatchQueue(label: "me.waveio.claudebot.file-export", qos: .userInitiated)

    init(presenter: UIViewController, name: String, mime: String, data: Data) {
        self.presenter = presenter
        self.name = NativeExportFiles.safeName(name)
        self.typeIdentifier = UTType(mimeType: mime)?.identifier ?? UTType.data.identifier
        self.data = data
    }

    func start(sharing: Bool) {
        guard !finished, let data, data.count <= BoundedData.maximumBytes else {
            finish(false); return
        }
        self.data = nil
        // Retain this operation through disk I/O so cancellation cannot orphan its output.
        writeQueue.async { [self] in
            var output: URL?
            do { output = try NativeExportFiles.write(data, name: name) } catch { }
            onMain { [self] in
                guard !finished else {
                    if let output { try? FileManager.default.removeItem(at: output.deletingLastPathComponent()) }
                    return
                }
                guard let output else { finish(false); return }
                fileURL = output
                directory = output.deletingLastPathComponent()
                guard UIApplication.shared.applicationState == .active,
                      let presenter, presenter.viewIfLoaded?.window != nil,
                      presenter.presentedViewController == nil else { finish(false); return }
                let sheet: UIViewController
                if sharing {
                    let activity = UIActivityViewController(activityItems: [self], applicationActivities: nil)
                    activity.completionWithItemsHandler = { [weak self] _, completed, _, error in
                        onMain { self?.finish(completed && error == nil) }
                    }
                    sheet = activity
                } else {
                    let picker = UIDocumentPickerViewController(forExporting: [output], asCopy: true)
                    picker.delegate = self
                    sheet = picker
                }
                if let popover = sheet.popoverPresentationController {
                    popover.sourceView = presenter.view
                    popover.sourceRect = CGRect(x: presenter.view.bounds.midX,
                                                y: presenter.view.bounds.midY, width: 1, height: 1)
                    popover.permittedArrowDirections = []
                }
                controller = sheet
                presenter.present(sheet, animated: true)
                sheet.presentationController?.delegate = self
            }
        }
    }

    func cancel() { finish(false) }
    func invalidate() { onResult = nil; finish(false) }

    private func finish(_ success: Bool) {
        guard !finished else { return }
        finished = true
        data = nil
        let presented = controller
        controller = nil
        if let presented, presented.presentingViewController != nil {
            presented.dismiss(animated: true) { [self] in complete(success) }
        } else { complete(success) }
    }

    private func complete(_ success: Bool) {
        if let directory { try? FileManager.default.removeItem(at: directory) }
        directory = nil
        fileURL = nil
        let callback = onResult
        onResult = nil
        callback?(success)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { cancel() }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        finish(!urls.isEmpty)
    }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { cancel() }

    func activityViewControllerPlaceholderItem(_ activityViewController: UIActivityViewController) -> Any {
        // UIKit requests this only after the bounded temporary file has been written.
        fileURL ?? URL(fileURLWithPath: "/")
    }
    func activityViewController(_ activityViewController: UIActivityViewController,
                                itemForActivityType activityType: UIActivity.ActivityType?) -> Any? { fileURL }
    func activityViewController(_ activityViewController: UIActivityViewController,
                                dataTypeIdentifierForActivityType activityType: UIActivity.ActivityType?) -> String {
        typeIdentifier
    }
}
