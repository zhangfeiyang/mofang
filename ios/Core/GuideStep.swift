import Foundation

// Port of com.mofang.cubear.GuideStep.java
// 用户执行时看到的一条指令:以魔方在手中的位置命名层("顶层向左拧"),而非中心色。
//
// 求解器说的是中心色——"拧绿面"——用户只能找绿面并转向镜头。给定相机最后看到的
// CubeFrame,每步改为命名所持魔方的顶/底/左/右/前/后层。另考虑两种等价做法:
// - 外层对(X L′)作为中间层的一转——不选前后之间的那层(难握且镜头看不见);
// - 背面层转为"保持背面、转前两层":状态相同,朝镜头的面会转,相机可以确认该步。
// 两者都会带着中心走(改变 after),进而改变后续每步的做法。朝镜头面看起来不变的
// 步会与下一步连着显示(then),让这对步骤自我确认。

final class GuideStep {
    static let FACES = "URFDLB"
    static let VIEW_RIGHT = [1, 0, 0]
    static let VIEW_UP = [0, 1, 0]
    static let VIEW_FRONT = [0, 0, 1]

    /// layer:中间层。
    static let MIDDLE = 0
    /// layer:+轴一侧的外层。
    static let OUTER = 1
    /// layer:+轴一侧的两层(除对侧外层以外的全部)。
    static let WIDE = 2

    /// 相机自身可确认的步骤代价。
    private static let STEP: Float = 1
    /// 相机看不见的步骤加价:用户要按"下一步"。
    private static let UNSEEN: Float = 1
    /// 非单外层握法的加价(新手觉得难):半步。
    private static let GRIP: Float = 0.5
    /// 两步并作一条指令的加价:高于非常规握法,低于一次"下一步"。
    private static let CHAIN: Float = 0.6

    /// 本步覆盖的第一个求解步下标。
    let first: Int
    /// 覆盖的求解步数。
    let count: Int
    /// 步前与步后魔方的持法。
    let frame: CubeFrame
    let after: CubeFrame
    /// 转动外层的中心色(URFDLB);转动的块无单一颜色时为 " "。
    let face: Character
    /// 转动所绕的魔方空间轴。
    let axis: [Int]
    /// OUTER / MIDDLE / WIDE。
    let layer: Int
    /// 绕 axis 的右手四分之一圈数:-1、+1 或 ±2。
    let quarters: Int
    /// 朝镜头的面 afterwards 是否不同(步骤自确认)。
    let seen: Bool
    /// 紧接着做的、自身不改变相机可见状态的动作;单独一步为 nil。
    let then: GuideStep?

    private init(first: Int, count: Int, frame: CubeFrame, face: Character, axis: [Int],
                 layer: Int, quarters: Int, seen: Bool?) {
        self.first = first
        self.count = count
        self.frame = frame
        self.face = face
        self.axis = axis
        self.layer = layer
        self.quarters = quarters
        let resolvedSeen = seen ?? ({
            let va = frame.toView(axis)
            return va[2] == 0 || (layer != GuideStep.MIDDLE && va[2] == 1)
        })()
        self.seen = resolvedSeen
        self.after = layer == GuideStep.OUTER ? frame : frame.turned(frame.toView(axis), quarters)
        self.then = nil
    }

    /// part(相机不可见)后接 then。
    private init(part: GuideStep, then: GuideStep, seen: Bool) {
        first = part.first
        count = part.count + then.count
        frame = part.frame
        face = part.face
        axis = part.axis
        layer = part.layer
        quarters = part.quarters
        after = then.after
        self.seen = seen
        self.then = then
    }

    /// 这一步单独做:链式步的第一段,或步本身。
    func alone() -> GuideStep {
        if then == nil { return self }
        return GuideStep(first: first, count: count - then!.count, frame: frame, face: face,
                         axis: axis, layer: layer, quarters: quarters, seen: false)
    }

    private func seenAs(_ visible: Bool) -> GuideStep {
        GuideStep(first: first, count: count, frame: frame, face: face, axis: axis,
                  layer: layer, quarters: quarters, seen: visible)
    }

    /// 从求解步 from 开始、至多 limit 步,以 frame 持握时的呈现。
    static func plan(_ moves: [String], _ states: [String]?, _ from: Int, _ frame: CubeFrame,
                     _ limit: Int) -> [GuideStep] {
        let planner = Planner(moves, states)
        var steps: [GuideStep] = []
        var held = frame
        var index = max(0, from)
        while index < moves.count && steps.count < limit {
            let step = planner.best(index, held)
            steps.append(step)
            held = step.after
            index += step.count
        }
        return steps
    }

    /// 这一步对用户的负担,用于在等价方案间选择。
    func effort() -> Float {
        var hands = GuideStep.STEP + (layer == GuideStep.OUTER ? 0 : GuideStep.GRIP)
        if then != nil {
            hands += GuideStep.CHAIN + GuideStep.STEP + (then!.layer == GuideStep.OUTER ? 0 : GuideStep.GRIP)
        }
        return hands + (seen ? 0 : GuideStep.UNSEEN)
    }

    /// 以(步下标, 持法)为键的动态规划,走完剩余步骤的最小代价:24 种持法 × 二十几步。
    private final class Planner {
        let moves: [String]
        let states: [String]?
        var memo: [Float]

        init(_ moves: [String], _ states: [String]?) {
            self.moves = moves
            self.states = (states != nil && states!.count > moves.count) ? states : nil
            memo = [Float](repeating: -1, count: (moves.count + 1) * 24)
        }

        func best(_ index: Int, _ held: CubeFrame) -> GuideStep {
            var best: GuideStep?
            var least: Float = 0
            // 平局保留最早的选项,普通外层转排第一。
            for option in options(index, held) {
                let total = option.effort() + cost(index + option.count, option.after)
                if best == nil || total < least {
                    least = total
                    best = option
                }
            }
            return best!
        }

        /// 从 index 开始的所有做法:单转,再把不可见的接上。
        private func options(_ index: Int, _ held: CubeFrame) -> [GuideStep] {
            let singleSteps = singles(index, held)
            var all = singleSteps
            for single in singleSteps {
                let next = index + single.count
                if single.seen || next >= moves.count { continue }
                for follow in singles(next, single.after) {
                    all.append(chained(single, follow))
                }
            }
            return all
        }

        private func singles(_ index: Int, _ held: CubeFrame) -> [GuideStep] {
            var out: [GuideStep] = []
            let outer = judged(outerStep(moves[index], index, held))
            out.append(outer)
            if index + 1 < moves.count && GuideStep.pairsIntoMiddle(moves[index], moves[index + 1]) {
                let middle = middleStep(moves[index], index, held)
                if middle.viewAxis()[2] == 0 { out.append(judged(middle)) }
            }
            if outer.isBack { out.append(judged(wideStep(moves[index], index, held))) }
            return out
        }

        private func chained(_ part: GuideStep, _ then: GuideStep) -> GuideStep {
            let seen: Bool
            if states == nil {
                seen = then.seen
            } else {
                seen = GuideStep.reading(states![part.first], part.frame)
                    != GuideStep.reading(states![part.first + part.count + then.count], then.after)
            }
            return GuideStep(part: part, then: then, seen: seen)
        }

        private func cost(_ index: Int, _ held: CubeFrame) -> Float {
            if index >= moves.count { return 0 }
            let key = index * 24 + held.id
            if memo[key] >= 0 { return memo[key] }
            let step = best(index, held)
            let total = step.effort() + cost(index + step.count, step.after)
            memo[key] = total
            return total
        }

        /// 告诉步骤相机能否看见它发生:前后的前面图像是否不同。
        private func judged(_ step: GuideStep) -> GuideStep {
            guard let states = states else { return step }
            let before = GuideStep.reading(states[step.first], step.frame)
            let after = GuideStep.reading(states[step.first + step.count], step.after)
            return step.seenAs(before != after)
        }
    }

    /// 相机读到的前面,从画面左上起行主序:seen 的逆。
    static func reading(_ state: String, _ held: CubeFrame) -> String {
        let layout = Array(CubeMoves.face(state, held.frontFace()))
        var picture = [Character](repeating: " ", count: 9)
        let turns = held.rotation()
        for i in 0..<9 {
            var row = i / 3, col = i % 3
            for _ in 0..<turns {
                let nextRow = col, nextCol = 2 - row
                row = nextRow
                col = nextCol
            }
            picture[i] = layout[row * 3 + col]
        }
        return String(picture)
    }

    /// 单个求解步。
    static func outerStep(_ move: String, _ index: Int, _ frame: CubeFrame) -> GuideStep {
        let face = Array(move)[0]
        let quarters = GuideStep.amount(move) == 1 ? -1 : GuideStep.amount(move) == 3 ? 1 : -2
        return GuideStep(first: index, count: 1, frame: frame, face: face,
                         axis: CubeFrame.normalOf(face), layer: OUTER, quarters: quarters, seen: nil)
    }

    /// 对面外层对 X^a Y^-a,作为反向的中间层转动。
    static func middleStep(_ move: String, _ index: Int, _ frame: CubeFrame) -> GuideStep {
        let face = Array(move)[0]
        let quarters = GuideStep.amount(move) == 1 ? 1 : GuideStep.amount(move) == 3 ? -1 : 2
        return GuideStep(first: index, count: 2, frame: frame, face: " ",
                         axis: CubeFrame.normalOf(face), layer: MIDDLE, quarters: quarters, seen: nil)
    }

    /// 保持本层不动、转动另外两层:状态相同。
    static func wideStep(_ move: String, _ index: Int, _ frame: CubeFrame) -> GuideStep {
        let face = Array(move)[0]
        let normal = CubeFrame.normalOf(face)
        let quarters = GuideStep.amount(move) == 1 ? -1 : GuideStep.amount(move) == 3 ? 1 : -2
        let opposite = [-normal[0], -normal[1], -normal[2]]
        return GuideStep(first: index, count: 1, frame: frame, face: " ",
                         axis: opposite, layer: WIDE, quarters: quarters, seen: nil)
    }

    /// 两个转动是否为相反层、同方向、同角度。
    static func pairsIntoMiddle(_ a: String?, _ b: String?) -> Bool {
        guard let a = a, let b = b, !a.isEmpty, !b.isEmpty else { return false }
        let ac = Array(a), bc = Array(b)
        guard let fa = FACES.firstIndex(of: ac[0]),
              let fb = FACES.firstIndex(of: bc[0]) else { return false }
        let faIdx = FACES.distance(from: FACES.startIndex, to: fa)
        let fbIdx = FACES.distance(from: FACES.startIndex, to: fb)
        if (faIdx + 3) % 6 != fbIdx { return false }
        return (amount(a) + amount(b)) % 4 == 0
    }

    /// 求解步的顺时针四分之一圈数:1、2 或 3。
    static func amount(_ move: String) -> Int {
        move.hasSuffix("2") ? 2 : move.hasSuffix("'") ? 3 : 1
    }

    /// 轴坐标 along 处的块是否属于转动块。
    static func turns(_ layer: Int, _ along: Int) -> Bool {
        layer == GuideStep.WIDE ? along >= 0 : along == layer
    }

    /// 视图空间中的转动轴。
    func viewAxis() -> [Int] { frame.toView(axis) }

    var isMiddle: Bool { layer == GuideStep.MIDDLE }

    var isWide: Bool { layer == GuideStep.WIDE }

    var isHalfTurn: Bool { abs(quarters) == 2 }

    /// 背对观看者的外层。
    var isBack: Bool { layer == GuideStep.OUTER && viewAxis()[2] == -1 }

    /// 朝向观看者的外层。
    var isFront: Bool { layer == GuideStep.OUTER && viewAxis()[2] == 1 }

    /// 朝镜头的整面原地转:前层或前两层。
    func turnsFrontFace() -> Bool { layer != GuideStep.MIDDLE && viewAxis()[2] == 1 }

    /// 该层在视图法向 faceNormal 的面上贴纸移动的视图方向。
    func motion(_ faceNormal: [Int]) -> [Int] {
        let across = CubeFrame.cross(viewAxis(), faceNormal)
        let sign: Int = quarters > 0 ? 1 : -1
        return [across[0] * sign, across[1] * sign, across[2] * sign]
    }

    /// 持握视角下的标准记号。
    func notation() -> String {
        then == nil ? ownNotation() : ownNotation() + " " + then!.notation()
    }

    private func ownNotation() -> String {
        let n = viewAxis()
        let letter: String
        let clockwise: Int
        if layer != GuideStep.MIDDLE {
            letter = (n[0] == 1 ? "R" : n[0] == -1 ? "L" : n[1] == 1 ? "U"
                : n[1] == -1 ? "D" : n[2] == 1 ? "F" : "B") + (layer == GuideStep.WIDE ? "w" : "")
            clockwise = ((-quarters % 4) + 4) % 4
        } else if n[0] != 0 {
            // M 像 L:绕 +x 的正转。
            letter = "M"
            clockwise = ((n[0] * quarters % 4) + 4) % 4
        } else if n[1] != 0 {
            // E 像 D:绕 +y 的正转。
            letter = "E"
            clockwise = ((n[1] * quarters % 4) + 4) % 4
        } else {
            // S 像 F:绕 +z 的负转。
            letter = "S"
            clockwise = ((-n[2] * quarters % 4) + 4) % 4
        }
        return clockwise == 2 ? letter + "2" : clockwise == 3 ? letter + "′" : letter
    }

    /// 手中的层位描述。
    func place() -> String {
        let n = viewAxis()
        if layer == GuideStep.MIDDLE {
            return n[0] != 0 ? "中间竖层" : n[1] != 0 ? "中间横层" : "中间夹层"
        }
        let side = n[0] != 0 ? (n[0] > 0 ? "右" : "左") : n[1] != 0 ? (n[1] > 0 ? "顶" : "底")
            : n[2] > 0 ? "前" : "后"
        if layer == GuideStep.WIDE {
            return (side == "顶" ? "上" : side == "底" ? "下" : side) + "两层"
        }
        return side + "层"
    }

    /// 方向描述。
    func direction() -> String {
        if isHalfTurn { return "半圈" }
        if turnsFrontFace() { return quarters < 0 ? "顺时针" : "逆时针" }
        let n = viewAxis()
        let moving = motion(n[2] == 0 ? GuideStep.VIEW_FRONT : GuideStep.VIEW_UP)
        if moving[0] != 0 { return moving[0] > 0 ? "向右" : "向左" }
        return moving[1] > 0 ? "向上" : "向下"
    }

    /// 完整指令:"顶层向左拧"、"前两层顺时针拧"等,链式用"再"连接。
    func caption() -> String {
        let own = place() + (isHalfTurn ? "拧半圈" : direction() + "拧")
        return then == nil ? own : own + ",再" + then!.caption()
    }
}
