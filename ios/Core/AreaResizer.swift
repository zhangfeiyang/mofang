import Foundation

// Port of com.mofang.cubear.AreaResizer.java
// 把打包的 RGBA 帧降采样到网络的归一化 RGB 平面。
//
// 与训练管线使用的 OpenCV INTER_AREA 缩放一致:每个输出像素是它覆盖的源面积的精确平均,
// 被边缘切割的源像素按比例计权,结果先舍入到 8 位再归一化——与网络训练时看到的值相同。
// 可分离实现:按帧尺寸一次性算好列/行方向的抽头表。

final class AreaResizer {
    private let srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int
    private let colIndex: [[Int]], rowIndex: [[Int]]
    private let colWeight: [[Float]], rowWeight: [[Float]]
    /// 水平过滤后的一行源数据:dstWidth × 3 通道。
    private var rowBuffer: [Float]
    /// 水平过滤后的所有行:srcHeight × dstWidth × 3。
    private var horizontal: [Float]

    init(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int) {
        self.srcWidth = srcWidth
        self.srcHeight = srcHeight
        self.dstWidth = dstWidth
        self.dstHeight = dstHeight
        var colIndex: [[Int]] = []
        var colWeight: [[Float]] = []
        AreaResizer.taps(src: srcWidth, dst: dstWidth, index: &colIndex, weight: &colWeight)
        var rowIndex: [[Int]] = []
        var rowWeight: [[Float]] = []
        AreaResizer.taps(src: srcHeight, dst: dstHeight, index: &rowIndex, weight: &rowWeight)
        self.colIndex = colIndex
        self.colWeight = colWeight
        self.rowIndex = rowIndex
        self.rowWeight = rowWeight
        rowBuffer = [Float](repeating: 0, count: dstWidth * 3)
        horizontal = [Float](repeating: 0, count: srcHeight * dstWidth * 3)
    }

    func fits(width: Int, height: Int) -> Bool { width == srcWidth && height == srcHeight }

    /// 每个输出单元覆盖的源像素,按单元跨度上的重叠比例加权。
    private static func taps(src: Int, dst: Int, index: inout [[Int]], weight: inout [[Float]]) {
        let scale = Double(src) / Double(dst)
        for d in 0..<dst {
            let start = Double(d) * scale, end = Double(d + 1) * scale
            let first = Int(floor(start)), last = min(Int(ceil(end)) - 1, src - 1)
            let n = last - first + 1
            var idx = [Int](repeating: 0, count: n)
            var w = [Float](repeating: 0, count: n)
            for k in 0..<n {
                let s = first + k
                let overlap = min(end, Double(s + 1)) - max(start, Double(s))
                idx[k] = s
                w[k] = Float(overlap / scale)
            }
            index.append(idx)
            weight.append(w)
        }
    }

    /// - Parameters:
    ///   - rgba: 与构建尺寸一致的打包帧
    ///   - out: 三个平面 (R,G,B),尺寸 dstWidth×dstHeight,值域 0..1
    func resize(_ rgba: [UInt8], _ out: inout [Float]) {
        for y in 0..<srcHeight {
            let rowBase = y * srcWidth * 4
            for x in 0..<dstWidth {
                var r: Float = 0, g: Float = 0, b: Float = 0
                let idx = colIndex[x]
                let w = colWeight[x]
                for k in 0..<idx.count {
                    let at = rowBase + idx[k] * 4
                    let wk = w[k]
                    r += Float(rgba[at]) * wk
                    g += Float(rgba[at + 1]) * wk
                    b += Float(rgba[at + 2]) * wk
                }
                rowBuffer[x * 3] = r
                rowBuffer[x * 3 + 1] = g
                rowBuffer[x * 3 + 2] = b
            }
            horizontal.replaceSubrange(y * dstWidth * 3..<(y + 1) * dstWidth * 3, with: rowBuffer)
        }
        let plane = dstWidth * dstHeight
        for y in 0..<dstHeight {
            let idx = rowIndex[y]
            let w = rowWeight[y]
            for x in 0..<dstWidth {
                var r: Float = 0, g: Float = 0, b: Float = 0
                for k in 0..<idx.count {
                    let at = (idx[k] * dstWidth + x) * 3
                    let wk = w[k]
                    r += horizontal[at] * wk
                    g += horizontal[at + 1] * wk
                    b += horizontal[at + 2] * wk
                }
                let o = y * dstWidth + x
                out[o] = Float((r).rounded()) / 255
                out[plane + o] = Float((g).rounded()) / 255
                out[2 * plane + o] = Float((b).rounded()) / 255
            }
        }
    }

    /// 同样的降采样,以打包 RGBA 字节输出,供调试图像使用。
    func resizeToRgba(_ rgba: [UInt8]) -> [UInt8] {
        var planes = [Float](repeating: 0, count: dstWidth * dstHeight * 3)
        resize(rgba, &planes)
        let plane = dstWidth * dstHeight
        var out = [UInt8](repeating: 0, count: plane * 4)
        for i in 0..<plane {
            out[i * 4] = UInt8((planes[i] * 255).rounded())
            out[i * 4 + 1] = UInt8((planes[plane + i] * 255).rounded())
            out[i * 4 + 2] = UInt8((planes[2 * plane + i] * 255).rounded())
            out[i * 4 + 3] = 255
        }
        return out
    }
}
