import UIKit

// Port of com.mofang.cubear.CubeNetView.java — 展开图实时显示已采集面。

final class CubeNetView: UIView {
    private var faces: [Character: [Int]] = [:]
    private var highlight: Character = "?"
    private var inferred: Character = "?"

    func setFaces(_ faces: [Character: [Int]]) {
        self.faces = faces
        setNeedsDisplay()
    }

    func setState(_ state: String) {
        guard state.count == 54 else { return }
        var full: [Character: [Int]] = [:]
        let order = Array("URFDLB")
        let chars = Array(state)
        for f in 0..<6 {
            var argb = [Int](repeating: 0, count: 9)
            for i in 0..<9 {
                argb[i] = Int(CubeColor.fromFace(chars[face * 9 + i]).rgb)
            }
            full[order[f]] = argb
        }
        setFaces(full)
    }

    func setHighlight(_ face: Character) {
        highlight = face
        setNeedsDisplay()
    }

    func setInferred(_ face: Character) {
        inferred = face
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let d = UIScreen.main.scale
        let w = bounds.width
        let faceSize = w / 4
        let gap = max(1.5 * d, faceSize * 0.06)
        let cell = (faceSize - gap * 2) / 3
        let inner = cell * 0.12
        let radius = cell * 0.22

        let slots: [(Character, Int, Int)] = [("U", 1, 0), ("L", 0, 1), ("F", 1, 1), ("R", 2, 1), ("B", 3, 1), ("D", 1, 2)]

        for (letter, col, row) in slots {
            let left = CGFloat(col) * faceSize + gap * 0.5
            let top = CGFloat(row) * faceSize + gap * 0.5
            let size = faceSize - gap
            let colors = faces[letter]
            let centre = CubeColor.fromFace(letter).rgb

            let bgColor: UIColor = colors != nil ? UIColor(argb: 0xFF050A09) : UIColor.white.withAlphaComponent(0.08)
            ctx.setFillColor(bgColor.cgColor)
            ctx.fill(CGRect(x: left, y: top, width: size, height: size))

            let step = size / 3
            for i in 0..<9 {
                let cl = left + CGFloat(i % 3) * step + inner * 0.5
                let ct = top + CGFloat(i / 3) * step + inner * 0.5
                let colorValue: Int
                if colors == nil {
                    colorValue = i == 4 ? PaletteHelper.withAlpha(centre, 0x99) : 0x1FFFFFFF
                } else {
                    colorValue = colors![i] == 0 ? 0x33FFFFFF : colors![i]
                }
                ctx.setFillColor(UIColor(argb: UInt32(colorValue)).cgColor)
                ctx.fill(CGRect(x: cl, y: ct, width: step - inner, height: step - inner))
            }

            if letter == inferred && colors != nil {
                ctx.setStrokeColor(UIColor(argb: 0xFF6FF2C2).cgColor)
                ctx.setLineWidth(1.4 * d)
                ctx.setLineDash(phase: 0, lengths: [3 * d, 2.5 * d])
                ctx.stroke(CGRect(x: left - gap * 0.3, y: top - gap * 0.3,
                                  width: size + gap * 0.6, height: size + gap * 0.6))
                ctx.setLineDash(phase: 0, lengths: [])
            }
            if letter == highlight {
                let pulse = 0.5 + 0.5 * CGFloat(sin(Date().timeIntervalSince1970 * 5))
                ctx.setStrokeColor(UIColor.systemGreen.withAlphaComponent(0.5 + 0.3 * pulse).cgColor)
                ctx.setLineWidth(2 * d)
                ctx.stroke(CGRect(x: left - gap * 0.2, y: top - gap * 0.2,
                                  width: size + gap * 0.4, height: size + gap * 0.4))
            }
        }
    }
}

extension UIColor {
    convenience init(argb: UInt32) {
        self.init(red: CGFloat((argb >> 16) & 0xFF) / 255,
                  green: CGFloat((argb >> 8) & 0xFF) / 255,
                  blue: CGFloat(argb & 0xFF) / 255,
                  alpha: CGFloat((argb >> 24) & 0xFF) / 255)
    }
}

extension PaletteHelper {
    static func withAlpha(_ rgb: Int, _ alpha: Int) -> Int {
        (alpha << 24) | (rgb & 0xFFFFFF)
    }
}
