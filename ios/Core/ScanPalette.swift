import Foundation

// Port of com.mofang.cubear.ScanPalette.java
// 这颗魔方在这盏灯下的六种颜色。
//
// 扫描求解后,六个中心读数已知正确,实时帧就可以按最近原型命名而不是按绝对阈值。
// 这在引导阶段最重要:识别一步是否完成意味着每一步都要读对九张贴纸,固定色相边界
// 的错误率足以让演示卡住。
//
// 第六面是推算而非扫描时,调色板以五个原型起步,并记住哪个颜色从未见过。离所有原型
// 都太远的贴块保持无名——它们只可能属于缺失的颜色——而该颜色第一个稳定的中心读数
// 会当场补全调色板。

final class ScanPalette {
    /// 超过该距离即不匹配任何已知原型。真实魔方上最接近的红橙对,即使漂移也大于此值,
    /// 所以只有真正没见过的颜色才会越界。
    private static let UNSEEN_CAP: Float = 24

    private var prototypes: [[Float]]
    private var names: [CubeColor]
    /// 尚无原型的颜色;集齐六色后为 nil。
    private(set) var missing: CubeColor?

    private init(_ prototypes: [[Float]], _ names: [CubeColor], _ missing: CubeColor?) {
        self.prototypes = prototypes
        self.names = names
        self.missing = missing
    }

    static func from(_ result: ColorAssignment.Result?) -> ScanPalette? {
        guard let result = result, !result.prototypes.isEmpty else { return nil }
        return ScanPalette(result.prototypes.map { $0 }, result.faceColors, nil)
    }

    static func fromFive(_ prototypes: [[Float]], _ names: [CubeColor], _ missing: CubeColor) -> ScanPalette {
        ScanPalette(prototypes.map { $0 }, names, missing)
    }

    /// 采用一个中心读数作为缺失颜色的原型。
    ///
    /// 调用方必须已确认镜头前的面就是缺失的颜色;任何与五个已知原型都不匹配的可靠
    /// 中心,只可能是它。
    func learnMissing(_ lab: [Float]?) {
        guard let missing = missing, let lab = lab else { return }
        prototypes.append(lab)
        names.append(missing)
        self.missing = nil
    }

    /// 从缺失颜色的可靠贴块补全五色调色板。
    ///
    /// 打乱的魔方上,该颜色已经出现在扫描过的面上,引导不必等未见过的中心转到镜头前。
    /// 只有当贴块比任何已扫描原型都更接近光照适配后的缺失颜色时才算数——否则离五个
    /// 原型都远的高光会被当成假的第六色。
    ///
    /// - Returns: 是否从该样本学到了缺失颜色
    @discardableResult
    func maybeLearn(_ sample: FaceSample?) -> Bool {
        guard let missing = missing, let sample = sample, let lab = sample.lab else { return false }
        let expected = adaptedMissing()
        var best = -1
        var bestScore = Float.greatestFiniteMagnitude
        for i in 0..<9 where sample.reliable[i] {
            let toExpected = Lab.distance(lab[i], expected)
            let toKnown = sqrtf(nearestDistanceSquared(lab[i]))
            if toExpected + 8 >= toKnown { continue }
            // 中心合格时优先取中心:那个读数已经命名过一个面。
            let score = toExpected + (i == 4 ? 0 : 4)
            if score < bestScore {
                bestScore = score
                best = i
            }
        }
        if best < 0 { return false }
        learnMissing(lab[best])
        _ = missing
        return true
    }

    /// 标准缺失颜色,按五个中心呈现的平均 Lab 偏移平移。
    private func adaptedMissing() -> [Float] {
        var offset = [Float](repeating: 0, count: 3)
        for i in 0..<prototypes.count {
            let rgb = names[i].rgb
            let canonical = Lab.fromRgb(Int((rgb >> 16) & 0xFF), Int((rgb >> 8) & 0xFF), Int(rgb & 0xFF))
            for channel in 0..<3 {
                offset[channel] += prototypes[i][channel] - canonical[channel]
            }
        }
        guard let missing = missing else { return offset }
        let rgb = missing.rgb
        var sixth = Lab.fromRgb(Int((rgb >> 16) & 0xFF), Int((rgb >> 8) & 0xFF), Int(rgb & 0xFF))
        let count = Float(prototypes.count)
        for channel in 0..<3 { sixth[channel] += offset[channel] / count }
        return sixth
    }

    private func nearestDistanceSquared(_ lab: [Float]) -> Float {
        var best = Float.greatestFiniteMagnitude
        for prototype in prototypes {
            best = min(best, Lab.distanceSquared(lab, prototype))
        }
        return best
    }

    func nearest(_ lab: [Float]) -> CubeColor {
        names[nearestIndex(lab)]
    }

    private func nearestIndex(_ lab: [Float]) -> Int {
        var best = 0
        var bestDistance = Float.greatestFiniteMagnitude
        for i in 0..<prototypes.count {
            let distance = Lab.distanceSquared(lab, prototypes[i])
            if distance < bestDistance {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    /// 按此调色板重命名实时样本。被标记不可靠的贴块保持未知,让调用方可以忽略而不是
    /// 按猜测行动;仍有颜色缺失时,越出上限的贴块同样保持未知,因为按五个最近原型命名
    /// 它们也是猜测。
    func relabel(_ sample: FaceSample?) -> FaceSample? {
        guard let sample = sample, let lab = sample.lab else { return sample }
        var colors = [CubeColor](repeating: .unknown, count: 9)
        for i in 0..<9 {
            if !sample.reliable[i] { continue }
            let reading = lab[i]
            if missing != nil && nearestDistanceSquared(reading) > Self.UNSEEN_CAP * Self.UNSEEN_CAP { continue }
            colors[i] = names[nearestIndex(reading)]
        }
        return FaceSample(colors, lab, sample.reliable, sample.confidence)
    }
}
