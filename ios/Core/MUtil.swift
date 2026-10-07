import Foundation

// Port of cs.min2phase.Util.java
// 64 位位技巧部分用 UInt64 位模式等价实现(Java 的算术右移在本用法下与逻辑右移等价,
// 因为结果只保留最低 4 位)。

enum MUtil {
    // Moves
    static let Ux1: Int = 0, Ux2 = 1, Ux3 = 2
    static let Rx1: Int = 3, Rx2 = 4, Rx3 = 5
    static let Fx1: Int = 6, Fx2 = 7, Fx3 = 8
    static let Dx1: Int = 9, Dx2 = 10, Dx3 = 11
    static let Lx1: Int = 12, Lx2 = 13, Lx3 = 14
    static let Bx1: Int = 15, Bx2 = 16, Bx3 = 17

    // Facelets
    static let U1 = 0, U2 = 1, U3 = 2, U4 = 3, U5 = 4, U6 = 5, U7 = 6, U8 = 7, U9 = 8
    static let R1 = 9, R2 = 10, R3 = 11, R4 = 12, R5 = 13, R6 = 14, R7 = 15, R8 = 16, R9 = 17
    static let F1 = 18, F2 = 19, F3 = 20, F4 = 21, F5 = 22, F6 = 23, F7 = 24, F8 = 25, F9 = 26
    static let D1 = 27, D2 = 28, D3 = 29, D4 = 30, D5 = 31, D6 = 32, D7 = 33, D8 = 34, D9 = 35
    static let L1 = 36, L2 = 37, L3 = 38, L4 = 39, L5 = 40, L6 = 41, L7 = 42, L8 = 43, L9 = 44
    static let B1 = 45, B2 = 46, B3 = 47, B4 = 48, B5 = 49, B6 = 50, B7 = 51, B8 = 52, B9 = 53

    // Colors
    static let U = 0, R = 1, F = 2, D = 3, L = 4, B = 5

    static let cornerFacelet: [[Int]] = [
        [U9, R1, F3], [U7, F1, L3], [U1, L1, B3], [U3, B1, R3],
        [D3, F9, R7], [D1, L9, F7], [D7, B9, L7], [D9, R9, B7],
    ]
    static let edgeFacelet: [[Int]] = [
        [U6, R2], [U8, F2], [U4, L2], [U2, B2], [D6, R8], [D2, F8],
        [D4, L8], [D8, B8], [F6, R4], [F4, L6], [B6, L4], [B4, R6],
    ]

    static var Cnk = [[Int]](repeating: [Int](repeating: 0, count: 13), count: 13)
    static let move2str = [
        "U ", "U2", "U'", "R ", "R2", "R'", "F ", "F2", "F'",
        "D ", "D2", "D'", "L ", "L2", "L'", "B ", "B2", "B'",
    ]
    static let ud2std: [Int] = [Ux1, Ux2, Ux3, Rx2, Fx2, Dx1, Dx2, Dx3, Lx2, Bx2, Rx1, Rx3, Fx1, Fx3, Lx1, Lx3, Bx1, Bx3]
    static var std2ud = [Int](repeating: 0, count: 18)
    static var ckmv2bit = [Int](repeating: 0, count: 11)

    final class Solution {
        var length = 0
        var depth1 = 0
        var verbose = 0
        var urfIdx = 0
        var moves = [Int](repeating: 0, count: 31)

        func setArgs(_ verbose: Int, _ urfIdx: Int, _ depth1: Int) {
            self.verbose = verbose
            self.urfIdx = urfIdx
            self.depth1 = depth1
        }

        func appendSolMove(_ curMove: Int) {
            if length == 0 {
                moves[length] = curMove
                length += 1
                return
            }
            let axisCur = curMove / 3
            let axisLast = moves[length - 1] / 3
            if axisCur == axisLast {
                let pow = (curMove % 3 + moves[length - 1] % 3 + 1) % 4
                if pow == 3 {
                    length -= 1
                } else {
                    moves[length - 1] = axisCur * 3 + pow
                }
                return
            }
            if length > 1
                && axisCur % 3 == axisLast % 3
                && axisCur == moves[length - 2] / 3 {
                let pow = (curMove % 3 + moves[length - 2] % 3 + 1) % 4
                if pow == 3 {
                    moves[length - 2] = moves[length - 1]
                    length -= 1
                } else {
                    moves[length - 2] = axisCur * 3 + pow
                }
                return
            }
            moves[length] = curMove
            length += 1
        }

        func toStringValue(_ inverseFlag: Int, _ separatorFlag: Int, _ lengthFlag: Int) -> String {
            var sb = ""
            let urf = (verbose & inverseFlag) != 0 ? (urfIdx + 3) % 6 : urfIdx
            if urf < 3 {
                for s in 0..<length {
                    if (verbose & separatorFlag) != 0 && s == depth1 { sb += ".  " }
                    sb += MUtil.move2str[CubieCube.urfMove[urf][moves[s]]] + " "
                }
            } else {
                for s in stride(from: length - 1, through: 0, by: -1) {
                    sb += MUtil.move2str[CubieCube.urfMove[urf][moves[s]]] + " "
                    if (verbose & separatorFlag) != 0 && s == depth1 { sb += ".  " }
                }
            }
            if (verbose & lengthFlag) != 0 {
                sb += "(\(length)f)"
            }
            return sb
        }
    }

    static func toCubieCube(_ f: [Int], _ ccRet: CubieCube) {
        for i in 0..<8 { ccRet.ca[i] = 0 }
        for i in 0..<12 { ccRet.ea[i] = 0 }
        for i in 0..<8 {
            var ori = 0
            while ori < 3 {
                if f[cornerFacelet[i][ori]] == U || f[cornerFacelet[i][ori]] == D { break }
                ori += 1
            }
            let col1 = f[cornerFacelet[i][(ori + 1) % 3]]
            let col2 = f[cornerFacelet[i][(ori + 2) % 3]]
            for j in 0..<8 {
                if col1 == cornerFacelet[j][1] / 9 && col2 == cornerFacelet[j][2] / 9 {
                    ccRet.ca[i] = (ori % 3) << 3 | j
                    break
                }
            }
        }
        for i in 0..<12 {
            for j in 0..<12 {
                if f[edgeFacelet[i][0]] == edgeFacelet[j][0] / 9
                    && f[edgeFacelet[i][1]] == edgeFacelet[j][1] / 9 {
                    ccRet.ea[i] = j << 1
                    break
                }
                if f[edgeFacelet[i][0]] == edgeFacelet[j][1] / 9
                    && f[edgeFacelet[i][1]] == edgeFacelet[j][0] / 9 {
                    ccRet.ea[i] = j << 1 | 1
                    break
                }
            }
        }
    }

    static func toFaceCube(_ cc: CubieCube) -> String {
        var f = [Character](repeating: "U", count: 54)
        let ts: [Character] = ["U", "R", "F", "D", "L", "B"]
        for i in 0..<54 { f[i] = ts[i / 9] }
        for c in 0..<8 {
            let j = cc.ca[c] & 7
            let ori = cc.ca[c] >> 3
            for n in 0..<3 {
                f[cornerFacelet[c][(n + ori) % 3]] = ts[cornerFacelet[j][n] / 9]
            }
        }
        for e in 0..<12 {
            let j = cc.ea[e] >> 1
            let ori = cc.ea[e] & 1
            for n in 0..<2 {
                f[edgeFacelet[e][(n + ori) % 2]] = ts[edgeFacelet[j][n] / 9]
            }
        }
        return String(f)
    }

    static func getNParity(_ idxIn: Int, _ n: Int) -> Int {
        var p = 0
        var idx = idxIn
        for i in stride(from: n - 2, through: 0, by: -1) {
            p ^= idx % (n - i)
            idx /= (n - i)
        }
        return p & 1
    }

    static func setVal(_ val0: Int, _ val: Int, _ isEdge: Bool) -> Int {
        isEdge ? (val << 1 | val0 & 1) : (val | val0 & ~7)
    }

    static func getVal(_ val0: Int, _ isEdge: Bool) -> Int {
        isEdge ? val0 >> 1 : val0 & 7
    }

    static func setNPerm(_ arr: inout [Int], _ idxIn: Int, _ n: Int, _ isEdge: Bool) {
        var val: UInt64 = 0xFEDCBA9876543210
        var extract: UInt64 = 0
        var idx = idxIn
        for p in 2...n {
            extract = extract << 4 | UInt64(idx % p)
            idx /= p
        }
        for i in 0..<(n - 1) {
            let v = Int(extract & 0xf) << 2
            extract >>= 4
            arr[i] = setVal(arr[i], Int((val >> UInt64(v)) & 0xf), isEdge)
            let m: UInt64 = (1 << UInt64(v)) &- 1
            val = (val & m) | (val >> 4) & ~m
        }
        arr[n - 1] = setVal(arr[n - 1], Int(val & 0xf), isEdge)
    }

    static func getNPerm(_ arr: [Int], _ n: Int, _ isEdge: Bool) -> Int {
        var idx = 0
        var val: UInt64 = 0xFEDCBA9876543210
        for i in 0..<(n - 1) {
            let v = getVal(arr[i], isEdge) << 2
            idx = (n - i) * idx + Int((val >> UInt64(v)) & 0xf)
            val = val &- (0x1111111111111110 << UInt64(v))
        }
        return idx
    }

    static func getComb(_ arr: [Int], _ mask: Int, _ isEdge: Bool) -> Int {
        let end = arr.count - 1
        var idxC = 0, r = 4
        for i in stride(from: end, through: 0, by: -1) {
            let perm = getVal(arr[i], isEdge)
            if (perm & 0xc) == mask {
                idxC += Cnk[i][r]
                r -= 1
            }
        }
        return idxC
    }

    static func setComb(_ arr: inout [Int], _ idxCIn: Int, _ mask: Int, _ isEdge: Bool) {
        let end = arr.count - 1
        var r = 4, fill = end
        var idxC = idxCIn
        for i in stride(from: end, through: 0, by: -1) {
            if idxC >= Cnk[i][r] {
                idxC -= Cnk[i][r]
                r -= 1
                arr[i] = setVal(arr[i], r | mask, isEdge)
            } else {
                if (fill & 0xc) == mask { fill -= 4 }
                arr[i] = setVal(arr[i], fill, isEdge)
                fill -= 1
            }
        }
    }

    static func initialize() {
        for i in 0..<18 { std2ud[ud2std[i]] = i }
        for i in 0..<10 {
            let ix = ud2std[i] / 3
            ckmv2bit[i] = 0
            for j in 0..<10 {
                let jx = ud2std[j] / 3
                ckmv2bit[i] |= ((ix == jx) || ((ix % 3 == jx % 3) && (ix >= jx)) ? 1 : 0) << j
            }
        }
        ckmv2bit[10] = 0
        for i in 0..<13 {
            Cnk[i][0] = 1
            Cnk[i][i] = 1
            // Java 的 for(j=1;j<i;j++) 允许 i=0 时空区间;Swift 需用 stride
            if i > 1 {
                for j in 1..<i {
                    Cnk[i][j] = Cnk[i - 1][j - 1] + Cnk[i - 1][j]
                }
            }
        }
    }
}
