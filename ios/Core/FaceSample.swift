import Foundation

// Port of com.mofang.cubear.FaceSample.java
// 一个扫描到的面。
//
// stickers 是临时性的逐贴纸猜测,用于实时叠加层和判断面是否稳定。权威着色稍后由
// ColorAssignment 基于 lab 完成,因此这里保留原始测量而不是提前收敛。

final class FaceSample {
    /// 顺时针重排用的下标映射。
    private static let CLOCKWISE = [6, 3, 0, 7, 4, 1, 8, 5, 2]

    let stickers: [CubeColor]
    /// 与 stickers 同序的九个 Lab 读数;临时样本为 nil。
    let lab: [[Float]]?
    /// false 表示该贴纸不是单一颜色,其读数不携带颜色证据。
    let reliable: [Bool]
    let confidence: Float

    convenience init(_ stickers: [CubeColor], _ confidence: Float) {
        self.init(stickers, nil, nil, confidence)
    }

    convenience init(_ stickers: [CubeColor], _ lab: [[Float]]?, _ confidence: Float) {
        self.init(stickers, lab, nil, confidence)
    }

    init(_ stickers: [CubeColor], _ lab: [[Float]]?, _ reliable: [Bool]?, _ confidence: Float) {
        precondition(stickers.count == 9, "A face needs 9 stickers")
        if let lab = lab { precondition(lab.count == 9, "A face needs 9 readings") }
        if let reliable = reliable { precondition(reliable.count == 9, "A face needs 9 reliability flags") }
        self.stickers = stickers
        self.lab = lab
        if let reliable = reliable {
            self.reliable = reliable
        } else {
            self.reliable = [Bool](repeating: true, count: 9)
        }
        self.confidence = confidence
    }

    var unreliableCount: Int {
        reliable.filter { !$0 }.count
    }

    var centerReliable: Bool { reliable[4] }

    var center: CubeColor { stickers[4] }

    /// 中心贴纸的 Lab,作为该面的颜色原型。
    var centerLab: [Float]? { lab?[4] }

    /// 左列相对右列、顶行相对底行在 Lab 上的距离差。
    ///
    /// 覆盖两个面的四边形会有一列(或一行)落在邻面上,分裂值约 50-90;真实面的九个
    /// 贴纸是混合色,通常低于 50。
    var spatialSplit: Float {
        guard let lab = lab else { return 0 }
        return max(Lab.distance(meanCells(lab, 0, 3, 6), meanCells(lab, 2, 5, 8)),
                   Lab.distance(meanCells(lab, 0, 1, 2), meanCells(lab, 6, 7, 8)))
    }

    private func meanCells(_ lab: [[Float]], _ a: Int, _ b: Int, _ c: Int) -> [Float] {
        [(lab[a][0] + lab[b][0] + lab[c][0]) / 3,
         (lab[a][1] + lab[b][1] + lab[c][1]) / 3,
         (lab[a][2] + lab[b][2] + lab[c][2]) / 3]
    }

    var signature: String {
        String(stickers.map { $0.face })
    }

    var containsUnknown: Bool {
        stickers.contains(.unknown)
    }

    func rotateClockwise() -> FaceSample {
        var rotated = [CubeColor](repeating: .unknown, count: 9)
        var rotatedLab: [[Float]]? = lab == nil ? nil : []
        var rotatedReliable = [Bool](repeating: false, count: 9)
        for i in 0..<9 {
            rotated[i] = stickers[FaceSample.CLOCKWISE[i]]
            rotatedReliable[i] = reliable[FaceSample.CLOCKWISE[i]]
            rotatedLab?.append(lab![FaceSample.CLOCKWISE[i]])
        }
        return FaceSample(rotated, rotatedLab, rotatedReliable, confidence)
    }
}
