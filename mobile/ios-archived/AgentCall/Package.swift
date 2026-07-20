// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AgentCall",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "AgentCall", targets: ["AgentCall"]),
    ],
    dependencies: [
        .package(url: "https://github.com/stasel/WebRTC", from: "122.0.0"),
    ],
    targets: [
        .target(
            name: "AgentCall",
            dependencies: [
                .product(name: "WebRTC", package: "WebRTC"),
            ],
            resources: [
                .process("Resources"),
            ]
        ),
    ]
)
