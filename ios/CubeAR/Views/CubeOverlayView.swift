import UIKit

// Port of com.mofang.cubear.CubeOverlayView.java(541 行)—— 精简为 Swift 版核心绘制。
// 在相机预览上叠加 AR 网格、稳定进度环、贴纸颜色和提示文本。

final class CubeOverlayView: UIView {
    var detectedFace: DetectedFace? {
        didSet { setNeedsDisplay() }
    }
    var stabilizeProgress: Float = 0 {
        didSet { setNeedsDisplay() }
    }
    var hint: String? {
        didSet { setNeedsDisplay() }
    }
    var struggling = false {
        didSet { setNeedsDisplay() }
    }
    /// 颜色覆盖:贴纸中心色 ARGB。设置后显示捕获成功的闪光。
    var captureFlash = false {
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
        let w = bounds.width, h = bounds.height

        // 画检测四边形
        if let face = detectedFace {
            drawQuad(ctx, face, w, h)
            drawStickers(ctx, face)
        }

        // 稳定进度环
        if stabilizeProgress > 0 {
            drawProgressRing(ctx, w, h)
        }

        // 提示文本
        if let hint = hint {
            drawHint(ctx, hint, w, h)
        }

        // 挣扎提示(面被遮挡)
        if struggling {
            drawStrugglingBadge(ctx, w, h)
        }

        // 采集闪光
        if captureFlash {
            ctx.setFillColor(UIColor.white.withAlphaComponent(0.3).cgColor)
            ctx.fill(bounds)
        }
    }

    private func drawQuad(_ ctx: CGContext, _ face: DetectedFace, _ w: CGFloat, _ h: CGFloat) {
        let c = face.corners
        let scaleX = w / CGFloat(face.imageWidth)
        let scaleY = h / CGFloat(face.imageHeight)
        let path = UIBezierPath()
        path.move(to: CGPoint(x: CGFloat(c[0]) * scaleX, y: CGFloat(c[1]) * scaleY))
        for i in 1..<4 {
            path.addLine(to: CGPoint(x: CGFloat(c[i * 2]) * scaleX, y: CGFloat(c[i * 2 + 1]) * scaleY))
        }
        path.close()

        let color: UIColor = face.disputed ? .orange : (face.refined ? .mint : .white)
        ctx.setStrokeColor(color.withAlphaComponent(0.8).cgColor)
        ctx.setLineWidth(2.5)
        ctx.addPath(path.cgPath)
        ctx.strokePath()
    }

    private func drawStickers(_ ctx: CGContext, _ face: DetectedFace) {
        guard let sample = face.sample else { return }
        let scaleX = bounds.width / CGFloat(face.imageWidth)
        let scaleY = bounds.height / CGFloat(face.imageHeight)
        for i in 0..<9 {
            let color = sample.stickers[i]
            guard color != .unknown else { continue }
            // 在四边形内绘制贴纸色圆点
            let t = (i % 3 + 0.5) / 3, u = (i / 3 + 0.5) / 3
            let c = face.corners
            // 双线性插值四边形内的点
            let px = CGFloat(c[0]) * (1 - t) * (1 - u) + CGFloat(c[2]) * t * (1 - u)
                + CGFloat(c[4]) * t * u + CGFloat(c[6]) * (1 - t) * u
            let py = CGFloat(c[1]) * (1 - t) * (1 - u) + CGFloat(c[3]) * t * (1 - u)
                + CGFloat(c[5]) * t * u + CGFloat(c[7]) * (1 - t) * u
            let x = px * scaleX, y = py * scaleY
            let r = min(scaleX, scaleY) * 0.04
            ctx.setFillColor(PaletteHelper.uiColor(color).withAlphaComponent(0.6).cgColor)
            ctx.fillEllipse(in: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2))
        }
    }

    private func drawProgressRing(_ ctx: CGContext, _ w: CGFloat, _ h: CGFloat) {
        let cx = w / 2, cy = h * 0.85
        let r = min(w, h) * 0.08
        let bg = UIBezierPath(arcCenter: CGPoint(x: cx, y: cy), radius: r,
                              startAngle: -.pi / 2, endAngle: 3 * .pi / 2, clockwise: true)
        ctx.setStrokeColor(UIColor.white.withAlphaComponent(0.2).cgColor)
        ctx.setLineWidth(4)
        ctx.addPath(bg.cgPath)
        ctx.strokePath()

        let fg = UIBezierPath(arcCenter: CGPoint(x: cx, y: cy), radius: r,
                              startAngle: -.pi / 2,
                              endAngle: -.pi / 2 + CGFloat(stabilizeProgress) * 2 * .pi,
                              clockwise: true)
        ctx.setStrokeColor(UIColor.systemGreen.cgColor)
        ctx.setLineWidth(4)
        ctx.setLineCap(.round)
        ctx.addPath(fg.cgPath)
        ctx.strokePath()
    }

    private func drawHint(_ ctx: CGContext, _ text: String, _ w: CGFloat, _ h: CGFloat) {
        let attrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.boldSystemFont(ofSize: 16),
            .foregroundColor: UIColor.white,
        ]
        let size = (text as NSString).size(withAttributes: attrs)
        let bg = CGRect(x: w / 2 - size.width / 2 - 12, y: h * 0.75 - size.height / 2 - 6,
                        width: size.width + 24, height: size.height + 12)
        ctx.setFillColor(UIColor.black.withAlphaComponent(0.5).cgColor)
        ctx.fillEllipse(in: bg)
        (text as NSString).draw(at: CGPoint(x: bg.midX - size.width / 2, y: bg.midY - size.height / 2),
                                withAttributes: attrs)
    }

    private func drawStrugglingBadge(_ ctx: CGContext, _ w: CGFloat, _ h: CGFloat) {
        let text = "面被遮挡"
        let attrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 14),
            .foregroundColor: UIColor.white,
        ]
        let size = (text as NSString).size(withAttributes: attrs)
        let bg = CGRect(x: w / 2 - size.width / 2 - 8, y: h * 0.12, width: size.width + 16, height: size.height + 8)
        ctx.setFillColor(UIColor.systemOrange.withAlphaComponent(0.7).cgColor)
        ctx.fillEllipse(in: bg)
        (text as NSString).draw(at: CGPoint(x: bg.midX - size.width / 2, y: bg.midY - size.height / 2),
                                withAttributes: attrs)
    }
}

/// 颜色转换辅助。
enum PaletteHelper {
    static func uiColor(_ color: CubeColor) -> UIColor {
        let rgb = color.rgb
        return UIColor(red: CGFloat((rgb >> 16) & 0xFF) / 255,
                       green: CGFloat((rgb >> 8) & 0xFF) / 255,
                       blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}
