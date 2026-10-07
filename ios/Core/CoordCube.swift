import Foundation

// Port of cs.min2phase.CoordCube.java
// 剪枝表以 4 位 nibble 打包在 32 位字里,位技巧用 UInt32 保证与 Java int 相同的回绕语义。

enum CoordCube {
    static let N_MOVES = 18
    static let N_MOVES2 = 10

    static let N_SLICE = 495
    static let N_TWIST = 2187
    static let N_TWIST_SYM = 324
    static let N_FLIP = 2048
    static let N_FLIP_SYM = 336
    static let N_PERM = 40320
    static let N_PERM_SYM = 2768
    static let N_MPERM = 24
    static let N_COMB = Search.USE_COMBP_PRUN ? 140 : 70
    static let P2_PARITY_MOVE = Search.USE_COMBP_PRUN ? 0xA5 : 0

    // phase1
    static var UDSliceMove = [[Int]](repeating: [Int](repeating: 0, count: N_MOVES), count: N_SLICE)
    static var TwistMove = [[Int]](repeating: [Int](repeating: 0, count: N_MOVES), count: N_TWIST_SYM)
    static var FlipMove = [[Int]](repeating: [Int](repeating: 0, count: N_MOVES), count: N_FLIP_SYM)
    static var UDSliceConj = [[Int]](repeating: [Int](repeating: 0, count: 8), count: N_SLICE)
    static var UDSliceTwistPrun = [UInt32](repeating: 0, count: N_SLICE * N_TWIST_SYM / 8 + 1)
    static var UDSliceFlipPrun = [UInt32](repeating: 0, count: N_SLICE * N_FLIP_SYM / 8 + 1)
    static var TwistFlipPrun = Search.USE_TWIST_FLIP_PRUN ? [UInt32](repeating: 0, count: N_FLIP * N_TWIST_SYM / 8 + 1) : []

    // phase2
    static var CPermMove = [[Int]](repeating: [Int](repeating: 0, count: N_MOVES2), count: N_PERM_SYM)
    static var EPermMove = [[Int]](repeating: [Int](repeating: 0, count: N_MOVES2), count: N_PERM_SYM)
    static var MPermMove = [[Int]](repeating: [Int](repeating: 0, count: N_MOVES2), count: N_MPERM)
    static var MPermConj = [[Int]](repeating: [Int](repeating: 0, count: 16), count: N_MPERM)
    static var CCombPMove: [[Int]] = []
    static var CCombPConj = [[Int]](repeating: [Int](repeating: 0, count: 16), count: N_COMB)
    static var MCPermPrun = [UInt32](repeating: 0, count: N_MPERM * N_PERM_SYM / 8 + 1)
    static var EPermCCombPPrun = [UInt32](repeating: 0, count: N_COMB * N_PERM_SYM / 8 + 1)

    /// 0: 未初始化, 1: 部分, 2: 完成
    static var initLevel = 0

    static func initialize(_ fullInit: Bool) {
        if initLevel == 2 || (initLevel == 1 && !fullInit) {
            return
        }
        if initLevel == 0 {
            CubieCube.initPermSym2Raw()
            initCPermMove()
            initEPermMove()
            initMPermMoveConj()
            initCombPMoveConj()

            CubieCube.initFlipSym2Raw()
            CubieCube.initTwistSym2Raw()
            initFlipMove()
            initTwistMove()
            initUDSliceMoveConj()
        }
        initMCPermPrun(fullInit)
        initPermCombPPrun(fullInit)
        initSliceTwistPrun(fullInit)
        initSliceFlipPrun(fullInit)
        if Search.USE_TWIST_FLIP_PRUN {
            initTwistFlipPrun(fullInit)
        }
        initLevel = fullInit ? 2 : 1
    }

    // Java 的 int 移位自动掩码移位数(&31),等价于 (index & 7) << 2;Swift 必须显式掩码。
    static func setPruning(_ table: inout [UInt32], _ index: Int, _ value: Int) {
        table[index >> 3] ^= UInt32(value) << ((index & 7) << 2)
    }

    static func getPruning(_ table: [UInt32], _ index: Int) -> Int {
        Int(table[index >> 3] >> ((index & 7) << 2) & 0xf)
    }

    static func initUDSliceMoveConj() {
        let c = CubieCube()
        let d = CubieCube()
        for i in 0..<N_SLICE {
            c.setUDSlice(i)
            var j = 0
            while j < N_MOVES {
                CubieCube.EdgeMult(c, CubieCube.moveCube[j]!, d)
                UDSliceMove[i][j] = d.getUDSlice()
                j += 3
            }
            var k = 0
            while k < 16 {
                CubieCube.EdgeConjugate(c, CubieCube.SymMultInv[0][k], d)
                UDSliceConj[i][k >> 1] = d.getUDSlice()
                k += 2
            }
        }
        for i in 0..<N_SLICE {
            var j = 0
            while j < N_MOVES {
                var udslice = UDSliceMove[i][j]
                for k in 1..<3 {
                    udslice = UDSliceMove[udslice][j]
                    UDSliceMove[i][j + k] = udslice
                }
                j += 3
            }
        }
    }

    static func initFlipMove() {
        let c = CubieCube()
        let d = CubieCube()
        for i in 0..<N_FLIP_SYM {
            c.setFlip(CubieCube.FlipS2R[i])
            for j in 0..<N_MOVES {
                CubieCube.EdgeMult(c, CubieCube.moveCube[j]!, d)
                FlipMove[i][j] = d.getFlipSym()
            }
        }
    }

    static func initTwistMove() {
        let c = CubieCube()
        let d = CubieCube()
        for i in 0..<N_TWIST_SYM {
            c.setTwist(CubieCube.TwistS2R[i])
            for j in 0..<N_MOVES {
                CubieCube.CornMult(c, CubieCube.moveCube[j]!, d)
                TwistMove[i][j] = d.getTwistSym()
            }
        }
    }

    static func initCPermMove() {
        let c = CubieCube()
        let d = CubieCube()
        for i in 0..<N_PERM_SYM {
            c.setCPerm(CubieCube.EPermS2R[i])
            for j in 0..<N_MOVES2 {
                CubieCube.CornMult(c, CubieCube.moveCube[MUtil.ud2std[j]]!, d)
                CPermMove[i][j] = d.getCPermSym()
            }
        }
    }

    static func initEPermMove() {
        let c = CubieCube()
        let d = CubieCube()
        for i in 0..<N_PERM_SYM {
            c.setEPerm(CubieCube.EPermS2R[i])
            for j in 0..<N_MOVES2 {
                CubieCube.EdgeMult(c, CubieCube.moveCube[MUtil.ud2std[j]]!, d)
                EPermMove[i][j] = d.getEPermSym()
            }
        }
    }

    static func initMPermMoveConj() {
        let c = CubieCube()
        let d = CubieCube()
        for i in 0..<N_MPERM {
            c.setMPerm(i)
            for j in 0..<N_MOVES2 {
                CubieCube.EdgeMult(c, CubieCube.moveCube[MUtil.ud2std[j]]!, d)
                MPermMove[i][j] = d.getMPerm()
            }
            for j in 0..<16 {
                CubieCube.EdgeConjugate(c, CubieCube.SymMultInv[0][j], d)
                MPermConj[i][j] = d.getMPerm()
            }
        }
    }

    static func initCombPMoveConj() {
        let c = CubieCube()
        let d = CubieCube()
        CCombPMove = [[Int]](repeating: [Int](repeating: 0, count: N_MOVES2), count: N_COMB)
        for i in 0..<N_COMB {
            c.setCComb(i % 70)
            for j in 0..<N_MOVES2 {
                CubieCube.CornMult(c, CubieCube.moveCube[MUtil.ud2std[j]]!, d)
                CCombPMove[i][j] = d.getCComb() + 70 * ((P2_PARITY_MOVE >> j & 1) ^ (i / 70))
            }
            for j in 0..<16 {
                CubieCube.CornConjugate(c, CubieCube.SymMultInv[0][j], d)
                CCombPConj[i][j] = d.getCComb() + 70 * (i / 70)
            }
        }
    }

    static func hasZero(_ val: UInt32) -> Bool {
        ((val &- 0x11111111) & ~val & 0x88888888) != 0
    }

    //          |  4 bits   |  4 bits   |  4 bits   | 2 bits | 1b | 1b | 4 bits |
    // PrunFlag:| MIN_DEPTH | MAX_DEPTH | INV_DEPTH | Padding| P2 | E2C| SYM_SHIFT |
    static func initRawSymPrun(_ PrunTable: inout [UInt32],
                               _ RawMove: [[Int]]?, _ RawConj: [[Int]]?,
                               _ SymMove: [[Int]], _ SymState: [Int],
                               _ PrunFlag: Int, _ fullInit: Bool) {
        let SYM_SHIFT = PrunFlag & 0xf
        let SYM_E2C_MAGIC: Int = ((PrunFlag >> 4) & 1) == 1 ? CubieCube.SYM_E2C_MAGIC : 0x00000000
        let IS_PHASE2 = ((PrunFlag >> 5) & 1) == 1
        let INV_DEPTH = PrunFlag >> 8 & 0xf
        let MAX_DEPTH = PrunFlag >> 12 & 0xf
        let MIN_DEPTH = PrunFlag >> 16 & 0xf
        let SEARCH_DEPTH = fullInit ? MAX_DEPTH : MIN_DEPTH

        let SYM_MASK = (1 << SYM_SHIFT) - 1
        let ISTFP = RawMove == nil
        let N_RAW = ISTFP ? N_FLIP : RawMove!.count
        let N_SIZE = N_RAW * SymMove.count
        let N_MOVES = IS_PHASE2 ? 10 : 18
        let NEXT_AXIS_MAGIC = N_MOVES == 10 ? 0x42 : 0x92492

        var depth = getPruning(PrunTable, N_SIZE) - 1
        var done = 0
        #if CUBEAR_PRUN_DEBUG
        var printedDepth = -2
        #endif

        if depth == -1 {
            for i in 0..<(N_SIZE / 8 + 1) {
                PrunTable[i] = 0x11111111
            }
            setPruning(&PrunTable, 0, 0 ^ 1)
            depth = 0
            done = 1
        }

        while depth < SEARCH_DEPTH {
            #if CUBEAR_PRUN_DEBUG
            if depth != printedDepth { print("  prun depth \(depth)/\(SEARCH_DEPTH) done=\(done)"); printedDepth = depth }
            #endif
            let mask = UInt32((depth + 1) * 0x11111111) ^ 0xffffffff
            for i in 0..<PrunTable.count {
                var val = PrunTable[i] ^ mask
                val &= val >> 1
                PrunTable[i] += val & (val >> 2) & 0x11111111
            }

            let inv = depth > INV_DEPTH
            let select = inv ? (depth + 2) : depth
            let selArrMask = UInt32(select) * 0x11111111
            let check = inv ? depth : (depth + 2)
            depth += 1
            let xorVal = depth ^ (depth + 1)
            var val: UInt32 = 0
            var i = 0
            while i < N_SIZE {
                defer { val >>= 4; i += 1 }
                if (i & 7) == 0 {
                    val = PrunTable[i >> 3]
                    if !hasZero(val ^ selArrMask) {
                        i += 7
                        continue
                    }
                }
                if (val & 0xf) != UInt32(select) {
                    continue
                }
                let raw = i % N_RAW
                let sym = i / N_RAW
                var flip = 0, fsym = 0
                if ISTFP {
                    flip = CubieCube.FlipR2S[raw]
                    fsym = flip & 7
                    flip >>= 3
                }

                var m = 0
                while m < N_MOVES {
                    var symx = SymMove[sym][m]
                    var rawx: Int
                    if ISTFP {
                        rawx = CubieCube.FlipS2RF[
                            FlipMove[flip][CubieCube.Sym8Move[m << 3 | fsym]] ^ fsym ^ (symx & SYM_MASK)]
                    } else {
                        rawx = RawConj![RawMove![raw][m]][symx & SYM_MASK]
                    }
                    symx >>= SYM_SHIFT
                    let idx = symx * N_RAW + rawx
                    let prun = getPruning(PrunTable, idx)
                    if prun != check {
                        if prun < depth - 1 {
                            m += NEXT_AXIS_MAGIC >> m & 3
                        }
                        // Java 的 for 循环 continue 仍会执行 m++
                        m += 1
                        continue
                    }
                    done += 1
                    if inv {
                        setPruning(&PrunTable, i, xorVal)
                        break
                    }
                    setPruning(&PrunTable, idx, xorVal)
                    var symState = SymState[symx]
                    var j = 1
                    symState >>= 1
                    while symState != 0 {
                        if (symState & 1) == 1 {
                            var idxx = symx * N_RAW
                            if ISTFP {
                                idxx += CubieCube.FlipS2RF[CubieCube.FlipR2S[rawx] ^ j]
                            } else {
                                idxx += RawConj![rawx][j ^ (SYM_E2C_MAGIC >> (j << 1) & 3)]
                            }
                            if getPruning(PrunTable, idxx) == check {
                                setPruning(&PrunTable, idxx, xorVal)
                                done += 1
                            }
                        }
                        j += 1
                        symState >>= 1
                    }
                    m += 1
                }
            }
        }
    }

    static func initTwistFlipPrun(_ fullInit: Bool) {
        initRawSymPrun(&TwistFlipPrun, nil, nil, TwistMove, CubieCube.SymStateTwist, 0x19603, fullInit)
    }

    static func initSliceTwistPrun(_ fullInit: Bool) {
        initRawSymPrun(&UDSliceTwistPrun, UDSliceMove, UDSliceConj, TwistMove, CubieCube.SymStateTwist, 0x69603, fullInit)
    }

    static func initSliceFlipPrun(_ fullInit: Bool) {
        initRawSymPrun(&UDSliceFlipPrun, UDSliceMove, UDSliceConj, FlipMove, CubieCube.SymStateFlip, 0x69603, fullInit)
    }

    static func initMCPermPrun(_ fullInit: Bool) {
        initRawSymPrun(&MCPermPrun, MPermMove, MPermConj, CPermMove, CubieCube.SymStatePerm, 0x8ea34, fullInit)
    }

    static func initPermCombPPrun(_ fullInit: Bool) {
        initRawSymPrun(&EPermCCombPPrun, CCombPMove, CCombPConj, EPermMove, CubieCube.SymStatePerm, 0x7d824, fullInit)
    }
}

/// 实例坐标节点(对应 Java 的 CoordCube 实例部分)。
final class CoordCubeNode {
    var twist = 0
    var tsym = 0
    var flip = 0
    var fsym = 0
    var slice = 0
    var prun = 0

    var twistc = 0
    var flipc = 0

    init() {}

    func set(_ node: CoordCubeNode) {
        twist = node.twist
        tsym = node.tsym
        flip = node.flip
        fsym = node.fsym
        slice = node.slice
        prun = node.prun

        if Search.USE_CONJ_PRUN {
            twistc = node.twistc
            flipc = node.flipc
        }
    }

    func calcPruning(_ isPhase1: Bool) {
        prun = max(
            max(
                CoordCube.getPruning(CoordCube.UDSliceTwistPrun,
                                     twist * CoordCube.N_SLICE + CoordCube.UDSliceConj[slice][tsym]),
                CoordCube.getPruning(CoordCube.UDSliceFlipPrun,
                                     flip * CoordCube.N_SLICE + CoordCube.UDSliceConj[slice][fsym])),
            max(
                Search.USE_CONJ_PRUN
                    ? CoordCube.getPruning(CoordCube.TwistFlipPrun,
                                           (twistc >> 3) << 11 | CubieCube.FlipS2RF[flipc ^ (twistc & 7)])
                    : 0,
                Search.USE_TWIST_FLIP_PRUN
                    ? CoordCube.getPruning(CoordCube.TwistFlipPrun,
                                           twist << 11 | CubieCube.FlipS2RF[flip << 3 | (fsym ^ tsym)])
                    : 0))
    }

    func setWithPrun(_ cc: CubieCube, _ depth: Int) -> Bool {
        #if CUBEAR_SEARCH_DEBUG
        Search.dbgSetWithPrunCalls += 1
        if Search.dbgSetWithPrunCalls <= 40 {
            print("  setWithPrun #\(Search.dbgSetWithPrunCalls) depth=\(depth) ca=\(cc.ca) ea=\(cc.ea)")
        }
        #endif
        twist = cc.getTwistSym()
        flip = cc.getFlipSym()
        tsym = twist & 7
        twist = twist >> 3

        prun = Search.USE_TWIST_FLIP_PRUN
            ? CoordCube.getPruning(CoordCube.TwistFlipPrun, twist << 11 | CubieCube.FlipS2RF[flip ^ tsym])
            : 0
        if prun > depth { return false }

        fsym = flip & 7
        flip = flip >> 3

        slice = cc.getUDSlice()
        prun = max(prun, max(
            CoordCube.getPruning(CoordCube.UDSliceTwistPrun,
                                 twist * CoordCube.N_SLICE + CoordCube.UDSliceConj[slice][tsym]),
            CoordCube.getPruning(CoordCube.UDSliceFlipPrun,
                                 flip * CoordCube.N_SLICE + CoordCube.UDSliceConj[slice][fsym])))
        if prun > depth { return false }

        if Search.USE_CONJ_PRUN {
            let pc = CubieCube()
            CubieCube.CornConjugate(cc, 1, pc)
            CubieCube.EdgeConjugate(cc, 1, pc)
            twistc = pc.getTwistSym()
            flipc = pc.getFlipSym()
            prun = max(prun,
                       CoordCube.getPruning(CoordCube.TwistFlipPrun,
                                            (twistc >> 3) << 11 | CubieCube.FlipS2RF[flipc ^ (twistc & 7)]))
        }

        return prun <= depth
    }

    @discardableResult
    func doMovePrun(_ cc: CoordCubeNode, _ m: Int, _ isPhase1: Bool) -> Int {
        slice = CoordCube.UDSliceMove[cc.slice][m]

        flip = CoordCube.FlipMove[cc.flip][CubieCube.Sym8Move[m << 3 | cc.fsym]]
        fsym = (flip & 7) ^ cc.fsym
        flip >>= 3

        twist = CoordCube.TwistMove[cc.twist][CubieCube.Sym8Move[m << 3 | cc.tsym]]
        tsym = (twist & 7) ^ cc.tsym
        twist >>= 3

        prun = max(
            max(
                CoordCube.getPruning(CoordCube.UDSliceTwistPrun,
                                     twist * CoordCube.N_SLICE + CoordCube.UDSliceConj[slice][tsym]),
                CoordCube.getPruning(CoordCube.UDSliceFlipPrun,
                                     flip * CoordCube.N_SLICE + CoordCube.UDSliceConj[slice][fsym])),
            Search.USE_TWIST_FLIP_PRUN
                ? CoordCube.getPruning(CoordCube.TwistFlipPrun,
                                       twist << 11 | CubieCube.FlipS2RF[flip << 3 | (fsym ^ tsym)])
                : 0)
        return prun
    }

    func doMovePrunConj(_ cc: CoordCubeNode, _ mIn: Int) -> Int {
        let m = CubieCube.SymMove[3][mIn]
        flipc = CoordCube.FlipMove[cc.flipc >> 3][CubieCube.Sym8Move[m << 3 | cc.flipc & 7]] ^ (cc.flipc & 7)
        twistc = CoordCube.TwistMove[cc.twistc >> 3][CubieCube.Sym8Move[m << 3 | cc.twistc & 7]] ^ (cc.twistc & 7)
        return CoordCube.getPruning(CoordCube.TwistFlipPrun,
                                    (twistc >> 3) << 11 | CubieCube.FlipS2RF[flipc ^ (twistc & 7)])
    }
}
