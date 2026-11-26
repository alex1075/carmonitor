import Foundation

let suites: [(String, () async throws -> Void)] = [
    ("guard", guardTests),
    ("signals", signalTests),
    ("elm", elmTests),
]
for (name, run) in suites {
    do { try await run() } catch { failed += 1; print("FAIL \(name): threw \(error)") }
}
print("passed: \(passed), failed: \(failed)")
exit(failed == 0 ? 0 : 1)
