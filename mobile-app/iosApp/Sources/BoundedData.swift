import Foundation

enum NativeFileError: Error {
    case tooLarge, empty, unreadable
}

enum BoundedData {
    static let maximumBytes = 20 * 1024 * 1024

    /// Never trust metadata alone: providers and growing files may report stale sizes.
    static func read(_ url: URL, maximumBytes: Int = maximumBytes) throws -> Data {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true else { throw NativeFileError.unreadable }
        if let size = values.fileSize, size > maximumBytes { throw NativeFileError.tooLarge }
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var data = Data()
        while true {
            // One extra byte detects an oversized stream without reading it all into memory.
            let chunk = try file.read(upToCount: min(64 * 1024, maximumBytes - data.count + 1)) ?? Data()
            if chunk.isEmpty { break }
            guard data.count + chunk.count <= maximumBytes else { throw NativeFileError.tooLarge }
            data.append(chunk)
        }
        guard !data.isEmpty else { throw NativeFileError.empty }
        return data
    }
}

enum NativeTemporaryFiles {
    /// Delete only copied imports owned by this app, including /var versus /private/var aliases.
    static func removeImport(_ url: URL, ownedDirectories: [URL] = [
        FileManager.default.temporaryDirectory,
        URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Documents/Inbox")
    ]) {
        guard url.isFileURL else { return }
        let path = url.standardizedFileURL.resolvingSymlinksInPath().path
        guard ownedDirectories.contains(where: {
            path.hasPrefix($0.standardizedFileURL.resolvingSymlinksInPath().path + "/")
        }) else { return }
        try? FileManager.default.removeItem(at: url)
    }
}
