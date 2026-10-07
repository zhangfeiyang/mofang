import Foundation

// Port of cs.min2phase.CubieCube.java
// Java 的 byte 数组在此用 Int 数组表示(所有取值 0..31,均为非负,Int8 不再必要)。

final class CubieCube {
    /// S_F2、S_U4、S_LR2 生成的 16 个对称。
    static var CubeSym = [CubieCube?](repeating: nil, count: 16)
    /// 18 个转动魔方。
    static var moveCube = [CubieCube?](repeating: nil, count: 18)

    static var moveCubeSym = [UInt64](repeating: 0, count: 18)
    static var firstMoveSym = [Int](repeating: 0, count: 48)

    static var SymMult = [[Int]](repeating: [Int](repeating: 0, count: 16), count: 16)
    static var SymMultInv = [[Int]](repeating: [Int](repeating: 0, count: 16), count: 16)
    static var SymMove = [[Int]](repeating: [Int](repeating: 0, count: 18), count: 16)
    static var Sym8Move = [Int](repeating: 0, count: 8 * 18)
    static var SymMoveUD = [[Int]](repeating: [Int](repeating: 0, count: 18), count: 16)

    /// ClassIndexToRepresentantArrays
    static var FlipS2R = [Int](repeating: 0, count: CoordCube.N_FLIP_SYM)
    static var TwistS2R = [Int](repeating: 0, count: CoordCube.N_TWIST_SYM)
    static var EPermS2R = [Int](repeating: 0, count: CoordCube.N_PERM_SYM)
    static var Perm2CombP = [Int](repeating: 0, count: CoordCube.N_PERM_SYM)
    static var PermInvEdgeSym = [Int](repeating: 0, count: CoordCube.N_PERM_SYM)
    static var MPermInv = [Int](repeating: 0, count: CoordCube.N_MPERM)

    static let SYM_E2C_MAGIC = 0x00DDDD00
    static func ESym2CSym(_ idx: Int) -> Int {
        idx ^ (SYM_E2C_MAGIC >> ((idx & 0xf) << 1) & 3)
    }

    static var FlipR2S = [Int](repeating: 0, count: CoordCube.N_FLIP)
    static var TwistR2S = [Int](repeating: 0, count: CoordCube.N_TWIST)
    static var EPermR2S = [Int](repeating: 0, count: CoordCube.N_PERM)
    static var FlipS2RF = [Int](repeating: 0, count: CoordCube.N_FLIP_SYM * 8)

    static var SymStateTwist = [Int](repeating: 0, count: CoordCube.N_TWIST_SYM)
    static var SymStateFlip = [Int](repeating: 0, count: CoordCube.N_FLIP_SYM)
    static var SymStatePerm = [Int](repeating: 0, count: CoordCube.N_PERM_SYM)

    static let urf1 = CubieCube(2531, 1373, 67026819, 1367)
    static let urf2 = CubieCube(2089, 1906, 322752913, 2040)
    static let urfMove: [[Int]] = [
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
        [6, 7, 8, 0, 1, 2, 3, 4, 5, 15, 16, 17, 9, 10, 11, 12, 13, 14],
        [3, 4, 5, 6, 7, 8, 0, 1, 2, 12, 13, 14, 15, 16, 17, 9, 10, 11],
        [2, 1, 0, 5, 4, 3, 8, 7, 6, 11, 10, 9, 14, 13, 12, 17, 16, 15],
        [8, 7, 6, 2, 1, 0, 5, 4, 3, 17, 16, 15, 11, 10, 9, 14, 13, 12],
        [5, 4, 3, 8, 7, 6, 2, 1, 0, 14, 13, 12, 17, 16, 15, 11, 10, 9],
    ]

    var ca = [0, 1, 2, 3, 4, 5, 6, 7]
    var ea = [0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22]
    var temps: CubieCube?

    init() {}

    init(_ cperm: Int, _ twist: Int, _ eperm: Int, _ flip: Int) {
        setCPerm(cperm)
        setTwist(twist)
        MUtil.setNPerm(&ea, eperm, 12, true)
        setFlip(flip)
    }

    init(_ c: CubieCube) {
        copy(from: c)
    }

    func copy(from c: CubieCube) {
        for i in 0..<8 { ca[i] = c.ca[i] }
        for i in 0..<12 { ea[i] = c.ea[i] }
    }

    func invCubieCube() {
        if temps == nil { temps = CubieCube() }
        let t = temps!
        for edge in 0..<12 {
            t.ea[ea[edge] >> 1] = edge << 1 | ea[edge] & 1
        }
        for corn in 0..<8 {
            t.ca[ca[corn] & 7] = corn | (0x20 >> (ca[corn] >> 3)) & 0x18
        }
        copy(from: t)
    }

    /// prod = a * b,仅角块。
    static func CornMult(_ a: CubieCube, _ b: CubieCube, _ prod: CubieCube) {
        for corn in 0..<8 {
            let oriA = a.ca[b.ca[corn] & 7] >> 3
            let oriB = b.ca[corn] >> 3
            prod.ca[corn] = a.ca[b.ca[corn] & 7] & 7 | ((oriA + oriB) % 3) << 3
        }
    }

    /// prod = a * b,仅角块,考虑镜像。
    static func CornMultFull(_ a: CubieCube, _ b: CubieCube, _ prod: CubieCube) {
        for corn in 0..<8 {
            let oriA = a.ca[b.ca[corn] & 7] >> 3
            let oriB = b.ca[corn] >> 3
            var ori = oriA + (oriA < 3 ? oriB : 6 - oriB)
            ori = ori % 3 + ((oriA < 3) == (oriB < 3) ? 0 : 3)
            prod.ca[corn] = a.ca[b.ca[corn] & 7] & 7 | ori << 3
        }
    }

    /// prod = a * b,仅棱块。
    static func EdgeMult(_ a: CubieCube, _ b: CubieCube, _ prod: CubieCube) {
        for ed in 0..<12 {
            prod.ea[ed] = a.ea[b.ea[ed] >> 1] ^ (b.ea[ed] & 1)
        }
    }

    /// b = S_idx^-1 * a * S_idx,仅角块。
    static func CornConjugate(_ a: CubieCube, _ idx: Int, _ b: CubieCube) {
        let sinv = CubeSym[SymMultInv[0][idx]]!
        let s = CubeSym[idx]!
        for corn in 0..<8 {
            let oriA = sinv.ca[a.ca[s.ca[corn] & 7] & 7] >> 3
            let oriB = a.ca[s.ca[corn] & 7] >> 3
            let ori = oriA < 3 ? oriB : (3 - oriB) % 3
            b.ca[corn] = sinv.ca[a.ca[s.ca[corn] & 7] & 7] & 7 | ori << 3
        }
    }

    /// b = S_idx^-1 * a * S_idx,仅棱块。
    static func EdgeConjugate(_ a: CubieCube, _ idx: Int, _ b: CubieCube) {
        let sinv = CubeSym[SymMultInv[0][idx]]!
        let s = CubeSym[idx]!
        for ed in 0..<12 {
            b.ea[ed] = sinv.ea[a.ea[s.ea[ed] >> 1] >> 1] ^ (a.ea[s.ea[ed] >> 1] & 1) ^ (s.ea[ed] & 1)
        }
    }

    static func getPermSymInv(_ idx: Int, _ sym: Int, _ isCorner: Bool) -> Int {
        var idxi = PermInvEdgeSym[idx]
        if isCorner {
            idxi = ESym2CSym(idxi)
        }
        return idxi & 0xfff0 | SymMult[idxi & 0xf][sym]
    }

    static func getSkipMoves(_ ssymIn: UInt64) -> Int {
        var ret = 0
        var ssym = ssymIn
        var i = 1
        ssym >>= 1
        while ssym != 0 {
            if (ssym & 1) == 1 {
                ret |= firstMoveSym[i]
            }
            i += 1
            ssym >>= 1
        }
        return ret
    }

    /// this = S_urf^-1 * this * S_urf。
    func URFConjugate() {
        if temps == nil { temps = CubieCube() }
        let t = temps!
        CubieCube.CornMult(CubieCube.urf2, self, t)
        CubieCube.CornMult(t, CubieCube.urf1, self)
        CubieCube.EdgeMult(CubieCube.urf2, self, t)
        CubieCube.EdgeMult(t, CubieCube.urf1, self)
    }

    // ---------- 坐标读写 ----------

    func getFlip() -> Int {
        var idx = 0
        for i in 0..<11 {
            idx = idx << 1 | ea[i] & 1
        }
        return idx
    }

    func setFlip(_ idxIn: Int) {
        var parity = 0
        var idx = idxIn
        for i in stride(from: 10, through: 0, by: -1) {
            let val = idx & 1
            parity ^= val
            ea[i] = ea[i] & ~1 | val
            idx >>= 1
        }
        ea[11] = ea[11] & ~1 | parity
    }

    func getFlipSym() -> Int {
        CubieCube.FlipR2S[getFlip()]
    }

    func getTwist() -> Int {
        var idx = 0
        for i in 0..<7 {
            idx += (idx << 1) + (ca[i] >> 3)
        }
        return idx
    }

    func setTwist(_ idxIn: Int) {
        var twst = 15
        var idx = idxIn
        for i in stride(from: 6, through: 0, by: -1) {
            let val = idx % 3
            twst -= val
            ca[i] = ca[i] & 7 | val << 3
            idx /= 3
        }
        ca[7] = ca[7] & 7 | (twst % 3) << 3
    }

    func getTwistSym() -> Int {
        CubieCube.TwistR2S[getTwist()]
    }

    func getUDSlice() -> Int {
        494 - MUtil.getComb(ea, 8, true)
    }

    func setUDSlice(_ idx: Int) {
        MUtil.setComb(&ea, 494 - idx, 8, true)
    }

    func getCPerm() -> Int {
        MUtil.getNPerm(ca, 8, false)
    }

    func setCPerm(_ idx: Int) {
        MUtil.setNPerm(&ca, idx, 8, false)
    }

    func getCPermSym() -> Int {
        CubieCube.ESym2CSym(CubieCube.EPermR2S[getCPerm()])
    }

    func getEPerm() -> Int {
        MUtil.getNPerm(ea, 8, true)
    }

    func setEPerm(_ idx: Int) {
        MUtil.setNPerm(&ea, idx, 8, true)
    }

    func getEPermSym() -> Int {
        CubieCube.EPermR2S[getEPerm()]
    }

    func getMPerm() -> Int {
        MUtil.getNPerm(ea, 12, true) % 24
    }

    func setMPerm(_ idx: Int) {
        MUtil.setNPerm(&ea, idx, 12, true)
    }

    func getCComb() -> Int {
        MUtil.getComb(ca, 0, false)
    }

    func setCComb(_ idx: Int) {
        MUtil.setComb(&ca, idx, 0, false)
    }

    /// 检查可解性。0=可解;-2 缺棱;-3 翻棱;-4 缺角;-5 拧角;-6 奇偶。
    func verify() -> Int {
        var sum = 0
        var edgeMask = 0
        for e in 0..<12 {
            edgeMask |= 1 << (ea[e] >> 1)
            sum ^= ea[e] & 1
        }
        if edgeMask != 0xfff { return -2 }
        if sum != 0 { return -3 }
        var cornMask = 0
        sum = 0
        for c in 0..<8 {
            cornMask |= 1 << (ca[c] & 7)
            sum += ca[c] >> 3
        }
        if cornMask != 0xff { return -4 }
        if sum % 3 != 0 { return -5 }
        if (MUtil.getNParity(MUtil.getNPerm(ea, 12, true), 12) ^ MUtil.getNParity(getCPerm(), 8)) != 0 {
            return -6
        }
        return 0
    }

    func selfSymmetry() -> UInt64 {
        let c = CubieCube(self)
        let d = CubieCube()
        let cperm = c.getCPermSym() >> 4
        var sym: UInt64 = 0
        for urfInv in 0..<6 {
            let cpermx = c.getCPermSym() >> 4
            if cperm == cpermx {
                for i in 0..<16 {
                    CubieCube.CornConjugate(c, CubieCube.SymMultInv[0][i], d)
                    if d.ca == ca {
                        CubieCube.EdgeConjugate(c, CubieCube.SymMultInv[0][i], d)
                        if d.ea == ea {
                            sym |= 1 << UInt64(min(urfInv << 4 | i, 48))
                        }
                    }
                }
            }
            c.URFConjugate()
            if urfInv % 3 == 2 {
                c.invCubieCube()
            }
        }
        return sym
    }

    static func initMove() {
        moveCube[0] = CubieCube(15120, 0, 119750400, 0)
        moveCube[3] = CubieCube(21021, 1494, 323403417, 0)
        moveCube[6] = CubieCube(8064, 1236, 29441808, 550)
        moveCube[9] = CubieCube(9, 0, 5880, 0)
        moveCube[12] = CubieCube(1230, 412, 2949660, 0)
        moveCube[15] = CubieCube(224, 137, 328552, 137)
        for a in stride(from: 0, to: 18, by: 3) {
            for p in 0..<2 {
                moveCube[a + p + 1] = CubieCube()
                EdgeMult(moveCube[a + p]!, moveCube[a]!, moveCube[a + p + 1]!)
                CornMult(moveCube[a + p]!, moveCube[a]!, moveCube[a + p + 1]!)
            }
        }
    }

    func toStringValue() -> String {
        var sb = ""
        for i in 0..<8 { sb += "|\(ca[i] & 7) \(ca[i] >> 3)" }
        sb += "\n"
        for i in 0..<12 { sb += "|\(ea[i] >> 1) \(ea[i] & 1)" }
        return sb
    }

    static func initSym() {
        var c = CubieCube()
        var d = CubieCube()

        let f2 = CubieCube(28783, 0, 259268407, 0)
        let u4 = CubieCube(15138, 0, 119765538, 7)
        let lr2 = CubieCube(5167, 0, 83473207, 0)
        for i in 0..<8 {
            lr2.ca[i] |= 3 << 3
        }

        for i in 0..<16 {
            CubeSym[i] = CubieCube(c)
            CornMultFull(c, u4, d)
            EdgeMult(c, u4, d)
            swapC(&c, &d)
            if i % 4 == 3 {
                CornMultFull(c, lr2, d)
                EdgeMult(c, lr2, d)
                swapC(&c, &d)
            }
            if i % 8 == 7 {
                CornMultFull(c, f2, d)
                EdgeMult(c, f2, d)
                swapC(&c, &d)
            }
        }
        for i in 0..<16 {
            for j in 0..<16 {
                CornMultFull(CubeSym[i]!, CubeSym[j]!, c)
                for k in 0..<16 where CubeSym[k]!.ca == c.ca {
                    SymMult[i][j] = k
                    SymMultInv[k][j] = i
                    break
                }
            }
        }
        for j in 0..<18 {
            for s in 0..<16 {
                CornConjugate(moveCube[j]!, SymMultInv[0][s], c)
                for m in 0..<18 where moveCube[m]!.ca == c.ca {
                    SymMove[s][j] = m
                    SymMoveUD[s][MUtil.std2ud[j]] = MUtil.std2ud[m]
                    break
                }
                if s % 2 == 0 {
                    Sym8Move[j << 3 | s >> 1] = SymMove[s][j]
                }
            }
        }

        for i in 0..<18 {
            moveCubeSym[i] = moveCube[i]!.selfSymmetry()
            var j = i
            for s in 0..<48 {
                if SymMove[s % 16][j] < i {
                    firstMoveSym[s] |= 1 << i
                }
                if s % 16 == 15 {
                    j = urfMove[2][j]
                }
            }
        }
    }

    private static func swapC(_ a: inout CubieCube, _ b: inout CubieCube) {
        // Java 版通过局部变量交换引用;Swift 用指针式交换内容以保持调用方语义。
        let ta = a.ca, tb = a.ea
        a.ca = b.ca; a.ea = b.ea
        b.ca = ta; b.ea = tb
    }

    @discardableResult
    static func initSym2Raw(_ N_RAW: Int, _ Sym2Raw: inout [Int], _ Raw2Sym: inout [Int],
                            _ SymState: inout [Int], _ coord: Int) -> Int {
        let c = CubieCube()
        let d = CubieCube()
        var count = 0
        var idx = 0
        let sym_inc = coord >= 2 ? 1 : 2
        let isEdge = coord != 1

        for i in 0..<N_RAW {
            if Raw2Sym[i] != 0 { continue }
            switch coord {
            case 0: c.setFlip(i)
            case 1: c.setTwist(i)
            case 2: c.setEPerm(i)
            default: break
            }
            var s = 0
            while s < 16 {
                if isEdge {
                    EdgeConjugate(c, s, d)
                } else {
                    CornConjugate(c, s, d)
                }
                switch coord {
                case 0: idx = d.getFlip()
                case 1: idx = d.getTwist()
                case 2: idx = d.getEPerm()
                default: break
                }
                if coord == 0 && Search.USE_TWIST_FLIP_PRUN {
                    FlipS2RF[count << 3 | s >> 1] = idx
                }
                if idx == i {
                    SymState[count] |= 1 << (s / sym_inc)
                }
                let symIdx = (count << 4 | s) / sym_inc
                Raw2Sym[idx] = symIdx
                s += sym_inc
            }
            Sym2Raw[count] = i
            count += 1
        }
        return count
    }

    static func initFlipSym2Raw() {
        _ = initSym2Raw(CoordCube.N_FLIP, &FlipS2R, &FlipR2S, &SymStateFlip, 0)
    }

    static func initTwistSym2Raw() {
        _ = initSym2Raw(CoordCube.N_TWIST, &TwistS2R, &TwistR2S, &SymStateTwist, 1)
    }

    static func initPermSym2Raw() {
        _ = initSym2Raw(CoordCube.N_PERM, &EPermS2R, &EPermR2S, &SymStatePerm, 2)
        let cc = CubieCube()
        for i in 0..<CoordCube.N_PERM_SYM {
            cc.setEPerm(EPermS2R[i])
            Perm2CombP[i] = MUtil.getComb(cc.ea, 0, true) + (Search.USE_COMBP_PRUN ? MUtil.getNParity(EPermS2R[i], 8) * 70 : 0)
            cc.invCubieCube()
            PermInvEdgeSym[i] = cc.getEPermSym()
        }
        for i in 0..<CoordCube.N_MPERM {
            cc.setMPerm(i)
            cc.invCubieCube()
            MPermInv[i] = cc.getMPerm()
        }
    }

    static func initialize() {
        initMove()
        initSym()
    }
}
