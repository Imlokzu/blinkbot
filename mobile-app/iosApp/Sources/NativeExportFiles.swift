import Foundation

enum NativeExportFiles {
    static func safeName(_ name: String) -> String {
        let component = name.replacingOccurrences(of: "\\", with: "/").components(separatedBy: "/").last ?? ""
        let stripped = component.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }
        let cleaned = String(String.UnicodeScalarView(stripped)).trimmingCharacters(in: .whitespacesAndNewlines)
        let bounded = String(cleaned.prefix(180))
        return bounded.isEmpty || bounded == "." || bounded == ".." ? "attachment" : bounded
    }

    static func write(_ data: Data, name: String,
                       root: URL = FileManager.default.temporaryDirectory) throws -> URL {
        guard data.count <= BoundedData.maximumBytes else { throw NativeFileError.tooLarge }
        let directory = root.appendingPathComponent("native-export-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        do {
            let url = directory.appendingPathComponent(safeName(name))
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            try? FileManager.default.removeItem(at: directory)
            throw error
        }
    }
}
