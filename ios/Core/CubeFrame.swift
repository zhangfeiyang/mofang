import Foundation

// Port of com.mofang.cubear.CubeFrame.java
// 魔方在镜头前的姿态:哪个面朝向观看者、哪个面朝上、哪个面朝右。
//
// 面以中心色命名(求解器的 URFDLB)。frame 保存指向观看者右方、上方与镜头方向的
// 魔方空间单位向量。一次稳定的注视即可完全确定:中心指出朝前的面,贴纸与期望状态
// 吻合的旋转指出上面是哪个邻居。

struct CubeFrame: Equatable {
    static let FACES = "URFDLB"
    static let X = [1, 0, 0]
    static let Y = [0, 1, 0]
    static let Z = [0, 0, 1]

    /// 指向观看者右方、上方与镜头方向的魔方空间单位向量。
    let right: [Int]
    let up: [Int]
    let front: [Int]

    private init(right: [Int], up: [Int], front: [Int]) {
        self.right = right
        self.up = up
        self.front = front
    }

    /// 绿面朝镜头、白面朝上:相机尚未看到魔方前的默认假设。
    static let `default` = CubeFrame.seen("F", 0)

    /// face 朝镜头、其读数顺时针旋转 rotation 次后与 facelet 串布局吻合的 frame。
    static func seen(_ face: Character, _ rotation: Int) -> CubeFrame {
        let frontVec = CubeFrame.normalOf(face)
        let layoutUp = CubeFrame.layoutUp(face)
        let layoutRight = CubeFrame.cross(layoutUp, frontVec)
        let up: [Int]
        switch rotation & 3 {
        case 1:
            // 需顺时针转一次才吻合的读数,拍摄时布局的左边缘在画面顶端:魔方顺时针滚了 90°。
            up = layoutRight
        case 2:
            up = CubeFrame.negate(layoutUp)
        case 3:
            up = CubeFrame.negate(layoutRight)
        default:
            up = layoutUp
        }
        return CubeFrame(right: CubeFrame.cross(up, frontVec), up: up, front: frontVec)
    }

    /// 一次稳定注视隐含的 frame;注视与任何旋转都不匹配时为 nil。
    ///
    /// 贴纸有重复的面会在多个旋转角匹配;接近 45° 滚转的读数角点可能错位一位;无论哪种,
    /// 与上一 frame 最接近的候选胜出——人们总是以 90° 为单位重新握持魔方。
    ///
    /// - Parameter mask: 这次注视对期望面的 matchMask
    static func fromLook(_ face: Character, _ mask: Int, _ rollStable: Bool, _ previous: CubeFrame?) -> CubeFrame? {
        guard CubeFrame.FACES.contains(face), (mask & 0xF) != 0 else { return nil }
        if let previous = previous, previous.frontFace() == face {
            // 同一面仍在前面:只有读数可信且旧滚转不再吻合时才相信新的滚转。
            if !rollStable || (mask & (1 << previous.rotation())) != 0 { return previous }
        }
        var best: CubeFrame?
        var bestAgreement = Int.min, bestUp = Int.min
        for rotation in 0..<4 {
            if (mask & (1 << rotation)) == 0 { continue }
            let candidate = CubeFrame.seen(face, rotation)
            if previous == nil { return candidate }
            let agreement = candidate.agreement(previous!)
            let keepsUp = CubeFrame.dot(candidate.up, previous!.up)
            if agreement > bestAgreement || (agreement == bestAgreement && keepsUp > bestUp) {
                best = candidate
                bestAgreement = agreement
                bestUp = keepsUp
            }
        }
        return best
    }

    /// 朝向镜头的面。
    func frontFace() -> Character { CubeFrame.faceOf(front) }

    /// 沿某视图空间轴方向所指的面。
    func faceAt(_ view: [Int]) -> Character { CubeFrame.faceOf(toCube(view)) }

    /// 魔方空间向量在视图空间的坐标。
    func toView(_ cube: [Int]) -> [Int] {
        [CubeFrame.dot(right, cube), CubeFrame.dot(up, cube), CubeFrame.dot(front, cube)]
    }

    /// 视图空间向量在魔方空间的坐标。
    func toCube(_ view: [Int]) -> [Int] {
        var out = [Int](repeating: 0, count: 3)
        for i in 0..<3 {
            out[i] = right[i] * view[0] + up[i] * view[1] + front[i] * view[2]
        }
        return out
    }

    /// 前面在此 frame 下的读数旋转位;seen 的逆。
    func rotation() -> Int {
        let face = frontFace()
        for rotation in 0..<4 where CubeFrame.seen(face, rotation).up == up {
            return rotation
        }
        preconditionFailure("not a rotation")
    }

    /// 中心本身转动了 quarters 个右手四分之一圈(绕视图空间 viewAxis)之后的 frame
    /// ——即双手保持外层不动时,中间层转动带来的效果。
    func turned(_ viewAxis: [Int], _ quarters: Int) -> CubeFrame {
        CubeFrame(
            right: toCube(CubeFrame.rotate(CubeFrame.X, viewAxis, -quarters)),
            up: toCube(CubeFrame.rotate(CubeFrame.Y, viewAxis, -quarters)),
            front: toCube(CubeFrame.rotate(CubeFrame.Z, viewAxis, -quarters)))
    }

    /// 24 种握持方式的稠密下标 0..23。
    var id: Int {
        let idx = CubeFrame.FACES.firstIndex(of: frontFace())!
        return CubeFrame.FACES.distance(from: CubeFrame.FACES.startIndex, to: idx) * 4 + rotation()
    }

    /// 两 frame 间旋转的迹:3 相同,1 相差 90°,-1 相差 180°。
    func agreement(_ other: CubeFrame) -> Int {
        CubeFrame.dot(right, other.right) + CubeFrame.dot(up, other.up) + CubeFrame.dot(front, other.front)
    }

    func toStringValue() -> String {
        "front \(frontFace()), up \(CubeFrame.faceOf(up)), right \(CubeFrame.faceOf(right))"
    }

    /// 面的外法向,CubeMoves 的坐标。
    static func normalOf(_ face: Character) -> [Int] {
        switch face {
        case "U": return [0, 1, 0]
        case "R": return [1, 0, 0]
        case "F": return [0, 0, 1]
        case "D": return [0, -1, 0]
        case "L": return [-1, 0, 0]
        case "B": return [0, 0, -1]
        default: preconditionFailure("no face \(face)")
        }
    }

    static func faceOf(_ v: [Int]) -> Character {
        if v[0] == 1 { return "R" }
        if v[0] == -1 { return "L" }
        if v[1] == 1 { return "U" }
        if v[1] == -1 { return "D" }
        if v[2] == 1 { return "F" }
        if v[2] == -1 { return "B" }
        preconditionFailure("\(v)")
    }

    /// facelet 串中某面 3×3 块第 0 行的方向(CubeMoves 的布局)。
    private static func layoutUp(_ face: Character) -> [Int] {
        switch face {
        case "U": return [0, 0, -1]
        case "D": return [0, 0, 1]
        default: return [0, 1, 0]
        }
    }

    /// v 绕单位 axis 转 quarters 个右手四分之一圈。
    static func rotate(_ v: [Int], _ axis: [Int], _ quarters: Int) -> [Int] {
        var out = v
        let turns = ((quarters % 4) + 4) % 4
        for _ in 0..<turns {
            let across = cross(axis, out)
            let along = dot(axis, out)
            for k in 0..<3 { out[k] = across[k] + axis[k] * along }
        }
        return out
    }

    static func dot(_ a: [Int], _ b: [Int]) -> Int { a[0] * b[0] + a[1] * b[1] + a[2] * b[2] }

    static func cross(_ a: [Int], _ b: [Int]) -> [Int] {
        [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
    }

    private static func negate(_ v: [Int]) -> [Int] { [-v[0], -v[1], -v[2]] }
}
