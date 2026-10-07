import Foundation

// Port of cs.min2phase.Tools.java(仅保留 App 与测试用到的部分;表缓存 I/O 与
// 随机状态生成器未被使用,未移植)。

enum MTools {
    /// 把转动记号字符串转成魔方定义串。
    ///
    /// - Parameter s: 转动串,仅识别外层转动 (U, R, F, D, L, B, U2, R2, ...)
    /// - Returns: 表示该打乱状态的魔方定义串
    static func fromScramble(_ s: String) -> String {
        var arr = [Int](repeating: 0, count: s.count)
        var j = 0
        var axis = -1
        for ch in s {
            switch ch {
            case "U": axis = 0
            case "R": axis = 3
            case "F": axis = 6
            case "D": axis = 9
            case "L": axis = 12
            case "B": axis = 15
            case " ":
                if axis != -1 {
                    arr[j] = axis
                    j += 1
                }
                axis = -1
            case "2": axis += 1
            case "'": axis += 2
            default: continue
            }
        }
        if axis != -1 {
            arr[j] = axis
            j += 1
        }
        return fromScramble(Array(arr[0..<j]))
    }

    static func fromScramble(_ scramble: [Int]) -> String {
        let c1 = CubieCube()
        let c2 = CubieCube()
        for i in 0..<scramble.count {
            CubieCube.CornMult(c1, CubieCube.moveCube[scramble[i]]!, c2)
            CubieCube.EdgeMult(c1, CubieCube.moveCube[scramble[i]]!, c2)
            // Java 版交换引用;交换内容等价(c1/c2 仅本地使用)
            let tc = c1.ca, te = c1.ea
            c1.ca = c2.ca; c1.ea = c2.ea
            c2.ca = tc; c2.ea = te
        }
        return MUtil.toFaceCube(c1)
    }

    /// 检查魔方定义串 s 是否可解。
    ///
    /// 0=可解;-1 每色 facelet 不恰好一张;-2 缺棱;-3 翻棱;-4 缺角;-5 拧角;-6 奇偶。
    static func verify(_ facelets: String) -> Int {
        Search().verify(facelets)
    }
}
