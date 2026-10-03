import Foundation
import Security

/// Runs the production reader on macOS without UIKit, a simulator, or a fabricated SDK.
@main
struct BoundedDataChecks {
    static func main() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("claudebot-native-checks-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("attachment.bin")
        let bytes = Data((0..<65_537).map { UInt8(truncatingIfNeeded: $0) })
        try bytes.write(to: url)
        try require(try BoundedData.read(url, maximumBytes: bytes.count) == bytes,
                    "The exact boundary must preserve every byte across reader chunks")
        try rejected(url, limit: bytes.count - 1, expected: .tooLarge)

        // Exercise the actual platform limit, including a sparse oversized provider file.
        let full = Data(repeating: 0x5a, count: BoundedData.maximumBytes)
        try full.write(to: url)
        try require(try BoundedData.read(url) == full, "20 MiB must be accepted intact")
        let file = try FileHandle(forWritingTo: url)
        try file.truncate(atOffset: UInt64(BoundedData.maximumBytes + 1))
        try file.close()
        try rejected(url, limit: BoundedData.maximumBytes, expected: .tooLarge)

        try Data().write(to: url)
        try rejected(url, limit: BoundedData.maximumBytes, expected: .empty)
        try rejected(directory, limit: BoundedData.maximumBytes, expected: .unreadable)
        do {
            _ = try BoundedData.read(directory.appendingPathComponent("missing"))
            throw CheckError.failed("Missing files must fail")
        } catch is CheckError { throw CheckError.failed("Missing files must fail") }
        catch { }

        let imports = directory.appendingPathComponent("imports")
        let sibling = directory.appendingPathComponent("imports-other")
        let alias = directory.appendingPathComponent("alias")
        try FileManager.default.createDirectory(at: imports, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: sibling, withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: imports)
        let imported = imports.appendingPathComponent("copy.bin")
        let external = sibling.appendingPathComponent("keep.bin")
        try bytes.write(to: imported)
        try bytes.write(to: external)
        NativeTemporaryFiles.removeImport(alias.appendingPathComponent("copy.bin"), ownedDirectories: [imports])
        try require(!FileManager.default.fileExists(atPath: imported.path), "Aliased imports must be removed")
        NativeTemporaryFiles.removeImport(external, ownedDirectories: [imports])
        NativeTemporaryFiles.removeImport(imports, ownedDirectories: [imports])
        try require(FileManager.default.fileExists(atPath: external.path), "Sibling directories are not owned imports")
        try require(FileManager.default.fileExists(atPath: imports.path), "Never remove an import root itself")

        try require(try NativeStorage.decodeSecret(status: errSecItemNotFound, item: nil) == nil,
                    "Only a missing Keychain item returns nil")
        let testValue = "native-check-value"
        try require(try NativeStorage.decodeSecret(status: errSecSuccess, item: Data(testValue.utf8) as CFData) == testValue,
                    "Successful Keychain values must preserve their bytes")
        try rejectedSecret(status: errSecInteractionNotAllowed, item: nil, expected: errSecInteractionNotAllowed)
        try rejectedSecret(status: errSecSuccess, item: nil, expected: errSecDecode)
        try rejectedSecret(status: errSecSuccess, item: Data([0xff]) as CFData, expected: errSecDecode)
        print("PASS: bounded files, 20 MiB, owned import cleanup, Keychain missing/denied/corrupt results")
    }

    private enum CheckError: Error { case failed(String) }

    private static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw CheckError.failed(message) }
    }

    private static func rejectedSecret(status: OSStatus, item: CFTypeRef?, expected: OSStatus) throws {
        do {
            _ = try NativeStorage.decodeSecret(status: status, item: item)
            throw CheckError.failed("Expected Keychain failure")
        } catch let error as KeychainError {
            try require(error.status == expected, "Preserve the Keychain failure status")
        }
    }

    private static func rejected(_ url: URL, limit: Int, expected: NativeFileError) throws {
        do {
            _ = try BoundedData.read(url, maximumBytes: limit)
            throw CheckError.failed("Expected rejection")
        } catch let actual as NativeFileError {
            switch (actual, expected) {
            case (.tooLarge, .tooLarge), (.empty, .empty), (.unreadable, .unreadable): return
            default: throw CheckError.failed("Wrong rejection reason")
            }
        }
    }
}
