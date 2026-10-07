import Foundation

// Port of com.mofang.cubear.CubeColor.java
// 六种贴纸颜色及其标准 URFDLB 中心映射。

enum CubeColor: Int, CaseIterable {
    case white = 0
    case red = 1
    case green = 2
    case yellow = 3
    case orange = 4
    case blue = 5
    case unknown = 6

    var face: Character {
        switch self {
        case .white: return "U"
        case .red: return "R"
        case .green: return "F"
        case .yellow: return "D"
        case .orange: return "L"
        case .blue: return "B"
        case .unknown: return "?"
        }
    }

    /// 0xFFRRGGBB(与安卓 argb 的低 24 位一致)。
    var rgb: UInt32 {
        switch self {
        case .white: return 0xF4F4F0
        case .red: return 0xE94335
        case .green: return 0x57C84D
        case .yellow: return 0xF5E629
        case .orange: return 0xFF9F2D
        case .blue: return 0x3974D9
        case .unknown: return 0x777777
        }
    }

    var chinese: String {
        switch self {
        case .white: return "白"
        case .red: return "红"
        case .green: return "绿"
        case .yellow: return "黄"
        case .orange: return "橙"
        case .blue: return "蓝"
        case .unknown: return "未知"
        }
    }

    static func fromFace(_ face: Character) -> CubeColor {
        allCases.first { $0.face == face } ?? .unknown
    }

    static func fromFace(_ face: String) -> CubeColor {
        guard let ch = face.first else { return .unknown }
        return fromFace(ch)
    }

    /// 对光照容忍的 HSV 分类器,为饱和的魔方贴纸调校。
    static func classify(red: Int, green: Int, blue: Int) -> CubeColor {
        let hsv = rgbToHsv(red: red, green: green, blue: blue)
        let h = hsv.0, s = hsv.1, v = hsv.2
        if v < 0.20 { return .unknown }
        if s < 0.22 && v > 0.48 { return .white }
        if s < 0.30 { return .unknown }
        if h < 12 || h >= 345 { return .red }
        if h < 42 { return .orange }
        if h < 78 { return .yellow }
        if h < 172 { return .green }
        if h < 275 { return .blue }
        return .red
    }

    static func rgbToHsv(red: Int, green: Int, blue: Int) -> (Double, Double, Double) {
        let r = Double(red) / 255, g = Double(green) / 255, b = Double(blue) / 255
        let mx = max(r, max(g, b)), mn = min(r, min(g, b))
        let delta = mx - mn
        var hue: Double
        if delta == 0 {
            hue = 0
        } else if mx == r {
            hue = 60 * (((g - b) / delta).truncatingRemainder(dividingBy: 6))
        } else if mx == g {
            hue = 60 * ((b - r) / delta + 2)
        } else {
            hue = 60 * ((r - g) / delta + 4)
        }
        if hue < 0 { hue += 360 }
        return (hue, mx == 0 ? 0 : delta / mx, mx)
    }
}
