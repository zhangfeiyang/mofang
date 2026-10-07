import UIKit

// Port of com.mofang.cubear.GuideCubeView.java — 简化为 2D 等距投影 3D 魔方。
// 显示当前转动步骤的目标面高亮。

final class GuideCubeView: UIView {
    var step: GuideStep? {
        didSet { setNeedsDisplay() }
    }
    var frameState: CubeFrame = CubeFrame.default {
        didSet { setNeedsDisplay() }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let size = min(bounds.width, bounds.height) * 0.8
        let origin = CGPoint(x: (bounds.width - size) / 2, y: (bounds.height - size) / 2)
        let cellSize = size / 3

        // 画三个可见面(F 正前,U 上,L 左)
        let visibleFaces = visibleFaceLetters()
        let faceColors = faceColorsForFrame()

        for (fi, faceLetter) in visibleFaces.enumerated() {
            let transform = faceTransforms[fi]
            for i in 0..<9 {
                let row = i / 3, col = i % 3
                let color = faceColors[faceLetter]?[i] ?? 0x777777
                let (px, py) = project(cell: i, faceIdx: fi, origin: origin, cellSize: cellSize, transform: transform)
                let alpha: CGFloat = step?.turnsFrontFace() == true && fi == 0 ? 0.9 : 0.6
                ctx.setFillColor(UIColor(argb: UInt32(color)).withAlphaComponent(alpha).cgColor)
                let inset = cellSize * 0.06
                let r = cellSize * 0.1
                let rect = CGRect(x: px + inset, y: py + inset,
                                  width: cellSize - inset * 2, height: cellSize - inset * 2)
                let path = UIBezierPath(roundedRect: rect, cornerRadius: r)
                ctx.addPath(path.cgPath)
                ctx.fillPath()
                _ = col
            }
        }
    }

    /// 当前 frame 下可见面的字母:前、上、左。
    private func visibleFaceLetters() -> [Character] {
        [frameState.frontFace(), frameState.faceAt([0, 1, 0]), frameState.faceAt([-1, 0, 0])]
    }

    /// 从扫描结果读取各面颜色。
    private func faceColorsForFrame() -> [Character: [Int]] {
        var out: [Character: [Int]] = [:]
        let solved = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB"
        for (i, letter) in "URFDLB".enumerated() {
            var argb = [Int](repeating: 0, count: 9)
            for c in 0..<9 {
                argb[c] = Int(CubeColor.fromFace(solved[solved.index(solved.startIndex, offsetBy: i * 9 + c)]).rgb)
            }
            out[letter] = argb
        }
        return out
    }

    /// 等距投影:三个可见面的 2D 变换(偏移 + 简单剪切)。
    private var faceTransforms: [[CGPoint]] {
        let skew: CGFloat = 0.3
        let unit = CGPoint(x: 1, y: 0)
        return [
            [CGPoint(x: 1, y: 0), CGPoint(x: 0, y: 1), CGPoint(x: -skew, y: -skew)],  // F
            [CGPoint(x: 1, y: 0), CGPoint(x: 0, y: 1), CGPoint(x: 0, y: -1)],          // U
            [CGPoint(x: 1, y: 0), CGPoint(x: 0, y: 1), CGPoint(x: skew, y: -skew)],    // L
        ]
    }

    /// 投影第 faceIdx 个可见面的第 cell 格到屏幕坐标。
    private func project(cell: Int, faceIdx: Int, origin: CGPoint, cellSize: CGFloat,
                         transform: [CGPoint]) -> (CGFloat, CGFloat) {
        let row = CGFloat(cell / 3), col = CGFloat(cell % 3)
        let t = transform
        let x = origin.x + col * cellSize * t.x + row * cellSize * t.y
            + CGFloat(faceIdx) * cellSize * (t.x * 0.3)
        let y = origin.y + row * cellSize * t.y - CGFloat(faceIdx) * cellSize * abs(t.y) * 0.3
        return (x, y)
    }
}
