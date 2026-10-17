import XCTest
@testable import CubeARCore

// Port of AreaResizerTest.java:网络输入(720×1280 → 160×288)按面积平均缩小,CHW、0..1。

final class AreaResizerTests: XCTestCase {
    /// 独立的二维参考:每个输出像素是它覆盖的源区域的平均。
    private func reference(_ rgba: [UInt8], _ w: Int, _ h: Int, _ dw: Int, _ dh: Int,
                           _ x: Int, _ y: Int, _ ch: Int) -> Float {
        let sx = Double(w) / Double(dw), sy = Double(h) / Double(dh)
        let x0 = Double(x) * sx, x1 = Double(x + 1) * sx, y0 = Double(y) * sy, y1 = Double(y + 1) * sy
        var sum = 0.0
        var py = Int(floor(y0))
        while Double(py) < ceil(y1) && py < h {
            let oy = min(y1, Double(py + 1)) - max(y0, Double(py))
            var px = Int(floor(x0))
            while Double(px) < ceil(x1) && px < w {
                let ox = min(x1, Double(px + 1)) - max(x0, Double(px))
                sum += Double(rgba[(py * w + px) * 4 + ch]) * ox * oy
                px += 1
            }
            py += 1
        }
        return Float((sum / (sx * sy)).rounded()) / 255
    }

    func testMatchesTheAreaAverageAtTheAppsFractionalScale() {
        let w = 90, h = 160, dw = 20, dh = 36   // 720×1280 → 160×288 的比例,缩小版
        var random = SeededRandom(1)
        let rgba = (0..<(w * h * 4)).map { _ in UInt8(random.next(256)) }
        var out = [Float](repeating: 0, count: dw * dh * 3)
        AreaResizer(srcWidth: w, srcHeight: h, dstWidth: dw, dstHeight: dh).resize(rgba, &out)
        let plane = dw * dh
        for y in 0..<dh {
            for x in 0..<dw {
                for ch in 0..<3 {
                    XCTAssertEqual(reference(rgba, w, h, dw, dh, x, y, ch), out[ch * plane + y * dw + x],
                                   accuracy: 1.01 / 255, "pixel \(x),\(y) ch \(ch)")
                }
            }
        }
    }

    func testIntegerFactorsAreBlockMeans() {
        let w = 8, h = 8
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        for i in 0..<(w * h) {
            rgba[i * 4] = i % 2 == 0 ? 200 : 100
            rgba[i * 4 + 1] = 50
            rgba[i * 4 + 2] = 255
        }
        var out = [Float](repeating: 0, count: 2 * 2 * 3)
        AreaResizer(srcWidth: w, srcHeight: h, dstWidth: 2, dstHeight: 2).resize(rgba, &out)
        XCTAssertEqual(150 / 255, out[0], accuracy: 1e-6)
        XCTAssertEqual(50 / 255, out[4], accuracy: 1e-6)
        XCTAssertEqual(1, out[8], accuracy: 1e-6)
    }

    /// 模型预热用的就是输入尺寸本身:1:1 时原样输出。
    func testSameSizeIsACopy() {
        let w = 4, h = 6
        var rgba = [UInt8](repeating: 255, count: w * h * 4)
        rgba[0] = 0
        var out = [Float](repeating: -1, count: w * h * 3)
        AreaResizer(srcWidth: w, srcHeight: h, dstWidth: w, dstHeight: h).resize(rgba, &out)
        XCTAssertEqual(0, out[0], accuracy: 1e-6)
        XCTAssertEqual(1, out[1], accuracy: 1e-6)
        XCTAssertEqual(1, out[w * h], accuracy: 1e-6)
    }
}
