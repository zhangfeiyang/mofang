import Foundation

// Port of com.mofang.cubear.CubeStateAssembler.java
// 收集面观察并解析成合法的 URFDLB facelet 串。
//
// 观察以池的方式保存,而不是到达时塞进六个固定桶。凭绝对颜色距离判断"是不是已有的面"
// 与凭绝对色相命名贴纸是同一种错误:两个中心在不同光照下恰好接近的面会碰撞、互相永久
// 覆盖,扫描永远停在五面。池按相对距离分组,由魔方自身结构决定哪种分组正确。

final class CubeStateAssembler {
    static let ORDER: [Character] = ["U", "R", "F", "D", "L", "B"]
    static let POOL_LIMIT = 40
    /// 颜色组在完成扫描前需要的观察数。单次看无法与跨界/偶发区分;两次独立一致的观察
    /// 是用户能被要求的最廉价冗余。
    static let MIN_LOOKS_PER_FACE = 2
    /// 放弃合法状态前尝试的替代原型命名数。
    static let NAMING_RETRIES = 6

    private var pool: [FaceSample] = []
    /// 一直接受的观察总数(驱逐也在内)。池的大小停在 POOL_LIMIT,无法告知重试循环
    /// 是否有任何变化:一旦满了,失败的尝试不会再重复,扫描会永远停在旧的拒绝上。
    private(set) var accepted = 0
    private var lastGroupCount = 0
    private(set) var palette: ScanPalette?
    private(set) var lastFailure = ""
    /// 见 suspect()。
    private(set) var suspect: CubeColor?
    /// 一次 assemble() 的重试阶梯剩余预算。
    private var searchBudget = 0
    /// 当前 assemble() 放弃的时间(纳秒)。读数互相矛盾的池曾每个尝试磨几秒;观察累积
    /// 后尝试会重复,有界拒绝代价很小。
    private var deadlineNanos = Double.greatestFiniteMagnitude
    static let SIX_FACE_BUDGET_NANOS: Double = 1_500_000_000

    private var timedOut = false

    /// 上一次 assemble() 是超时而非证明矛盾时为 true。
    var lastAttemptTimedOut: Bool { timedOut }

    /// 当前池的分组;池变化后为 nil。聚类是池大小的立方,UI 每帧都要已确立的颜色,
    /// 每次重算会给主线程加上几十毫秒。
    private var labelCache: [Int]?

    /// 对同一批观察的独立组装器。组装在工作线程跑几秒,扫描在主线程继续添加观察;
    /// 必须用快照而不是活池。
    func copy() -> CubeStateAssembler {
        let copy = CubeStateAssembler()
        copy.pool = pool
        copy.accepted = accepted
        copy.lastGroupCount = lastGroupCount
        copy.palette = palette
        copy.lastFailure = lastFailure
        return copy
    }

    /// 最近一次成功组装背后的颜色原型,供之后命名帧。
    func paletteValue() -> ScanPalette? { palette }

    /// 记录一次观察。发现未见过的面颜色时返回 true。
    @discardableResult
    func put(_ face: FaceSample?) -> Bool {
        guard let face = face else { return false }
        if face.lab == nil {
            if face.center == .unknown || face.containsUnknown { return false }
        } else if !face.centerReliable || !Lab.isStickerCenter(face.centerLab) {
            return false
        }
        // 这里故意没有空间分裂门。打乱的魔方上,诚实面的列是随机颜色混合,其均值与
        // 跨界四边形的两半一样远(实测:诚实 58-105 对跨界 60-90),任何绝对阈值都会
        // 拒绝诚实的注视——而每个被拒的注视都是更慢的扫描。跨界在有累积证据的地方
        // 处理:按色聚类、共识近距过滤、重试阶梯。
        pool.append(face)
        accepted += 1
        labelCache = nil
        namedCache = nil
        if pool.count > CubeStateAssembler.POOL_LIMIT { evict() }
        let groups = distinctFaceCount()
        let grew = groups > lastGroupCount
        lastGroupCount = groups
        return grew
    }

    /// 丢弃价值最低的观察腾出空间,而不是最旧的。
    ///
    /// 普通 FIFO 驱逐是个陷阱:长扫描中池满后,它会丢掉最初几个面仅有的诚实注视,颜色
    /// 无声消失,扫描退化到五个组。价值顺序:聚类器已丢弃的注视 → 未确认的单次注视
    /// (跨界看了一次,再没出现)→ 最大组最旧的注视——瘦但真实的组总能活下来。最新的
    /// 注视永远不是受害者:它可能是用户此刻展示的面的第一眼。
    private func evict() {
        let label = clusteredLabels()
        let newest = pool.count - 1
        var victim = -1
        if let label = label {
            let size = CubeStateAssembler.groupSizes(label)
            for i in 0..<newest where victim < 0 {
                if label[i] < 0 { victim = i }
            }
            for i in 0..<newest where victim < 0 {
                if size[label[i]] < CubeStateAssembler.MIN_LOOKS_PER_FACE { victim = i }
            }
            if victim < 0 {
                var largest = -1
                for id in 0..<size.count {
                    if largest < 0 || size[id] > size[largest] { largest = id }
                }
                for i in 0..<newest where victim < 0 {
                    if label[i] == largest { victim = i }
                }
            }
        }
        pool.remove(at: victim < 0 ? 0 : victim)
        labelCache = nil
        namedCache = nil
    }

    /// 每组 id 的成员数;id 是点下标,数组覆盖整个池。
    private static func groupSizes(_ label: [Int]) -> [Int] {
        var size = [Int](repeating: 0, count: label.count)
        for id in label where id >= 0 { size[id] += 1 }
        return size
    }

    /// 已收集的面:至少两次观察支持的颜色组。
    ///
    /// 只见过一次的颜色不算已收集:单次可能是跨界或偶发。把它算进来会让一个垃圾观察
    /// 占据面的槽位——扫描显示"6 个面"却有一个从未展示的颜色,五面推算因池里有六组
    /// 而拒绝运行。只计确认的组保持数字诚实,失败模式变为"那个颜色的点不亮":用户
    /// 知道该再展示哪个面。
    var size: Int { lastGroupCount }

    /// 六个颜色组存在且各持有至少两次观察时为 true。
    var isComplete: Bool { confirmedGroups == 6 }

    /// 有足够观察算作已收集面的组,聚类器上限六组。
    private var confirmedGroups: Int {
        guard let label = clusteredLabels() else {
            return pool.isEmpty ? 0 : min(6, countByEnum())
        }
        var confirmed = 0
        for size in CubeStateAssembler.groupSizes(label) where size >= CubeStateAssembler.MIN_LOOKS_PER_FACE {
            confirmed += 1
        }
        return confirmed
    }

    /// 已收集面的颜色,按求解器的方式一一命名。
    var establishedColors: Set<CubeColor> {
        Set(namedGroups.map { $0.name })
    }

    /// 每个已收集面的样子,供扫描图:每面一个代表注视,所有可读贴块按已收集中心命名。
    ///
    /// - Returns: 面字母 (URFDLB) → 九个 ARGB 颜色,不可读贴块为 0
    func preview() -> [Character: [Int]] {
        let groups = namedGroups
        var out: [Character: [Int]] = [:]
        for group in groups {
            let look = group.representative
            var argb = [Int](repeating: 0, count: 9)
            for cell in 0..<9 {
                guard let lab = look.lab, look.reliable[cell] else { continue }
                var best: CubeColor?
                var nearest = Double.greatestFiniteMagnitude
                for other in groups {
                    let d = Double(Lab.distance(lab[cell], other.representative.centerLab!))
                    if d < nearest { nearest = d; best = other.name }
                }
                // 尚未收集的面离所有中心都远:显示临时猜测而不是强归到已收集的颜色。
                if nearest > 30 && look.stickers[cell] != .unknown { best = look.stickers[cell] }
                argb[cell] = best == nil ? 0 : Int(best!.rgb)
            }
            argb[4] = Int(group.name.rgb)
            out[group.name.face] = argb
        }
        return out
    }

    final class NamedGroup {
        let name: CubeColor
        let representative: FaceSample

        init(name: CubeColor, representative: FaceSample) {
            self.name = name
            self.representative = representative
        }
    }

    private var namedCache: [NamedGroup]?

    /// 带一一对应颜色名的已收集面。
    ///
    /// 按多数阈值色命名组可能把两组都叫红——红橙只差几度色相——所以组按求解器命名
    /// 原型的方式命名:不同标准色的最小代价分配。
    private var namedGroups: [NamedGroup] {
        if namedCache != nil && labelCache != nil { return namedCache! }
        var named: [NamedGroup] = []
        guard let label = clusteredLabels() else { return named }
        let size = CubeStateAssembler.groupSizes(label)
        var representatives: [FaceSample] = []
        for id in 0..<label.count {
            if size[id] < CubeStateAssembler.MIN_LOOKS_PER_FACE { continue }
            var group: [FaceSample] = []
            for i in 0..<label.count where label[i] == id { group.append(pool[i]) }
            if let central = mostCentral(largestCluster(group), -1) {
                representatives.append(central)
            }
        }
        if representatives.isEmpty {
            namedCache = named
            return named
        }
        var prototypes = [[Float]](repeating: [], count: representatives.count)
        for i in 0..<prototypes.count { prototypes[i] = representatives[i].centerLab! }
        guard let names = ColorAssignment.namePrototypes(prototypes, 0) else { return named }
        for i in 0..<names.count {
            named.append(NamedGroup(name: names[i], representative: representatives[i]))
        }
        namedCache = named
        return named
    }

    var lastFailureValue: String { lastFailure }

    var observations: [FaceSample] { pool }

    /// 随接受观察增长,即使池已满在驱逐。
    var acceptedCount: Int { accepted }

    func clear() {
        pool.removeAll()
        accepted = 0
        labelCache = nil
        namedCache = nil
        lastGroupCount = 0
        palette = nil
        lastFailure = ""
    }

    /// 分组面的临时中心颜色,仅用于进度显示。
    var scannedColors: Set<CubeColor> {
        var colors = Set<CubeColor>()
        for face in pool { colors.insert(face.center) }
        colors.remove(.unknown)
        return colors
    }

    /// 两次观察与整个池的颜色展开相比足够近时,算同一个面。
    private static let SAME_FACE_SHARE = 0.20
    /// 下限避免只含红橙的池把每个颜色拆成两半。按真机池标定:一面的观察相差 2-7 Lab
    /// 单位,而红橙中心相距 34——旧的 32 下限差一个暖房就会把它们并成一组,扫描永远
    /// 停在五色。
    private static let SAME_FACE_FLOOR = 24.0
    /// 左右或上下 Lab 均值相差约此值的注视,形状像压在边上的四边形。从不直接拒绝——
    /// 诚实的打乱面也达到同样的值——但组装器必须不信任哪个面时,它给组排序。
    static let STRADDLE_SPLIT: Float = 52

    /// 池支持多少个不同的面颜色。
    ///
    /// 多余的组被丢弃而不是强行合并。强行合并第七个垃圾簇到六个正是把红粘到橙、然后在
    /// 非法着色上报告 6/6 的原因。
    private func distinctFaceCount() -> Int {
        guard let label = clusteredLabels() else { return min(pool.count, countByEnum()) }
        return confirmedGroups
    }

    /// 相对合并并丢弃多余弱组后的标签。Lab 不完整时为 nil。池变化前缓存;调用方视
    /// 数组为只读。
    private func clusteredLabels() -> [Int]? {
        if let labelCache = labelCache { return labelCache }
        let centers = centersWithLab()
        if centers.count != pool.count || centers.isEmpty { return nil }
        var label = CubeStateAssembler.mergeRelatively(centers)
        labelCache = dropExtraGroups(label)
        return labelCache
    }

    private func countByEnum() -> Int {
        var colors = Set<CubeColor>()
        for face in pool { colors.insert(face.center) }
        colors.remove(.unknown)
        return colors.count
    }

    private func centersWithLab() -> [[Float]] {
        var centers: [[Float]] = []
        for face in pool {
            guard let centerLab = face.centerLab else { return centers }
            centers.append(centerLab)
        }
        return centers
    }

    /// 合并相对池的整体展开较近的组。
    ///
    /// 簇距离矩阵上的完全连接:合并 A、B 后它们到每个 C 的距离取二者较大值,每次合并
    /// O(n) 更新而非全量重扫。标签是每簇最靠前成员的点下标。
    private static func mergeRelatively(_ points: [[Float]]) -> [Int] {
        let n = points.count
        var distance = [[Double]](repeating: [Double](repeating: 0, count: n), count: n)
        var widest = 0.0
        for i in 0..<n {
            for j in (i + 1)..<n {
                let d = Double(Lab.distance(points[i], points[j]))
                distance[i][j] = d
                distance[j][i] = d
                widest = max(widest, d)
            }
        }
        let cut = max(CubeStateAssembler.SAME_FACE_FLOOR, widest * CubeStateAssembler.SAME_FACE_SHARE)
        var label = [Int](repeating: 0, count: n)
        var alive = [Bool](repeating: true, count: n)
        for i in 0..<n { label[i] = i; alive[i] = true }
        var groups = n
        while groups > 1 {
            var best = Double.greatestFiniteMagnitude
            var a = -1, b = -1
            for i in 0..<n {
                if !alive[i] { continue }
                for j in (i + 1)..<n {
                    if alive[j] && distance[i][j] < best {
                        best = distance[i][j]; a = i; b = j
                    }
                }
            }
            if a < 0 || best > cut { break }
            alive[b] = false
            for k in 0..<n {
                if !alive[k] || k == a { continue }
                let merged = max(distance[a][k], distance[b][k])
                distance[a][k] = merged
                distance[k][a] = merged
            }
            for i in 0..<n where label[i] == b { label[i] = a }
            groups -= 1
        }
        return label
    }

    /// 多余的簇被丢弃而不是合并。合并多余簇正是垃圾灰中心造出第七组时把红粘到橙的原因。
    private func dropExtraGroups(_ label: [Int]) -> [Int] {
        var label = label
        while CubeStateAssembler.countGroups(label) > 6 {
            let weak = weakestGroup(label)
            if weak < 0 { break }
            for i in 0..<label.count where label[i] == weak { label[i] = -1 }
        }
        return label
    }

    /// 超过六组时放弃的组:观察数最少的那个。
    ///
    /// 曾按平均置信度排序,而跨界四边形读九张平色贴纸,得分反超诚实观察:单个跨界存活,
    /// 有七次观察的真面被丢掉,颜色从扫描中消失。读数重复的频率才是面与偶发的分界;
    /// 置信度只打破平局,然后看新近度。
    private func weakestGroup(_ label: [Int]) -> Int {
        let size = CubeStateAssembler.groupSizes(label)
        var score = [Double](repeating: 0, count: label.count)
        var newest = [Int](repeating: 0, count: label.count)
        for i in 0..<label.count {
            let id = label[i]
            if id < 0 { continue }
            let face = pool[i]
            score[id] += Double(face.confidence) + (Lab.isStickerCenter(face.centerLab) ? 100.0 : 0.0)
            newest[id] = i
        }
        var weak = -1
        for id in 0..<label.count {
            if size[id] == 0 { continue }
            if weak < 0 || size[id] < size[weak] { weak = id; continue }
            if size[id] > size[weak] { continue }
            let mean = score[id] / Double(size[id]), weakMean = score[weak] / Double(size[weak])
            if mean < weakMean || (mean == weakMean && newest[id] < newest[weak]) { weak = id }
        }
        return weak
    }

    private static func countGroups(_ label: [Int]) -> Int {
        var seen: [Int] = []
        for value in label where value >= 0 {
            if !seen.contains(value) { seen.append(value) }
        }
        return seen.count
    }

    /// 从六个扫描面组装魔方;失败返回 nil 并设置 lastFailure。
    ///
    /// 五个面永远不够。推断第六面曾挽救互相矛盾的扫描,但矛盾就是一张误读的贴纸,
    /// 误读的面送进推断会产出合法却不是手中那个的魔方:真机扫描换了一张红一张橙,
    /// 推断用其余五面重建黄色,还原在第 15 步出错。读数无法修复的六面扫描要求重新
    /// 展示某个面(suspect)。
    func assemble() -> String? {
        suspect = nil
        if !isComplete {
            lastFailure = "还没有六个面"
            return nil
        }
        deadlineNanos = nowNanos() + CubeStateAssembler.SIX_FACE_BUDGET_NANOS
        timedOut = false
        defer { deadlineNanos = Double.greatestFiniteMagnitude }
        let six = assembleLegalState()
        if six == nil && expired {
            timedOut = true
            lastFailure = "核对超时,继续采集后会再试"
        }
        return six
    }

    /// assemble() 失败后:读数最可疑的面的颜色,请再展示给相机;无突出面时为 nil。
    var suspectColor: CubeColor? { suspect }

    /// 忘掉该中心色的所有观察,以便重新读取。
    func forget(_ color: CubeColor) {
        let label = clusteredLabels()
        var keep: [FaceSample] = []
        let named = namedGroups
        var representative: FaceSample?
        for group in named where group.name == color { representative = group.representative }
        for i in 0..<pool.count {
            var same = false
            if let rep = representative, let label = label, label[i] >= 0,
               label[i] == labelOf(rep, label) {
                same = true
            }
            if !same { keep.append(pool[i]) }
        }
        if keep.count == pool.count { return }
        pool = keep
        labelCache = nil
        namedCache = nil
        lastGroupCount = distinctFaceCount()
    }

    private func labelOf(_ look: FaceSample, _ label: [Int]) -> Int {
        for i in 0..<pool.count where pool[i] === look { return label[i] }
        return Int.min
    }

    func assembleLegalState() -> String? {
        // 下面的重试阶梯以该预算为界;没有上限,簇组合会在无望的池上把求解线程卡住
        // 几分钟。
        searchBudget = 120
        let centers = centersWithLab()
        if centers.count != pool.count || pool.count < 6 { return assembleWithoutReadings() }

        // 使用与进度报告相同的分组。把五个真实面强行分裂成六个会发明一个仍能通过求解器
        // 奇偶校验的重复——错误的魔方。
        guard let raw = groupsOf(6) else {
            lastFailure = "分组不是 6 个独立面"
            return nil
        }

        // 共享中心颜色的观察不全是同一个面:压在边上的四边形有合理的中心,落进那个颜色
        // 的桶,然后毒化中位数。
        var groups: [[FaceSample]] = []
        for group in raw { groups.append(largestCluster(group)) }

        var chosen: [FaceSample?] = []
        for i in 0..<6 { chosen.append(consensus(groups[i], -1)!) }
        var state = tryAssemble(chosen.compactMap { $0 })
        if state != nil { return state }

        var trimmed = [FaceSample?](repeating: nil, count: 6)
        for i in 0..<6 {
            let group = groups[i]
            trimmed[i] = group.count < 2 ? chosen[i]
                : consensus(group, byDisagreement(group)[0])
        }
        state = tryAssemble(trimmed.compactMap { $0 })
        if state != nil { return state }

        for face in 0..<6 {
            let group = groups[face]
            if group.count < 2 { continue }
            let original = chosen[face]
            let worstFirst = byDisagreement(group)
            for attempt in 0..<min(2, worstFirst.count) {
                chosen[face] = consensus(group, worstFirst[attempt])
                state = tryAssemble(chosen.compactMap { $0 })
                if state != nil { return state }
            }
            chosen[face] = original
        }

        // 一个面的最大簇仍可能是重复的跨界。也试试次选簇。
        state = tryClusterCombinations(raw)
        if state != nil { return state }

        for i in 0..<6 { chosen[i] = consensus(groups[i], -1)! }
        state = repairBySwaps(chosen.compactMap { $0 })
        if state != nil { return state }

        lastFailure = suspect == nil ? "六面色块对不上魔方结构"
            : "有色块读得不准,请把\(suspect!.chinese)色中心那面再正对镜头看一眼"
        return nil
    }

    private func tryClusterCombinations(_ raw: [[FaceSample]]) -> String? {
        cartesianAssemble(CubeStateAssembler.clusterChoices(raw), 0, [FaceSample?](repeating: nil, count: 6))
    }

    private static func meanSplit(_ group: [FaceSample]) -> Float {
        var total: Float = 0
        for look in group { total += look.spatialSplit }
        return group.isEmpty ? 0 : total / Float(group.count)
    }

    /// 每个颜色组的一致簇各产生一个共识读数。
    private static func clusterChoices(_ raw: [[FaceSample]]) -> [[FaceSample]] {
        var options: [[FaceSample]] = []
        for group in raw {
            var parts = partitionLooks(group)
            parts.sort { a, b in compareClusters(a, b) }
            var choices: [FaceSample] = []
            let limit = min(4, parts.count)
            for i in 0..<limit { choices.append(consensus(parts[i], -1)!) }
            options.append(choices)
        }
        return options
    }

    private func cartesianAssemble(_ options: [[FaceSample]], _ index: Int,
                                   _ chosen: inout [FaceSample?]) -> String? {
        if index == options.count {
            if searchBudget <= 0 || expired { return nil }
            searchBudget -= 1
            return tryAssemble(chosen.compactMap { $0 })
        }
        for pick in options[index] {
            chosen[index] = pick
            let state = cartesianAssemble(options, index + 1, &chosen)
            if state != nil { return state }
        }
        return nil
    }

    /// 把共享中心颜色的观察分成实际相似面的簇。
    ///
    /// 与共识近滤相同的 24 单位切点做完全连接:一面的光照变体(~18-28)留在同簇,
    /// 跨界四边形则不然。
    private static let SAME_LOOK_CUT: Float = 24

    static func partitionLooks(_ group: [FaceSample]) -> [[FaceSample]] {
        var parts: [[FaceSample]] = []
        let n = group.count
        if n == 0 { return parts }
        if n == 1 {
            parts.append(group)
            return parts
        }
        var dist = [[Float]](repeating: [Float](repeating: 0, count: n), count: n)
        for i in 0..<n {
            for j in (i + 1)..<n {
                let d = meanCellDistance(group[i], alignTo(group[i], group[j]))
                dist[i][j] = d
                dist[j][i] = d
            }
        }
        var label = [Int](repeating: 0, count: n)
        for i in 0..<n { label[i] = i }
        var clusters = n
        while clusters > 1 {
            var best = Float.greatestFiniteMagnitude
            var mergeA = -1, mergeB = -1
            for i in 0..<n {
                for j in (i + 1)..<n {
                    if label[i] == label[j] { continue }
                    var link: Float = 0
                    for p in 0..<n {
                        if label[p] != label[i] { continue }
                        for q in 0..<n where label[q] == label[j] {
                            link = max(link, dist[p][q])
                        }
                    }
                    if link < best { best = link; mergeA = label[i]; mergeB = label[j] }
                }
            }
            if mergeA < 0 || best > SAME_LOOK_CUT { break }
            for i in 0..<n where label[i] == mergeB { label[i] = mergeA }
            clusters -= 1
        }
        var seen: [Int] = []
        for i in 0..<n {
            var index = seen.firstIndex(of: label[i]) ?? -1
            if index < 0 {
                seen.append(label[i])
                parts.append([])
                index = parts.count - 1
            }
            parts[index].append(group[i])
        }
        return parts
    }

    static func largestCluster(_ group: [FaceSample]) -> [FaceSample] {
        var parts = partitionLooks(group)
        if parts.isEmpty { return group }
        parts.sort { a, b in compareClusters(a, b) }
        return parts[0]
    }

    private static func compareClusters(_ a: [FaceSample], _ b: [FaceSample]) -> Bool {
        if a.count != b.count { return a.count > b.count }
        return reliableCells(b) < reliableCells(a)
    }

    private static func reliableCells(_ looks: [FaceSample]) -> Int {
        var n = 0
        for look in looks { n += 9 - look.unreliableCount }
        return n
    }

    /// 从五个不同的面求解,按结构推算第六面。
    ///
    /// 没人扫的面不是任意的:五个中心命名五个颜色后,第六色就是剩下的那个,九色规则
    /// 锁定未见面持有的各色数量,块唯一性安放它们。这让一个面(通常是底面)难以干净
    /// 展示时扫描也能成功。
    func assembleFromFiveFaces() -> String? {
        searchBudget = 120
        guard var groups = groupsOf(5) else {
            lastFailure = "还没有 5 个独立面"
            return nil
        }
        for i in 0..<groups.count { groups[i] = largestCluster(groups[i]) }

        var chosen: [FaceSample?] = []
        for i in 0..<5 { chosen.append(consensus(groups[i], -1)!) }
        var result = SixthFaceSolver.solve(chosen.compactMap { $0 }, deadlineNanos: deadlineNanos)
        if let result = result {
            palette = result.palette
            return result.state
        }

        var trimmed = [FaceSample?](repeating: nil, count: 5)
        for i in 0..<5 {
            let group = groups[i]
            trimmed[i] = group.count < 2 ? chosen[i] : consensus(group, byDisagreement(group)[0])
        }
        result = SixthFaceSolver.solve(trimmed.compactMap { $0 }, deadlineNanos: deadlineNanos)
        if let result = result {
            palette = result.palette
            return result.state
        }

        for face in 0..<5 {
            let group = groups[face]
            if group.count < 2 { continue }
            let original = chosen[face]
            let worstFirst = byDisagreement(group)
            for attempt in 0..<min(2, worstFirst.count) {
                if expired { return nil }
                chosen[face] = consensus(group, worstFirst[attempt])
                result = SixthFaceSolver.solve(chosen.compactMap { $0 }, deadlineNanos: deadlineNanos)
                if let result = result {
                    palette = result.palette
                    lastFailure = ""
                    return result.state
                }
            }
            chosen[face] = original
        }
        lastFailure = "五面推算不出第六面,色块读数有歧义"
        return nil
    }

    /// 期望的最多几个最大颜色组。
    ///
    /// 之外未确认的单次注视被忽略而不是否决组装——一个离群的注视曾直接挡住五面推断。
    /// 但确认的多余组不同:池真的展示了比要求更多的面。
    ///
    /// - Returns: expected 个组;池不支持那么多颜色时为 nil
    private func groupsOf(_ expected: Int) -> [[FaceSample]]? {
        guard let label = clusteredLabels() else { return nil }
        let size = CubeStateAssembler.groupSizes(label)
        var ids: [Int] = []
        for i in 0..<label.count where label[i] >= 0 && !ids.contains(label[i]) {
            ids.append(label[i])
        }
        if ids.count < expected { return nil }
        ids.sort { a, b in size[b] < size[a] }
        for k in expected..<ids.count where size[ids[k]] >= CubeStateAssembler.MIN_LOOKS_PER_FACE {
            return nil
        }
        var groups: [[FaceSample]] = []
        for k in 0..<expected {
            var members: [FaceSample] = []
            for i in 0..<label.count where label[i] == ids[k] { members.append(pool[i]) }
            groups.append(members)
        }
        return groups
    }

    /// 把一面的所有观察按格合并成单一读数(逐格中位数)。
    ///
    /// 选组内置信度最高的观察曾让一次坏采集毁掉整个扫描。跨界四边形读九张完美平色贴纸,
    /// 置信度比丢了贴块的诚实观察更高,置信度单独就把面交给了跨界。池里有两处这种情
    /// 况,逐面交换无法恢复,因为每次交换仍信任另一个跨界。跨观察的中位数直接以多胜少。
    ///
    /// - Parameter skip: 要排除的观察下标,-1 表示全用
    static func consensus(_ group: [FaceSample], _ skip: Int) -> FaceSample? {
        guard let reference = mostCentral(group, skip) else { return nil }

        var aligned: [FaceSample] = []
        var close: [FaceSample] = []
        for i in 0..<group.count {
            if i == skip { continue }
            let candidate = alignTo(reference, group[i])
            aligned.append(candidate)
            if meanCellDistance(reference, candidate) <= SAME_LOOK_CUT { close.append(candidate) }
        }
        // 跨界四边形置信度高,不得以多胜少压过几张诚实观察。丢弃与组质心不符的观察。
        if close.count >= 2 && close.count < aligned.count { aligned = close }
        if aligned.count == 1 { return aligned[0] }

        var lab = [[Float]](repeating: [Float](repeating: 0, count: 3), count: 9)
        var reliable = [Bool](repeating: false, count: 9)
        var confidence: Float = 0
        for cell in 0..<9 {
            for axis in 0..<3 {
                var values = aligned.map { $0.lab![cell][axis] }
                values.sort()
                let middle = values.count / 2
                lab[cell][axis] = values.count % 2 == 1 ? values[middle]
                    : (values[middle - 1] + values[middle]) / 2
            }
            var trusted = 0
            for look in aligned where look.reliable[cell] { trusted += 1 }
            reliable[cell] = trusted * 2 > aligned.count
        }
        for look in aligned { confidence += look.confidence }
        return FaceSample(reference.stickers, lab, reliable, confidence / Float(aligned.count))
    }

    /// 把观察旋转到与参考最佳匹配的方向。
    ///
    /// 一面的观察以任意滚转到达,逐格平均前必须先转到共同方向。
    private static func alignTo(_ reference: FaceSample, _ sample: FaceSample) -> FaceSample {
        var best = sample
        var rotated = sample
        var lowest = Double.greatestFiniteMagnitude
        for _ in 0..<4 {
            var cost = 0.0
            for cell in 0..<9 {
                cost += Double(Lab.distance(reference.lab![cell], rotated.lab![cell]))
            }
            if cost < lowest { lowest = cost; best = rotated }
            rotated = rotated.rotateClockwise()
        }
        return best
    }

    /// 最近的观察,而不是置信度最高的——跨界得 1.0 分。
    private static func mostCentral(_ group: [FaceSample], _ skip: Int) -> FaceSample? {
        var best: FaceSample?
        var lowest = Double.greatestFiniteMagnitude
        for i in 0..<group.count {
            if i == skip { continue }
            var cost = 0.0
            var n = 0
            let pivot = group[i]
            for j in 0..<group.count {
                if j == skip || j == i { continue }
                cost += Double(meanCellDistance(pivot, alignTo(pivot, group[j])))
                n += 1
            }
            let mean = n == 0 ? 0 : cost / Double(n)
            if mean < lowest { lowest = mean; best = pivot }
        }
        return best
    }

    private static func meanCellDistance(_ a: FaceSample, _ b: FaceSample) -> Float {
        var total: Float = 0
        var n = 0
        for cell in 0..<9 {
            guard a.lab != nil, b.lab != nil else { continue }
            if !a.reliable[cell] || !b.reliable[cell] { continue }
            total += Lab.distance(a.lab![cell], b.lab![cell])
            n += 1
        }
        return n == 0 ? 999 : total / Float(n)
    }

    /// 按与组共识的距离排序观察,最差在前。
    private static func byDisagreement(_ group: [FaceSample]) -> [Int] {
        guard let agreed = consensus(group, -1) else { return Array(0..<group.count) }
        var cost = [Double](repeating: 0, count: group.count)
        var order: [Int] = []
        for i in 0..<group.count {
            let aligned = alignTo(agreed, group[i])
            for cell in 0..<9 {
                cost[i] += Double(Lab.distance(agreed.lab![cell], aligned.lab![cell]))
            }
            order.append(i)
        }
        order.sort { a, b in cost[b] < cost[a] }
        return order
    }

    /// 对一组六个观察运行颜色分配与方向搜索。
    private func tryAssemble(_ chosen: [FaceSample]) -> String? {
        for naming in 0...CubeStateAssembler.NAMING_RETRIES {
            if expired { return nil }
            guard let result = ColorAssignment.assign(chosen, naming) else { break }
            var resolved: [FaceSample] = []
            for i in 0..<6 {
                resolved.append(FaceSample(result.colors[i], chosen[i].lab,
                                           chosen[i].reliable, chosen[i].confidence))
            }
            if let state = orderAndSearch(resolved) {
                palette = ScanPalette.from(result)
                return state
            }
        }
        return nil
    }

    /// 单交换先试(最便宜优先),然后才是交换对。
    private static let SWAP_CANDIDATES = 160
    /// 最便宜的单交换组合成交换对。
    private static let DOUBLE_SWAP_BASE = 40
    /// 只有当下一个合法修复比它贵这么多倍时才接受:两个代价相近的合法修复意味着读数
    /// 无法区分魔方,猜测正是错误魔方进入求解器的方式。交换两张读得很好的贴纸的代价
    /// 约为撤销一张红橙完全读反的两倍,余量必须低于二。
    private static let REPAIR_MARGIN = 1.8

    /// 着色非法的六面,通过交换贴纸之间的颜色修复。
    ///
    /// 每色九张是强制的,误读从不单独出现:红读成橙会把某些橙挤到红。真机实测,两个面
    /// 上红橙互换,计数完好但校验失败。交换彼此颜色最接近的两张贴纸恰好撤销这种情况,
    /// 随机交换几乎从不产出合法魔方,所以明显优于其他所有合法结果的就是真解。都没有
    /// 时,suspect 指向持有最不确定贴纸的面。
    private func repairBySwaps(_ chosen: [FaceSample]) -> String? {
        guard let result = ColorAssignment.assign(chosen, 0) else { return nil }
        var prototypeOf = [[Float]](repeating: [], count: CubeColor.allCases.count)
        for f in 0..<6 { prototypeOf[result.faceColors[f].ordinal] = result.prototypes[f] }

        // 把贴纸 i 命名为颜色 c 的代价,相对其当前颜色。
        var cells = [Int](repeating: 0, count: 48)
        var n = 0
        for f in 0..<6 {
            for c in 0..<9 where c != 4 {
                cells[n] = f * 9 + c
                n += 1
            }
        }
        var extra = [[Double]](repeating: [Double](repeating: 0, count: CubeColor.allCases.count), count: 48)
        var leastMargin = Double.greatestFiniteMagnitude
        var leastCertain = -1
        for i in 0..<48 {
            let f = cells[i] / 9, c = cells[i] % 9
            let own = result.colors[f][c]
            let evidence = chosen[f].reliable[c]
            let base = evidence ? Double(Lab.distanceSquared(chosen[f].lab![c], prototypeOf[own.ordinal])) : 0
            for k in 0..<6 {
                let other = result.faceColors[k]
                let cost = evidence ? Double(Lab.distanceSquared(chosen[f].lab![c], result.prototypes[k])) : 0
                extra[i][other.ordinal] = cost - base
                if evidence && other != own && cost - base < leastMargin {
                    leastMargin = cost - base
                    leastCertain = f
                }
            }
        }
        suspect = leastCertain < 0 ? nil : result.faceColors[leastCertain]

        var swaps: [(cost: Double, i: Int, j: Int)] = []
        for i in 0..<48 {
            let a = result.colors[cells[i] / 9][cells[i] % 9]
            for j in (i + 1)..<48 {
                let b = result.colors[cells[j] / 9][cells[j] % 9]
                if a == b { continue }
                swaps.append((extra[i][b.ordinal] + extra[j][a.ordinal], i, j))
            }
        }
        swaps.sort { x, y in x.cost < y.cost }

        var tries: [[Int]] = []
        var costs: [Double] = []
        for k in 0..<min(CubeStateAssembler.SWAP_CANDIDATES, swaps.count) {
            tries.append([swaps[k].i, swaps[k].j])
            costs.append(swaps[k].cost)
        }
        let state = bestRepair(chosen, result, cells, tries, costs)
        // 有歧义的单交换不得落到交换对:在其自身代价下唯一的交换对,在已有更便宜对手
        // 时仍是猜测。
        if state != nil || ambiguous || expired { return state }

        // 两次独立失误:互不共享贴纸的最便宜交换对。
        var doubles: [(cost: Double, x: Int, y: Int)] = []
        let base = min(CubeStateAssembler.DOUBLE_SWAP_BASE, swaps.count)
        for x in 0..<base {
            for y in (x + 1)..<base {
                let p = swaps[x], q = swaps[y]
                if p.i == q.i || p.i == q.j || p.j == q.i || p.j == q.j { continue }
                doubles.append((p.cost + q.cost, x, y))
            }
        }
        doubles.sort { x, y in x.cost < y.cost }
        tries.removeAll()
        costs.removeAll()
        for d in doubles {
            let p = swaps[d.x], q = swaps[d.y]
            tries.append([p.i, p.j, q.i, q.j])
            costs.append(d.cost)
        }
        return bestRepair(chosen, result, cells, tries, costs)
    }

    /// bestRepair 发现两个合法修复代价相近时设置。
    private var ambiguous = false

    /// tries 中最便宜的合法修复,若它明显优于下一个合法修复。
    private func bestRepair(_ chosen: [FaceSample], _ result: ColorAssignment.Result,
                            _ cells: [Int], _ tries: [[Int]], _ costs: [Double]) -> String? {
        ambiguous = false
        var best: String?
        var bestCost = 0.0
        for t in 0..<tries.count {
            if expired { return nil }
            let cost = costs[t]
            if let b = best, cost > max(bestCost, 1.0) * CubeStateAssembler.REPAIR_MARGIN { break }
            var colors = result.colors.map { $0 }
            let swap = tries[t]
            for k in stride(from: 0, to: swap.count, by: 2) {
                let a = cells[swap[k]], b = cells[swap[k + 1]]
                let held = colors[a / 9][a % 9]
                colors[a / 9][a % 9] = colors[b / 9][b % 9]
                colors[b / 9][b % 9] = held
            }
            var resolved: [FaceSample] = []
            for f in 0..<6 {
                resolved.append(FaceSample(colors[f], chosen[f].lab, chosen[f].reliable,
                                           chosen[f].confidence))
            }
            guard let state = orderAndSearch(resolved) else { continue }
            if state == best { continue }
            if best != nil {
                ambiguous = true
                return nil
            }
            best = state
            bestCost = cost
        }
        if best != nil {
            palette = ScanPalette.from(result)
            suspect = nil
        }
        return best
    }

    /// 没有 Lab 读数的样本的后备方案,让组装器独立可用。
    private func assembleWithoutReadings() -> String? {
        var byCenter: [CubeColor: FaceSample] = [:]
        for face in pool where face.center != .unknown {
            byCenter[face.center] = face
        }
        if byCenter.count != 6 { return nil }
        var ordered = [FaceSample?](repeating: nil, count: 6)
        for i in 0..<CubeStateAssembler.ORDER.count {
            ordered[i] = byCenter[CubeColor.fromFace(CubeStateAssembler.ORDER[i])]
            if ordered[i] == nil { return nil }
        }
        var state = [Character]()
        var usedEdges = [Bool](repeating: false, count: 64)
        var usedCorners = [Bool](repeating: false, count: 64)
        return searchRotations(ordered.compactMap { $0 }, 0, &state, &usedEdges, &usedCorners)
    }

    /// 六个解析后的面排进 URFDLB 顺序,然后搜索每个面的相机滚转。
    private func orderAndSearch(_ resolved: [FaceSample]) -> String? {
        var ordered = [FaceSample?](repeating: nil, count: 6)
        for i in 0..<CubeStateAssembler.ORDER.count {
            for face in resolved where face.center.face == CubeStateAssembler.ORDER[i] {
                ordered[i] = face
                break
            }
            if ordered[i] == nil { return nil }
        }
        var state = [Character]()
        var usedEdges = [Bool](repeating: false, count: 64)
        var usedCorners = [Bool](repeating: false, count: 64)
        return searchRotations(ordered.compactMap { $0 }, 0, &state, &usedEdges, &usedCorners)
    }

    /// 一旦放了面 k 就完整的块:其最后一张 facelet 属于面 k。
    private static let EDGES_DONE_AT = piecesDoneAt(CubeRules.EDGES)
    private static let CORNERS_DONE_AT = piecesDoneAt(CubeRules.CORNERS)

    private static func piecesDoneAt(_ pieces: [[Int]]) -> [[Int]] {
        var byFace: [[Int]] = (0..<6).map { _ in [] }
        for p in 0..<pieces.count {
            var last = 0
            for facelet in pieces[p] { last = max(last, facelet / 9) }
            byFace[last].append(p)
        }
        return byFace
    }

    /// 在面的滚转上深度优先,已完成的棱/角一不可能或重复就立刻拒绝部分魔方。错误的
    /// 滚转几乎总在其完成的第一个块上破裂,所以只访问少量分支,而暴力法要在全部 4^6
    /// 组合上跑完整验证。
    private func searchRotations(_ faces: [FaceSample], _ index: Int, _ state: inout [Character],
                                 _ usedEdges: inout [Bool], _ usedCorners: inout [Bool]) -> String? {
        if index == faces.count {
            let candidate = String(state)
            if !CubeRules.piecesArePlausible(candidate) { return nil }
            return MTools.verify(candidate) == 0 ? candidate : nil
        }
        var rotated = faces[index]
        for _ in 0..<4 {
            let oldLength = state.count
            for color in rotated.stickers { state.append(color.face) }
            let edgesTaken = claim(&state, CubeRules.EDGES, CubeStateAssembler.EDGES_DONE_AT[index], &usedEdges)
            let cornersTaken = edgesTaken < 0 ? -1
                : claim(&state, CubeRules.CORNERS, CubeStateAssembler.CORNERS_DONE_AT[index], &usedCorners)
            if edgesTaken >= 0 && cornersTaken >= 0 {
                let found = searchRotations(faces, index + 1, &state, &usedEdges, &usedCorners)
                if found != nil { return found }
            }
            release(&state, CubeRules.EDGES, CubeStateAssembler.EDGES_DONE_AT[index], &usedEdges, edgesTaken)
            release(&state, CubeRules.CORNERS, CubeStateAssembler.CORNERS_DONE_AT[index], &usedCorners, cornersTaken)
            state.removeSubrange(oldLength..<state.count)
            rotated = rotated.rotateClockwise()
        }
        return nil
    }

    /// 标记新完成的块为已用。返回认领的数量;其中一个不可能或已被用时返回 -1(并释放)。
    private static func claim(_ state: inout [Character], _ pieces: [[Int]], _ completed: [Int],
                              _ used: inout [Bool]) -> Int {
        var claimed = 0
        for p in completed {
            let key = pieceKey(state, pieces[p])
            if key < 0 || used[key] {
                for q in 0..<claimed {
                    used[pieceKey(state, pieces[completed[q]])] = false
                }
                return -1
            }
            used[key] = true
            claimed += 1
        }
        return claimed
    }

    private static func release(_ state: inout [Character], _ pieces: [[Int]], _ completed: [Int],
                                _ used: inout [Bool], _ claimed: Int) {
        for q in 0..<claimed {
            used[pieceKey(state, pieces[completed[q]])] = false
        }
    }

    /// 块的面字母位掩码;没有真实块显示这些颜色时为 -1。
    private static func pieceKey(_ state: [Character], _ facelets: [Int]) -> Int {
        var mask = 0
        for facelet in facelets {
            guard let bit = "URFDLB".firstIndex(of: state[facelet]) else { return -1 }
            let bitIndex = "URFDLB".distance(from: "URFDLB".startIndex, to: bit)
            if (mask & (1 << bitIndex)) != 0 { return -1 }
            mask |= 1 << bitIndex
        }
        // 对面(U/D、R/L、F/B)不共块。
        if (mask & 0b001001) == 0b001001 || (mask & 0b010010) == 0b010010 || (mask & 0b100100) == 0b100100 {
            return -1
        }
        return mask
    }

    private var expired: Bool { nowNanos() > deadlineNanos }

    private func nowNanos() -> Double {
        Double(DispatchTime.now().uptimeNanoseconds)
    }
}

extension Array {
    // 辅助:label.count 由调用处替换为 label.count
    func lengthGetter() -> Int { count }
}
