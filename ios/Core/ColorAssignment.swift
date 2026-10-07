import Foundation

// Port of com.mofang.cubear.ColorAssignment.java
// 一次决定全部 54 张贴纸的颜色,而不是逐贴纸独立过阈值。
//
// 真实帧上逐贴纸阈值会失败:同一面内红与橙的色相差距(2-4 度)小于同一物理颜色跨帧的
// 漂移。这里六张中心贴纸充当本次扫描的颜色原型,再用最小代价分配把其余贴纸按"每色恰好
// 九张"的魔方自身约束分下去。

enum ColorAssignment {
    /// 贴纸与被禁止的原型配对时加上的代价。
    private static let FORBIDDEN = 1e9

    final class Result {
        /// 按面提供顺序排列的六面九格的最终颜色。
        let colors: [[CubeColor]]
        /// 每张贴纸到所配原型的 Lab 距离,布局同 colors。
        let residuals: [[Float]]
        /// 每面中心定义的原型序号,即该面携带的颜色。
        let faceColors: [CubeColor]
        /// 充当原型的六个中心读数,与 faceColors 对齐。
        let prototypes: [[Float]]

        init(colors: [[CubeColor]], residuals: [[Float]], faceColors: [CubeColor], prototypes: [[Float]]) {
            self.colors = colors
            self.residuals = residuals
            self.faceColors = faceColors
            self.prototypes = prototypes
        }

        /// 按匹配优劣排序的贴纸位置,最差在前,格式 face*9+index。
        var byDescendingResidual: [Int] {
            var order = Array(0..<54)
            order.sort { i, j in
                residuals[i / 9][i % 9] > residuals[j / 9][j % 9]
            }
            return order
        }
    }

    /// 把六个扫描面解析成一致的着色。
    ///
    /// - Parameters:
    ///   - faces: 恰好六个携带 Lab 读数的面
    ///   - nameOffset: 旋转原型到颜色的命名,主命名未通过魔方合法性检查时供调用方重试;0 为最优命名
    static func assign(_ faces: [FaceSample?], _ nameOffset: Int) -> Result? {
        if faces.count != 6 { return nil }
        for face in faces where face == nil || face!.lab == nil { return nil }

        var prototypes = [[Float]](repeating: [], count: 6)
        for f in 0..<6 { prototypes[f] = faces[f]!.lab![4] }

        guard var names = namePrototypes(prototypes, nameOffset) else { return nil }

        // 54 张贴纸对 54 个槽:每个原型九个槽,强制每种颜色恰好九张。
        var cost = [[Double]](repeating: [Double](repeating: 0, count: 54), count: 54)
        for sticker in 0..<54 {
            let face = sticker / 9, cell = sticker % 9
            let lab = faces[face]!.lab![cell]
            let isCenter = cell == 4
            // 被遮挡或过曝的贴纸不携带颜色证据,对所有原型代价相同,由九张约束决定归属。
            let wildcard = !isCenter && !faces[face]!.reliable[cell]
            for slot in 0..<54 {
                let prototype = slot / 9
                if isCenter && prototype != face {
                    cost[sticker][slot] = FORBIDDEN
                } else if isCenter || wildcard {
                    cost[sticker][slot] = 0
                } else {
                    cost[sticker][slot] = Double(Lab.distanceSquared(lab, prototypes[prototype]))
                }
            }
        }

        let assignment = Hungarian.solve(cost)
        var colors = [[CubeColor]](repeating: [CubeColor](repeating: .unknown, count: 9), count: 6)
        var residuals = [[Float]](repeating: [Float](repeating: 0, count: 9), count: 6)
        for sticker in 0..<54 {
            let face = sticker / 9, cell = sticker % 9
            let prototype = assignment[sticker] / 9
            colors[face][cell] = names[prototype]
            residuals[face][cell] = Lab.distance(faces[face]!.lab![cell], prototypes[prototype])
        }

        let faceColors = names
        return Result(colors: colors, residuals: residuals, faceColors: faceColors, prototypes: prototypes)
    }

    /// 给每个原型一个不同的颜色名。按一对一分配求解而不是最近参考色:红与橙互相接近,
    /// 但每个名字只能被一个原型占用,较暗的原型因此被强制分配到红色。
    ///
    /// 支持五个或六个原型。五个时留一个标准色未用——那就是缺失的面——重试时可以把该
    /// 名字分配给某个原型。
    static func namePrototypes(_ prototypes: [[Float]], _ offset: Int) -> [CubeColor]? {
        if prototypes.isEmpty || prototypes.count > 6 { return nil }
        let palette: [CubeColor] = [.white, .red, .green, .yellow, .orange, .blue]
        let n = prototypes.count
        var references = [[Float]](repeating: [], count: palette.count)
        for i in 0..<palette.count {
            let rgb = palette[i].rgb
            references[i] = Lab.fromRgb(Int((rgb >> 16) & 0xFF), Int((rgb >> 8) & 0xFF), Int(rgb & 0xFF))
        }

        var cost = [[Double]](repeating: [Double](repeating: 0, count: palette.count), count: n)
        for p in 0..<n {
            for r in 0..<palette.count {
                cost[p][r] = Double(Lab.distanceSquared(prototypes[p], references[r]))
            }
        }

        let naming = Hungarian.solve(cost)
        var names = [CubeColor](repeating: .unknown, count: n)
        for p in 0..<n { names[p] = palette[naming[p]] }
        if offset == 0 { return names }

        // 重试命名先试最便宜的替代:交换两个已分配的名字,或在某颜色仍空闲时把那个
        // 剩余名字给一个原型。
        var alternatives: [(Int, Int, Int)] = []
        for a in 0..<n {
            for b in (a + 1)..<n { alternatives.append((a, b, 0)) }
        }
        var used = [Bool](repeating: false, count: palette.count)
        for column in naming { used[column] = true }
        for p in 0..<n {
            for unused in 0..<palette.count where !used[unused] {
                alternatives.append((p, unused, 1))
            }
        }
        alternatives.sort { lhs, rhs in
            let lhsCost = lhs.2 == 0 ? swapPenalty(cost, naming, lhs.0, lhs.1)
                : cost[lhs.0][lhs.1] - cost[lhs.0][naming[lhs.0]]
            let rhsCost = rhs.2 == 0 ? swapPenalty(cost, naming, rhs.0, rhs.1)
                : cost[rhs.0][rhs.1] - cost[rhs.0][naming[rhs.0]]
            return lhsCost < rhsCost
        }
        if offset > alternatives.count { return nil }
        let alternative = alternatives[offset - 1]
        if alternative.2 == 0 {
            names.swapAt(alternative.0, alternative.1)
        } else {
            names[alternative.0] = palette[alternative.1]
        }
        return names
    }

    /// 两个原型互换名字后总代价增加多少;小意味着命名本就是五五开。
    private static func swapPenalty(_ cost: [[Double]], _ naming: [Int], _ first: Int, _ second: Int) -> Double {
        let current = cost[first][naming[first]] + cost[second][naming[second]]
        let swapped = cost[first][naming[second]] + cost[second][naming[first]]
        return swapped - current
    }
}
