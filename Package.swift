// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ZLTM90Plus",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "ZLTM90Plus", targets: ["ZLTM90Plus"])
    ],
    targets: [
        .target(name: "ZLTM90Plus")
    ]
)
