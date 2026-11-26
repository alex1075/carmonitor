// swift-tools-version:6.0
import PackageDescription

// Tests are a plain executable (swift run SelfTest), like the Android core's SelfTest: the Command Line Tools
// ship neither XCTest nor the Swift Testing macros, and this runs the same with or without Xcode.
let package = Package(
    name: "CarMonitorKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "CarMonitorKit", targets: ["CarMonitorKit"])],
    targets: [
        .target(name: "CarMonitorKit"),
        .executableTarget(name: "SelfTest", dependencies: ["CarMonitorKit"]),
    ]
)
