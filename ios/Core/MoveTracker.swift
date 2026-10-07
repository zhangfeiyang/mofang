import Foundation

// Port of com.mofang.cubear.MoveTracker.java
// 从相机所见跟随用户完成求解。
//
// 匹配会向前看几步,相机错过一步也不会困住用户,且每个判断都可被手动控制覆盖。

final class MoveTracker {
    /// 一次观察最多可确认当前步之后的几步。
    static let LOOKAHEAD = 3
    /// 面被识别前须一致的可行读贴纸数。
    static let MIN_MATCHING = 7

    enum Outcome { case none, confirmed, advanced, mismatch }

    let moves: [String]
    let states: [String]
    private(set) var index = 0
    /// 每个面字母(URFDLB)上次与当前状态吻合的旋转角,或 -1。
    private var reference = [-1, -1, -1, -1, -1, -1]
    /// 每个面的旋转上次被确认时的步骤。
    private var referenceStep = [-1, -1, -1, -1, -1, -1]
    private var mismatches = 0

    init(_ start: String, _ moves: [String]) {
        self.moves = moves
        states = [String](repeating: "", count: moves.count + 1)
        states[0] = start
        for i in 0..<moves.count {
            states[i + 1] = CubeMoves.apply(states[i], moves[i])
        }
    }

    var currentIndex: Int { index }

    var size: Int { moves.count }

    var done: Bool { index >= moves.count }

    var currentMove: String { done ? "" : moves[index] }

    /// 当前步之前应有的魔方状态。
    var state: String { states[index] }

    var mismatchesCount: Int { mismatches }

    func next() { jump(index + 1) }

    func previous() { jump(index - 1) }

    func jump(_ to: Int) {
        index = max(0, min(moves.count, to))
        mismatches = 0
    }

    /// - Parameters:
    ///   - named: 一次稳定注视,贴纸已按扫描调色板命名
    ///   - rollStable: 面接近 45° 滚转时为 false,角点顺序(及读数旋转)可能在帧间翻转
    func observe(_ named: FaceSample?, _ rollStable: Bool) -> Outcome {
        guard let named = named, !done else { return .none }
        let face = named.center.face
        guard let slot = "URFDLB".firstIndex(of: face) else { return .none }
        let slotIndex = "URFDLB".distance(from: "URFDLB".startIndex, to: slot)
        let last = min(moves.count, index + MoveTracker.LOOKAHEAD)
        var masks = [Int](repeating: 0, count: last - index + 1)
        var firstFuture = -1
        for k in index...last {
            masks[k - index] = MoveTracker.matchMask(CubeMoves.face(states[k], face), named)
            if k > index && masks[k - index] != 0 && firstFuture < 0 { firstFuture = k }
        }
        let current = masks[0] != 0
        if !current && firstFuture < 0 {
            mismatches += 1
            return .mismatch
        }
        mismatches = 0
        if !current {
            // 只有更后面的状态能解释所见:那些步已经被做了。
            let mask = masks[firstFuture - index]
            index = firstFuture
            remember(slotIndex, mask, rollStable)
            return .advanced
        }
        if firstFuture < 0 {
            // 明确是当前状态;这里也是重新学习握姿的地方。
            remember(slotIndex, masks[0], rollStable)
            return .confirmed
        }
        // 内容无法区分这些状态——典型是被转的面,其贴纸只在旋转。本步中看到的旋转角
        // 决定;更老的角可能早于重新握持,看起来恰像 90° 转动。
        let ref = rollStable && referenceStep[slotIndex] == index ? reference[slotIndex] : -1
        if ref < 0 || (masks[0] & (1 << ref)) != 0 {
            if ref < 0 { remember(slotIndex, masks[0], rollStable) }
            return .confirmed
        }
        for k in (index + 1)...last {
            if (masks[k - index] & (1 << ref)) != 0 {
                index = k
                referenceStep[slotIndex] = index
                return .advanced
            }
        }
        return .none
    }

    /// 旋转角无歧义时记住面匹配的旋转角。
    private func remember(_ slot: Int, _ mask: Int, _ rollStable: Bool) {
        if rollStable && mask.nonzeroBitCount == 1 {
            reference[slot] = mask.trailingZeroBitCount
            referenceStep[slot] = index
        }
    }

    /// 顺时针旋转 r 次 observed 与 canonical 在至少 MIN_MATCHING 张可行贴纸上一致、
    /// 且无冲突时,bit r 置位。
    static func matchMask(_ canonical: String?, _ observed: FaceSample?) -> Int {
        guard let canonical = canonical, canonical.count == 9, let observed = observed else { return 0 }
        var mask = 0
        var rotated = observed
        let canon = Array(canonical)
        for turn in 0..<4 {
            var agreed = 0
            var conflict = false
            for i in 0..<9 where !conflict {
                let sticker = rotated.stickers[i]
                if sticker == .unknown { continue }
                if sticker.face == canon[i] { agreed += 1 } else { conflict = true }
            }
            if !conflict && agreed >= MoveTracker.MIN_MATCHING {
                mask |= 1 << turn
            }
            rotated = rotated.rotateClockwise()
        }
        return mask
    }

    /// 检测四边形是否离 45° 滚转足够远、角点顺序稳定:左上最远的角仅在对角线附近有歧义。
    static func rollStable(_ corners: [Float]) -> Bool {
        let angle = atan2(corners[3] - corners[1], corners[2] - corners[0]) * 180 / .pi
        return abs(angle) < 30
    }
}
