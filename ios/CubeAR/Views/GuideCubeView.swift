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
        // 展开角布局:U 在上、L 在左、F 在右下(标准魔方角展开),简单可靠。
        let cellSize = min(bounds.width, bounds.height) / 9.5
        let originX = (bounds.width - cellSize * 6.5) / 2
        let originY = (bounds.height - cellSize * 6.5) / 2
        let faceColors = faceColorsForFrame()
        let highlightFront = step?.turnsFrontFace() == true

        let layout: [(Character, Int, Int, Int)] = [
            ("U", 1, 0, 0), ("L", 0, 1, 1), ("F", 1, 1, 0),
        ]
        for (letter, col, row, faceIdx) in layout {
            let fx = originX + CGFloat(col) * cellSize * 3
            let fy = originY + CGFloat(row) * cellSize * 3
            let emphasized = highlightFront && faceIdx == 0
            let alpha: CGFloat = emphasized ? 0.95 : 0.65
            let inset = cellSize * 0.06
            let r = cellSize * 0.1
            let colors = faceColors[letter]
            for i in 0..<9 {
                let cx = fx + CGFloat(i % 3) * cellSize
                let cy = fy + CGFloat(i / 3) * cellSize
                let color = colors?[i] ?? 0x777777
                ctx.setFillColor(UIColor(argb: UInt32(color)).withAlphaComponent(alpha).cgColor)
                let rect = CGRect(x: cx + inset, y: cy + inset,
                                  width: cellSize - inset * 2, height: cellSize - inset * 2)
                ctx.addPath(UIBezierPath(roundedRect: rect, cornerRadius: r).cgPath)
                ctx.fillPath()
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
}
