import Foundation

// Port of com.mofang.cubear.GuideSession.java
// 相机跟随下的引导还原:下一步是什么、魔方如何持握。
//
// 每条指令都相对 frame() 表述,frame 由与期望状态吻合的稳定注视学得,用户可以把任意
// 面朝向镜头。移动中心的步骤(中间层、前两层)也会移动 frame;frame 会随之传递,
// 确认一步的注视若发现握法不同会纠正它。

final class GuideSession {
    enum Result { case none, confirmed, advanced, mismatch }

    private let tracker: MoveTracker
    private var frame: CubeFrame
    private var frameKnown: Bool
    /// 每步开始时的 frame,供 previous() 恢复。
    private var frameAt: [Int: CubeFrame] = [:]
    private var lastMismatch: String?
    private(set) var settledMismatches = 0

    /// - Parameters:
    ///   - frame: 起始持法(已知程度见 frameKnown)
    ///   - frameKnown: frame 是否来自对此魔方的注视而非猜测
    init(_ start: String, _ moves: [String], _ frame: CubeFrame?, _ frameKnown: Bool) {
        tracker = MoveTracker(start, moves)
        self.frame = frame ?? CubeFrame.default
        self.frameKnown = frame != nil && frameKnown
    }

    var trackerValue: MoveTracker { tracker }

    var currentFrame: CubeFrame { frame }

    var isFrameKnown: Bool { frameKnown }

    var isDone: Bool { tracker.done }

    /// 连续多次注视匹配不到任何期望状态、且读数完全相同:魔方停在了意料之外的位置。
    /// 转动中途的注视也会 mismatch,但它们彼此不同,半圈加停顿已足以触发报警。
    var settledMismatchCount: Int { settledMismatches }

    /// 当前要显示的步骤;还原完成后为 nil。
    var currentStep: GuideStep? {
        let plan = planSteps(1)
        return plan.isEmpty ? nil : plan[0]
    }

    /// 当前步骤及其后至多 limit-1 步。
    func planSteps(_ limit: Int) -> [GuideStep] {
        GuideStep.plan(tracker.moves, tracker.states, tracker.currentIndex, frame, limit)
    }

    /// 对魔方进行一次稳定注视。
    ///
    /// - Parameters:
    ///   - named: 贴纸已按扫描调色板命名的注视
    ///   - upright: 接近 45° 滚转时为 false,读数旋转可能偏差一位
    func observe(_ named: FaceSample?, _ upright: Bool) -> Result {
        guard let named = named, !tracker.done else { return .none }
        let before = tracker.currentIndex
        // 先按指令所述的步骤判断:从前面看,前两层转半圈与背/前两层各转半圈完全相同,
        // 跟踪器的前瞻会把下一步也算进去。
        let step = currentStep
        if showsDone(step, named, upright) {
            tracker.jump(before + step!.count)
            follow(before, before + step!.count)
            _ = learn(named, tracker.state, upright)
            settle(nil)
            return .advanced
        }
        let outcome = tracker.observe(named, upright)
        if outcome == .mismatch {
            settle(named.signature)
        } else if outcome != .none {
            settle(nil)
        }
        if outcome == .advanced { follow(before, tracker.currentIndex) }
        if outcome == .advanced || outcome == .confirmed {
            // 跟踪器已判定魔方所处状态;注视说明持法。
            _ = learn(named, tracker.state, upright)
        }
        switch outcome {
        case .advanced: return .advanced
        case .confirmed: return .confirmed
        case .mismatch: return .mismatch
        case .none: return .none
        }
    }

    private func settle(_ mismatch: String?) {
        if let mismatch = mismatch {
            settledMismatches = mismatch == lastMismatch ? settledMismatches + 1 : 1
        } else {
            settledMismatches = 0
        }
        lastMismatch = mismatch
    }

    /// 用户说当前步已完成。
    func next() {
        settle(nil)
        let before = tracker.currentIndex
        let step = currentStep
        tracker.jump(before + (step == nil ? 1 : step!.count))
        follow(before, tracker.currentIndex)
    }

    /// 回到上一步开始的位置,并恢复当时的持法。
    func previous() {
        settle(nil)
        let before = tracker.currentIndex
        let earlier = frameAt.filter { $0.key < before }.max { $0.key < $1.key }
        let target = earlier == nil ? max(0, before - 1) : earlier!.key
        if let earlier = earlier { frame = earlier.value }
        frameAt = frameAt.filter { $0.key < target }
        tracker.jump(target)
    }

    /// 从一次稳定注视更新持法。
    ///
    /// - Returns: 注视与期望面的任何旋转都不符时为 false
    @discardableResult
    func learn(_ named: FaceSample, _ state: String, _ upright: Bool) -> Bool {
        guard let next = GuideSession.fromLook(named, state, upright, frameKnown ? frame : nil) else {
            return false
        }
        frame = next
        frameKnown = true
        return true
    }

    /// 对 state 状态魔方的一次稳定注视所隐含的 frame,不匹配时为 nil。
    static func fromLook(_ named: FaceSample?, _ state: String?, _ upright: Bool,
                         _ previous: CubeFrame?) -> CubeFrame? {
        guard let named = named, let state = state else { return nil }
        let face = named.center.face
        guard "URFDLB".contains(face) else { return nil }
        let mask = MoveTracker.matchMask(CubeMoves.face(state, face), named)
        return CubeFrame.fromLook(face, mask, upright, previous)
    }

    /// 注视是否显示 step 已完成,按持法判断:朝镜头的面在步骤预测的滚转角下读作结果状态,
    /// 且不再读作步骤之前的状态。前层原地转、或保持背面转前两层,都需要旧滚转做参照,
    /// 而跟踪器只在面读出单一旋转时才能从注视学到它——半圈对称的面永远不会。前两层
    /// 不改变贴纸:对跟踪器而言魔方只是换了个角度被拿着。持法两者皆知。
    private func showsDone(_ step: GuideStep?, _ named: FaceSample, _ upright: Bool) -> Bool {
        guard let step = step, step.seen, upright, frameKnown else { return false }
        let face = named.center.face
        if face != step.after.frontFace() { return false }
        let states = tracker.states
        let doneState = states[step.first + step.count]
        if (MoveTracker.matchMask(CubeMoves.face(doneState, face), named)
            & (1 << step.after.rotation())) == 0 {
            return false
        }
        if step.frame.frontFace() != face { return true }
        return (MoveTracker.matchMask(CubeMoves.face(states[step.first], face), named)
            & (1 << step.frame.rotation())) == 0
    }

    /// 把 frame 传递到求解步 from 与 to 之间完成的步骤:移动中心的转动会改变朝镜头的颜色。
    private func follow(_ from: Int, _ to: Int) {
        guard to > from else { return }
        for step in GuideStep.plan(tracker.moves, tracker.states, from, frame, to - from) {
            if step.first >= to { break }
            frameAt[step.first] = step.frame
            // 停在中间层对之内,意味着它的第一次外层转被单独做了。
            if step.first + step.count > to { break }
            frame = step.after
        }
    }
}
