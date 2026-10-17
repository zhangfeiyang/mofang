import Foundation

// Port of com.mofang.cubear.ScanDump.java
// 组装失败时把观测池写出来,便于离线复现(与安卓 scan-fail.json 同格式,
// 可直接放进 app/src/test/resources 给 ScanFailReplayTest 用)。
//
// 不写就只能猜:真机上的失败往往只在真实画面里复现。iOS 写到 App 的 Documents 目录,
// 用 Xcode「Devices and Simulators → 下载容器」取回。

enum ScanDump {
    /// 观测池的 JSON 文本。
    static func json(_ pool: [FaceSample]) -> String {
        var out = "[\n"
        for (i, face) in pool.enumerated() {
            out += "  {\"confidence\": \(face.confidence), \"center\": \"\(name(face.center))\", \"lab\": ["
            for cell in 0..<9 {
                let lab = face.lab?[cell] ?? [0, 0, 0]
                out += (cell == 0 ? "" : ", ") + "[\(lab[0]), \(lab[1]), \(lab[2])]"
            }
            out += "], \"reliable\": ["
            for cell in 0..<9 {
                out += (cell == 0 ? "" : ", ") + (face.reliable[cell] ? "true" : "false")
            }
            out += "]}" + (i == pool.count - 1 ? "\n" : ",\n")
        }
        return out + "]\n"
    }

    /// 尽力而为:诊断文件写不出来,总好过因此抛错。
    static func write(_ pool: [FaceSample], to url: URL) {
        try? json(pool).write(to: url, atomically: true, encoding: .utf8)
    }

    /// 与 Java 枚举名一致(WHITE、RED…),回放测试按名字解析。
    private static func name(_ color: CubeColor) -> String {
        switch color {
        case .white: return "WHITE"
        case .red: return "RED"
        case .green: return "GREEN"
        case .yellow: return "YELLOW"
        case .orange: return "ORANGE"
        case .blue: return "BLUE"
        case .unknown: return "UNKNOWN"
        }
    }
}
