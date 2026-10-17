import Foundation
@testable import CubeARCore

/// 测试用的文案表:直接读 ios/l10n/<语言>.json,缺键退回英文(与 App 一致)。
enum TestL10n {
    static let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent("l10n")

    static func table(_ language: String) -> [String: String] {
        let url = directory.appendingPathComponent(language + ".json")
        guard let data = try? Data(contentsOf: url),
              let table = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return [:] }
        return table
    }

    static var languages: [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? [])
            .filter { $0.hasSuffix(".json") }.map { String($0.dropLast(5)) }.sorted()
    }

    static func use(_ language: String) {
        L10n.use(table(language), fallback: table("en"))
    }
}
