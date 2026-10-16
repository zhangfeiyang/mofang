import Foundation

// 平台无关的面检测接口:核心逻辑(CubeAnalyzer)只依赖此协议。
// iOS 端由 CubeAR/Vision/CubeFaceModel.swift 用 CoreML 实现;
// Linux 单元测试不需要真模型即可编译运行(分析器可传 nil)。

/// 一次网络评估的输出:角点与面存在概率。
struct FaceDetection {
    /// 四个角点,TL,TR,BR,BL × x,y,帧像素坐标。
    let corners: [Double]
    /// 画面中确实存在一个面的置信度 0..1。
    let presence: Float
}

/// 魔方面检测模型的抽象。
protocol FaceDetecting: AnyObject {
    /// - Returns: 姿态可信时返回角点(帧像素坐标);否则 nil。
    func detectFace(_ rgba: [UInt8], width: Int, height: Int) -> FaceDetection?
}
