import UIKit

// Port of com.mofang.cubear.GuideCubeView.java + GuideCube.java 的绘制部分。
// 右上角的 3D 小魔方:按用户手里的持法摆放,循环演示当前步骤——转动层在 3D 中转过去,
// 箭头沿它带动的色块走。几何在 Core/GuideCubeModel.swift。

final class GuideCubeView: UIView {
    private static let MINT = UIColor(argb: GuideCubeModel.MINT)

    private let model = GuideCubeModel()
    private var state: String?
    private var partway: String?
    private var step: GuideStep?
    private var startedAt: CFTimeInterval = 0
    private var link: CADisplayLink?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isAccessibilityElement = true
        accessibilityLabel = "当前步骤的三维动画"
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    /// 步骤或持法变化时从头播放,每条指令都从第一帧开始。
    ///
    /// - Parameter partwayState: 链式步骤第一段做完时的魔方,或 nil
    func show(_ cubeState: String, _ partwayState: String?, _ next: GuideStep) {
        let same = step.map { $0.first == next.first && $0.count == next.count && $0.frame == next.frame } ?? false
        if !same || cubeState != state {
            startedAt = CACurrentMediaTime()
            accessibilityValue = next.caption()
        }
        state = cubeState
        partway = partwayState
        step = next
        setNeedsDisplay()
    }

    // MARK: - 动画循环(只在可见时运行)

    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateLink()
    }

    override var isHidden: Bool {
        didSet { updateLink() }
    }

    private func updateLink() {
        let visible = window != nil && !isHidden
        if visible && link == nil {
            let link = CADisplayLink(target: WeakTarget(self), selector: #selector(WeakTarget.tick))
            link.add(to: .main, forMode: .common)
            self.link = link
        } else if !visible {
            link?.invalidate()
            link = nil
        }
    }

    fileprivate func tick() { setNeedsDisplay() }

    // MARK: - 绘制

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), var step = step, var cube = state else { return }
        let pad = bounds.width * 0.04
        let box = bounds.insetBy(dx: pad, dy: pad)
        var now = (CACurrentMediaTime() - startedAt) * 1000
        // 链式步骤分两段播放,每段从它开始时的魔方演起。
        var label = ""
        if let then = step.then, let partway = partway, partway.count == 54 {
            let local = now.truncatingRemainder(dividingBy: GuideCubeModel.CYCLE_MS * 2)
            let second = local >= GuideCubeModel.CYCLE_MS
            label = second ? "② " : "① "
            if second { cube = partway }
            step = second ? then : step.alone()
            now = local.truncatingRemainder(dividingBy: GuideCubeModel.CYCLE_MS)
        }

        let cx = box.midX
        let cy = box.midY - box.height * 0.09
        // 这个偏航与俯仰下魔方轮廓约 4.0 × 4.4 个单位;这样缩放能装进方框并在下方留出说明条。
        let scale = min(box.width, box.height) * 0.165
        let phase = Float(now.truncatingRemainder(dividingBy: GuideCubeModel.CYCLE_MS) / GuideCubeModel.CYCLE_MS)
        let count = model.collectFaces(cube, step.frame, step.axis, step.layer,
                                       GuideCubeModel.twistAngle(phase, step.quarters))

        ctx.saveGState()
        ctx.clip(to: box)
        ctx.setFillColor(UIColor(argb: 0x5500_0000).cgColor)
        ctx.fillEllipse(in: CGRect(x: cx - scale * 1.55, y: cy + scale * 1.05,
                                   width: scale * 3.1, height: scale * 0.37))
        ctx.setLineJoin(.round)
        ctx.setLineCap(.round)
        for i in 0..<count {
            let id = model.order[i]
            ctx.beginPath()
            for c in 0..<4 {
                let p = model.corner(id, c)
                let point = CGPoint(x: cx + CGFloat(p.0) * scale, y: cy - CGFloat(p.1) * scale)
                if c == 0 { ctx.move(to: point) } else { ctx.addLine(to: point) }
            }
            ctx.closePath()
            ctx.setFillColor(UIColor(argb: model.colors[id]).cgColor)
            if model.isPlastic[id] {
                ctx.fillPath()
            } else {
                ctx.setStrokeColor(UIColor(argb: model.turning[id] ? 0xCC74_F5C5 : 0x3300_0000).cgColor)
                ctx.setLineWidth(box.width * (model.turning[id] ? 0.012 : 0.008))
                ctx.drawPath(using: .fillStroke)
            }
        }
        drawArrow(ctx, cx, cy, scale, box.width, step, phase)
        ctx.restoreGState()

        drawCaption(ctx, box, step, label)
    }

    /// 视图空间的点投到屏幕,与小块用同一台相机。
    private func project(_ x: Float, _ y: Float, _ z: Float, _ cx: CGFloat, _ cy: CGFloat, _ scale: CGFloat) -> CGPoint {
        let p = GuideCubeModel.camera((x, y, z))
        return CGPoint(x: cx + CGFloat(p.0) * scale, y: cy - CGFloat(p.1) * scale)
    }

    /// 方向提示:整面转动(前层、前两层)在前面画一圈;否则沿该层带动的色块画直箭头——
    /// 它穿过的层画在前面,后层画在顶面。
    private func drawArrow(_ ctx: CGContext, _ cx: CGFloat, _ cy: CGFloat, _ scale: CGFloat,
                           _ boxW: CGFloat, _ step: GuideStep, _ phase: Float) {
        let pulse = CGFloat(0.55 + 0.45 * sinf(min(1, max(0, (phase - 0.14) / 0.64)) * .pi))
        if step.turnsFrontFace() {
            drawRing(ctx, cx, cy, scale, boxW, step.quarters > 0, step.isHalfTurn, pulse)
            return
        }
        let n = step.viewAxis()
        let face = n[2] == 0 ? GuideStep.VIEW_FRONT : GuideStep.VIEW_UP
        let d = step.motion(face)
        let lift = GuideCubeModel.LIFT, layer = Float(step.layer), reach: Float = 1.3
        let bx = Float(face[0]) * lift + Float(n[0]) * layer
        let by = Float(face[1]) * lift + Float(n[1]) * layer
        let bz = Float(face[2]) * lift + Float(n[2]) * layer
        let from = project(bx - Float(d[0]) * reach, by - Float(d[1]) * reach, bz - Float(d[2]) * reach, cx, cy, scale)
        let to = project(bx + Float(d[0]) * reach, by + Float(d[1]) * reach, bz + Float(d[2]) * reach, cx, cy, scale)

        ctx.setLineCap(.round)
        ctx.setStrokeColor(UIColor(argb: 0x9900_0000).cgColor)
        ctx.setLineWidth(boxW * 0.044)
        ctx.strokeLineSegments(between: [from, to])
        ctx.setStrokeColor(GuideCubeView.MINT.withAlphaComponent((90 + 150 * pulse) / 255).cgColor)
        ctx.setLineWidth(boxW * 0.028)
        ctx.strokeLineSegments(between: [from, to])
        drawHead(ctx, from, to, boxW)

        if step.isHalfTurn { drawTwice(ctx, project(bx, by, bz, cx, cy, scale), boxW) }
    }

    /// 前面上的方向圈,从前面看顺时针,除非是逆时针。
    private func drawRing(_ ctx: CGContext, _ cx: CGFloat, _ cy: CGFloat, _ scale: CGFloat, _ boxW: CGFloat,
                          _ counter: Bool, _ twice: Bool, _ pulse: CGFloat) {
        let dir: Float = counter ? -1 : 1
        let sweep: Float = twice ? 3.2 : 2.5
        let start: Float = counter ? 0.9 : -0.7
        let radius: Float = 1.22
        let steps = 22
        let lift = GuideCubeModel.LIFT
        ctx.beginPath()
        for i in 0...steps {
            let a = start + dir * sweep * Float(i) / Float(steps)
            let p = project(radius * sinf(a), radius * cosf(a), lift, cx, cy, scale)
            if i == 0 { ctx.move(to: p) } else { ctx.addLine(to: p) }
        }
        ctx.setStrokeColor(GuideCubeView.MINT.withAlphaComponent((90 + 150 * pulse) / 255).cgColor)
        ctx.setLineWidth(boxW * 0.028)
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        ctx.strokePath()

        let tip = start + dir * sweep
        let back = tip - dir * 0.28
        drawHead(ctx, project(radius * sinf(back), radius * cosf(back), lift, cx, cy, scale),
                 project(radius * sinf(tip), radius * cosf(tip), lift, cx, cy, scale), boxW)
        if twice { drawTwice(ctx, project(0, 0, lift, cx, cy, scale), boxW) }
    }

    /// 实心箭头,尖在 to,方向从 from 指过去。
    private func drawHead(_ ctx: CGContext, _ from: CGPoint, _ to: CGPoint, _ boxW: CGFloat) {
        var dx = to.x - from.x, dy = to.y - from.y
        let len = hypot(dx, dy)
        if len < 1 { return }
        dx /= len
        dy /= len
        let px = -dy, py = dx
        let head = boxW * 0.055
        ctx.beginPath()
        ctx.move(to: CGPoint(x: to.x + dx * head * 0.35, y: to.y + dy * head * 0.35))
        ctx.addLine(to: CGPoint(x: to.x - dx * head + px * head * 0.6, y: to.y - dy * head + py * head * 0.6))
        ctx.addLine(to: CGPoint(x: to.x - dx * head - px * head * 0.6, y: to.y - dy * head - py * head * 0.6))
        ctx.closePath()
        ctx.setFillColor(GuideCubeView.MINT.cgColor)
        ctx.fillPath()
    }

    private func drawTwice(_ ctx: CGContext, _ at: CGPoint, _ boxW: CGFloat) {
        let r = boxW * 0.085
        ctx.setFillColor(UIColor(argb: 0xCC00_0000).cgColor)
        ctx.fillEllipse(in: CGRect(x: at.x - r, y: at.y - r, width: r * 2, height: r * 2))
        let attributes: [NSAttributedString.Key: Any] = [
            .font: Theme.font(boxW * 0.10, .bold), .foregroundColor: GuideCubeView.MINT,
        ]
        let text = "×2" as NSString
        let size = text.size(withAttributes: attributes)
        text.draw(at: CGPoint(x: at.x - size.width / 2, y: at.y - size.height / 2), withAttributes: attributes)
    }

    /// 底部的说明条:要转那层的中心色圆点 + 指令。
    private func drawCaption(_ ctx: CGContext, _ box: CGRect, _ step: GuideStep, _ label: String) {
        let pad = box.width * 0.06
        let chip = CGRect(x: box.minX + pad, y: box.maxY - box.height * 0.20,
                          width: box.width - pad * 2, height: box.height * 0.20 - pad * 0.35)
        ctx.setFillColor(UIColor(argb: 0xE610_1C18).cgColor)
        ctx.addPath(UIBezierPath(roundedRect: chip, cornerRadius: chip.height / 2).cgPath)
        ctx.fillPath()
        let dot = step.face != " "
        let textLeft = chip.minX + chip.height * (dot ? 0.95 : 0.45)
        let textRight = chip.maxX - chip.height * 0.45
        if dot {
            let r = chip.height * 0.22
            let c = CGPoint(x: chip.minX + chip.height * 0.52, y: chip.midY)
            ctx.setFillColor(UIColor(argb: CubeColor.fromFace(step.face).argb).cgColor)
            ctx.fillEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
        }
        let text = (label + step.caption()) as NSString
        var size = chip.height * 0.40
        var attributes: [NSAttributedString.Key: Any] = [.font: Theme.font(size, .bold), .foregroundColor: UIColor.white]
        var measured = text.size(withAttributes: attributes)
        if measured.width > textRight - textLeft {
            size *= (textRight - textLeft) / measured.width
            attributes[.font] = Theme.font(size, .bold)
            measured = text.size(withAttributes: attributes)
        }
        text.draw(at: CGPoint(x: (textLeft + textRight - measured.width) / 2, y: chip.midY - measured.height / 2),
                  withAttributes: attributes)
    }
}

/// CADisplayLink 强引用 target;经由它中转,视图释放时循环自然断开。
private final class WeakTarget: NSObject {
    private weak var view: GuideCubeView?

    init(_ view: GuideCubeView) { self.view = view }

    @objc func tick() { view?.tick() }
}
