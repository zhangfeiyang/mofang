// swift-tools-version:5.9
// 该 SwiftPM 包用于在没有 Xcode 的环境(Linux CI)编译并运行核心逻辑测试;
// iOS 应用工程直接以源文件引用 Core/ 与 Vision/(平台相关部分在 CubeAR/)。
import PackageDescription

let package = Package(
    name: "CubeARCore",
    targets: [
        .target(name: "CubeARCore", path: "Core"),
        .testTarget(name: "CubeARCoreTests", dependencies: ["CubeARCore"], path: "CoreTests"),
    ]
)
