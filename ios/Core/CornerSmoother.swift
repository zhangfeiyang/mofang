import Foundation

// Port of com.mofang.cubear.CornerSmoother.java
// 在使用检测四边形之前,把它在连续帧间做平均。
//
// 网络的角点误差一部分是系统性的,一部分是帧间抖动;只有抖动能被免费去掉,平均就是
// 去抖。它在精化前作用于粗角点,让精化器从更稳的估计出发。
//
// 一旦四边形跳变就放弃平均,这样跟随转动中的魔方仍然即时,只有真正静止的魔方才会被
// 平滑。角点按循环序对齐到上一帧:面接近 45 度时网络可能从不同的角点开始,错位平均会
// 把四边形压塌。

final class CornerSmoother {
    /// 跟踪建立后,最新观测的权重。
    private static let BLEND = 0.4

    private var smoothed: [Double]?

    /// 返回本帧的平滑四边形 (x0,y0..x3,y3);输入为空时返回 nil。
    func update(_ corners: [Double]?) -> [Double]? {
        guard var corners = corners else {
            smoothed = nil
            return nil
        }
        if let s = smoothed {
            corners = CornerSmoother.alignedTo(s, corners)
            if CornerSmoother.jumped(s, corners) { smoothed = nil }
        }
        guard let s = smoothed else {
            smoothed = corners
            return corners
        }
        var out = s
        for i in 0..<8 { out[i] = s[i] * (1 - CornerSmoother.BLEND) + corners[i] * CornerSmoother.BLEND }
        smoothed = out
        return out
    }

    func reset() { smoothed = nil }

    /// 把 corners 旋转到与 reference 循环序最接近的起点。
    static func alignedTo(_ reference: [Double], _ corners: [Double]) -> [Double] {
        var best = 0
        var lowest = Double.greatestFiniteMagnitude
        for shift in 0..<4 {
            var cost = 0.0
            for i in 0..<4 {
                let j = (i + shift) % 4
                cost += hypot(reference[i * 2] - corners[j * 2], reference[i * 2 + 1] - corners[j * 2 + 1])
            }
            if cost < lowest { lowest = cost; best = shift }
        }
        var out = [Double](repeating: 0, count: 8)
        for i in 0..<4 {
            let j = (i + best) % 4
            out[i * 2] = corners[j * 2]
            out[i * 2 + 1] = corners[j * 2 + 1]
        }
        return out
    }

    /// 一个角点移动超过四边形自身尺寸的五分之一,说明魔方被转动了。
    private static func jumped(_ previous: [Double], _ current: [Double]) -> Bool {
        let diagonal = hypot(previous[0] - previous[4], previous[1] - previous[5])
        let limit = max(18.0, diagonal * 0.20)
        for i in 0..<4 {
            if hypot(previous[i * 2] - current[i * 2], previous[i * 2 + 1] - current[i * 2 + 1]) > limit {
                return true
            }
        }
        return false
    }
}
