import Foundation
import CoreML

/// Port of com.mofang.cubear.CubeFaceModel.java
/// 用轻量卷积网络定位魔方面。
///
/// 网络直接回归四个角点,并报告画面中是否确实存在一个面。
/// 整帧以相机自身的竖屏宽高比送入,与训练渲染一致。
/// 使用 Core ML(从 ONNX 转换)而非 onnxruntime-objc,减少依赖。

final class CubeFaceModel {
    static let INPUT_WIDTH = 160
    static let INPUT_HEIGHT = 288
    static let PRESENCE_THRESHOLD: Float = 0.6

    private var model: MLModel?
    private var resizer: AreaResizer?
    private var input = [Float](repeating: 0, count: 3 * INPUT_WIDTH * INPUT_HEIGHT)

    struct Result {
        let corners: [Double]
        let presence: Float
    }

    struct DebugResult {
        let corners: [Double]?
        let presence: Float
        let accepted: Bool
        let reject: String?
        let areaFraction: Double
        let aspect: Double
        let shortest: Double

        var toResult: Result? {
            accepted ? Result(corners: corners ?? [], presence: presence) : nil
        }
    }

    /// 加载模型(App bundle 内的 cubeface.mlmodelc)。失败返回 nil。
    static func create() -> CubeFaceModel? {
        guard let url = Bundle.main.url(forResource: "cubeface", withExtension: "mlmodelc"),
              let mlModel = try? MLModel(contentsOf: url) else {
            return nil
        }
        let m = CubeFaceModel()
        m.model = mlModel
        return m
    }

    /// 运行网络于一帧 RGBA。无可信面时返回 nil。
    func detect(_ rgba: [UInt8], width: Int, height: Int) -> DebugResult? {
        evaluate(rgba, width: width, height: height).toResult
    }

    func evaluate(_ rgba: [UInt8], width: Int, height: Int) -> DebugResult {
        guard let model = model else {
            return DebugResult(corners: nil, presence: -1, accepted: false, reject: "no_model",
                               areaFraction: 0, aspect: 0, shortest: 0)
        }
        if resizer == nil || !resizer!.fits(width: width, height: height) {
            resizer = AreaResizer(srcWidth: width, srcHeight: height,
                                  dstWidth: Self.INPUT_WIDTH, dstHeight: Self.INPUT_HEIGHT)
        }
        resizer!.resize(rgba, &input)

        // 构建 CVPixelBuffer 或 MLMultiArray 输入
        guard let array = try? MLMultiArray(shape: [1, 3, NSNumber(value: INPUT_HEIGHT), NSNumber(value: INPUT_WIDTH)],
                                            dataType: .float32) else {
            return DebugResult(corners: nil, presence: -1, accepted: false, reject: "tensor",
                               areaFraction: 0, aspect: 0, shortest: 0)
        }
        let plane = INPUT_WIDTH * INPUT_HEIGHT
        for i in 0..<plane {
            array[i] = NSNumber(value: input[i])
            array[plane + i] = NSNumber(value: input[plane + i])
            array[2 * plane + i] = NSNumber(value: input[2 * plane + i])
        }

        guard let output = try? model.prediction(from: array) else {
            return DebugResult(corners: nil, presence: -1, accepted: false, reject: "inference",
                               areaFraction: 0, aspect: 0, shortest: 0)
        }

        // 输出: corners [1,8], presence [1,1,1]
        var cornerValues = [Float](repeating: 0, count: 8)
        var presence: Float = 0
        for key in output.keys {
            let arr = output[key]!
            let shape = arr.shape.map { $0.intValue }
            if shape.count >= 2 && shape.last! == 8 {
                for i in 0..<8 { cornerValues[i] = arr[i].floatValue }
            } else {
                presence = arr[0].floatValue
            }
        }

        var points = [Double](repeating: 0, count: 8)
        for i in 0..<4 {
            points[i * 2] = Double(cornerValues[i * 2]) * Double(width)
            points[i * 2 + 1] = Double(cornerValues[i * 2 + 1]) * Double(height)
        }
        let stats = CubeFaceModel.measure(points, width: width, height: height)
        if presence < CubeFaceModel.PRESENCE_THRESHOLD {
            return DebugResult(corners: points, presence: presence, accepted: false, reject: "presence",
                               areaFraction: stats.areaFraction, aspect: stats.aspect, shortest: stats.shortest)
        }
        if let reject = stats.reject {
            return DebugResult(corners: points, presence: presence, accepted: false, reject: reject,
                               areaFraction: stats.areaFraction, aspect: stats.aspect, shortest: stats.shortest)
        }
        return DebugResult(corners: points, presence: presence, accepted: true, reject: nil,
                           areaFraction: stats.areaFraction, aspect: stats.aspect, shortest: stats.shortest)
    }

    struct ShapeStats {
        let areaFraction: Double
        let aspect: Double
        let shortest: Double
        let reject: String?
    }

    /// 拒绝采样器无法有意义扭曲的退化四边形。
    static func measure(_ corners: [Double], width: Int, height: Int) -> ShapeStats {
        var area = 0.0
        for i in 0..<4 {
            let n = (i + 1) % 4
            area += corners[i * 2] * corners[n * 2 + 1] - corners[n * 2] * corners[i * 2 + 1]
        }
        area = abs(area) / 2.0
        let areaFraction = area / (Double(width) * Double(height))
        var shortest = Double.greatestFiniteMagnitude, longest = 0.0
        for i in 0..<4 {
            let n = (i + 1) % 4
            let side = hypot(corners[i * 2] - corners[n * 2], corners[i * 2 + 1] - corners[n * 2 + 1])
            shortest = min(shortest, side)
            longest = max(longest, side)
        }
        let aspect = shortest <= 0 ? 99.0 : longest / shortest
        var reject: String?
        if areaFraction < 0.006 { reject = "implausible_area" }
        else if shortest <= 12 { reject = "implausible_short" }
        else if aspect >= 2.2 { reject = "implausible_aspect" }
        return ShapeStats(areaFraction: areaFraction, aspect: aspect, shortest: shortest, reject: reject)
    }
}
