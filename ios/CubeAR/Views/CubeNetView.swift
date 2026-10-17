import UIKit

// Port of com.mofang.cubear.CubeNetView.java
// 展开成十字的魔方——U 在上,L F R B 横排,D 在下——随采集逐面填满。
//
// 六个点只能说采了几个面;这张图说采了哪些、读到了什么。空位仍显示中心色,用户知道
// 下一步该把哪个颜色转向镜头。整个魔方确定后图上显示每一块色块,这正是照着解法做
// 之前核对颜色的时机。

final class CubeNetView: UIView {
    /// 十字里的面位置:(字母, 列, 行)。
    private static let SLOTS: [(Character, Int, Int)] = [
        ("U", 1, 0), ("L", 0, 1), ("F", 1, 1), ("R", 2, 1), ("B", 3, 1), ("D", 1, 2),
    ]

    private var faces: [Character: [Int]] = [:]
    private var highlight: Character = "?"
    private var inferred: Character = "?"
    /// 正对镜头的那一面的脉动描边;用图层动画,不必逐帧重绘整张图。
    private let highlightLayer = CAShapeLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        contentMode = .redraw
        highlightLayer.fillColor = nil
        highlightLayer.strokeColor = Theme.mint.cgColor
        highlightLayer.lineWidth = 2
        highlightLayer.isHidden = true
        layer.addSublayer(highlightLayer)
        isAccessibilityElement = true
        accessibilityLabel = L10n.t("a11y.net")
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    /// 每面字母的扫描预览;缺的字母画成空位。
    func setFaces(_ faces: [Character: [Int]]) {
        if faces == self.faces { return }
        self.faces = faces
        setNeedsDisplay()
    }

    /// 已知魔方的全部色块,URFDLB 顺序。
    func setState(_ state: String) {
        guard state.count == 54 else { return }
        var full: [Character: [Int]] = [:]
        let chars = Array(state)
        for (f, letter) in "URFDLB".enumerated() {
            full[letter] = (0..<9).map { Int(CubeColor.fromFace(chars[f * 9 + $0]).argb) }
        }
        setFaces(full)
    }

    /// 镜头此刻看着的面,脉动描边;"?" 为无。
    func setHighlight(_ face: Character) {
        if face == highlight { return }
        highlight = face
        updateHighlight()
    }

    func setInferred(_ face: Character) {
        if face == inferred { return }
        inferred = face
        setNeedsDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        highlightLayer.frame = bounds
        updateHighlight()
    }

    private struct Metrics {
        let faceSize: CGFloat, gap: CGFloat, ox: CGFloat, oy: CGFloat, radius: CGFloat
    }

    private var metrics: Metrics {
        let w = bounds.width, h = bounds.height
        let faceSize = min(w / 4, h / 3)
        let gap = max(1.5, faceSize * 0.06)
        let cell = (faceSize - gap * 2) / 3
        return Metrics(faceSize: faceSize, gap: gap, ox: (w - faceSize * 4) / 2,
                       oy: (h - faceSize * 3) / 2, radius: cell * 0.22)
    }

    private func slotRect(_ col: Int, _ row: Int, _ m: Metrics) -> CGRect {
        CGRect(x: m.ox + CGFloat(col) * m.faceSize + m.gap * 0.5,
               y: m.oy + CGFloat(row) * m.faceSize + m.gap * 0.5,
               width: m.faceSize - m.gap, height: m.faceSize - m.gap)
    }

    private func updateHighlight() {
        guard let slot = CubeNetView.SLOTS.first(where: { $0.0 == highlight }), bounds.width > 0 else {
            highlightLayer.isHidden = true
            highlightLayer.removeAnimation(forKey: "pulse")
            return
        }
        let m = metrics
        let rect = slotRect(slot.1, slot.2, m).insetBy(dx: -m.gap * 0.45, dy: -m.gap * 0.45)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        highlightLayer.path = UIBezierPath(roundedRect: rect, cornerRadius: m.radius * 1.7).cgPath
        highlightLayer.isHidden = false
        CATransaction.commit()
        if highlightLayer.animation(forKey: "pulse") == nil {
            let pulse = CABasicAnimation(keyPath: "opacity")
            pulse.fromValue = 1
            pulse.toValue = 0.55
            pulse.duration = 0.66
            pulse.autoreverses = true
            pulse.repeatCount = .infinity
            pulse.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            highlightLayer.add(pulse, forKey: "pulse")
        }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let m = metrics
        let cell = (m.faceSize - m.gap * 2) / 3
        let inner = cell * 0.12

        for (face, col, row) in CubeNetView.SLOTS {
            let slot = slotRect(col, row, m)
            let colors = faces[face]
            let centre = Int(CubeColor.fromFace(face).argb)

            ctx.setFillColor(UIColor(argb: colors != nil ? 0xFF05_0A09 : 0x14FF_FFFF).cgColor)
            ctx.addPath(UIBezierPath(roundedRect: slot, cornerRadius: m.radius * 1.4).cgPath)
            ctx.fillPath()

            let step = slot.width / 3
            for i in 0..<9 {
                let cellRect = CGRect(x: slot.minX + CGFloat(i % 3) * step + inner * 0.5,
                                      y: slot.minY + CGFloat(i / 3) * step + inner * 0.5,
                                      width: step - inner, height: step - inner)
                let color: Int
                if let colors = colors {
                    color = colors[i] == 0 ? 0x33FF_FFFF : colors[i]
                } else {
                    color = i == 4 ? CubeNetView.withAlpha(centre, 0x99) : 0x1FFF_FFFF
                }
                ctx.setFillColor(UIColor(argb: UInt32(truncatingIfNeeded: color)).cgColor)
                ctx.addPath(UIBezierPath(roundedRect: cellRect, cornerRadius: m.radius).cgPath)
                ctx.fillPath()
            }

            if face == inferred && colors != nil {
                let outline = slot.insetBy(dx: -m.gap * 0.3, dy: -m.gap * 0.3)
                ctx.setStrokeColor(Theme.mint.cgColor)
                ctx.setLineWidth(1.4)
                ctx.setLineDash(phase: 0, lengths: [3, 2.5])
                ctx.addPath(UIBezierPath(roundedRect: outline, cornerRadius: m.radius * 1.6).cgPath)
                ctx.strokePath()
                ctx.setLineDash(phase: 0, lengths: [])
            }
        }
    }

    private static func withAlpha(_ argb: Int, _ alpha: Int) -> Int {
        (argb & 0x00FF_FFFF) | (max(0, min(255, alpha)) << 24)
    }
}
