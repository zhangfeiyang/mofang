import Foundation

// Port of com.mofang.cubear.FaceRefiner.java
// 把粗略的面四边形吸附到画面里实际存在的贴纸格栅上。
//
// 检测器给的角点较松。贴纸在面被粗略矫正后很容易看到——被深色塑料机身分隔的明亮或
// 饱和贴块——本类矫正粗四边形周围区域,找到这些贴块,并拟合它们构成的 3×3 格栅。
// 两个关键性质:
// - 平移一行到相邻面的窗口是危险的失败(读到九张完美平色贴纸);矫正后邻面贴纸被
//   透视压缩,每个 blob 按方正度和均匀度加权,折叠窗口会失去外来行的权重;
// - 拟合以其自身上一轮答案矫正的瓦片迭代,必须收敛。持续滑动的拟合没有锚定在单一
//   面上,会被拒绝而非采信。
// 纯 Swift 处理 RGBA 缓冲,无 OpenCV。非线程安全:每个分析线程一个实例。

final class FaceRefiner {
    /// 矫正瓦片覆盖的面周围余量,以贴纸间距计。
    static let MARGIN = 1.0
    /// 瓦片边长(像素):五个间距 × 每格 32px。
    static let TILE = 160
    private static let ITERATIONS = 3
    /// 后一轮迭代任何贴纸中心移动不得超过此值(以间距计)。
    private static let MAX_SETTLE = 0.35
    private static let MAX_RMS = 0.2
    private static let MIN_SUPPORT = 4.5
    private static let W_PRIOR = 0.3, W_SCALE = 0.6
    private static let MAX_BLOBS = 40

    final class Result {
        /// 格栅边界,从最左上角点顺时针,图像像素:x0,y0..x3,y3。
        let quad: [Double]
        /// 图像像素中的平均贴纸间距。
        let pitch: Double
        /// 锚定最终拟合的贴纸数。
        let matched: Int
        /// 最终拟合的 blob 权重和,0..9。
        let support: Double

        init(quad: [Double], pitch: Double, matched: Int, support: Double) {
            self.quad = quad
            self.pitch = pitch
            self.matched = matched
            self.support = support
        }
    }

    private let scale = Double(FaceRefiner.TILE) / (3 + 2 * FaceRefiner.MARGIN)
    private var value = [Int](repeating: 0, count: FaceRefiner.TILE * FaceRefiner.TILE)
    private var mask = [UInt8](repeating: 0, count: FaceRefiner.TILE * FaceRefiner.TILE)
    private var horizontal = [UInt8](repeating: 0, count: FaceRefiner.TILE * FaceRefiner.TILE)
    private var eroded = [UInt8](repeating: 0, count: FaceRefiner.TILE * FaceRefiner.TILE)
    private var labels = [Int](repeating: 0, count: FaceRefiner.TILE * FaceRefiner.TILE)
    private var queue = [Int](repeating: 0, count: FaceRefiner.TILE * FaceRefiner.TILE)
    private var histogram = [Int](repeating: 0, count: 256)
    private var point = [Double](repeating: 0, count: 2)
    private final class Blob {
        var x = 0.0, y = 0.0, area = 0.0, aspect = 0.0
    }
    private var blobs: [Blob] = []
    private var blobCount = 0

    init() {
        for _ in 0..<FaceRefiner.MAX_BLOBS { blobs.append(Blob()) }
    }

    /// - Parameters:
    ///   - rgba: 帧字节,每像素 R,G,B,A,行紧排
    ///   - quad: 帧像素的粗四边形,从最左上角点顺时针
    /// - Returns: 精化四边形;无法锚定面格栅时为 nil
    func refine(_ rgba: [UInt8], width: Int, height: Int, quad: [Double]) -> Result? {
        var current = quad
        var previous: [Double]? = nil
        var result: Result?
        for iteration in 0..<FaceRefiner.ITERATIONS {
            guard var toImage = Homography.fromSquare(3, current) else { return nil }
            renderTile(rgba, width: width, height: height, toImage: toImage)
            findBlobs()
            guard let fit = fitLattice(strict: iteration > 0), fit.count >= 5,
                  fit.support >= FaceRefiner.MIN_SUPPORT else { return nil }

            // 当前四边形格栅单位下匹配的 blob,及其窗口格。
            var src = [Double](repeating: 0, count: fit.count * 2)
            var dst = [Double](repeating: 0, count: fit.count * 2)
            for k in 0..<fit.count {
                src[k * 2] = Double(fit.cellI[k]) + 1.5
                src[k * 2 + 1] = Double(fit.cellJ[k]) + 1.5
                let blob = blobs[fit.blob[k]]
                dst[k * 2] = blob.x / scale - FaceRefiner.MARGIN
                dst[k * 2 + 1] = blob.y / scale - FaceRefiner.MARGIN
            }
            let lattice: [Double]? = fit.count >= 6
                ? Homography.fit(src, dst, fit.count)
                : Homography.fitAffine(src, dst, fit.count)
            guard let lattice = lattice else { return nil }
            var rms = 0.0
            for k in 0..<fit.count {
                Homography.apply(lattice, src[k * 2], src[k * 2 + 1], &point)
                rms += FaceRefiner.sq(point[0] - dst[k * 2]) + FaceRefiner.sq(point[1] - dst[k * 2 + 1])
            }
            rms = (rms / Double(fit.count)).squareRoot()
            if rms > FaceRefiner.MAX_RMS { return nil }

            var corners = [Double](repeating: 0, count: 8)
            let unit: [[Double]] = [[0, 0], [3, 0], [3, 3], [0, 3]]
            for c in 0..<4 {
                Homography.apply(lattice, unit[c][0], unit[c][1], &point)
                Homography.apply(toImage, point[0], point[1], &point)
                corners[c * 2] = point[0]
                corners[c * 2 + 1] = point[1]
            }
            let ordered = FaceRefiner.orderCorners(corners)
            var shortest = Double.greatestFiniteMagnitude, longest = 0.0
            for c in 0..<4 {
                let n = (c + 1) % 4
                let side = hypot(ordered[n * 2] - ordered[c * 2], ordered[n * 2 + 1] - ordered[c * 2 + 1])
                shortest = min(shortest, side)
                longest = max(longest, side)
            }
            if !(shortest >= 9) || longest / shortest > 2.6 { return nil }
            result = Result(quad: ordered, pitch: 0, matched: fit.count, support: fit.support)
            // pitch = 周长/12(与 Java 一致,由 total 计算)
            var total = 0.0
            for c in 0..<4 {
                let n = (c + 1) % 4
                total += hypot(ordered[n * 2] - ordered[c * 2], ordered[n * 2 + 1] - ordered[c * 2 + 1])
            }
            result = Result(quad: ordered, pitch: total / 12.0, matched: fit.count, support: fit.support)
            if let prev = previous, iteration == FaceRefiner.ITERATIONS - 1,
               FaceRefiner.maxCentreShift(prev, ordered) / result!.pitch > FaceRefiner.MAX_SETTLE {
                return nil
            }
            previous = ordered
            current = ordered
        }
        _ = toImage
        return result
    }

    // --------------------------------------------------------------- 瓦片与 blob

    /// 把格栅单位 [-MARGIN, 3+MARGIN]² 矫正到瓦片,双线性,界外为黑。
    private func renderTile(_ rgba: [UInt8], width: Int, height: Int, toImage: [Double]) {
        let inv = 1.0 / scale
        for ty in 0..<FaceRefiner.TILE {
            let ly = Double(ty) * inv - FaceRefiner.MARGIN
            for tx in 0..<FaceRefiner.TILE {
                let lx = Double(tx) * inv - FaceRefiner.MARGIN
                let w = toImage[6] * lx + toImage[7] * ly + toImage[8]
                let sx = (toImage[0] * lx + toImage[1] * ly + toImage[2]) / w
                let sy = (toImage[3] * lx + toImage[4] * ly + toImage[5]) / w
                let index = ty * FaceRefiner.TILE + tx
                let x0 = Int(floor(sx)), y0 = Int(floor(sy))
                if x0 < 0 || y0 < 0 || x0 >= width - 1 || y0 >= height - 1 {
                    value[index] = 0
                    continue
                }
                let fx = sx - Double(x0), fy = sy - Double(y0)
                let p = (y0 * width + x0) * 4
                let q = p + width * 4
                var mx = 0
                for ch in 0..<3 {
                    let top = Double(rgba[p + ch]) * (1 - fx) + Double(rgba[p + 4 + ch]) * fx
                    let bottom = Double(rgba[q + ch]) * (1 - fx) + Double(rgba[q + 4 + ch]) * fx
                    let v = Int(top * (1 - fy) + bottom * fy + 0.5)
                    if v > mx { mx = v }
                }
                value[index] = mx
            }
        }
    }

    private func findBlobs() {
        let lo = Int((FaceRefiner.MARGIN * scale).rounded()), hi = Int(((FaceRefiner.MARGIN + 3) * scale).rounded())
        for i in 0..<256 { histogram[i] = 0 }
        for y in lo..<hi {
            for x in lo..<hi { histogram[value[y * FaceRefiner.TILE + x]] += 1 }
        }
        let threshold = FaceRefiner.clamp(FaceRefiner.otsu(histogram), 28, 170)
        for i in 0..<value.count {
            mask[i] = value[i] > threshold ? 1 : 0
        }
        let k = max(1, Int((scale * 0.06).rounded()))
        erode(k)

        for i in 0..<labels.count { labels[i] = 0 }
        let expected = FaceRefiner.sq(0.80 * scale)
        blobCount = 0
        var next = 1
        for start in 0..<labels.count {
            if eroded[start] == 0 || labels[start] != 0 { continue }
            let label = next
            next += 1
            var head = 0, tail = 0
            queue[tail] = start
            tail += 1
            labels[start] = label
            var area = 0.0
            var sx = 0.0, sy = 0.0, sxx = 0.0, syy = 0.0, sxy = 0.0
            var minX = FaceRefiner.TILE, minY = FaceRefiner.TILE, maxX = -1, maxY = -1
            while head < tail {
                let at = queue[head]
                head += 1
                let x = at % FaceRefiner.TILE, y = at / FaceRefiner.TILE
                area += 1
                sx += Double(x); sy += Double(y)
                sxx += Double(x * x); syy += Double(y * y); sxy += Double(x * y)
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                if y < minY { minY = y }
                if y > maxY { maxY = y }
                if x > 0 { tail = visit(at - 1, label, tail) }
                if x < FaceRefiner.TILE - 1 { tail = visit(at + 1, label, tail) }
                if y > 0 { tail = visit(at - FaceRefiner.TILE, label, tail) }
                if y < FaceRefiner.TILE - 1 { tail = visit(at + FaceRefiner.TILE, label, tail) }
            }
            if area < 0.20 * expected || area > 2.2 * expected { continue }
            // 被瓦片边缘切断:质心偏向内侧。
            if minX == 0 || minY == 0 || maxX >= FaceRefiner.TILE - 1 || maxY >= FaceRefiner.TILE - 1 { continue }
            let mx = sx / area, my = sy / area
            let cxx = sxx / area - mx * mx, cyy = syy / area - my * my, cxy = sxy / area - mx * my
            let trace = cxx + cyy, det = cxx * cyy - cxy * cxy
            let disc = sqrt(max(trace * trace / 4 - det, 0))
            let l1 = trace / 2 + disc, l2 = max(trace / 2 - disc, 1e-6)
            let aspect = (l1 / l2).squareRoot()
            let fill = area / (12 * (l1 * l2).squareRoot())
            if aspect > 2.2 || fill < 0.58 { continue }
            if blobCount == FaceRefiner.MAX_BLOBS { continue }
            let blob = blobs[blobCount]
            blobCount += 1
            blob.x = mx
            blob.y = my
            blob.area = area
            blob.aspect = aspect
        }
    }

    private func visit(_ at: Int, _ label: Int, _ tailIn: Int) -> Int {
        var tail = tailIn
        if eroded[at] == 0 || labels[at] != 0 { return tail }
        labels[at] = label
        queue[tail] = at
        tail += 1
        return tail
    }

    /// 半径 k 的方形腐蚀,两次滑动窗口。瓦片外像素视为已设置,边界不腐蚀(OpenCV 默认)。
    private func erode(_ k: Int) {
        for y in 0..<FaceRefiner.TILE {
            let base = y * FaceRefiner.TILE
            var zeros = 0
            for x in 0..<min(k, FaceRefiner.TILE) where mask[base + x] == 0 { zeros += 1 }
            for x in 0..<FaceRefiner.TILE {
                let enter = x + k, leave = x - k - 1
                if enter < FaceRefiner.TILE && mask[base + enter] == 0 { zeros += 1 }
                if leave >= 0 && mask[base + leave] == 0 { zeros -= 1 }
                horizontal[base + x] = zeros == 0 ? 1 : 0
            }
        }
        for x in 0..<FaceRefiner.TILE {
            var zeros = 0
            for y in 0..<min(k, FaceRefiner.TILE) where horizontal[y * FaceRefiner.TILE + x] == 0 { zeros += 1 }
            for y in 0..<FaceRefiner.TILE {
                let enter = y + k, leave = y - k - 1
                if enter < FaceRefiner.TILE && horizontal[enter * FaceRefiner.TILE + x] == 0 { zeros += 1 }
                if leave >= 0 && horizontal[leave * FaceRefiner.TILE + x] == 0 { zeros -= 1 }
                eroded[y * FaceRefiner.TILE + x] = zeros == 0 ? 1 : 0
            }
        }
    }

    static func otsu(_ histogram: [Int]) -> Int {
        var total = 0
        var weighted = 0
        for v in 0..<256 {
            total += histogram[v]
            weighted += v * histogram[v]
        }
        if total == 0 { return 128 }
        var sumB = 0.0, wB = 0.0, best = -1.0
        var threshold = 128
        for v in 0..<256 {
            wB += Double(histogram[v])
            if wB == 0 { continue }
            let wF = Double(total - Int(wB))
            if wF == 0 { break }
            sumB += Double(v * histogram[v])
            let mB = sumB / wB, mF = (Double(weighted) - sumB) / wF
            let between = wB * wF * (mB - mF) * (mB - mF)
            if between > best { best = between; threshold = v }
        }
        return threshold
    }

    // --------------------------------------------------------------- 格栅

    private final class Fit {
        var count = 0
        var blob = [Int](repeating: 0, count: 9)
        var cellI = [Int](repeating: 0, count: 9)
        var cellJ = [Int](repeating: 0, count: 9)
        var support = 0.0
    }

    /// 一个 blob 作为格栅贴纸的权重,0..1。
    static func blobWeight(_ aspect: Double, _ areaRatio: Double, _ residualRatio: Double,
                           _ strict: Bool) -> Double {
        let aLo = strict ? 1.30 : 1.55, aHi = strict ? 1.75 : 2.2
        let wAspect = clamp01((aHi - aspect) / (aHi - aLo))
        let dev = abs(log(areaRatio))
        let dLo = strict ? 0.30 : 0.45, dHi = strict ? 0.75 : 1.0
        let wArea = clamp01((dHi - dev) / (dHi - dLo))
        return wAspect * wArea * (1 - residualRatio * residualRatio)
    }

    /// 找出最像单面的 3×3 blob 窗口。
    ///
    /// 假设 = 一个 blob 加两步到 1-2 个间距外的邻居(两步,手指盖住中行不打断格栅)、
    /// 近乎垂直。其 7×7 扩展的每个 3×3 窗口按解释的 blob 权重和打分,偏离粗四边形中心
    /// 与间距有小惩罚。
    private func fitLattice(strict: Bool) -> Fit? {
        let n = blobCount
        if n < 4 { return nil }
        let pitch = scale
        let prior = (1.5 + FaceRefiner.MARGIN) * scale
        var stepX = [Double](repeating: 0, count: 2 * n)
        var stepY = [Double](repeating: 0, count: 2 * n)
        var cellOf = [Int](repeating: -1, count: 49)
        var residOf = [Double](repeating: 0, count: 49)
        var gi = [Double](repeating: 0, count: n)
        var gj = [Double](repeating: 0, count: n)
        var resid = [Double](repeating: 0, count: n)
        var memberArea = [Double](repeating: 0, count: 9)
        var members = [Int](repeating: 0, count: 9)
        var memberCell = [Int](repeating: 0, count: 9)
        var weights = [Double](repeating: 0, count: 9)

        var bestScore = -Double.greatestFiniteMagnitude
        var best: Fit?
        for c in 0..<n {
            let origin = blobs[c]
            var steps = 0
            for i in 0..<n where i != c {
                let dx = blobs[i].x - origin.x, dy = blobs[i].y - origin.y
                let dist = hypot(dx, dy)
                if dist > 0.6 * pitch && dist < 1.6 * pitch {
                    stepX[steps] = dx; stepY[steps] = dy; steps += 1
                } else if dist >= 1.6 * pitch && dist < 3.1 * pitch {
                    stepX[steps] = dx / 2; stepY[steps] = dy / 2; steps += 1
                }
            }
            for a in 0..<steps {
                for b in (a + 1)..<steps {
                    let ux = stepX[a], uy = stepY[a], vx = stepX[b], vy = stepY[b]
                    let lu = hypot(ux, uy), lv = hypot(vx, vy)
                    if lu <= 0.6 * pitch || lu >= 1.6 * pitch || lv <= 0.6 * pitch || lv >= 1.6 * pitch { continue }
                    let cos = abs(ux * vx + uy * vy) / (lu * lv)
                    let ratio = lu / lv
                    if cos > 0.45 || ratio <= 0.6 || ratio >= 1.67 { continue }
                    let det = ux * vy - uy * vx
                    if abs(det) < 1e-9 { continue }
                    let tol = 0.3 * min(lu, lv)
                    for i in 0..<49 { cellOf[i] = -1 }
                    for i in 0..<n {
                        let dx = blobs[i].x - origin.x, dy = blobs[i].y - origin.y
                        let ci = (dx * vy - dy * vx) / det, cj = (ux * dy - uy * dx) / det
                        let ri = ci.rounded(), rj = cj.rounded()
                        let ex = (ci - ri) * ux + (cj - rj) * vx, ey = (ci - ri) * uy + (cj - rj) * vy
                        let r = hypot(ex, ey)
                        gi[i] = ri; gj[i] = rj; resid[i] = r
                        if abs(ri) > 3 || abs(rj) > 3 || r > tol { continue }
                        let cell = Int(ri + 3) * 7 + Int(rj + 3)
                        if cellOf[cell] < 0 || residOf[cell] > r {
                            cellOf[cell] = i
                            residOf[cell] = r
                        }
                    }
                    let latticePitch = (lu * lv).squareRoot()
                    let scalePenalty = abs(log(latticePitch / pitch))
                    for oi in -2...2 {
                        for oj in -2...2 {
                            var m = 0
                            for di in -1...1 {
                                for dj in -1...1 {
                                    let cell = (oi + di + 3) * 7 + (oj + dj + 3)
                                    if cellOf[cell] < 0 { continue }
                                    members[m] = cellOf[cell]
                                    memberCell[m] = (di + 1) * 3 + (dj + 1)
                                    memberArea[m] = blobs[cellOf[cell]].area
                                    m += 1
                                }
                            }
                            if m < 4 || Double(m) < bestScore - 0.5 { continue }
                            let median = FaceRefiner.median(memberArea, m)
                            var support = 0.0
                            for k in 0..<m {
                                let blob = blobs[members[k]]
                                weights[k] = FaceRefiner.blobWeight(blob.aspect, blob.area / median,
                                                                    resid[members[k]] / tol, strict)
                                support += weights[k]
                            }
                            let cx = origin.x + Double(oi) * ux + Double(oj) * vx
                            let cy = origin.y + Double(oi) * uy + Double(oj) * vy
                            let offset = hypot(cx - prior, cy - prior) / pitch
                            let score = support - FaceRefiner.W_PRIOR * offset - FaceRefiner.W_SCALE * scalePenalty
                            if score > bestScore {
                                bestScore = score
                                if best == nil { best = Fit() }
                                best!.count = 0
                                best!.support = support
                                for k in 0..<m where weights[k] > 0.25 {
                                    best!.blob[best!.count] = members[k]
                                    best!.cellI[best!.count] = memberCell[k] / 3 - 1
                                    best!.cellJ[best!.count] = memberCell[k] % 3 - 1
                                    best!.count += 1
                                }
                            }
                        }
                    }
                }
            }
        }
        return best
    }

    // --------------------------------------------------------------- 辅助

    /// 从最左上角点顺时针(图像坐标),与 FaceSampler.orderCorners 一致。
    static func orderCorners(_ quad: [Double]) -> [Double] {
        var cx = 0.0, cy = 0.0
        for i in 0..<4 { cx += quad[i * 2]; cy += quad[i * 2 + 1] }
        cx /= 4; cy /= 4
        var index = [0, 1, 2, 3]
        index.sort { a, b in
            atan2(quad[a * 2 + 1] - cy, quad[a * 2] - cx) < atan2(quad[b * 2 + 1] - cy, quad[b * 2] - cx)
        }
        var start = 0
        var minimum = Double.greatestFiniteMagnitude
        for i in 0..<4 {
            let sum = quad[index[i] * 2] + quad[index[i] * 2 + 1]
            if sum < minimum { minimum = sum; start = i }
        }
        var out = [Double](repeating: 0, count: 8)
        for i in 0..<4 {
            let from = index[(start + i) % 4]
            out[i * 2] = quad[from * 2]
            out[i * 2 + 1] = quad[from * 2 + 1]
        }
        return out
    }

    /// 两个四边形对应贴纸中心的平均距离(以 a 的间距计),取最佳循环对应。
    static func meanCentreShift(_ a: [Double], _ b: [Double]) -> Double {
        guard var ha = Homography.fromSquare(3, a) else { return Double.greatestFiniteMagnitude }
        var pitch = 0.0
        for i in 0..<4 {
            let n = (i + 1) % 4
            pitch += hypot(a[n * 2] - a[i * 2], a[n * 2 + 1] - a[i * 2 + 1])
        }
        pitch /= 12
        var pa = [Double](repeating: 0, count: 2), pb = [Double](repeating: 0, count: 2)
        var best = Double.greatestFiniteMagnitude
        for shift in 0..<4 {
            var rolled = [Double](repeating: 0, count: 8)
            for i in 0..<4 {
                rolled[i * 2] = b[((i + shift) % 4) * 2]
                rolled[i * 2 + 1] = b[((i + shift) % 4) * 2 + 1]
            }
            guard var hb = Homography.fromSquare(3, rolled) else { continue }
            var total = 0.0
            for r in 0..<3 {
                for c in 0..<3 {
                    Homography.apply(ha, Double(c) + 0.5, Double(r) + 0.5, &pa)
                    Homography.apply(hb, Double(c) + 0.5, Double(r) + 0.5, &pb)
                    total += hypot(pa[0] - pb[0], pa[1] - pb[1])
                }
            }
            best = min(best, total / 9)
        }
        _ = ha
        return best / max(pitch, 1e-9)
    }

    /// 精化格栅与检测四边形是否描述同一个面。演示视频上精化器 13% 的帧滑到相邻行,
    /// 检测器则跨界;要求两者在此间距数内一致。
    static let AGREEMENT = 0.4

    static func agrees(_ lattice: [Double], _ coarse: [Double]) -> Bool {
        meanCentreShift(lattice, coarse) < AGREEMENT
    }

    /// 两个四边形对应贴纸中心的最大距离,像素。
    static func maxCentreShift(_ a: [Double], _ b: [Double]) -> Double {
        guard var ha = Homography.fromSquare(3, a), var hb = Homography.fromSquare(3, b) else {
            return Double.greatestFiniteMagnitude
        }
        var pa = [Double](repeating: 0, count: 2), pb = [Double](repeating: 0, count: 2)
        var worst = 0.0
        for r in 0..<3 {
            for c in 0..<3 {
                Homography.apply(ha, Double(c) + 0.5, Double(r) + 0.5, &pa)
                Homography.apply(hb, Double(c) + 0.5, Double(r) + 0.5, &pb)
                worst = max(worst, hypot(pa[0] - pb[0], pa[1] - pb[1]))
            }
        }
        _ = ha; _ = hb
        return worst
    }

    private static func median(_ values: [Double], _ n: Int) -> Double {
        var copy = Array(values[0..<n])
        copy.sort()
        return n % 2 == 1 ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2
    }

    private static func sq(_ v: Double) -> Double { v * v }

    private static func clamp01(_ v: Double) -> Double { v < 0 ? 0 : (v > 1 ? 1 : v) }

    private static func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int { v < lo ? lo : (v > hi ? hi : v) }
}
