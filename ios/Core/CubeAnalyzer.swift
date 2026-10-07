import Foundation

// Port of com.mofang.cubear.CubeAnalyzer.java(相机无关核心)。
// 将 CameraX 帧转换为检测面:粗角点、格栅精化、贴纸读数。
// iOS 端由 CameraManager(AVFoundation)驱动 process()。

final class CubeAnalyzer {
    static let MIN_FRAME_NANOS: Double = 45_000_000
    static let FALLBACK_AFTER_DECLINES = 3
    static let LATTICE_MEMORY = 2

    private let refiner = FaceRefiner()
    private let anchors: AnchorSearch
    private let smoother = CornerSmoother()
    private var model: CubeFaceModel?
    private var lastAnalysisNanos: Double = 0
    private var modelDeclines = 0
    private var lastLattice: [Double]?
    private var latticeAge = Int.max

    init(model: CubeFaceModel?) {
        self.model = model
        anchors = AnchorSearch(FaceRefiner())
    }

    /// 分析一帧打包 RGBA。分析线程调用。
    func process(_ rgba: [UInt8], width: Int, height: Int) -> DetectedFace? {
        let now = Double(DispatchTime.now().uptimeNanoseconds)
        if now - lastAnalysisNanos < CubeAnalyzer.MIN_FRAME_NANOS { return nil }
        lastAnalysisNanos = now
        return detect(rgba, width: width, height: height)
    }

    private func detect(_ rgba: [UInt8], width: Int, height: Int) -> DetectedFace? {
        let evaluated = model?.detect(rgba, width: width, height: height)
        if evaluated == nil {
            if model != nil { modelDeclines += 1; return nil }
            return nil
        }
        modelDeclines = 0
        let coarseQuad = CornerSmoother.alignedTo(coarseQuad0(rgba, width, height), evaluated!.corners.map { Double($0) })

        let lattice = refineTracked(evaluated!.corners.map { Double($0) }, width: width, height: height)
        guard let lattice = lattice else { return nil }

        guard let sample = FaceSampler.sample(rgba, width: width, height: height,
                                              quad: lattice.quad, refined: true,
                                              quality: Float(lattice.support / 9)) else { return nil }
        var flat = [Float](repeating: 0, count: 8)
        for i in 0..<8 { flat[i] = Float(lattice.quad[i]) }
        return DetectedFace(sample, flat, width, height, Float(lattice.support / 9), true, false)
    }

    private func coarseQuad0(_ rgba: [UInt8], _ width: Int, _ height: Int) -> [Double] {
        [Double(width) * 0.2, Double(height) * 0.2,
         Double(width) * 0.8, Double(height) * 0.2,
         Double(width) * 0.8, Double(height) * 0.8,
         Double(width) * 0.2, Double(height) * 0.8]
    }

    private func refineTracked(_ coarseQuad: [Double], width: Int, height: Int) -> FaceRefiner.Result? {
        var result: FaceRefiner.Result?
        if let last = lastLattice, latticeAge <= CubeAnalyzer.LATTICE_MEMORY,
           CubeAnalyzer.sameFace(last, coarseQuad) {
            result = refiner.refine(rgba: [], width: width, height: height, quad: last)
        }
        if result == nil {
            result = refiner.refine(rgba: [], width: width, height: height, quad: coarseQuad)
        }
        if result != nil {
            lastLattice = result!.quad
            latticeAge = 0
        } else {
            if latticeAge < Int.max { latticeAge += 1 }
        }
        return result
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
