import Foundation
import Security
import Darwin
import CoreFoundation

private func emit(_ line: String) {
    FileHandle.standardOutput.write(Data((line + "\n").utf8))
}

private func fail() -> Never {
    emit("ERROR")
    exit(1)
}

private func decode(_ value: String) -> String? {
    guard let bytes = Data(base64Encoded: value), let decoded = String(data: bytes, encoding: .utf8) else { return nil }
    return decoded
}

let input = FileHandle.standardInput.readDataToEndOfFile()
guard input.count <= 100_000,
      let text = String(data: input, encoding: .utf8) else { fail() }
var fields = text.components(separatedBy: "\n")
if fields.last == "" { fields.removeLast() }
guard fields.count == 4,
      let service = decode(fields[1]),
      let account = decode(fields[2]),
      !service.isEmpty,
      !account.isEmpty else { fail() }

let operation = fields[0]
let query: [String: Any] = [
    kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: service,
    kSecAttrAccount as String: account,
]

switch operation {
case "READ":
    var lookup = query
    lookup[kSecReturnData as String] = true
    lookup[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: CFTypeRef?
    let status = SecItemCopyMatching(lookup as CFDictionary, &result)
    if status == errSecItemNotFound {
        emit("MISSING")
    } else if status == errSecSuccess,
              let data = result as? Data,
              data.count <= 65_536 {
        emit("VALUE")
        emit(data.base64EncodedString())
    } else { fail() }
case "WRITE":
    guard fields[3] != "-", let secret = decode(fields[3]) else { fail() }
    let secretData = Data(secret.utf8)
    let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: secretData] as CFDictionary)
    if status == errSecItemNotFound {
        var item = query
        item[kSecValueData as String] = secretData
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { fail() }
    } else if status != errSecSuccess { fail() }
    emit("OK")
case "DELETE":
    guard fields[3] == "-" else { fail() }
    let status = SecItemDelete(query as CFDictionary)
    guard status == errSecSuccess || status == errSecItemNotFound else { fail() }
    emit("OK")
default:
    fail()
}
