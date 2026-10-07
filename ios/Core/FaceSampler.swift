import Foundation

// Port of com.mofang.cubear.FaceSampler.java
// 给定面的四个角点,读取九张贴纸。
//
// 所有检测器共用:面怎么定位一直在变,面怎么测量从未变过。每格产出中位色加一个 Lab
// 读数,不是单一平色的格子直接拒绝,而不是报成一张看似合理的贴纸。
//
// 支持两种几何。来自 FaceRefiner 的 quad 是贴纸格栅本身,每格在精确中心用大贴块读取;
// 检测器直接给出的粗 quad 可能偏三分之一张贴纸,所以向中心收缩,用窄贴块读取——即
// App 一直使用的保守读法。
//
// 直接作用于打包 RGBA 帧:无扭曲图像,无逐帧原生分配。

enum FaceSampler {
    /// 粗四边形的角点收缩:在角点松散时让 3×3 细分落在贴纸上而不是黑框上。
    static let COARSE_INSET = 0.84
    /// 粗四边形的贴块半宽(占一格的比例)。在一 struggle 会话的 60 个真实扭曲面上标定:
    /// 0.14 的贴块平均只能读 3.2/9 格,0.10 能读 3.9——磨损斑驳的贴纸会惩罚松散四边形上
    /// 的大贴块,窄贴块也更能避开收缩拖向边缘格的黑框。
    static let COARSE_RADIUS = 0.10
    /// 精化四边形的贴块半宽。格栅精确到间距的百分之几,贴纸连圆角约占 0.8 格,0.22 留在
    /// 内部的同时让中位数拥有四倍的投票像素。
    static let REFINED_RADIUS = 0.22
    /// 精化格栅的外圈贴纸仍稍微向内读取:演示视频上,外圈格按精确偏移的 0.92 采样读对
    /// 的面最多、读错两张的最少(对比精确中心与粗采样)。贴纸最外圈正是斜切阴影与边缘
    /// 眩光所在。
    static let REFINED_INSET = 0.92
    /// 每个贴块边的采样数;中位数要的是投票,不是每个像素。
    private static let GRID = 14
    /// 离中位数很远的样本超过该比例,即判定为手指、边缘或眩光。0.32 假设出厂哑光贴纸;
    /// 真实玩过的魔方磨损白实测 0.39-0.66,被整体丢弃。中位数本身保持稳健——对半骑跨的
    /// 格子才是这个余量要防的——所以 0.45 换来对磨损贴纸的容忍,不招来双色格子。
    private static let MAX_DISPERSION: Float = 0.45
    /// 偏离中位数超过该通道偏差的样本算离群。
    private static let OUTLIER_DEVIATION = 46

    /// 把角点向质心收缩 scale。
    static func inset(_ quad: [Double], _ scale: Double) -> [Double] {
        var cx = 0.0, cy = 0.0
        for i in 0..<4 { cx += quad[i * 2]; cy += quad[i * 2 + 1] }
        cx /= 4; cy /= 4
        var out = [Double](repeating: 0, count: 8)
        for i in 0..<4 {
            out[i * 2] = cx + scale * (quad[i * 2] - cx)
            out[i * 2 + 1] = cy + scale * (quad[i * 2 + 1] - cy)
        }
        return out
    }

    /// - Parameters:
    ///   - rgba: 打包帧,每像素 R,G,B,A
    ///   - quad: 从左上顺时针的四个角点,帧像素
    ///   - refined: quad 是否为 FaceRefiner 格栅边界
    ///   - quality: 面定位质量,折入报告的置信度
    /// - Returns: 样本;quad 退化时为 nil
    static func sample(_ rgba: [UInt8], width: Int, height: Int, quad: [Double],
                       refined: Bool, quality: Float) -> FaceSample? {
        let geometry = inset(quad, refined ? REFINED_INSET : COARSE_INSET)
        guard var toImage = Homography.fromSquare(3, geometry) else { return nil }
        let radius = refined ? REFINED_RADIUS : COARSE_RADIUS
        var colors = [CubeColor](repeating: .unknown, count: 9)
        var lab = [[Float]](repeating: [], count: 9)
        var reliable = [Bool](repeating: false, count: 9)
        var r = [Int](repeating: 0, count: GRID * GRID)
        var g = [Int](repeating: 0, count: GRID * GRID)
        var b = [Int](repeating: 0, count: GRID * GRID)
        var histogram = [Int](repeating: 0, count: 256)
        var point = [Double](repeating: 0, count: 2)
        var known = 0
        for row in 0..<3 {
            for col in 0..<3 {
                let index = row * 3 + col
                var count = 0
                for sy in 0..<GRID {
                    let ly = Double(row) + 0.5 + radius * (2.0 * (Double(sy) + 0.5) / Double(GRID) - 1)
                    for sx in 0..<GRID {
                        let lx = Double(col) + 0.5 + radius * (2.0 * (Double(sx) + 0.5) / Double(GRID) - 1)
                        Homography.apply(toImage, lx, ly, &point)
                        let px = clamp(Int(point[0].rounded()), 0, width - 1)
                        let py = clamp(Int(point[1].rounded()), 0, height - 1)
                        let at = (py * width + px) * 4
                        r[count] = Int(rgba[at])
                        g[count] = Int(rgba[at + 1])
                        b[count] = Int(rgba[at + 2])
                        count += 1
                    }
                }
                let median = (medianOf(r, count, &histogram), medianOf(g, count, &histogram), medianOf(b, count, &histogram))
                var outliers = 0
                for i in 0..<count {
                    let deviation = max(abs(r[i] - median.0), max(abs(g[i] - median.1), abs(b[i] - median.2)))
                    if deviation > OUTLIER_DEVIATION { outliers += 1 }
                }
                let uniform = Float(outliers) <= MAX_DISPERSION * Float(count)
                lab[index] = Lab.fromRgb(median.0, median.1, median.2)
                // 阴影下的白色贴纸读作深灰——L 50-70 且色度接近零——正是黑色机身的模样,
                // 只是更暗。旧的 L<78 阈值吞掉了所有未充分受光的白色面。
                let plastic = lab[index][0] < 45 && Lab.chroma(lab[index]) < 25
                reliable[index] = uniform && !plastic
                let color: CubeColor = reliable[index]
                    ? CubeColor.classify(red: median.0, green: median.1, blue: median.2)
                    : .unknown
                colors[index] = color
                if color != .unknown { known += 1 }
            }
        }
        _ = toImage
        return FaceSample(colors, lab, reliable, (Float(known) / 9) * quality)
    }

    /// 直方图法求下中位数。中位数不受镜面高光和贴纸四周黑斜边影响——二者都会把均值
    /// 拖离贴纸真实颜色。
    static func medianOf(_ values: [Int], _ count: Int, _ histogram: inout [Int]) -> Int {
        for i in 0..<256 { histogram[i] = 0 }
        for i in 0..<count { histogram[values[i]] += 1 }
        var seen = 0
        for value in 0..<256 {
            seen += histogram[value]
            if seen * 2 >= count { return value }
        }
        return 255
    }

    private static func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int {
        v < lo ? lo : (v > hi ? hi : v)
    }
}
