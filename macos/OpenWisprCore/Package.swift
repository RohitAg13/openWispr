// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "OpenWisprCore",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .library(name: "OpenWisprCore", targets: ["OpenWisprCore"]),
    ],
    targets: [
        .target(name: "OpenWisprCore"),
        .testTarget(
            name: "OpenWisprCoreTests",
            dependencies: ["OpenWisprCore"]
        ),
    ]
)
