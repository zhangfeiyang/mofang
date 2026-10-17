import XCTest
@testable import CubeARCore

// Port of RefinerParityTest.java:Swift 版格点精定位与采样,对照它们移植自的 Python 原型
// (tools/pipeline_sim.py)在真机标注帧上的结果——refiner-parity.json 记录了每帧的网络
// 粗四边形、原型精定位出的格栅和读数。
//
// Linux 上没有 JPEG 解码器,帧要先解成原始 RGB:
//   python3 ios/tools/decode_parity_frames.py <输出目录>
//   CUBEAR_PARITY_FRAMES=<输出目录> swift test --filter RefinerParityTests
// 没有设这个环境变量时跳过。

final class RefinerParityTests: XCTestCase {
    private struct Entry {
        let file: String
        let coarse: [Double]
        let refined: [Double]?
        let lab: [Double]?
        let reliable: [Bool]?
    }

    private func entries() throws -> [Entry] {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
        let url = root.appendingPathComponent("app/src/test/resources/refiner-parity.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [[String: Any]]
        return json.map { e in
            Entry(file: e["file"] as! String,
                  coarse: e["coarse"] as! [Double],
                  refined: e["refined"] as? [Double],
                  lab: e["lab"] as? [Double],
                  reliable: e["reliable"] as? [Bool])
        }
    }

    /// 与安卓测试相同:1080×1920 的帧按面积平均缩到 720×1280 的分析帧。
    private func frame(_ file: String, in directory: String) -> [UInt8]? {
        guard let name = try? FileManager.default.contentsOfDirectory(atPath: directory)
            .first(where: { $0.hasPrefix(file + ".") && $0.hasSuffix(".rgb") }) else { return nil }
        let size = name.dropFirst(file.count + 1).dropLast(4).split(separator: "x").compactMap { Int($0) }
        guard size.count == 2, let data = FileManager.default.contents(atPath: directory + "/" + name),
              data.count == size[0] * size[1] * 3 else { return nil }
        var rgba = [UInt8](repeating: 255, count: size[0] * size[1] * 4)
        data.withUnsafeBytes { (rgb: UnsafeRawBufferPointer) in
            for i in 0..<(size[0] * size[1]) {
                rgba[i * 4] = rgb[i * 3]
                rgba[i * 4 + 1] = rgb[i * 3 + 1]
                rgba[i * 4 + 2] = rgb[i * 3 + 2]
            }
        }
        return AreaResizer(srcWidth: size[0], srcHeight: size[1], dstWidth: 720, dstHeight: 1280).resizeToRgba(rgba)
    }

    func testSwiftRefinerAndSamplerMatchThePrototypeOnRealFrames() throws {
        guard let directory = ProcessInfo.processInfo.environment["CUBEAR_PARITY_FRAMES"] else {
            throw XCTSkip("set CUBEAR_PARITY_FRAMES to the decoded real frames")
        }
        let refiner = FaceRefiner()
        var frames = 0, agreeOnSuccess = 0, bothRefined = 0, close = 0
        var worst = 0.0
        var faces = 0, cells = 0, nearCells = 0
        var totalLab = 0.0
        for entry in try entries() {
            guard let rgba = frame(entry.file, in: directory) else { continue }
            frames += 1
            let result = refiner.refine(rgba, width: 720, height: 1280, quad: entry.coarse)
            if (result != nil) == (entry.refined != nil) { agreeOnSuccess += 1 }
            if let result = result, let expected = entry.refined {
                bothRefined += 1
                let shift = FaceRefiner.maxCentreShift(expected, result.quad) / result.pitch
                worst = max(worst, shift)
                if shift < 0.08 { close += 1 }
            }
            // 采样器单独对照:在原型的格栅上读,与原型读数比 Lab 距离。
            guard let expected = entry.refined, let lab = entry.lab, let reliable = entry.reliable,
                  let sample = FaceSampler.sample(rgba, width: 720, height: 1280, quad: expected,
                                                  refined: true, quality: 1),
                  let read = sample.lab else { continue }
            faces += 1
            for cell in 0..<9 where reliable[cell] && sample.reliable[cell] {
                let d = sqrt(pow(Double(read[cell][0]) - lab[cell * 3], 2)
                    + pow(Double(read[cell][1]) - lab[cell * 3 + 1], 2)
                    + pow(Double(read[cell][2]) - lab[cell * 3 + 2], 2))
                totalLab += d
                cells += 1
                if d < 6 { nearCells += 1 }
            }
        }
        print("refiner parity: success agrees \(agreeOnSuccess)/\(frames), lattices within 0.08 pitch "
            + "\(close)/\(bothRefined), worst \(worst); sampler: \(faces) faces, mean dLab "
            + "\(totalLab / Double(max(1, cells))), within 6: \(nearCells)/\(cells)")
        XCTAssertGreaterThan(frames, 50, "fixture frames found")
        XCTAssertGreaterThanOrEqual(Double(agreeOnSuccess), Double(frames) * 0.92)
        XCTAssertGreaterThanOrEqual(Double(close), Double(bothRefined) * 0.95)
        XCTAssertGreaterThan(faces, 50)
        XCTAssertLessThan(totalLab / Double(max(1, cells)), 3.0)
        XCTAssertGreaterThanOrEqual(Double(nearCells), Double(cells) * 0.97)
    }
}
