import AppKit
import Darwin
import Foundation

private let maximumInputBytes = 2 * 1024 * 1024 + 4096
private let maximumTextBytes = 2 * 1024 * 1024
private let maximumFileBytes: UInt64 = 20 * 1024 * 1024

private final class ShareCoordinator: NSObject, NSSharingServicePickerDelegate, NSSharingServiceDelegate {
    private let application: NSApplication
    private let window: NSWindow
    private let anchorView: NSView
    private var picker: NSSharingServicePicker?
    private var selectedService: NSSharingService?
    private var finished = false

    init(application: NSApplication) {
        self.application = application
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1, height: 1),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        anchorView = NSView(frame: NSRect(x: 0, y: 0, width: 1, height: 1))
        super.init()
        window.contentView = anchorView
        window.isReleasedWhenClosed = false
        window.level = .floating
        window.alphaValue = 0.01
        window.collectionBehavior = [.moveToActiveSpace, .transient]
    }

    func present(item: Any) {
        let mouse = NSEvent.mouseLocation
        let visibleFrame = NSScreen.main?.visibleFrame ?? NSRect(x: mouse.x, y: mouse.y, width: 1, height: 1)
        let x = min(max(mouse.x, visibleFrame.minX), visibleFrame.maxX - 1)
        let y = min(max(mouse.y, visibleFrame.minY), visibleFrame.maxY - 1)
        window.setFrameOrigin(NSPoint(x: x, y: y))
        window.makeKeyAndOrderFront(nil)
        application.activate(ignoringOtherApps: true)

        let newPicker = NSSharingServicePicker(items: [item])
        picker = newPicker
        newPicker.delegate = self
        newPicker.show(relativeTo: anchorView.bounds, of: anchorView, preferredEdge: .minY)
    }

    func sharingServicePicker(_ sharingServicePicker: NSSharingServicePicker, didChoose service: NSSharingService?) {
        guard let service else {
            finish(success: false)
            return
        }
        selectedService = service
        service.delegate = self
    }

    func sharingService(_ sharingService: NSSharingService, didShareItems items: [Any]) {
        finish(success: true)
    }

    func sharingService(_ sharingService: NSSharingService, didFailToShareItems items: [Any], error: Error) {
        finish(success: false)
    }

    private func finish(success: Bool) {
        guard !finished else { return }
        finished = true
        FileHandle.standardOutput.write(Data((success ? "OK\n" : "CANCEL\n").utf8))
        window.close()
        application.terminate(nil)
    }
}

private func readBoundedInput() throws -> Data {
    var data = Data()
    while true {
        let remaining = maximumInputBytes + 1 - data.count
        if remaining <= 0 { throw ShareError.invalidInput }
        guard let chunk = try FileHandle.standardInput.read(upToCount: min(remaining, 64 * 1024)), !chunk.isEmpty else {
            return data
        }
        data.append(chunk)
        if data.count > maximumInputBytes { throw ShareError.invalidInput }
    }
}

private enum ShareError: Error {
    case invalidInput
    case invalidFile
}

private func validatedItem(from data: Data) throws -> Any {
    guard
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
        let kind = object["kind"] as? String,
        let value = object["value"] as? String
    else { throw ShareError.invalidInput }

    switch kind {
    case "text":
        guard value.utf8.count <= maximumTextBytes else { throw ShareError.invalidInput }
        return value
    case "file":
        guard value.hasPrefix("/"), !value.utf8.contains(0) else { throw ShareError.invalidFile }
        let path = value.withCString { pathPointer -> (Int32, stat) in
            var information = stat()
            return (lstat(pathPointer, &information), information)
        }
        guard path.0 == 0, (path.1.st_mode & S_IFMT) == S_IFREG,
              path.1.st_size >= 0, UInt64(path.1.st_size) <= maximumFileBytes else {
            throw ShareError.invalidFile
        }
        return URL(fileURLWithPath: value, isDirectory: false)
    default:
        throw ShareError.invalidInput
    }
}

func main() {
    do {
        let item = try validatedItem(from: readBoundedInput())
        let application = NSApplication.shared
        application.setActivationPolicy(.accessory)
        let coordinator = ShareCoordinator(application: application)
        coordinator.present(item: item)
        withExtendedLifetime(coordinator) {
            application.run()
        }
    } catch {
        FileHandle.standardOutput.write(Data("CANCEL\n".utf8))
    }
}

main()
