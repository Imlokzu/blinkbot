import XCTest
import UIKit
@testable import ClaudeBotApp

final class NativeExportFilesTests: XCTestCase {
    func testSafeNamesStayInsideTheExportDirectory() {
        XCTAssertEqual(NativeExportFiles.safeName("../../report.pdf"), "report.pdf")
        XCTAssertEqual(NativeExportFiles.safeName("C:\\tmp\\report.pdf"), "report.pdf")
        XCTAssertEqual(NativeExportFiles.safeName("report\0.pdf"), "report.pdf")
        XCTAssertEqual(NativeExportFiles.safeName(".."), "attachment")
        XCTAssertEqual(NativeExportFiles.safeName("résumé 2026.pdf"), "résumé 2026.pdf")
    }

    func testExportPreservesNameAndBytes() throws {
        let data = Data([0, 1, 2, 255])
        let url = try NativeExportFiles.write(data, name: "../report.pdf")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        XCTAssertEqual(url.lastPathComponent, "report.pdf")
        XCTAssertEqual(try Data(contentsOf: url), data)
    }

    func testEmptyExportCreatesAnEmptyDocumentWhilePickingStillRejectsIt() throws {
        let url = try NativeExportFiles.write(Data(), name: "empty.txt")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        XCTAssertEqual(url.lastPathComponent, "empty.txt")
        XCTAssertEqual(try Data(contentsOf: url), Data())
        XCTAssertThrowsError(try BoundedData.read(url))
    }

    func testExactSizeExportSucceedsButOversizedExportFails() throws {
        let url = try NativeExportFiles.write(Data(count: BoundedData.maximumBytes), name: "exact.bin")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        XCTAssertEqual(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, BoundedData.maximumBytes)
        XCTAssertThrowsError(try NativeExportFiles.write(Data(count: BoundedData.maximumBytes + 1), name: "large"))
    }

    func testCancelledMultiPickerReturnsEmptyExactlyOnce() {
        let picker = NativePicker(presenter: UIViewController())
        var count = 0
        picker.onFilesResult = { files in count += 1; XCTAssertTrue(files.isEmpty) }
        picker.cancel()
        picker.cancel()
        XCTAssertEqual(count, 1)
    }

    func testInvalidatedMultiPickerDiscardsCallback() {
        let picker = NativePicker(presenter: UIViewController())
        picker.onFilesResult = { _ in XCTFail("Detached hosts must not receive queued selections") }
        picker.invalidate()
        picker.cancel()
    }
}
