import Foundation

// Port of com.mofang.cubear.FaceStabilizer.java
// 只有读数在连续数帧内保持稳定,才放行一个面。
//
// 稳定性以原始 Lab 读数判断,而不是临时颜色名。按名字判断时,一张在红橙间闪烁的贴纸
// (固定阈值必然如此)会不断重置计数,面就永远采集不到。测量本身远比贴在它上面的
// 标签稳定。

final class FaceStabilizer {
    /// 每贴块允许的帧间漂移。它约束的是稳定性,不是颜色身份。
    private static let DRIFT_TOLERANCE: Float = 14
    /// 每面容忍的遮挡/眩光贴块数。九色约束加块规则能轻松恢复这个数量,而多要求一个
    /// 贴块就是多一帧展示,所以保持分配步骤能吸收的上限。
    private static let MAX_UNRELIABLE = 3

    /// 稳定过程中连续允许失败的帧数。检测与采样都会偶发丢帧,每次都重启计数会让采集
    /// 面的成功率远低于单帧成功率所暗示的水平。
    private static let MISSES_TOLERATED = 2

    private let framesRequired: Int
    /// 稳定过程首帧与末帧之间的最短时间。间隔 33ms 的两帧几乎测不出静止——扫过的魔方
    /// 在两帧里读数几乎相同——30fps 回放演示视频时,错误采集从 6% 升到 9%(对比 15fps)。
    /// 按时间设门让检查在设备任何分析帧率下同等严格。
    private let minSpanNanos: Double
    private var canonical: FaceSample?
    private var stableFrames = 0
    private var runStartedNanos: Double = 0
    private var lastNanos: Double = 0
    private var consecutiveMisses = 0
    private var emitted = false
    private var labSum: [[Float]]?
    private var labCount: [Int]?

    convenience init(framesRequired: Int) {
        self.init(framesRequired: framesRequired, minSpanNanos: 0)
    }

    init(framesRequired: Int, minSpanNanos: Double) {
        self.framesRequired = framesRequired
        self.minSpanNanos = minSpanNanos
    }

    func push(_ sample: FaceSample?) -> FaceSample? {
        push(sample, nowNanos: currentNanos())
    }

    func push(_ sample: FaceSample?, nowNanos: Double) -> FaceSample? {
        lastNanos = nowNanos
        if !FaceStabilizer.usable(sample) {
            consecutiveMisses += 1
            if consecutiveMisses > FaceStabilizer.MISSES_TOLERATED { resetCandidate() }
            return nil
        }
        let sample = sample!
        consecutiveMisses = 0
        if canonical == nil || !FaceStabilizer.holdsStill(canonical!, sample) {
            canonical = sample
            stableFrames = 1
            runStartedNanos = nowNanos
            emitted = false
            startAveraging(sample)
            return nil
        }
        // 用户以任意面内角度持握魔方,检测器的角点顺序在角度跨过边界时会翻转 90 度——
        // 同一个静止的面会以旋转后的形式到达。按原样比较格子会在每次翻转时重置计数,
        // 永远采不到面;先做滚动对齐再比较,"保持不动"才在任何角度下都意味着不动。
        let aligned = canonical!.lab == nil || sample.lab == nil
            ? sample
            : FaceStabilizer.alignedTo(canonical!, sample)
        stableFrames += 1
        accumulate(aligned)
        if !emitted && stableFrames >= framesRequired && nowNanos - runStartedNanos >= minSpanNanos {
            emitted = true
            return averaged(aligned)
        }
        return nil
    }

    /// 当前稳定过程距放行的进度,0 到 1。
    ///
    /// 喂给叠加层后,用户能知道还要举稳多久——这正是满怀信心等待与在采集完成前放弃的
    /// 区别。
    var progress: Float {
        if canonical == nil || emitted { return 0 }
        let frames = Float(stableFrames) / Float(framesRequired)
        if minSpanNanos <= 0 { return min(1, frames) }
        let span = Float((lastNanos - runStartedNanos) / minSpanNanos)
        return min(1, min(frames, span))
    }

    /// 把 sample 旋转到 reference 的滚动坐标系。
    ///
    /// 由面的颜色图案决定:四个四分之一圈中,格子与参考吻合最好的那个就是同一物理面
    /// 在同一滚动角。打乱的面几乎不可能旋转对称,赢家毫无歧义。
    private static func alignedTo(_ reference: FaceSample, _ sample: FaceSample) -> FaceSample {
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

    private func startAveraging(_ sample: FaceSample) {
        guard sample.lab != nil else { labSum = nil; labCount = nil; return }
        labSum = [[Float]](repeating: [Float](repeating: 0, count: 3), count: 9)
        labCount = [Int](repeating: 0, count: 9)
        accumulate(sample)
    }

    private func accumulate(_ sample: FaceSample) {
        guard labSum != nil, var sum = labSum, var counts = labCount, let lab = sample.lab else { return }
        for i in 0..<9 where sample.reliable[i] {
            for channel in 0..<3 { sum[i][channel] += lab[i][channel] }
            counts[i] += 1
        }
        labSum = sum
        labCount = counts
    }

    /// 放行整个稳定过程读数的均值而非最后一帧。传感器噪声在帧间独立,平均能缩小它,
    /// 而下游颜色分配的上限正取决于交给它的测量质量。
    private func averaged(_ latest: FaceSample) -> FaceSample {
        guard let sum = labSum, let counts = labCount else { return latest }
        var mean = [[Float]](repeating: [], count: 9)
        var reliable = [Bool](repeating: false, count: 9)
        for i in 0..<9 {
            if counts[i] == 0 {
                mean[i] = latest.lab![i]
                reliable[i] = false
                continue
            }
            mean[i] = [sum[i][0] / Float(counts[i]), sum[i][1] / Float(counts[i]), sum[i][2] / Float(counts[i])]
            reliable[i] = true
        }
        return FaceSample(latest.stickers, mean, reliable, latest.confidence)
    }

    private static func usable(_ sample: FaceSample?) -> Bool {
        guard let sample = sample else { return false }
        if sample.lab == nil { return !sample.containsUnknown && sample.confidence >= 0.80 }
        // 中心命名整个面,所以唯独它必须可信。这里没有分裂门:58 的阈值是按一个其绿色
        // 会被它排除的样本池标定的,而这颗魔方诚实的绿色面读数在 75-90,那道门无声地
        // 让一个面永远采不到。合成真值说诚实的打乱面可达 105。跨界由下游处理,那里有
        // 多视角证据:按色聚类、共识近距过滤、重试阶梯、每色两见规则。
        return sample.centerReliable && sample.unreliableCount <= MAX_UNRELIABLE
    }

    private static func holdsStill(_ reference: FaceSample, _ current: FaceSample) -> Bool {
        if reference.lab == nil || current.lab == nil {
            return reference.signature == current.signature
        }
        let aligned = alignedTo(reference, current)
        for i in 0..<9 {
            if !reference.reliable[i] || !aligned.reliable[i] { continue }
            if Lab.distance(reference.lab![i], aligned.lab![i]) > DRIFT_TOLERANCE { return false }
        }
        return true
    }

    func resetCandidate() {
        canonical = nil
        stableFrames = 0
        runStartedNanos = 0
        consecutiveMisses = 0
        emitted = false
        labSum = nil
        labCount = nil
    }

    private func currentNanos() -> Double {
        Double(DispatchTime.now().uptimeNanoseconds)
    }
}
