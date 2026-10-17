import UIKit

// Port of com.mofang.cubear.CubeOverlayView.java
// 相机画面上的 AR 层:跟踪到的面的格栅、沿边框走的采集进度、采集反馈、引导箭头与
// 复原时的彩纸。文字与按钮在上面的 HUD 里。
//
// 检测以分析帧率到来,每秒 10–20 次,直接画会让格栅跳动。画出的四边形改为每个显示帧
// 向最新检测滑过去,且角点顺序与上一次循环匹配,检测器选的左上角翻转时格子不会打转。
//
// 安卓在整屏 Canvas 上重画;这里每个元素是一个 CAShapeLayer,每帧只换路径,由
// Core Animation 在 GPU 上合成——分析流水线需要的 CPU 不被整屏重绘吃掉。

final class CubeOverlayView: UIView {
    private static let MINT = UIColor(argb: 0xFF6F_F2C2)
    private static let AMBER = UIColor(argb: 0xFFFF_C857)
    private static let INK = UIColor(argb: 0xFF05_261D)
    private static let FLASH_MS: Double = 520
    /// 滑向新检测的时间常数。
    private static let GLIDE_MS: Double = 55
    private static let DASH: [NSNumber] = [14, 9]

    private var state = CubeUiState.builder(.scanning).build()
    private var shown = [CGFloat](repeating: 0, count: 8)
    private var target = [CGFloat](repeating: 0, count: 8)
    private var hasShown = false
    private var shownShift = 0
    private var visibility: CGFloat = 0
    private var shownProgress: CGFloat = 0
    private var lastFrame: Double = 0
    private var flashAt: Double = -100_000
    private var link: CADisplayLink?

    private let cells = (0..<9).map { _ in CAShapeLayer() }
    private let grid = CAShapeLayer()
    private let edge = CAShapeLayer()
    private let ticks = CAShapeLayer()
    private let ring = CAShapeLayer()
    private let flashFill = CAShapeLayer()
    private let flashFrame = CAShapeLayer()
    private let badge = CAShapeLayer()
    private let check = CAShapeLayer()
    private let arrowShadow = CAShapeLayer()
    private let arrow = CAShapeLayer()
    private let arrowHead = CAShapeLayer()
    private let searching = CAShapeLayer()
    private let twice = UILabel()
    private var confetti: CAEmitterLayer?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
        let strokes = [grid, edge, ticks, ring, flashFrame, check, arrowShadow, arrow, searching]
        for shape in strokes {
            shape.fillColor = nil
            shape.lineCap = .round
            shape.lineJoin = .round
        }
        for shape in cells + [grid, edge, ticks, ring, flashFill, flashFrame, badge, check,
                              arrowShadow, arrow, arrowHead, searching] {
            shape.isHidden = true
            layer.addSublayer(shape)
        }
        grid.lineCap = .butt
        twice.text = "×2"
        twice.font = Theme.font(26, .bold)
        twice.textColor = CubeOverlayView.MINT
        twice.textAlignment = .center
        twice.backgroundColor = UIColor(argb: 0xCC00_0000)
        twice.layer.cornerRadius = 22
        twice.clipsToBounds = true
        twice.frame = CGRect(x: 0, y: 0, width: 44, height: 44)
        twice.isHidden = true
        addSubview(twice)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func setState(_ state: CubeUiState) {
        self.state = state
        wake()
    }

    /// 采到一个面后在四边形上短暂闪一下。
    func onFaceCaptured() {
        flashAt = CubeOverlayView.now()
        wake()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        for sublayer in layer.sublayers ?? [] where sublayer is CAShapeLayer {
            sublayer.frame = bounds
        }
    }

    // MARK: - 动画循环

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            if link == nil {
                let link = CADisplayLink(target: OverlayTicker(self), selector: #selector(OverlayTicker.tick))
                link.add(to: .main, forMode: .common)
                self.link = link
            }
        } else {
            link?.invalidate()
            link = nil
        }
    }

    private func wake() {
        link?.isPaused = false
    }

    private static func now() -> Double { CACurrentMediaTime() * 1000 }

    fileprivate func tick() {
        let now = CubeOverlayView.now()
        let dt = lastFrame == 0 ? 16 : min(60, now - lastFrame)
        lastFrame = now
        var animating = false

        let tracked = mapDetection()
        if tracked {
            var shift = hasShown ? CubeOverlayView.bestShift(shown, target) : 0
            var jump: CGFloat = 0
            for i in 0..<4 {
                let j = (i + shift) % 4
                jump = max(jump, hypot(target[j * 2] - shown[i * 2], target[j * 2 + 1] - shown[i * 2 + 1]))
            }
            let diagonal = hypot(target[0] - target[4], target[1] - target[5])
            if !hasShown || visibility < 0.05 || jump > diagonal * 0.6 {
                shift = 0
                shown = target
            } else {
                let k = CGFloat(1 - exp(-dt / CubeOverlayView.GLIDE_MS))
                var next = shown
                for i in 0..<4 {
                    let j = (i + shift) % 4
                    next[i * 2] += (target[j * 2] - shown[i * 2]) * k
                    next[i * 2 + 1] += (target[j * 2 + 1] - shown[i * 2 + 1]) * k
                }
                shown = next
                if jump > 0.5 { animating = true }
            }
            shownShift = shift
            hasShown = true
            visibility = min(1, visibility + CGFloat(dt / 110))
        } else {
            visibility = max(0, visibility - CGFloat(dt / 200))
        }
        if visibility > 0 && visibility < 1 { animating = true }

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let latticeShown = hasShown && visibility > 0.01
        if latticeShown {
            drawLattice()
            if drawRing(dt) { animating = true }
            if drawFlash(now) { animating = true }
        } else {
            hide(cells + [grid, edge, ticks, ring, flashFill, flashFrame, badge, check])
        }
        var arrowShown = false
        if latticeShown && tracked && state.phase == .guiding && state.stepOnLiveFace,
           let step = state.guideStep {
            arrowShown = drawStepArrow(step, now)
            animating = true
        }
        if !arrowShown {
            hide([arrowShadow, arrow, arrowHead])
            twice.isHidden = true
        }
        if !tracked && (state.phase == .scanning || state.phase == .guiding) {
            drawSearching(now)
            animating = true
        } else {
            searching.isHidden = true
        }
        CATransaction.commit()
        link?.isPaused = !animating
    }

    private func hide(_ shapes: [CAShapeLayer]) {
        for shape in shapes { shape.isHidden = true }
    }

    /// 把检测映射到视图坐标;预览层 resizeAspectFill 以同样方式裁剪分析帧。
    private func mapDetection() -> Bool {
        guard let detection = state.detectedFace, detection.imageWidth > 0, detection.imageHeight > 0,
              bounds.width > 0 else { return false }
        let iw = CGFloat(detection.imageWidth), ih = CGFloat(detection.imageHeight)
        let scale = max(bounds.width / iw, bounds.height / ih)
        let ox = (bounds.width - iw * scale) / 2, oy = (bounds.height - ih * scale) / 2
        for i in 0..<4 {
            target[i * 2] = ox + CGFloat(detection.corners[i * 2]) * scale
            target[i * 2 + 1] = oy + CGFloat(detection.corners[i * 2 + 1]) * scale
        }
        return true
    }

    /// to 的角点循环移位多少,最贴近 from 的角点。
    private static func bestShift(_ from: [CGFloat], _ to: [CGFloat]) -> Int {
        var best = 0
        var lowest = CGFloat.greatestFiniteMagnitude
        for shift in 0..<4 {
            var cost: CGFloat = 0
            for i in 0..<4 {
                let j = (i + shift) % 4
                cost += hypot(to[j * 2] - from[i * 2], to[j * 2 + 1] - from[i * 2 + 1])
            }
            if cost < lowest {
                lowest = cost
                best = shift
            }
        }
        return best
    }

    private func point(_ i: Int) -> CGPoint { CGPoint(x: shown[i * 2], y: shown[i * 2 + 1]) }

    private func quadPath(grow: CGFloat = 1) -> CGPath {
        let c = centroid()
        let path = CGMutablePath()
        for i in 0..<4 {
            let p = CGPoint(x: c.x + (shown[i * 2] - c.x) * grow, y: c.y + (shown[i * 2 + 1] - c.y) * grow)
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        path.closeSubpath()
        return path
    }

    private func centroid() -> CGPoint {
        CGPoint(x: (shown[0] + shown[2] + shown[4] + shown[6]) / 4,
                y: (shown[1] + shown[3] + shown[5] + shown[7]) / 4)
    }

    private static func map(_ h: [Double], _ x: Double, _ y: Double) -> CGPoint {
        var out = [0.0, 0.0]
        Homography.apply(h, x, y, &out)
        return CGPoint(x: out[0], y: out[1])
    }

    // MARK: - 格栅

    private func drawLattice() {
        guard let toView = Homography.fromSquare(3, shown.map { Double($0) }) else {
            hide(cells + [grid, edge, ticks])
            return
        }
        let alpha = visibility
        // 画出的四边形可能从检测的另一个角起笔;读数转同样的量,每种颜色才落在自己的贴纸上。
        var live = state.liveFace
        if var turned = live {
            for _ in 0..<((4 - shownShift) % 4) { turned = turned.rotateClockwise() }
            live = turned
        }
        let refined = state.detectedFace?.refined ?? false
        let disputed = state.detectedFace?.disputed ?? false
        let readable = live.map { $0.unreliableCount == 0 } ?? false
        let edgeColor = state.tooFarToCapture ? CubeOverlayView.AMBER
            : readable && refined ? UIColor(argb: 0xFFDF_FFF3) : UIColor.white

        for cell in 0..<9 {
            let shape = cells[cell]
            guard let live = live, !state.tooFarToCapture, live.reliable[cell],
                  live.stickers[cell] != .unknown else {
                shape.isHidden = true
                continue
            }
            shape.path = cellPath(toView, cell % 3, cell / 3, 0.14)
            shape.fillColor = UIColor(argb: live.stickers[cell].argb).withAlphaComponent(alpha * 0.58).cgColor
            shape.isHidden = false
        }

        let lines = CGMutablePath()
        for k in 1..<3 {
            lines.move(to: CubeOverlayView.map(toView, Double(k), 0))
            lines.addLine(to: CubeOverlayView.map(toView, Double(k), 3))
            lines.move(to: CubeOverlayView.map(toView, 0, Double(k)))
            lines.addLine(to: CubeOverlayView.map(toView, 3, Double(k)))
        }
        grid.path = lines
        grid.lineWidth = 1.2
        grid.strokeColor = UIColor.white.withAlphaComponent(alpha * 0.45).cgColor
        grid.isHidden = false

        let edgeAlpha = disputed ? alpha / 2 : alpha
        edge.path = quadPath()
        edge.lineWidth = refined ? 3.2 : 2.4
        edge.strokeColor = edgeColor.withAlphaComponent(edgeAlpha).cgColor
        edge.lineDashPattern = disputed ? CubeOverlayView.DASH : nil
        edge.isHidden = false

        // 角上的短线让边框读作"锁定",而不只是一圈轮廓。
        let marks = CGMutablePath()
        for i in 0..<4 {
            let p = point(i), next = point((i + 1) % 4), prev = point((i + 3) % 4)
            marks.move(to: p)
            marks.addLine(to: CGPoint(x: p.x + (next.x - p.x) * 0.16, y: p.y + (next.y - p.y) * 0.16))
            marks.move(to: p)
            marks.addLine(to: CGPoint(x: p.x + (prev.x - p.x) * 0.16, y: p.y + (prev.y - p.y) * 0.16))
        }
        ticks.path = marks
        ticks.lineWidth = 5
        ticks.strokeColor = edge.strokeColor
        ticks.isHidden = false
    }

    private func cellPath(_ toView: [Double], _ col: Int, _ row: Int, _ inset: Double) -> CGPath {
        let c = Double(col), r = Double(row)
        let corners = [(c + inset, r + inset), (c + 1 - inset, r + inset),
                       (c + 1 - inset, r + 1 - inset), (c + inset, r + 1 - inset)]
        let path = CGMutablePath()
        for (i, corner) in corners.enumerated() {
            let p = CubeOverlayView.map(toView, corner.0, corner.1)
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        path.closeSubpath()
        return path
    }

    /// 采集进度沿面自己的边框走,从左上角起。在面外另画一个圆环会读成第二个、更大的目标,
    /// 与格栅争夺注意力;画在用户正盯着的边框上,"别动"就说在了视线所在的地方。
    private func drawRing(_ dt: Double) -> Bool {
        let goal: CGFloat = state.phase == .scanning || state.phase == .guiding
            ? CGFloat(state.stabilizeProgress) : 0
        let k = CGFloat(1 - exp(-dt / 70))
        shownProgress += (goal - shownProgress) * k
        if abs(goal - shownProgress) < 0.003 { shownProgress = goal }
        let animating = abs(goal - shownProgress) > 0.003
        if shownProgress <= 0.01 || state.tooFarToCapture {
            ring.isHidden = true
            return animating
        }
        // 从画出的四边形里最靠左上的角起笔,进度总从那里开始。
        var start = 0
        var best = CGFloat.greatestFiniteMagnitude
        for i in 0..<4 where shown[i * 2] + shown[i * 2 + 1] < best {
            best = shown[i * 2] + shown[i * 2 + 1]
            start = i
        }
        var sides = [CGFloat](repeating: 0, count: 4)
        for i in 0..<4 {
            let a = point((start + i) % 4), b = point((start + i + 1) % 4)
            sides[i] = hypot(b.x - a.x, b.y - a.y)
        }
        var remaining = sides.reduce(0, +) * min(1, shownProgress)
        let path = CGMutablePath()
        path.move(to: point(start))
        for i in 0..<4 where remaining > 0 {
            let a = point((start + i) % 4), b = point((start + i + 1) % 4)
            let t = min(1, remaining / max(sides[i], 1e-3))
            path.addLine(to: CGPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t))
            remaining -= sides[i]
        }
        ring.path = path
        ring.lineWidth = 6
        ring.strokeColor = CubeOverlayView.MINT.withAlphaComponent(visibility).cgColor
        ring.isHidden = false
        return animating
    }

    /// 面上一闪白光、向外扩的薄荷绿框和一个对勾。
    private func drawFlash(_ now: Double) -> Bool {
        let elapsed = now - flashAt
        guard elapsed >= 0 && elapsed <= CubeOverlayView.FLASH_MS else {
            hide([flashFill, flashFrame, badge, check])
            return false
        }
        let t = CGFloat(elapsed / CubeOverlayView.FLASH_MS)
        let ease = 1 - t * t
        flashFill.path = quadPath()
        flashFill.fillColor = UIColor.white.withAlphaComponent(90 / 255 * ease).cgColor
        flashFill.isHidden = false

        flashFrame.path = quadPath(grow: 1 + 0.18 * t)
        flashFrame.lineWidth = 3
        flashFrame.strokeColor = CubeOverlayView.MINT.withAlphaComponent(230 / 255 * ease).cgColor
        flashFrame.isHidden = false

        let c = centroid()
        let r = 17 * (0.7 + 0.3 * min(1, t * 4))
        badge.path = CGPath(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2), transform: nil)
        badge.fillColor = CubeOverlayView.MINT.withAlphaComponent(ease).cgColor
        badge.isHidden = false

        let tick = CGMutablePath()
        tick.move(to: CGPoint(x: c.x - r * 0.42, y: c.y + r * 0.02))
        tick.addLine(to: CGPoint(x: c.x - r * 0.10, y: c.y + r * 0.34))
        tick.addLine(to: CGPoint(x: c.x + r * 0.45, y: c.y - r * 0.30))
        check.path = tick
        check.lineWidth = 3.2
        check.strokeColor = CubeOverlayView.INK.withAlphaComponent(ease).cgColor
        check.isHidden = false
        return true
    }

    // MARK: - 引导箭头

    /// 当前步骤的方向提示,画在用户举着的那一面上:整面转动画一圈,侧层或中间层带过这一面
    /// 的那一行/列画箭头。后层不画:魔方举得高时面上方的箭头落在顶栏上,右上角的小魔方
    /// 已经演示那层顶排怎么走。
    private func drawStepArrow(_ step: GuideStep, _ now: Double) -> Bool {
        if step.turnsFrontFace() {
            drawRingArrow(now, counter: step.quarters > 0, twice: step.isHalfTurn)
            return true
        }
        let axis = step.viewAxis()
        if axis[2] != 0 { return false }
        // 持法是按检测自己的角点顺序学到的;画出的四边形可能从别的角起,所以映射检测的格栅。
        var quad = [Double](repeating: 0, count: 8)
        for j in 0..<4 {
            let i = (j - shownShift + 4) % 4
            quad[j * 2] = Double(shown[i * 2])
            quad[j * 2 + 1] = Double(shown[i * 2 + 1])
        }
        guard let toView = Homography.fromSquare(3, quad) else { return false }
        // 格栅单位:u 向右、v 向下,一格一张贴纸;视图 +y 是格栅 -v。
        let dir = step.motion(GuideStep.VIEW_FRONT)
        let cu = 1.5 + Double(axis[0] * step.layer), cv = 1.5 - Double(axis[1] * step.layer)
        let du = Double(dir[0]), dv = Double(-dir[1]), reach = 1.3
        let from = CubeOverlayView.map(toView, cu - du * reach, cv - dv * reach)
        let to = CubeOverlayView.map(toView, cu + du * reach, cv + dv * reach)
        let line = CGMutablePath()
        line.move(to: from)
        line.addLine(to: to)
        styleArrow(line, shadowWidth: 10, now: now)

        let len = hypot(to.x - from.x, to.y - from.y)
        guard len >= 1 else { return true }
        let ux = (to.x - from.x) / len, uy = (to.y - from.y) / len
        let h: CGFloat = 16
        let head = CGMutablePath()
        head.move(to: CGPoint(x: to.x + ux * h * 0.5, y: to.y + uy * h * 0.5))
        head.addLine(to: CGPoint(x: to.x - ux * h * 0.6 - uy * h * 0.75, y: to.y - uy * h * 0.6 + ux * h * 0.75))
        head.addLine(to: CGPoint(x: to.x - ux * h * 0.6 + uy * h * 0.75, y: to.y - uy * h * 0.6 - ux * h * 0.75))
        head.closeSubpath()
        showHead(head)
        showTwice(step.isHalfTurn, at: CGPoint(x: (from.x + to.x) / 2, y: (from.y + to.y) / 2))
        return true
    }

    /// 围着要转的面画一圈箭头,沿转动方向流动。镜头从外面看着那一面——记号正是从这个
    /// 视角定义的——所以屏幕上的顺时针就是魔方上的顺时针。
    private func drawRingArrow(_ now: Double, counter: Bool, twice isTwice: Bool) {
        let c = centroid()
        var radius: CGFloat = 0
        for i in 0..<4 { radius = max(radius, hypot(shown[i * 2] - c.x, shown[i * 2 + 1] - c.y)) }
        radius *= 0.72
        let start: CGFloat = (counter ? 120 : 60) * .pi / 180
        let sweep: CGFloat = (counter ? -1 : 1) * (isTwice ? 300 : 240) * .pi / 180
        // 按转动方向逐点画弧,虚线相位前移时就总是朝该转的方向流动。
        let arc = CGMutablePath()
        let segments = 60
        for i in 0...segments {
            let a = start + sweep * CGFloat(i) / CGFloat(segments)
            let p = CGPoint(x: c.x + radius * cos(a), y: c.y + radius * sin(a))
            if i == 0 { arc.move(to: p) } else { arc.addLine(to: p) }
        }
        styleArrow(arc, shadowWidth: 9, now: now)

        let tip = start + sweep
        let tx = c.x + radius * cos(tip), ty = c.y + radius * sin(tip)
        // 沿行进方向的切线。
        let dir: CGFloat = counter ? -1 : 1
        let ux = -sin(tip) * dir, uy = cos(tip) * dir
        let h: CGFloat = 16
        let head = CGMutablePath()
        head.move(to: CGPoint(x: tx + ux * h, y: ty + uy * h))
        head.addLine(to: CGPoint(x: tx - uy * h * 0.75, y: ty + ux * h * 0.75))
        head.addLine(to: CGPoint(x: tx + uy * h * 0.75, y: ty - ux * h * 0.75))
        head.closeSubpath()
        showHead(head)
        showTwice(isTwice, at: c)
    }

    private func styleArrow(_ path: CGPath, shadowWidth: CGFloat, now: Double) {
        arrowShadow.path = path
        arrowShadow.lineWidth = shadowWidth
        arrowShadow.strokeColor = UIColor(argb: 0x6600_0000).cgColor
        arrowShadow.isHidden = false
        arrow.path = path
        arrow.lineWidth = 6
        arrow.strokeColor = CubeOverlayView.MINT.cgColor
        arrow.lineDashPattern = CubeOverlayView.DASH
        // 相位往负方向走,虚线就沿路径前进:每 540 毫秒走过一个 14+9 的周期。
        arrow.lineDashPhase = -CGFloat(now.truncatingRemainder(dividingBy: 540) / 540) * 23
        arrow.isHidden = false
    }

    private func showHead(_ path: CGPath) {
        arrowHead.path = path
        arrowHead.fillColor = CubeOverlayView.MINT.cgColor
        arrowHead.isHidden = false
    }

    private func showTwice(_ visible: Bool, at center: CGPoint) {
        twice.isHidden = !visible
        if visible { twice.center = center }
    }

    /// 没有跟踪到面时,画面中间四个呼吸的角框。
    private func drawSearching(_ now: Double) {
        let w = bounds.width, h = bounds.height
        let cx = w / 2, cy = h * 0.40
        let breath = CGFloat(0.5 + 0.5 * sin(now / 520))
        let half = min(w, h) * (0.30 + 0.02 * breath)
        let arm = half * 0.28
        let path = CGMutablePath()
        for (sx, sy) in [(-1.0, -1.0), (1.0, -1.0), (1.0, 1.0), (-1.0, 1.0)] as [(CGFloat, CGFloat)] {
            let x = cx + sx * half, y = cy + sy * half
            path.move(to: CGPoint(x: x - sx * arm, y: y))
            path.addLine(to: CGPoint(x: x, y: y))
            path.addLine(to: CGPoint(x: x, y: y - sy * arm))
        }
        searching.path = path
        searching.lineWidth = 4
        searching.strokeColor = UIColor.white.withAlphaComponent((110 + 90 * breath) / 255).cgColor
        searching.isHidden = false
    }

    // MARK: - 彩纸

    /// 复原时撒一把彩纸:从画面中上部抛起,受重力落下,2.6 秒后淡出。
    func celebrate() {
        confetti?.removeFromSuperlayer()
        let w = max(1, bounds.width), h = max(1, bounds.height)
        let emitter = CAEmitterLayer()
        emitter.frame = bounds
        emitter.emitterPosition = CGPoint(x: w / 2, y: h * 0.35)
        emitter.emitterShape = .rectangle
        emitter.emitterSize = CGSize(width: w * 0.8, height: h * 0.2)
        emitter.beginTime = CACurrentMediaTime()
        let palette: [UInt32] = [CubeColor.white.argb, CubeColor.red.argb, CubeColor.green.argb,
                                 CubeColor.yellow.argb, CubeColor.orange.argb, CubeColor.blue.argb, 0xFF6F_F2C2]
        emitter.emitterCells = palette.map { argb -> CAEmitterCell in
            let cell = CAEmitterCell()
            cell.contents = CubeOverlayView.confettiPiece
            cell.color = UIColor(argb: argb).cgColor
            // 约 0.12 秒的一阵,总共九十来片。
            cell.birthRate = 110
            cell.lifetime = 2.6
            cell.velocity = h * 0.8
            cell.velocityRange = h * 0.25
            cell.emissionLongitude = -.pi / 2
            cell.emissionRange = .pi / 3
            cell.yAcceleration = h * 1.6
            cell.spinRange = 12
            // 碎片图是 2 倍像素画的,缩回一半就是 14×6 点,再上下浮动四成。
            cell.scale = 0.5
            cell.scaleRange = 0.2
            return cell
        }
        layer.addSublayer(emitter)
        confetti = emitter
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { emitter.birthRate = 0 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.1) {
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 1
            fade.toValue = 0
            fade.duration = 0.5
            emitter.opacity = 0
            emitter.add(fade, forKey: "fade")
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.7) { [weak self] in
            emitter.removeFromSuperlayer()
            if self?.confetti === emitter { self?.confetti = nil }
        }
    }

    private static let confettiPiece: CGImage? = {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: 28, height: 12.6)
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            UIColor.white.setFill()
            UIBezierPath(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 3.8).fill()
        }.cgImage
    }()
}

/// CADisplayLink 强引用 target;经由它中转,视图释放时循环自然断开。
private final class OverlayTicker: NSObject {
    private weak var view: CubeOverlayView?

    init(_ view: CubeOverlayView) { self.view = view }

    @objc func tick() { view?.tick() }
}
