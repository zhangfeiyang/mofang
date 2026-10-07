import Foundation

// Port of com.mofang.cubear.CubeRules.java
// 魔方自身的结构约束,用于拒绝并定位误读。
//
// 扫描结果不是 54 个独立的颜色。每个角块连接三个互相相邻的面、每个棱块连接两个,
// 所以"白黄红"角块或"红橙"棱块无论如何都不可能存在。检查这一点能抓住颜色匹配后
// 仍然存活的坏贴纸,并指出哪张贴纸有问题,而不只是说整个扫描失败。

enum CubeRules {
    /// 每个角块的三张贴纸的 facelet 下标,标准 URFDLB 布局。
    static let CORNERS: [[Int]] = [
        [8, 9, 20], [6, 18, 38], [0, 36, 47], [2, 45, 11],
        [29, 26, 15], [27, 44, 24], [33, 53, 42], [35, 17, 51],
    ]
    /// 每个棱块的两张贴纸的 facelet 下标。
    static let EDGES: [[Int]] = [
        [5, 10], [7, 19], [3, 37], [1, 46], [32, 16], [28, 25],
        [30, 43], [34, 52], [23, 12], [21, 41], [50, 39], [48, 14],
    ]
    /// 互为对面、因此绝不会共属一块的面的字母。
    private static let OPPOSITE = ["UD", "RL", "FB"]

    static func areOpposite(_ a: Character, _ b: Character) -> Bool {
        for pair in OPPOSITE {
            if pair.contains(a), pair.contains(b) { return a != b }
        }
        return false
    }

    /// 中心块定义六个颜色,重复意味着两个面被读成了同一颜色。
    static func centersAreDistinct(_ state: String) -> Bool {
        let chars = Array(state)
        var seen = Set<Character>()
        for face in 0..<6 {
            let centre = chars[face * 9 + 4]
            if seen.contains(centre) { return false }
            seen.insert(centre)
        }
        return true
    }

    /// 逐贴纸统计它参与违反的结构规则数。全零意味着读数至少在块级别自洽;
    /// 计数最高的贴纸最值得重新判定。
    static func violations(_ state: String) -> [Int] {
        let chars = Array(state)
        var blame = [Int](repeating: 0, count: 54)
        for corner in CORNERS {
            let a = chars[corner[0]], b = chars[corner[1]], c = chars[corner[2]]
            if a == b || b == c || a == c
                || areOpposite(a, b) || areOpposite(b, c) || areOpposite(a, c) {
                for index in corner { blame[index] += 1 }
            }
        }
        for edge in EDGES {
            let a = chars[edge[0]], b = chars[edge[1]]
            if a == b || areOpposite(a, b) {
                for index in edge { blame[index] += 1 }
            }
        }
        // 真实魔方的每个角块和棱块都唯一;重复意味着误读。
        blamePieceDuplicates(chars, CORNERS, &blame)
        blamePieceDuplicates(chars, EDGES, &blame)
        return blame
    }

    private static func blamePieceDuplicates(_ chars: [Character], _ pieces: [[Int]], _ blame: inout [Int]) {
        var signatures = [String](repeating: "", count: pieces.count)
        for i in 0..<pieces.count {
            var colors = [Character](repeating: "?", count: pieces[i].count)
            for j in 0..<pieces[i].count { colors[j] = chars[pieces[i][j]] }
            signatures[i] = String(colors.sorted())
        }
        for i in 0..<pieces.count {
            for j in (i + 1)..<pieces.count where signatures[i] == signatures[j] {
                for index in pieces[i] { blame[index] += 1 }
                for index in pieces[j] { blame[index] += 1 }
            }
        }
    }

    /// 没有任何角块或棱块违反结构规则时为真。
    static func piecesArePlausible(_ state: String) -> Bool {
        if !centersAreDistinct(state) { return false }
        for count in violations(state) where count > 0 { return false }
        return true
    }

    /// 结构违规总数,用于给候选读数排序。
    static func violationCount(_ state: String) -> Int {
        violations(state).reduce(0, +)
    }
}
