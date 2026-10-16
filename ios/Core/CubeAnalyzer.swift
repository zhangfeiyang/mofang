import Foundation

// Port of com.mofang.cubear.CubeAnalyzer.java(相机无关核心)。
// 将相机帧转换为检测面:网络粗角点 → 帧间平滑 → 格栅精化 → 贴纸读数。
// 网络连续拒识几帧后改用锚点搜索兜底;与相机平台的对接由 App 层完成
//(iOS: CameraManager + CubeFaceModel;Linux 测试:直接喂帧)。

final class CubeAnalyzer {
    /// 分析线程限流:两次分析至少间隔的纳米秒。
    static let MIN_FRAME_NANOS: Double = 45_000_000
    /// 网络连续拒识达到该帧数后,改用锚点搜索兜底。
    static let FALLBACK_AFTER_DECLINES = 3
    /// 精化格栅的跟踪记忆(帧)。
    static let LATTICE_MEMORY = 2

    private let refiner = FaceRefiner()
    private let anchors: AnchorSearch
    private let smoother = CornerSmoother()
    private let model: FaceDetecting?
    private var packedRgba: [UInt8] = []
    private var lastAnalysisNanos: Double = 0
    private var modelDeclines = 0
    private var lastLattice: [Double]?
    private var latticeAge = Int.max

    init(model: FaceDetecting?) {
        self.model = model
        anchors = AnchorSearch(FaceRefiner())
    }

    /// 分析一帧打包 RGBA。分析线程调用。
    func process(_ rgba: [UInt8], width: Int, height: Int) -> DetectedFace? {
        let now = Double(DispatchTime.now().uptimeNanoseconds)
        if now - lastAnalysisNanos < CubeAnalyzer.MIN_FRAME_NANOS { return nil }
        lastAnalysisNanos = now
        packedRgba = rgba
        return detect(width, height)
    }

    private func detect(_ width: Int, _ height: Int) -> DetectedFace? {
        let rgba = packedRgba
        let evaluated = model?.detectFace(rgba, width: width, height: height)
        if evaluated == nil {
            if model != nil {
                modelDeclines += 1
                if modelDeclines < CubeAnalyzer.FALLBACK_AFTER_DECLINES {
                    ageLattice()
                    return nil
                }
            }
            return searchAnchors(width, height)
        }
        modelDeclines = 0
        // 平滑粗角点给精化一个更稳的起点;精化结果每帧重测,平滑不会延迟它。
        let coarseQuad = FaceRefiner.orderCorners(smoother.update(evaluated!.corners)!)

        var lattice = refineTracked(coarseQuad, width: width, height: height)
        var disputed = false
        if let l = lattice, !FaceRefiner.agrees(l.quad, coarseQuad) {
            // 精化器与检测器不一致时,不能从这个格栅继续跟踪。
            lastLattice = nil
            latticeAge = Int.max
            lattice = nil
            disputed = true
        }
        let quad = lattice?.quad ?? coarseQuad
        guard let sample = FaceSampler.sample(rgba, width: width, height: height,
                                              quad: quad, refined: lattice != nil,
                                              quality: evaluated!.presence) else { return nil }
        var flat = [Float](repeating: 0, count: 8)
        for i in 0..<8 { flat[i] = Float(quad[i]) }
        return DetectedFace(sample, flat, width, height, evaluated!.presence,
                            lattice != nil, disputed)
    }

    /// 网络连续拒识后的兜底:从上次面的位置附近直接找格栅。
    /// 这条路只在网络失明时运行,不与网络竞争,因此没有第二意见可交叉验证。
    private func searchAnchors(_ width: Int, _ height: Int) -> DetectedFace? {
        modelDeclines = 0
        let hint = latticeAge <= 6 ? lastLattice : nil
        guard let lattice = anchors.find(packedRgba, width: width, height: height, hint: hint) else {
            ageLattice()
            return nil
        }
        lastLattice = lattice.quad
        latticeAge = 0
        let score = Float(lattice.support / 9.0)
        guard let sample = FaceSampler.sample(packedRgba, width: width, height: height,
                                              quad: lattice.quad, refined: true,
                                              quality: score) else { return nil }
        var flat = [Float](repeating: 0, count: 8)
        for i in 0..<8 { flat[i] = Float(lattice.quad[i]) }
        return DetectedFace(sample, flat, width, height, score, true, false)
    }

    /// 上一帧格栅还看着同一个面时从它精化,否则从粗角点精化。
    private func refineTracked(_ coarseQuad: [Double], width: Int, height: Int) -> FaceRefiner.Result? {
        var result: FaceRefiner.Result?
        if let last = lastLattice, latticeAge <= CubeAnalyzer.LATTICE_MEMORY,
           CubeAnalyzer.sameFace(last, coarseQuad) {
            result = refiner.refine(packedRgba, width: width, height: height, quad: last)
        }
        if result == nil {
            result = refiner.refine(packedRgba, width: width, height: height, quad: coarseQuad)
        }
        if result != nil {
            lastLattice = result!.quad
            latticeAge = 0
        } else {
            ageLattice()
        }
        return result
    }

    private func ageLattice() {
        if latticeAge < Int.max { latticeAge += 1 }
    }

    static func sameFace(_ lattice: [Double], _ coarse: [Double]) -> Bool {
        let lp = meanSide(lattice) / 3, cp = meanSide(coarse) / 3
        let dx = centre(lattice, 0) - centre(coarse, 0), dy = centre(lattice, 1) - centre(coarse, 1)
        let ratio = cp / lp
        return hypot(dx, dy) < 1.2 * lp && ratio > 0.65 && ratio < 1.55
    }

    private static func centre(_ quad: [Double], _ axis: Int) -> Double {
        (quad[axis] + quad[2 + axis] + quad[4 + axis] + quad[6 + axis]) / 4
    }

    private static func meanSide(_ quad: [Double]) -> Double {
        var total = 0.0
        for i in 0..<4 {
            let n = (i + 1) % 4
            total += hypot(quad[n * 2] - quad[i * 2], quad[n * 2 + 1] - quad[i * 2 + 1])
        }
        return total / 4
    }
}
