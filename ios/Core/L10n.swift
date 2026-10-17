import Foundation

// 界面文字查表。所有文案以 ios/l10n/<语言>.json 为唯一来源:
// - iOS:tools/gen_strings.py 生成各 <语言>.lproj/Localizable.strings,App 启动时把 lookup 接到
//   Bundle.main(系统按用户语言选表,缺键时退回英文);
// - Linux 测试:直接读 JSON(见 CoreTests 的 TestL10n)。
// 占位符写作 {0}、{1}…,各语言可以调换顺序;不用 %@,Linux 的 Foundation 对它支持不全。

enum L10n {
    /// 按键取当前语言的文案;没有时返回 nil。启动时设置一次,之后只读。
    static var lookup: ((String) -> String?)?

    /// 键对应的文案,缺失时返回键本身(界面上一眼能看出漏翻)。
    static func t(_ key: String) -> String {
        lookup?(key) ?? key
    }

    /// 带占位符的文案:{0}、{1}… 依次替换为 args。
    static func t(_ key: String, _ args: CustomStringConvertible...) -> String {
        var text = t(key)
        for (i, arg) in args.enumerated() {
            text = text.replacingOccurrences(of: "{\(i)}", with: arg.description)
        }
        return text
    }

    /// 从 JSON 表(键 → 文案)建立 lookup;fallback 表补齐缺失的键。
    static func use(_ table: [String: String], fallback: [String: String] = [:]) {
        lookup = { table[$0] ?? fallback[$0] }
    }
}
