import AVFoundation
import Foundation

/// 把 Core 的 L10n 接到 App 包里的 Localizable.strings(由 ios/l10n/*.json 生成)。
/// 系统按用户的语言偏好在 App 支持的语言里选表;某个键缺失时退回英文。
enum AppStrings {
    private static let missing = "\u{0}"

    /// 必须在任何界面创建之前调用:视图控制器的按钮在初始化时就取文案。
    static func install() {
        let english = Bundle.main.path(forResource: "en", ofType: "lproj").flatMap(Bundle.init(path:))
        L10n.lookup = { key in
            let value = Bundle.main.localizedString(forKey: key, value: missing, table: nil)
            if value != missing { return value }
            let fallback = english?.localizedString(forKey: key, value: missing, table: nil)
            return fallback == missing ? nil : fallback
        }
    }

    /// 界面所用的语言(lproj 名,如 zh-Hans、pt-BR、en)。
    static var language: String { Bundle.main.preferredLocalizations.first ?? "en" }

    /// 与界面语言一致的系统语音。优先用户所在地区的口音(en-GB、zh-HK…),
    /// 其次该语言的常用口音;系统没有这种语言的语音时为 nil。
    static func speechVoice() -> AVSpeechSynthesisVoice? {
        let language = self.language
        let base = String(language.split(separator: "-").first ?? Substring(language))
        let current = AVSpeechSynthesisVoice.currentLanguageCode()
        if current.hasPrefix(base + "-") || current == base,
           !(language == "zh-Hans" && ["zh-TW", "zh-HK"].contains(current)),
           !(language == "zh-Hant" && current == "zh-CN"),
           let voice = AVSpeechSynthesisVoice(language: current) {
            return voice
        }
        let usual: [String: String] = ["zh-Hans": "zh-CN", "zh-Hant": "zh-TW", "en": "en-US", "es": "es-ES",
                                       "fr": "fr-FR", "de": "de-DE", "pt-BR": "pt-BR", "pt-PT": "pt-PT",
                                       "nb": "nb-NO", "ar": "ar-001", "nl": "nl-NL", "it": "it-IT"]
        if let code = usual[language], let voice = AVSpeechSynthesisVoice(language: code) { return voice }
        return AVSpeechSynthesisVoice(language: language)
            ?? AVSpeechSynthesisVoice.speechVoices().first { $0.language == base || $0.language.hasPrefix(base + "-") }
    }
}
