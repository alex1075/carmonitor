import Foundation

nonisolated(unsafe) var passed = 0
nonisolated(unsafe) var failed = 0

func check(_ ok: Bool, _ msg: @autoclosure () -> String, file: String = #fileID, line: Int = #line) {
    if ok { passed += 1 } else { failed += 1; print("FAIL \(file):\(line) \(msg())") }
}

func eq<T: Equatable>(_ a: T, _ b: T, _ msg: String, file: String = #fileID, line: Int = #line) {
    check(a == b, "\(msg): got \(a), expected \(b)", file: file, line: line)
}

/// Repo root, found from this file's path, so tests can read shared files (guard vectors, bundled OBDb sets).
let repoRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

func repoFile(_ path: String) throws -> Data { try Data(contentsOf: repoRoot.appendingPathComponent(path)) }
