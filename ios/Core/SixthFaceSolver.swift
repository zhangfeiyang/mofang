import Foundation

// Port of com.mofang.cubear.SixthFaceSolver.java
// 推算没人扫到的那个面。
//
// 五个面恰好留下九张贴纸未见,魔方自身结构锁定它们:每对棱块颜色、每组角块三色唯一
// 确定该块——可见块清点后,剩下的恰好是触及缺失面的块,每块只剩一张贴纸待定。
//
// 五个已扫面仍带第六色的贴纸;仅按五个中心命名会把它们强行归给邻居(多为黄归橙),
// 块检查会拒绝完全良好的扫描。缺失色的原型按五个中心呈现的 Lab 偏移平移标准读数,
// 再用比该估计更近于任何已扫中心的贴块精化。
//
// 错误的相对滚转会在第一处不可能/重复的块上死掉,因此遍历五个面的四种滚转代价很小。
// 第六面并不总是唯一——剩余末层棱可能共享可见色——多个合法补全都存活时,调用方必须
// 再看一眼而非猜测。

enum SixthFaceSolver {
    static let FACES = "URFDLB"
    private static let FACE_CHARS: [Character] = Array("URFDLB")
    static let NAMING_RETRIES = 6
    /// 贴块要比任何已扫原型更接近缺失色估计 OUTLIER_MARGIN 以上,才被采信为该色的样本。
    private static let OUTLIER_MARGIN: Float = 8

    /// 标准 URFDLB 布局中每角/棱块的 facelet 下标(0..53)。
    static let CORNER_FACELETS: [[Int]] = [
        [8, 9, 20], [6, 18, 38], [0, 36, 47], [2, 45, 11],
        [29, 26, 15], [27, 44, 24], [33, 53, 42], [35, 17, 51],
    ]
    static let EDGE_FACELETS: [[Int]] = [
        [5, 10], [7, 19], [3, 37], [1, 46], [32, 16], [28, 25],
        [30, 43], [34, 52], [23, 12], [21, 41], [50, 39], [48, 14],
    ]

    final class Result {
        /// 补全推算面后的合法 54-facelet 状态。
        let state: String
        /// 第六面学到之前,按五个已扫原型命名实时帧。
        let palette: ScanPalette

        init(state: String, palette: ScanPalette) {
            self.state = state
            self.palette = palette
        }
    }

    /// - Parameter faces: 恰好五个观察,每个带 Lab 读数
    static func solve(_ faces: [FaceSample?]) -> Result? {
        solve(faces, deadlineNanos: Double.greatestFiniteMagnitude)
    }

    /// deadlineNanos(纳秒)到即放弃。读数不一致会让补全搜索在每个命名下耗尽节点预算;
    /// 观察累积后调用方会重试,有界拒绝代价很小。
    static func solve(_ faces: [FaceSample?], deadlineNanos: Double) -> Result? {
        if faces.count != 5 { return nil }
        var prototypes = [[Float]](repeating: [], count: 5)
        for i in 0..<5 {
            guard let face = faces[i], face.lab != nil, face.centerReliable else { return nil }
            prototypes[i] = face.lab![4]
        }

        for naming in 0...NAMING_RETRIES {
            if nowNanos() > deadlineNanos { return nil }
            guard let names = ColorAssignment.namePrototypes(prototypes, naming) else { break }
            guard let missingColor = leftover(names) else { continue }

            var sixth = adaptedPrototype(prototypes, names, missingColor)
            sixth = refineFromOutliers(faces.compactMap { $0 }, prototypes, sixth)

            // 先严格:每张可读贴纸保留名字,唯一合法补全直接返回。只有严格搜索一无所获
            // ——错标贴纸的特征,一个错标否决所有补全——第二轮才把每面残差最差的格子
            // 置为无名,让块约束重新推演。
            for untrust in [0, SixthFaceSolver.UNTRUST_PER_FACE] {
                let named = nameStickers(faces.compactMap { $0 }, prototypes, names,
                                         missingColor, sixth, untrust)
                var found: [String] = []
                var foundSet = Set<String>()
                var budget = [Double(Double(SixthFaceSolver.NODE_BUDGET)), deadlineNanos]
                var slots = [FaceSample?](repeating: nil, count: 6)
                searchRolls(named, names, &slots, 0, missingColor.face, &foundSet, &budget)
                found = Array(foundSet)
                // 五个面不一定锁定第六面——剩余 D 层棱可能共享可见色,两个补全都通过
                // 求解。猜会还原成错误的魔方,因此主命名有歧义即止;只有这种着色一无所获
                // 时才尝试后续命名。
                if found.count > 1 {
                    if naming == 0 { return nil }
                    break
                }
                if found.count == 1 {
                    return Result(state: found[0],
                                  palette: ScanPalette.fromFive(prototypes, names, missingColor))
                }
            }
        }
        return nil
    }

    /// 五个原型都没有被命名到的那个标准色。
    private static func leftover(_ names: [CubeColor]) -> CubeColor? {
        var seen = [Bool](repeating: false, count: FACES.count)
        for name in names {
            guard let index = FACE_CHARS.firstIndex(of: name.face), !seen[index] else { return nil }
            seen[index] = true
        }
        var missingIndex = -1
        for i in 0..<seen.count where !seen[i] {
            if missingIndex >= 0 { return nil }
            missingIndex = i
        }
        guard missingIndex >= 0 else { return nil }
        return CubeColor.fromFace(FACE_CHARS[missingIndex])
    }

    /// 缺失色的标准读数,按五个已扫中心相对自身标准读数的平均 Lab 偏移平移。
    /// 温暖的房间会整体移动所有贴纸,第六色的预期外观随之而变。
    private static func adaptedPrototype(_ prototypes: [[Float]], _ names: [CubeColor],
                                         _ missing: CubeColor) -> [Float] {
        var offset = [Float](repeating: 0, count: 3)
        for i in 0..<prototypes.count {
            let canonical = labOf(names[i])
            for channel in 0..<3 {
                offset[channel] += prototypes[i][channel] - canonical[channel]
            }
        }
        var sixth = labOf(missing)
        let count = Float(prototypes.count)
        for channel in 0..<3 { sixth[channel] += offset[channel] / count }
        return sixth
    }

    /// 用比它更近于缺失色估计、比任何已扫中心更远的贴块均值替换适配估计。这些贴块正是
    /// 打乱魔方上的缺失色,用它们做原型是测量这颗魔方在这盏灯下,而不是猜测。
    private static func refineFromOutliers(_ faces: [FaceSample], _ known: [[Float]],
                                           _ sixth: [Float]) -> [Float] {
        var sum = [Float](repeating: 0, count: 3)
        var count = 0
        for face in faces {
            for cell in 0..<9 where face.reliable[cell] {
                let toSixth = Lab.distance(face.lab![cell], sixth)
                var toKnown = Float.greatestFiniteMagnitude
                for prototype in known {
                    toKnown = min(toKnown, Lab.distance(face.lab![cell], prototype))
                }
                if toSixth + OUTLIER_MARGIN < toKnown {
                    for channel in 0..<3 { sum[channel] += face.lab![cell][channel] }
                    count += 1
                }
            }
        }
        if count < 2 { return sixth }
        return [sum[0] / Float(count), sum[1] / Float(count), sum[2] / Float(count)]
    }

    private static func labOf(_ color: CubeColor) -> [Float] {
        let rgb = color.rgb
        return Lab.fromRgb(Int((rgb >> 16) & 0xFF), Int((rgb >> 8) & 0xFF), Int(rgb & 0xFF))
    }

    /// 重试级:每面留给块约束重推的无名格子数。分配的容量受害者——其颜色不得不让位的
    /// 贴纸——总排在其面残差榜第一,所以只置无名那一格恰好移除破块因素。严格搜索先跑;
    /// 漂移合成扫描上这对组合恢复 39/40,严格单独只有 28/40,干净扫描仍然精确解析。
    private static let UNTRUST_PER_FACE = 1

    private static func nameStickers(_ faces: [FaceSample], _ prototypes: [[Float]],
                                     _ names: [CubeColor], _ missing: CubeColor, _ sixth: [Float],
                                     _ untrustPerFace: Int) -> [FaceSample] {
        let FORBIDDEN = 1e9
        let slots = prototypes.count * 9 + 8
        var slotColor = [CubeColor](repeating: .unknown, count: slots)
        var slotPrototype = [[Float]](repeating: [], count: slots)
        var nextSlot = 0
        for p in 0..<prototypes.count {
            for _ in 0..<9 {
                slotColor[nextSlot] = names[p]
                slotPrototype[nextSlot] = prototypes[p]
                nextSlot += 1
            }
        }
        for _ in 0..<8 {
            slotColor[nextSlot] = missing
            slotPrototype[nextSlot] = sixth
            nextSlot += 1
        }

        // 每个可读贴块一行;不可靠贴块不带颜色证据,不占槽。
        var rows = 0
        for face in faces {
            for cell in 0..<9 where face.reliable[cell] { rows += 1 }
        }
        var cost = [[Double]](repeating: [Double](repeating: 0, count: slots), count: rows)
        // nil = 待定(可靠但未命名),UNKNOWN = 不可靠 —— Java 靠 null/UNKNOWN 区分。
        var stickers = [[CubeColor?]](repeating: [CubeColor?](repeating: nil, count: 9), count: faces.count)
        var assignedResidual = [Float](repeating: 0, count: rows)
        var faceResidual = [Float](repeating: 0, count: 9)
        var faceRows = [Int](repeating: -1, count: 9)
        var row = 0
        for f in 0..<faces.count {
            let own = names[f]
            for cell in 0..<9 {
                if !faces[f].reliable[cell] {
                    stickers[f][cell] = .unknown
                    continue
                }
                for s in 0..<slots {
                    cost[row][s] = (cell == 4 && slotColor[s] != own)
                        ? FORBIDDEN
                        : Double(Lab.distanceSquared(faces[f].lab![cell], slotPrototype[s]))
                }
                row += 1
            }
        }
        let assignment = Hungarian.solve(cost)

        var named = [FaceSample?](repeating: nil, count: faces.count)
        row = 0
        for f in 0..<faces.count {
            // 每面读数最差的格子正是错标所在;按残差置无名前 K 个(有下限),让块约束重推。
            for cell in 0..<9 { faceRows[cell] = -1 }
            for cell in 0..<9 {
                // 跳过不可靠贴纸(nil = 可靠待命名,必须处理;Java 靠 null != UNKNOWN 区分)
                if stickers[f][cell] == .unknown { continue }
                let slot = assignment[row]
                assignedResidual[row] = Lab.distance(faces[f].lab![cell], slotPrototype[slot])
                faceRows[cell] = row
                faceResidual[cell] = assignedResidual[row]
                row += 1
            }
            var marked = 0
            while marked < untrustPerFace {
                var worst = -1
                var worstValue: Float = -1
                for cell in 0..<9 where faceRows[cell] >= 0 {
                    if faceResidual[cell] > worstValue {
                        worstValue = faceResidual[cell]
                        worst = cell
                    }
                }
                if worst < 0 { break }
                stickers[f][worst] = .unknown
                faceRows[worst] = -1
                marked += 1
            }
            for cell in 0..<9 where stickers[f][cell] == .unknown && faceRows[cell] >= 0 {
                stickers[f][cell] = slotColor[assignment[faceRows[cell]]]
            }
            for cell in 0..<9 where faceRows[cell] >= 0 {
                stickers[f][cell] = slotColor[assignment[faceRows[cell]]]
            }
            let finalStickers = stickers[f].map { $0 ?? CubeColor.unknown }
            named[f] = FaceSample(finalStickers, faces[f].lab, faces[f].reliable, faces[f].confidence)
        }
        return named.compactMap { $0 }
    }

    /// 遍历五个面的未知滚转组合;每面中心固定。
    private static func searchRolls(_ named: [FaceSample], _ names: [CubeColor],
                                    _ slots: inout [FaceSample?], _ index: Int, _ missing: Character,
                                    _ found: inout Set<String>, _ budget: inout [Double]) {
        if budget[0] <= 0 { return }
        if index == named.count {
            var sig = ""
            for f in 0..<6 {
                if let slot = slots[f] { sig += "[\(slot.signature)]" } else { sig += "(nil)" }
            }
        }
            var state = [Character](repeating: "?", count: 54)
            for face in 0..<FACE_CHARS.count {
                let slot = slots[face]
                for cell in 0..<9 {
                    if slot == nil {
                        state[face * 9 + cell] = cell == 4 ? missing : "?"
                    } else if slot!.stickers[cell] == .unknown {
                        state[face * 9 + cell] = "?"
                    } else {
                        state[face * 9 + cell] = slot!.stickers[cell].face
                    }
                }
            }
            fillMissing(&state, &found, &budget)
            return
        }
        guard let slotIdx = FACE_CHARS.firstIndex(of: names[index].face) else { return }
        var rotated = named[index]
        for _ in 0..<4 {
            slots[slotIdx] = rotated
            searchRolls(named, names, &slots, index + 1, missing, &found, &budget)
            rotated = rotated.rotateClockwise()
        }
    }

    /// 排列在放弃前允许多少个完全可见块识别失败。
    ///
    /// 烧掉的块意味着一张贴纸标签可疑;该块不认领,贴纸保持命名。最终校验会评判得到的
    /// 魔方,烧掉但其实正确的块没有代价,错标的块只是校验失败而非否决整个搜索。
    private static let MAX_BURNED_EDGES = 2
    private static let MAX_BURNED_CORNERS = 2
    /// 一次命名尝试可花的搜索节点数。健康的扫描需要几百;中毒或多洞的会组合爆炸,调用方
    /// 有自己的重试阶梯——触顶只意味着这种着色一无所获。
    private static let NODE_BUDGET = 60_000

    /// 从 budget = {nodes, deadline} 取一个节点;任一耗尽即 false。
    private static func spend(_ budget: inout [Double]) -> Bool {
        if budget[0] <= 0 { return false }
        budget[0] -= 1
        if Int(budget[0]) & 1023 == 0 && nowNanos() > budget[1] { budget[0] = 0 }
        return true
    }

    /// 补全每个 '?' facelet 或拒绝该摆法。
    ///
    /// 先识别并勾掉完全可见的块;错误的相对滚转在这里产生不可能或重复的块。无法识别的
    /// 块(预算内)烧掉而非否决摆法——真实读数带着几张错名贴纸。每处至少一张可读贴纸
    /// 但非全部的位置——缺失面的边缘,以及可疑贴纸留下的无名格——是开放位置,未用的
    /// 块按唯一性规则枚举安放,直到补全的 54 字符串通过结构检查与求解器校验。记录每个
    /// 合法补全:第六面不总唯一,返回第一个会悄悄还原错误的魔方。
    private static func fillMissing(_ state: inout [Character], _ found: inout Set<String>,
                                    _ budget: inout [Double]) {
        if !spend(&budget) { return }
        dbgFills += 1
        var edgeUsed = [Bool](repeating: false, count: 12)
        var cornerUsed = [Bool](repeating: false, count: 8)
        var openEdges: [Int] = []
        var openCorners: [Int] = []
        var burnedEdges = 0
        var burnedCorners = 0
        for piece in 0..<12 {
            let a = EDGE_FACELETS[piece][0], b = EDGE_FACELETS[piece][1]
            let ka = state[a] != "?", kb = state[b] != "?"
            if ka && kb {
                let identified = edgePiece(state[a], state[b])
                if identified < 0 || edgeUsed[identified] {
                    burnedEdges += 1
                    if burnedEdges > MAX_BURNED_EDGES {
                        return
                    }
                    continue
                }
                edgeUsed[identified] = true
            } else if ka || kb {
                openEdges.append(piece)
            } else {
                return
            }
        }
        for piece in 0..<8 {
            var known = 0
            for facelet in CORNER_FACELETS[piece] where state[facelet] != "?" { known += 1 }
            if known == 3 {
                let identified = cornerPiece(state, CORNER_FACELETS[piece])
                if identified < 0 || cornerUsed[identified] {
                    burnedCorners += 1
                    if burnedCorners > MAX_BURNED_CORNERS { return }
                    continue
                }
                cornerUsed[identified] = true
            } else if known >= 1 {
                openCorners.append(piece)
            } else {
                return
            }
        }
        placeEdges(&state, openEdges, 0, &edgeUsed, openCorners, &cornerUsed, &found, &budget)
    }

    /// 深度优先把未用棱块放到开放位置;角块随后。
    private static func placeEdges(_ state: inout [Character], _ openEdges: [Int], _ edgeIndex: Int,
                                   _ edgeUsed: inout [Bool], _ openCorners: [Int],
                                   _ cornerUsed: inout [Bool], _ found: inout Set<String>,
                                   _ budget: inout [Double]) {
        if !spend(&budget) { return }
        if edgeIndex == openEdges.count {
            placeCorners(&state, openCorners, 0, &cornerUsed, &found, &budget)
            return
        }
        let position = openEdges[edgeIndex]
        let knownSide = state[EDGE_FACELETS[position][0]] != "?" ? 0 : 1
        let known = state[EDGE_FACELETS[position][knownSide]]
        let unknown = EDGE_FACELETS[position][1 - knownSide]
        for piece in 0..<12 {
            if edgeUsed[piece] { continue }
            let colors = edgeColors(piece)
            var other: Character
            if colors[0] == known {
                other = colors[1]
            } else if colors[1] == known {
                other = colors[0]
            } else {
                continue
            }
            edgeUsed[piece] = true
            state[unknown] = other
            placeEdges(&state, openEdges, edgeIndex + 1, &edgeUsed, openCorners, &cornerUsed, &found, &budget)
            state[unknown] = "?"
            edgeUsed[piece] = false
        }
    }

    private static func placeCorners(_ state: inout [Character], _ openCorners: [Int], _ index: Int,
                                     _ cornerUsed: inout [Bool], _ found: inout Set<String>,
                                     _ budget: inout [Double]) {
        if !spend(&budget) { return }
        if index == openCorners.count {
            let candidate = String(state)
            // 廉价的结构检查先行,省得跑求解器自己的表。
            if CubeRules.piecesArePlausible(candidate) && MTools.verify(candidate) == 0 {
                found.insert(candidate)
                // 两个合法补全已意味着"有歧义";剩余搜索只会确认,差的命名下它占大头。
                if found.count > 1 { budget[0] = 0 }
            }
            return
        }
        let position = openCorners[index]
        var knownCount = 0
        for facelet in CORNER_FACELETS[position] where state[facelet] != "?" { knownCount += 1 }
        // 精确尺寸:多余的 0 会被读成 facelet 0 而破坏搜索。
        var known = [Character](repeating: " ", count: knownCount)
        var unknowns = [Int](repeating: -1, count: 3 - knownCount)
        var knownNext = 0, unknownNext = 0
        for facelet in CORNER_FACELETS[position] {
            if state[facelet] != "?" {
                known[knownNext] = state[facelet]
                knownNext += 1
            } else {
                unknowns[unknownNext] = facelet
                unknownNext += 1
            }
        }
        for piece in 0..<8 {
            if cornerUsed[piece] { continue }
            let colors = cornerColors(piece)
            // 该位置的每个已知颜色都必须落在候选块上;其余颜色按所有顺序放到开放 facelet。
            var taken = [Bool](repeating: false, count: 3)
            var matches = true
            for k in 0..<knownCount where matches {
                var foundColor = false
                for c in 0..<3 where !taken[c] && colors[c] == known[k] {
                    taken[c] = true
                    foundColor = true
                    break
                }
                if !foundColor { matches = false }
            }
            if !matches { continue }
            var rest = [Character](repeating: " ", count: 3 - knownCount)
            var r = 0
            for c in 0..<3 where !taken[c] {
                rest[r] = colors[c]
                r += 1
            }
            cornerUsed[piece] = true
            placeRest(&state, &unknowns, rest, 0, openCorners, index, &cornerUsed, &found, &budget)
            cornerUsed[piece] = false
        }
    }

    /// 把选中块的其余颜色按所有顺序放到开放 facelet。
    private static func placeRest(_ state: inout [Character], _ unknowns: inout [Int], _ rest: [Character],
                                  _ restIndex: Int, _ openCorners: [Int], _ cornerIndex: Int,
                                  _ cornerUsed: inout [Bool], _ found: inout Set<String>,
                                  _ budget: inout [Double]) {
        if !spend(&budget) { return }
        if restIndex == rest.count {
            placeCorners(&state, openCorners, cornerIndex + 1, &cornerUsed, &found, &budget)
            return
        }
        for u in 0..<unknowns.count {
            if unknowns[u] < 0 { continue }
            let save = state[unknowns[u]]
            state[unknowns[u]] = rest[restIndex]
            unknowns[u] = -unknowns[u] - 1 // 标记已用
            placeRest(&state, &unknowns, rest, restIndex + 1, openCorners, cornerIndex, &cornerUsed, &found, &budget)
            unknowns[u] = -unknowns[u] - 1 // 恢复
            state[unknowns[u]] = save
        }
    }

    /// 携带颜色 {a, b} 的棱块;没有能显示该对的棱块时为 -1。
    private static func edgePiece(_ a: Character, _ b: Character) -> Int {
        for piece in 0..<12 {
            let colors = edgeColors(piece)
            if (colors[0] == a && colors[1] == b) || (colors[0] == b && colors[1] == a) {
                return piece
            }
        }
        return -1
    }

    private static func cornerPiece(_ state: [Character], _ facelets: [Int]) -> Int {
        var observed = [Character](repeating: " ", count: 3)
        for i in 0..<3 { observed[i] = state[facelets[i]] }
        let sorted = observed.sorted()
        for piece in 0..<8 {
            let colors = cornerColors(piece).sorted()
            if sorted == colors { return piece }
        }
        return -1
    }

    private static func edgeColors(_ piece: Int) -> [Character] {
        [FACE_CHARS[EDGE_FACELETS[piece][0] / 9], FACE_CHARS[EDGE_FACELETS[piece][1] / 9]]
    }

    private static func cornerColors(_ piece: Int) -> [Character] {
        [FACE_CHARS[CORNER_FACELETS[piece][0] / 9], FACE_CHARS[CORNER_FACELETS[piece][1] / 9],
         FACE_CHARS[CORNER_FACELETS[piece][2] / 9]]
    }

    private static var dbgFills = 0
    static var dbgSR = 0


    private static func nowNanos() -> Double {
        Double(DispatchTime.now().uptimeNanoseconds)
    }
}
