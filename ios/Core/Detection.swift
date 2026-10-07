import Foundation

// Port of com.mofang.cubear.DetectedFace.java
// 一次识别出的面,加上分析帧中的四个角点。

final class DetectedFace {
    let sample: FaceSample
    /// TL, TR, BR, BL,以 x,y 对表示。
    let corners: [Float]
    let imageWidth: Int
    let imageHeight: Int
    let detectionScore: Float
    /// 角点被 FaceRefiner 吸附到可见贴纸格栅时为 true;false 表示检测器的粗略估计,
    /// 其读数可能来自边框或相邻面。
    let refined: Bool
    /// 精化器锚定在检测器不同意(其中之一在错误的面上或跨边)的格栅上时为 true。
    /// 这种帧会显示但绝不采集。
    let disputed: Bool

    init(_ sample: FaceSample, _ corners: [Float], _ imageWidth: Int, _ imageHeight: Int,
         _ detectionScore: Float) {
        self.init(sample, corners, imageWidth, imageHeight, detectionScore, false, false)
    }

    init(_ sample: FaceSample, _ corners: [Float], _ imageWidth: Int, _ imageHeight: Int,
         _ detectionScore: Float, _ refined: Bool, _ disputed: Bool) {
        precondition(corners.count == 8, "Four corners required")
        self.sample = sample
        self.corners = corners
        self.imageWidth = imageWidth
        self.imageHeight = imageHeight
        self.detectionScore = detectionScore
        self.refined = refined
        self.disputed = disputed
    }

    /// 四边形最短边占帧短边的比例。
    ///
    /// 相机勉强分辨的面无法诚实读取:每张贴纸只落在几个像素上,中位数回来得看似合理
    /// 却是错的,毒化观察池。调用方应以此门控采集,而不是信任这种样本。
    var minSideFraction: Float {
        var shortest = Float.greatestFiniteMagnitude
        for i in 0..<4 {
            let next = (i + 1) % 4
            let dx = corners[next * 2] - corners[i * 2]
            let dy = corners[next * 2 + 1] - corners[i * 2 + 1]
            shortest = min(shortest, Float(hypot(dx, dy)))
        }
        return shortest / Float(min(imageWidth, imageHeight))
    }

    /// 四边形中心的帧像素坐标。
    var centerX: Float { (corners[0] + corners[2] + corners[4] + corners[6]) / 4 }

    var centerY: Float { (corners[1] + corners[3] + corners[5] + corners[7]) / 4 }
}

// Port of com.mofang.cubear.DetectionTracker.java
// 桥接少量丢失的帧,短暂丢失期间 AR 四边形不会闪烁。
// 平滑已在更上游的 CornerSmoother 中进行;这里重复只会增加延迟。

final class DetectionTracker {
    private var tracked: DetectedFace?
    private var missedFrames = 0

    func update(_ detection: DetectedFace?) -> DetectedFace? {
        guard let detection = detection else {
            missedFrames += 1
            if missedFrames > 4 { tracked = nil }
            return tracked
        }
        missedFrames = 0
        tracked = detection
        return tracked
    }

    func reset() {
        tracked = nil
        missedFrames = 0
    }
}

// Port of com.mofang.cubear.AnchorSearch.java
// 无网络时从几个可能位置精化找到面。
//
// 精化器已能在粗四边形附近找到贴纸格栅,容忍一个间距的平移、大幅滚转与 60% 的尺寸
// 误差,少数锚点——上次见到面的位置,加上人们持握魔方的尺寸与高度处的居中方块——
// 覆盖网络拒识几帧或未加载的情况。每帧轮换尝试几个锚点,长时间失明也不会卡住分析
// 线程。

final class AnchorSearch {
    /// 面边长占帧宽的比例,从大到小。
    private static let SIDES = [0.62, 0.46, 0.34]
    /// 面中心高度占帧高的比例。
    private static let HEIGHTS = [0.44, 0.58, 0.32]
    private static let ATTEMPTS_PER_FRAME = 3

    private let refiner: FaceRefiner
    private var next = 0

    init(_ refiner: FaceRefiner) {
        self.refiner = refiner
    }

    /// - Parameter hint: 上次已知的面四边形,最先尝试;可为 nil
    func find(_ rgba: [UInt8], width: Int, height: Int, hint: [Double]?) -> FaceRefiner.Result? {
        var attempts = 0
        if let hint = hint {
            if let result = refiner.refine(rgba, width: width, height: height, quad: hint) {
                return result
            }
            attempts += 1
        }
        let anchors = AnchorSearch.SIDES.count * AnchorSearch.HEIGHTS.count
        while attempts < AnchorSearch.ATTEMPTS_PER_FRAME {
            let k = next % anchors
            next += 1
            let side = AnchorSearch.SIDES[k % AnchorSearch.SIDES.count] * Double(width)
            let cx = Double(width) / 2, cy = AnchorSearch.HEIGHTS[k / AnchorSearch.SIDES.count] * Double(height)
            let h = side / 2
            let quad: [Double] = [cx - h, cy - h, cx + h, cy - h, cx + h, cy + h, cx - h, cy + h]
            if let result = refiner.refine(rgba, width: width, height: height, quad: quad) {
                return result
            }
            attempts += 1
        }
        return nil
    }
}

// Port of com.mofang.cubear.CaptureGate.java
// 决定一次检测的读数能否用于采集。
//
// 精化的检测总是可以;精化器无法锚定时检测器自己的四边形也可以(手指、模糊、看不见):
// 重训检测器的角点足以通过内缩采样器读取。被拒的是有争议的帧——精化器找到的格栅与
// 检测器不一致,其中之一在错误的面上或跨边,无法分辨。那些正是大多数错误采集背后的
// 读数。
//
// 未精化的帧还必须在中心显示一张贴纸。精化失败多因遮盖,拇指横在中部会读成平色、完美
// "可靠"的肤色:演示视频上这类注视形成垃圾组,挡住五面推断直到第六面到来。精化帧豁免,
// 暖光下的白面(米色而非白色)仍可采集。
//
// 门还会记录未精化帧的长连续,几乎总意味着面被部分遮挡,UI 可以据此提示。

final class CaptureGate {
    /// 连续未精化检测达到此数即认为面被部分遮挡。
    static let STRUGGLING_AFTER = 8

    private var coarseStreak = 0

    /// 该检测的样本可用于采集时返回 true。
    func admit(_ face: DetectedFace?) -> Bool {
        guard let face = face else { return false }
        if face.refined {
            coarseStreak = 0
            return true
        }
        if coarseStreak < Int.max { coarseStreak += 1 }
        return !face.disputed && CaptureGate.stickerLikeCentre(face.sample)
    }

    /// 像彩色贴纸那样饱和,或像受光良好的白色那样明亮中性。
    static func stickerLikeCentre(_ sample: FaceSample?) -> Bool {
        let lab = sample?.centerLab
        if lab == nil { return sample != nil }
        let chroma = Lab.chroma(lab!)
        return chroma >= 40 || (lab![0] >= 68 && chroma <= 22)
    }

    /// 检测持续到达而精化器未锚定时为 true:可能是遮挡。
    func struggling() -> Bool {
        coarseStreak >= CaptureGate.STRUGGLING_AFTER
    }

    func reset() { coarseStreak = 0 }
}
