import Foundation

// Port of the geometry half of com.mofang.cubear.GuideCube.java
// 循环演示当前引导步骤的 3D 小魔方,按用户手里的持法摆放。
//
// 27 个小块按 54 色块状态着色,再由步骤的 CubeFrame 摆进视图空间:镜头看到的面在前,
// 它上方的面在上。观察点在前面偏右上——人拿着魔方时眼睛所在的位置——所以前、顶、
// 右三面可见,步骤能转的每一层都至少穿过其中一面。转动层按与 CubeMoves 相同的约定在
// 3D 中旋转。这里只有数学;绘制(阴影、箭头、说明条)在 App 层的 GuideCubeView。

final class GuideCubeModel {
    static let CYCLE_MS: Double = 2000
    static let CUBIE: Float = 0.96
    static let STICKER: Float = 0.78
    static let PLASTIC: UInt32 = 0xFF14_1414
    static let PLASTIC_TURN: UInt32 = 0xFF2A_2A2A
    static let MINT: UInt32 = 0xFF74_F5C5
    static let YAW: Float = -0.55
    static let PITCH: Float = 0.46
    /// 箭头离色块平面的高度,以中心为原点、小块为单位。
    static let LIFT: Float = 1.62
    static let MAX_QUADS = 256

    /// 每个四边形 4 个角 × xyz,已转到观察相机空间(x 向右,y 向上,z 朝观察者)。
    private(set) var corners = [Float](repeating: 0, count: MAX_QUADS * 12)
    private(set) var colors = [UInt32](repeating: 0, count: MAX_QUADS)
    private(set) var depth = [Float](repeating: 0, count: MAX_QUADS)
    private(set) var turning = [Bool](repeating: false, count: MAX_QUADS)
    private(set) var isPlastic = [Bool](repeating: false, count: MAX_QUADS)
    private(set) var order = [Int](repeating: 0, count: MAX_QUADS)
    private var frame = CubeFrame.default
    /// 27 个位置 × 6 个方向的色块 ARGB(没有色块为 0),每帧由状态串重建一次。
    private var cubieFaces = [UInt32](repeating: 0, count: 27 * 6)
    /// 每个色块下标的位置与法向,见 stickerPos。
    private static let STICKER_POS: [[Int]] = (0..<54).map { stickerPos($0) }

    /// 转动层的缓动角度:先静止,再转动,最后停在结果上。
    static func twistAngle(_ phase: Float, _ quarters: Int) -> Float {
        if phase <= 0.14 { return 0 }
        // 带符号:逆时针一步走四分之一圈的近路,而不是顺时针转四分之三。
        let target = Float.pi / 2 * Float(quarters)
        if phase >= 0.78 { return target }
        let t = (phase - 0.14) / 0.64
        let ease = t * t * (3 - 2 * t)
        return target * ease
    }

    /// 为动画的一帧填充四边形缓冲,并按深度排好 order(远的在前)。
    ///
    /// - Parameters:
    ///   - axis: 转动层在魔方空间的轴
    ///   - layer: 转动哪些层,同 GuideStep.layer
    ///   - twist: 该层的右手旋转角(弧度)
    /// - Returns: 生成的四边形数
    @discardableResult
    func collectFaces(_ state: String, _ held: CubeFrame, _ axis: [Int], _ layer: Int, _ twist: Float) -> Int {
        frame = held
        paint(state.count == 54 ? state : GuideCubeModel.solved())
        let turnAxis = [Float(axis[0]), Float(axis[1]), Float(axis[2])]
        var n = 0
        for x in -1...1 {
            for y in -1...1 {
                for z in -1...1 {
                    let along = x * axis[0] + y * axis[1] + z * axis[2]
                    let moving = GuideStep.turns(layer, along)
                    let core = x == 0 && y == 0 && z == 0
                    // emitCubie 返回下一个空位,不是数量。
                    if !core {
                        n = emitCubie(n, x, y, z, moving ? twist : 0, moving ? turnAxis : nil)
                    }
                    // 中心块没有色块,但它的侧面能把切口的中间补上。
                    if twist != 0 {
                        n = emitCut(n, x, y, z, axis, along, layer, moving ? twist : 0,
                                    moving ? turnAxis : nil)
                    }
                }
            }
        }
        sortByDepth(n)
        return n
    }

    /// 第 quad 个四边形的第 corner 个角,相机空间 xyz。
    func corner(_ quad: Int, _ corner: Int) -> (Float, Float, Float) {
        let base = quad * 12 + corner * 3
        return (corners[base], corners[base + 1], corners[base + 2])
    }

    private func emitCubie(_ start: Int, _ x: Int, _ y: Int, _ z: Int, _ twist: Float, _ axis: [Float]?) -> Int {
        let at = GuideCubeModel.slot(x, y, z) * 6
        var n = start
        n = emitShellAndSticker(n, x, y, z, 1, 0, 0, cubieFaces[at], twist, axis)
        n = emitShellAndSticker(n, x, y, z, -1, 0, 0, cubieFaces[at + 1], twist, axis)
        n = emitShellAndSticker(n, x, y, z, 0, 1, 0, cubieFaces[at + 2], twist, axis)
        n = emitShellAndSticker(n, x, y, z, 0, -1, 0, cubieFaces[at + 3], twist, axis)
        n = emitShellAndSticker(n, x, y, z, 0, 0, 1, cubieFaces[at + 4], twist, axis)
        n = emitShellAndSticker(n, x, y, z, 0, 0, -1, cubieFaces[at + 5], twist, axis)
        return n
    }

    /// 转动层与相邻层相接处的塑料。静止的魔方里这些面是藏着的;层一转就露出来,
    /// 不画的话魔方看起来是空心的——中间层转动时最明显。
    private func emitCut(_ start: Int, _ x: Int, _ y: Int, _ z: Int, _ axis: [Int], _ along: Int,
                         _ layer: Int, _ twist: Float, _ turnAxis: [Float]?) -> Int {
        var n = start
        for side in [-1, 1] {
            let neighbour = along + side
            let faces = abs(neighbour) <= 1
                && GuideStep.turns(layer, along) != GuideStep.turns(layer, neighbour)
            if faces {
                n = emitFace(n, x, y, z, axis[0] * side, axis[1] * side, axis[2] * side, 0, twist, turnAxis)
            }
        }
        return n
    }

    private func emitShellAndSticker(_ start: Int, _ x: Int, _ y: Int, _ z: Int,
                                     _ nx: Int, _ ny: Int, _ nz: Int, _ color: UInt32,
                                     _ twist: Float, _ axis: [Float]?) -> Int {
        if x * nx + y * ny + z * nz <= 0 { return start }
        var n = emitFace(start, x, y, z, nx, ny, nz, 0, twist, axis)
        if color != 0 { n = emitFace(n, x, y, z, nx, ny, nz, color, twist, axis) }
        return n
    }

    private func emitFace(_ n: Int, _ cx: Int, _ cy: Int, _ cz: Int, _ nx: Int, _ ny: Int, _ nz: Int,
                          _ color: UInt32, _ twist: Float, _ axis: [Float]?) -> Int {
        if n >= GuideCubeModel.MAX_QUADS { return n }
        // 背向观察者的面跳过:先画再被覆盖,仍会从小块间的缝里漏出杂色线。
        let normal = place(Float(nx), Float(ny), Float(nz), twist, axis)
        if normal.2 <= 0.04 { return n }
        let plastic = color == 0
        let base = plastic ? (axis != nil ? GuideCubeModel.PLASTIC_TURN : GuideCubeModel.PLASTIC) : color
        // 来自左上方的柔光,让可见的三面读起来是实体。
        let light = normal.0 * -0.38 + normal.1 * 0.74 + normal.2 * 0.56
        let paint = GuideCubeModel.shade(base, 0.74 + 0.26 * max(0, light))
        let half = GuideCubeModel.CUBIE * 0.5
        let sticker = (plastic ? GuideCubeModel.CUBIE : GuideCubeModel.STICKER) * 0.5
        let ox = Float(nx) * half, oy = Float(ny) * half, oz = Float(nz) * half
        let ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float
        if nx != 0 { (ux, uy, uz, vx, vy, vz) = (0, sticker, 0, 0, 0, sticker) }
        else if ny != 0 { (ux, uy, uz, vx, vy, vz) = (sticker, 0, 0, 0, 0, sticker) }
        else { (ux, uy, uz, vx, vy, vz) = (sticker, 0, 0, 0, sticker, 0) }
        let fx = Float(cx) + ox, fy = Float(cy) + oy, fz = Float(cz) + oz
        var dz: Float = 0
        dz += store(n, 0, place(fx - ux - vx, fy - uy - vy, fz - uz - vz, twist, axis))
        dz += store(n, 1, place(fx + ux - vx, fy + uy - vy, fz + uz - vz, twist, axis))
        dz += store(n, 2, place(fx + ux + vx, fy + uy + vy, fz + uz + vz, twist, axis))
        dz += store(n, 3, place(fx - ux + vx, fy - uy + vy, fz - uz + vz, twist, axis))
        // 色块与其下的塑料壳共面;不加偏置,舍入决定谁最后画,壳会把色块盖住。
        depth[n] = dz / 4 + (plastic ? 0 : 0.01)
        colors[n] = paint
        isPlastic[n] = plastic
        turning[n] = axis != nil && !plastic
        return n + 1
    }

    /// 写入一个角,返回它的深度。
    private func store(_ quad: Int, _ corner: Int, _ p: (Float, Float, Float)) -> Float {
        let base = quad * 12 + corner * 3
        corners[base] = p.0
        corners[base + 1] = p.1
        corners[base + 2] = p.2
        return p.2
    }

    static func shade(_ argb: UInt32, _ k: Float) -> UInt32 {
        let r = min(255, UInt32((Float((argb >> 16) & 0xFF) * k).rounded()))
        let g = min(255, UInt32((Float((argb >> 8) & 0xFF) * k).rounded()))
        let b = min(255, UInt32((Float(argb & 0xFF) * k).rounded()))
        return (argb & 0xFF00_0000) | (r << 16) | (g << 8) | b
    }

    /// 魔方空间 → 转动层 → 持握者视图 → 略高、偏右的观察相机。
    private func place(_ x: Float, _ y: Float, _ z: Float, _ twist: Float, _ axis: [Float]?) -> (Float, Float, Float) {
        var p = (x, y, z)
        if let axis = axis, twist != 0 { p = GuideCubeModel.rodrigues(p, axis, twist) }
        let r = frame.right, u = frame.up, f = frame.front
        let view = (Float(r[0]) * p.0 + Float(r[1]) * p.1 + Float(r[2]) * p.2,
                    Float(u[0]) * p.0 + Float(u[1]) * p.1 + Float(u[2]) * p.2,
                    Float(f[0]) * p.0 + Float(f[1]) * p.1 + Float(f[2]) * p.2)
        return GuideCubeModel.camera(view)
    }

    /// 视图空间的点经同一台略高、偏右的相机:x 向右、y 向上,z 朝观察者。
    static func camera(_ v: (Float, Float, Float)) -> (Float, Float, Float) {
        rotateX(rotateY(v, YAW), PITCH)
    }

    private func sortByDepth(_ count: Int) {
        for i in 0..<count { order[i] = i }
        if count < 2 { return }
        for i in 1..<count {
            let key = order[i]
            let d = depth[key]
            var j = i - 1
            while j >= 0 && depth[order[j]] > d {
                order[j + 1] = order[j]
                j -= 1
            }
            order[j + 1] = key
        }
    }

    /// 按状态串给 27 个位置的六个方向上色。
    private func paint(_ state: String) {
        for i in 0..<cubieFaces.count { cubieFaces[i] = 0 }
        for (index, letter) in state.enumerated() where index < 54 {
            let pn = GuideCubeModel.STICKER_POS[index]
            let face = GuideCubeModel.faceSlot(pn[3], pn[4], pn[5])
            if face >= 0 {
                cubieFaces[GuideCubeModel.slot(pn[0], pn[1], pn[2]) * 6 + face] = CubeColor.fromFace(letter).argb
            }
        }
    }

    private static func slot(_ x: Int, _ y: Int, _ z: Int) -> Int { (x + 1) * 9 + (y + 1) * 3 + (z + 1) }

    /// 位于 (x,y,z) 的小块六个方向(+x,-x,+y,-y,+z,-z)上的色块 ARGB,没有色块为 0。
    static func cubieColors(_ state: String, _ x: Int, _ y: Int, _ z: Int) -> [UInt32] {
        let model = GuideCubeModel()
        model.paint(state)
        let at = slot(x, y, z) * 6
        return Array(model.cubieFaces[at..<at + 6])
    }

    /// 色块下标对应的小块位置 xyz 与法向 xyz,与 CubeMoves 一致。
    static func stickerPos(_ index: Int) -> [Int] {
        let face = index / 9, local = index % 9, row = local / 3, col = local % 3
        switch face {
        case 0: return [col - 1, 1, row - 1, 0, 1, 0]
        case 1: return [1, 1 - row, 1 - col, 1, 0, 0]
        case 2: return [col - 1, 1 - row, 1, 0, 0, 1]
        case 3: return [col - 1, -1, 1 - row, 0, -1, 0]
        case 4: return [-1, 1 - row, col - 1, -1, 0, 0]
        default: return [1 - col, 1 - row, -1, 0, 0, -1]
        }
    }

    private static func faceSlot(_ nx: Int, _ ny: Int, _ nz: Int) -> Int {
        if nx == 1 { return 0 }
        if nx == -1 { return 1 }
        if ny == 1 { return 2 }
        if ny == -1 { return 3 }
        if nz == 1 { return 4 }
        if nz == -1 { return 5 }
        return -1
    }

    private static func rodrigues(_ v: (Float, Float, Float), _ u: [Float], _ a: Float) -> (Float, Float, Float) {
        let c = cosf(a), s = sinf(a)
        let dot = v.0 * u[0] + v.1 * u[1] + v.2 * u[2]
        let cx = u[1] * v.2 - u[2] * v.1
        let cy = u[2] * v.0 - u[0] * v.2
        let cz = u[0] * v.1 - u[1] * v.0
        return (v.0 * c + cx * s + u[0] * dot * (1 - c),
                v.1 * c + cy * s + u[1] * dot * (1 - c),
                v.2 * c + cz * s + u[2] * dot * (1 - c))
    }

    static func rotateY(_ v: (Float, Float, Float), _ a: Float) -> (Float, Float, Float) {
        let c = cosf(a), s = sinf(a)
        return (v.0 * c + v.2 * s, v.1, -v.0 * s + v.2 * c)
    }

    static func rotateX(_ v: (Float, Float, Float), _ a: Float) -> (Float, Float, Float) {
        let c = cosf(a), s = sinf(a)
        return (v.0, v.1 * c - v.2 * s, v.1 * s + v.2 * c)
    }

    static func solved() -> String {
        "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB"
    }
}
