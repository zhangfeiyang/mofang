import Foundation

// Port of cs.min2phase.Search.java
// 二阶段算法的更快更小实现,使用对称性减少内存(约 1MB)。
// 版权 (C) 2015 Shuang Chen,GPL-3.0(与安卓端一致地引入)。

final class Search {
    static let USE_TWIST_FLIP_PRUN = true

    // 研究用途选项
    static let MAX_PRE_MOVES = 20
    static let TRY_INVERSE = true
    static let TRY_THREE_AXES = true

    static let USE_COMBP_PRUN = USE_TWIST_FLIP_PRUN
    static let USE_CONJ_PRUN = USE_TWIST_FLIP_PRUN
    static var MIN_P1LENGTH_PRE = 7
    static var MAX_DEPTH2 = 12

    static var inited = false

    /// Java 类加载时的 static 初始化顺序:Util → CubieCube。首次求解前调用一次。
    private static let bootstrapped: Void = {
        MUtil.initialize()
        CubieCube.initialize()
    }()

    var move = [Int](repeating: 0, count: 31)
    var nodeUD: [CoordCubeNode]
    var nodeRL: [CoordCubeNode]
    var nodeFB: [CoordCubeNode]

    var selfSym: UInt64 = 0
    var conjMask = 0
    var urfIdx = 0
    var length1 = 0
    var depth1 = 0
    var maxDep2 = 0
    var solLen = 0
    var solution: MUtil.Solution?
    var probe: Int64 = 0
    var probeMax: Int64 = 0
    var probeMin: Int64 = 0
    var verbose = 0
    var valid1 = 0
    var allowShorter = false
    var cc = CubieCube()
    var urfCubieCube: [CubieCube]
    var urfCoordCube: [CoordCubeNode]
    var phase1Cubie: [CubieCube]

    var preMoveCubes: [CubieCube]
    var preMoves = [Int](repeating: 0, count: MAX_PRE_MOVES)
    var preMoveLen = 0
    var maxPreMoves = 0

    var isRec = false

    /// 用 " . " 分隔两阶段。
    static let USE_SEPARATOR = 0x1
    /// 把解反转为打乱/状态生成器。
    static let INVERSE_SOLUTION = 0x2
    /// 追加 "(21f)" 之类的标签。
    static let APPEND_LENGTH = 0x4
    /// 保证解最优。
    static let OPTIMAL_SOLUTION = 0x8

    init() {
        nodeUD = (0..<21).map { _ in CoordCubeNode() }
        nodeRL = (0..<21).map { _ in CoordCubeNode() }
        nodeFB = (0..<21).map { _ in CoordCubeNode() }
        phase1Cubie = (0..<21).map { _ in CubieCube() }
        urfCubieCube = (0..<6).map { _ in CubieCube() }
        urfCoordCube = (0..<6).map { _ in CoordCubeNode() }
        preMoveCubes = (0...Self.MAX_PRE_MOVES).map { _ in CubieCube() }
    }

    /// 计算给定魔方的还原步骤。
    ///
    /// - Parameters:
    ///   - facelets: 魔方定义串(U1..B9 位置上的颜色字母)
    ///   - maxDepth: 允许的最大步数
    ///   - probeMax: phase 2 探测上限;超时返回错误码
    ///   - probeMin: phase 2 最小探测数;找得更短才继续
    ///   - verbose: 输出格式,见 USE_SEPARATOR 等
    /// - Returns: 解串或错误码("Error 1".."Error 8")
    func solution(_ facelets: String, _ maxDepth: Int, _ probeMax: Int64, _ probeMin: Int64, _ verbose: Int) -> String {
        Search.bootstrapped
        let check = verify(facelets)
        if check != 0 {
            return "Error \(abs(check))"
        }
        solLen = maxDepth + 1
        probe = 0
        self.probeMax = probeMax
        self.probeMin = min(probeMin, probeMax)
        self.verbose = verbose
        solution = nil
        isRec = false

        CoordCube.initialize(false)
        initSearch()

        return (verbose & Search.OPTIMAL_SOLUTION) == 0 ? search() : searchopt()
    }

    func initSearch() {
        conjMask = (Search.TRY_INVERSE ? 0 : 0x38) | (Search.TRY_THREE_AXES ? 0 : 0x36)
        selfSym = cc.selfSymmetry()
        conjMask |= (Int(selfSym >> 16) & 0xffff) != 0 ? 0x12 : 0
        conjMask |= (Int(selfSym >> 32) & 0xffff) != 0 ? 0x24 : 0
        conjMask |= (Int(selfSym >> 48) & 0xffff) != 0 ? 0x38 : 0
        selfSym &= 0xffffffffffff
        maxPreMoves = conjMask > 7 ? 0 : Search.MAX_PRE_MOVES

        for i in 0..<6 {
            urfCubieCube[i].copy(from: cc)
            urfCoordCube[i].setWithPrun(urfCubieCube[i], 20)
            cc.URFConjugate()
            if i % 3 == 2 {
                cc.invCubieCube()
            }
        }
    }

    func next(_ probeMax: Int64, _ probeMin: Int64, _ verbose: Int) -> String {
        probe = 0
        self.probeMax = probeMax
        self.probeMin = min(probeMin, probeMax)
        solution = nil
        isRec = (self.verbose & Search.OPTIMAL_SOLUTION) == (verbose & Search.OPTIMAL_SOLUTION)
        self.verbose = verbose
        return (verbose & Search.OPTIMAL_SOLUTION) == 0 ? search() : searchopt()
    }

    static func isInited() -> Bool { inited }

    var numberOfProbes: Int64 { probe }

    var length: Int { solLen }

    static func initTables() {
        bootstrapped
        CoordCube.initialize(true)
        inited = true
    }

    func verify(_ facelets: String) -> Int {
        var count = 0x000000
        var f = [Int](repeating: 0, count: 54)
        let chars = Array(facelets)
        guard chars.count == 54 else { return -1 }
        let center: [Character] = [
            chars[MUtil.U5], chars[MUtil.R5], chars[MUtil.F5],
            chars[MUtil.D5], chars[MUtil.L5], chars[MUtil.B5],
        ]
        for i in 0..<54 {
            guard let idx = center.firstIndex(of: chars[i]) else { return -1 }
            f[i] = idx
            count += 1 << (f[i] << 2)
        }
        if count != 0x999999 {
            return -1
        }
        MUtil.toCubieCube(f, cc)
        return cc.verify()
    }

    func phase1PreMoves(_ maxl: Int, _ lmIn: Int, _ cc: CubieCube, _ ssym: Int) -> Int {
        var lm = lmIn
        preMoveLen = maxPreMoves - maxl
        if isRec ? depth1 == length1 - preMoveLen
            : (preMoveLen == 0 || (0x36FB7 >> lm & 1) == 0) {
            depth1 = length1 - preMoveLen
            phase1Cubie[0] = cc
            allowShorter = depth1 == Search.MIN_P1LENGTH_PRE && preMoveLen != 0

            let ok = nodeUD[depth1 + 1].setWithPrun(cc, depth1)
            if ok && phase1(nodeUD[depth1 + 1], ssym, depth1, -1) == 0 {
                return 0
            }
        }

        if maxl == 0 || preMoveLen + Search.MIN_P1LENGTH_PRE >= length1 {
            return 1
        }

        var skipMoves = CubieCube.getSkipMoves(UInt64(ssym))
        if maxl == 1 || preMoveLen + 1 + Search.MIN_P1LENGTH_PRE >= length1 {
            skipMoves |= 0x36FB7
        }

        lm = lm / 3 * 3
        var m = 0
        while m < 18 {
            if m == lm || m == lm - 9 || m == lm + 9 {
                m += 2
                m += 1
                continue
            }
            if isRec && m != preMoves[maxPreMoves - maxl] || (skipMoves & 1 << m) != 0 {
                m += 1
                continue
            }
            CubieCube.CornMult(CubieCube.moveCube[m]!, cc, preMoveCubes[maxl])
            CubieCube.EdgeMult(CubieCube.moveCube[m]!, cc, preMoveCubes[maxl])
            preMoves[maxPreMoves - maxl] = m
            let ret = phase1PreMoves(maxl - 1, m, preMoveCubes[maxl], ssym & Int(CubieCube.moveCubeSym[m] & 0xffffffff))
            if ret == 0 {
                return 0
            }
            m += 1
        }
        return 1
    }

    func search() -> String {
        for length1 in (isRec ? self.length1 : 0)..<solLen {
            self.length1 = length1
            maxDep2 = min(Search.MAX_DEPTH2, solLen - length1 - 1)
            for urfIdx in (isRec ? self.urfIdx : 0)..<6 {
                self.urfIdx = urfIdx
                if (conjMask & 1 << urfIdx) != 0 {
                    continue
                }
                if phase1PreMoves(maxPreMoves, -30, urfCubieCube[urfIdx], Int(selfSym & 0xffff)) == 0 {
                    return solution == nil ? "Error 8" : solution!.toStringValue(Search.INVERSE_SOLUTION, Search.USE_SEPARATOR, Search.APPEND_LENGTH)
                }
            }
        }
        return solution == nil ? "Error 7" : solution!.toStringValue(Search.INVERSE_SOLUTION, Search.USE_SEPARATOR, Search.APPEND_LENGTH)
    }

    /// 0: 找到或超过探测上限;1: 还差 1+maxDep2 步,试下一个 power;2: 还差 2+maxDep2 步,试下一个 axis
    func initPhase2Pre() -> Int {
        isRec = false
        if probe >= (solution == nil ? probeMax : probeMin) {
            return 0
        }
        probe += 1

        for i in valid1..<depth1 {
            CubieCube.CornMult(phase1Cubie[i], CubieCube.moveCube[move[i]]!, phase1Cubie[i + 1])
            CubieCube.EdgeMult(phase1Cubie[i], CubieCube.moveCube[move[i]]!, phase1Cubie[i + 1])
        }
        valid1 = depth1

        var p2corn = phase1Cubie[depth1].getCPermSym()
        var p2csym = p2corn & 0xf
        p2corn >>= 4
        var p2edge = phase1Cubie[depth1].getEPermSym()
        var p2esym = p2edge & 0xf
        p2edge >>= 4
        var p2mid = phase1Cubie[depth1].getMPerm()
        var edgei = CubieCube.getPermSymInv(p2edge, p2esym, false)
        var corni = CubieCube.getPermSymInv(p2corn, p2csym, true)

        let lastMove = depth1 == 0 ? -1 : move[depth1 - 1]
        let lastPre = preMoveLen == 0 ? -1 : preMoves[preMoveLen - 1]

        var ret = 0
        let p2switchMax = (preMoveLen == 0 ? 1 : 2) * (depth1 == 0 ? 1 : 2)
        var p2switchMask = (1 << p2switchMax) - 1
        for p2switch in 0..<p2switchMax {
            // 0 normal; 1 lastmove; 2 lastmove + premove; 3 premove
            if (p2switchMask >> p2switch & 1) != 0 {
                p2switchMask &= ~(1 << p2switch)
                ret = initPhase2(p2corn, p2csym, p2edge, p2esym, p2mid, edgei, corni)
                if ret == 0 || ret > 2 {
                    break
                } else if ret == 2 {
                    p2switchMask &= 0x4 << p2switch
                }
            }
            if p2switchMask == 0 {
                break
            }
            if (p2switch & 1) == 0 && depth1 > 0 {
                let m = MUtil.std2ud[lastMove / 3 * 3 + 1]
                move[depth1 - 1] = MUtil.ud2std[m] * 2 - move[depth1 - 1]

                p2mid = CoordCube.MPermMove[p2mid][m]
                p2corn = CoordCube.CPermMove[p2corn][CubieCube.SymMoveUD[p2csym][m]]
                p2csym = CubieCube.SymMult[p2corn & 0xf][p2csym]
                p2corn >>= 4
                p2edge = CoordCube.EPermMove[p2edge][CubieCube.SymMoveUD[p2esym][m]]
                p2esym = CubieCube.SymMult[p2edge & 0xf][p2esym]
                p2edge >>= 4
                corni = CubieCube.getPermSymInv(p2corn, p2csym, true)
                edgei = CubieCube.getPermSymInv(p2edge, p2esym, false)
            } else if preMoveLen > 0 {
                let m = MUtil.std2ud[lastPre / 3 * 3 + 1]
                preMoves[preMoveLen - 1] = MUtil.ud2std[m] * 2 - preMoves[preMoveLen - 1]

                p2mid = CubieCube.MPermInv[CoordCube.MPermMove[CubieCube.MPermInv[p2mid]][m]]
                p2corn = CoordCube.CPermMove[corni >> 4][CubieCube.SymMoveUD[corni & 0xf][m]]
                corni = p2corn & ~0xf | CubieCube.SymMult[p2corn & 0xf][corni & 0xf]
                p2corn = CubieCube.getPermSymInv(corni >> 4, corni & 0xf, true)
                p2csym = p2corn & 0xf
                p2corn >>= 4
                p2edge = CoordCube.EPermMove[edgei >> 4][CubieCube.SymMoveUD[edgei & 0xf][m]]
                edgei = p2edge & ~0xf | CubieCube.SymMult[p2edge & 0xf][edgei & 0xf]
                p2edge = CubieCube.getPermSymInv(edgei >> 4, edgei & 0xf, false)
                p2esym = p2edge & 0xf
                p2edge >>= 4
            }
        }
        if depth1 > 0 {
            move[depth1 - 1] = lastMove
        }
        if preMoveLen > 0 {
            preMoves[preMoveLen - 1] = lastPre
        }
        return ret == 0 ? 0 : 2
    }

    func initPhase2(_ p2cornIn: Int, _ p2csymIn: Int, _ p2edgeIn: Int, _ p2esymIn: Int,
                    _ p2midIn: Int, _ edgei: Int, _ corni: Int) -> Int {
        let p2corn = p2cornIn, p2csym = p2csymIn, p2edge = p2edgeIn, p2esym = p2esymIn, p2mid = p2midIn
        let prun = max(
            max(
                CoordCube.getPruning(CoordCube.EPermCCombPPrun,
                                     (edgei >> 4) * CoordCube.N_COMB + CoordCube.CCombPConj[CubieCube.Perm2CombP[corni >> 4] & 0xff][CubieCube.SymMultInv[edgei & 0xf][corni & 0xf]]),
                CoordCube.getPruning(CoordCube.EPermCCombPPrun,
                                     p2edge * CoordCube.N_COMB + CoordCube.CCombPConj[CubieCube.Perm2CombP[p2corn] & 0xff][CubieCube.SymMultInv[p2esym][p2csym]])),
            CoordCube.getPruning(CoordCube.MCPermPrun,
                                 p2corn * CoordCube.N_MPERM + CoordCube.MPermConj[p2mid][p2csym]))

        if prun > maxDep2 {
            return prun - maxDep2
        }

        var depth2 = maxDep2
        while depth2 >= prun {
            let ret = phase2(p2edge, p2esym, p2corn, p2csym, p2mid, depth2, depth1, 10)
            if ret < 0 {
                break
            }
            depth2 -= ret
            solLen = 0
            solution = MUtil.Solution()
            solution!.setArgs(verbose, urfIdx, depth1)
            for i in 0..<(depth1 + depth2) {
                solution!.appendSolMove(move[i])
            }
            for i in stride(from: preMoveLen - 1, through: 0, by: -1) {
                solution!.appendSolMove(preMoves[i])
            }
            solLen = solution!.length
            depth2 -= 1
        }

        if depth2 != maxDep2 {
            maxDep2 = min(Search.MAX_DEPTH2, solLen - length1 - 1)
            return probe >= probeMin ? 0 : 1
        }
        return 1
    }

    /// 0: 找到或超探测上限;1: 试下一个 power;2: 试下一个 axis
    func phase1(_ node: CoordCubeNode, _ ssym: Int, _ maxl: Int, _ lm: Int) -> Int {
        if node.prun == 0 && maxl < 5 {
            if allowShorter || maxl == 0 {
                depth1 -= maxl
                let ret = initPhase2Pre()
                depth1 += maxl
                return ret
            } else {
                return 1
            }
        }

        let skipMoves = CubieCube.getSkipMoves(UInt64(ssym))

        for axis in stride(from: 0, to: 18, by: 3) {
            if axis == lm || axis == lm - 9 {
                continue
            }
            for power in 0..<3 {
                let m = axis + power

                if isRec && m != move[depth1 - maxl]
                    || skipMoves != 0 && (skipMoves & 1 << m) != 0 {
                    continue
                }

                var prun = nodeUD[maxl].doMovePrun(node, m, true)
                if prun > maxl {
                    break
                } else if prun == maxl {
                    continue
                }

                if Search.USE_CONJ_PRUN {
                    prun = nodeUD[maxl].doMovePrunConj(node, m)
                    if prun > maxl {
                        break
                    } else if prun == maxl {
                        continue
                    }
                }

                move[depth1 - maxl] = m
                valid1 = min(valid1, depth1 - maxl)
                let ret = phase1(nodeUD[maxl], ssym & Int(CubieCube.moveCubeSym[m] & 0xffffffff), maxl - 1, axis)
                if ret == 0 {
                    return 0
                } else if ret >= 2 {
                    break
                }
            }
        }
        return 1
    }

    func searchopt() -> String {
        var maxprun1 = 0
        var maxprun2 = 0
        for i in 0..<6 {
            urfCoordCube[i].calcPruning(false)
            if i < 3 {
                maxprun1 = max(maxprun1, urfCoordCube[i].prun)
            } else {
                maxprun2 = max(maxprun2, urfCoordCube[i].prun)
            }
        }
        urfIdx = maxprun2 > maxprun1 ? 3 : 0
        phase1Cubie[0] = urfCubieCube[urfIdx]
        for length1 in (isRec ? self.length1 : 0)..<solLen {
            self.length1 = length1
            let ud = urfCoordCube[0 + urfIdx]
            let rl = urfCoordCube[1 + urfIdx]
            let fb = urfCoordCube[2 + urfIdx]

            if ud.prun <= length1 && rl.prun <= length1 && fb.prun <= length1
                && phase1opt(ud, rl, fb, selfSym, length1, -1) == 0 {
                return solution == nil ? "Error 8" : solution!.toStringValue(Search.INVERSE_SOLUTION, Search.USE_SEPARATOR, Search.APPEND_LENGTH)
            }
        }
        return solution == nil ? "Error 7" : solution!.toStringValue(Search.INVERSE_SOLUTION, Search.USE_SEPARATOR, Search.APPEND_LENGTH)
    }

    /// 0: 找到或超探测上限;1: 试下一个 power;2: 试下一个 axis
    func phase1opt(_ ud: CoordCubeNode, _ rl: CoordCubeNode, _ fb: CoordCubeNode,
                   _ ssym: UInt64, _ maxl: Int, _ lmIn: Int) -> Int {
        var lm = lmIn
        if ud.prun == 0 && rl.prun == 0 && fb.prun == 0 && maxl < 5 {
            maxDep2 = maxl
            depth1 = length1 - maxl
            return initPhase2Pre() == 0 ? 0 : 1
        }

        let skipMoves = CubieCube.getSkipMoves(ssym)

        for axis in stride(from: 0, to: 18, by: 3) {
            if axis == lm || axis == lm - 9 {
                continue
            }
            for power in 0..<3 {
                var m = axis + power

                if isRec && m != move[length1 - maxl]
                    || skipMoves != 0 && (skipMoves & 1 << m) != 0 {
                    continue
                }

                // UD 轴
                var prun_ud = max(nodeUD[maxl].doMovePrun(ud, m, false),
                                  Search.USE_CONJ_PRUN ? nodeUD[maxl].doMovePrunConj(ud, m) : 0)
                if prun_ud > maxl {
                    break
                } else if prun_ud == maxl {
                    continue
                }

                // RL 轴
                m = CubieCube.urfMove[2][m]

                var prun_rl = max(nodeRL[maxl].doMovePrun(rl, m, false),
                                  Search.USE_CONJ_PRUN ? nodeRL[maxl].doMovePrunConj(rl, m) : 0)
                if prun_rl > maxl {
                    break
                } else if prun_rl == maxl {
                    continue
                }

                // FB 轴
                m = CubieCube.urfMove[2][m]

                var prun_fb = max(nodeFB[maxl].doMovePrun(fb, m, false),
                                  Search.USE_CONJ_PRUN ? nodeFB[maxl].doMovePrunConj(fb, m) : 0)
                if prun_ud == prun_rl && prun_rl == prun_fb && prun_fb != 0 {
                    prun_fb += 1
                }

                if prun_fb > maxl {
                    break
                } else if prun_fb == maxl {
                    continue
                }

                m = CubieCube.urfMove[2][m]

                move[length1 - maxl] = m
                valid1 = min(valid1, length1 - maxl)
                let ret = phase1opt(nodeUD[maxl], nodeRL[maxl], nodeFB[maxl], ssym & CubieCube.moveCubeSym[m], maxl - 1, axis)
                if ret == 0 {
                    return 0
                }
            }
        }
        return 1
    }

    /// -1: 未找到;X: 比预期短 X 步的解,解长 = depth - X
    func phase2(_ edgeIn: Int, _ esymIn: Int, _ cornIn: Int, _ csymIn: Int, _ midIn: Int,
                _ maxl: Int, _ depth: Int, _ lm: Int) -> Int {
        let edge = edgeIn, esym = esymIn, corn = cornIn, csym = csymIn, mid = midIn
        if edge == 0 && corn == 0 && mid == 0 {
            return maxl
        }
        let moveMask = MUtil.ckmv2bit[lm]
        var m = 0
        while m < 10 {
            if (moveMask >> m & 1) != 0 {
                // Java for 循环 continue 仍执行 m++;0x42>>m&3 在 m≥3 时为 0,缺自增即死循环
                m += 0x42 >> m & 3
                m += 1
                continue
            }
            let midx = CoordCube.MPermMove[mid][m]
            var cornx = CoordCube.CPermMove[corn][CubieCube.SymMoveUD[csym][m]]
            let csymx = CubieCube.SymMult[cornx & 0xf][csym]
            cornx >>= 4
            var edgex = CoordCube.EPermMove[edge][CubieCube.SymMoveUD[esym][m]]
            let esymx = CubieCube.SymMult[edgex & 0xf][esym]
            edgex >>= 4
            let edgei = CubieCube.getPermSymInv(edgex, esymx, false)
            let corni = CubieCube.getPermSymInv(cornx, csymx, true)

            var prun = CoordCube.getPruning(CoordCube.EPermCCombPPrun,
                                            (edgei >> 4) * CoordCube.N_COMB + CoordCube.CCombPConj[CubieCube.Perm2CombP[corni >> 4] & 0xff][CubieCube.SymMultInv[edgei & 0xf][corni & 0xf]])
            if prun > maxl + 1 {
                return maxl - prun + 1
            } else if prun >= maxl {
                m += 0x42 >> m & 3 & (maxl - prun)
                m += 1
                continue
            }
            prun = max(
                CoordCube.getPruning(CoordCube.MCPermPrun,
                                     cornx * CoordCube.N_MPERM + CoordCube.MPermConj[midx][csymx]),
                CoordCube.getPruning(CoordCube.EPermCCombPPrun,
                                     edgex * CoordCube.N_COMB + CoordCube.CCombPConj[CubieCube.Perm2CombP[cornx] & 0xff][CubieCube.SymMultInv[esymx][csymx]]))
            if prun >= maxl {
                m += 0x42 >> m & 3 & (maxl - prun)
                m += 1
                continue
            }
            let ret = phase2(edgex, esymx, cornx, csymx, midx, maxl - 1, depth + 1, m)
            if ret >= 0 {
                move[depth] = MUtil.ud2std[m]
                return ret
            }
            if ret < -2 {
                break
            }
            if ret < -1 {
                m += 0x42 >> m & 3
            }
            m += 1
        }
        return -1
    }
}
