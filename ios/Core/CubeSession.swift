import Foundation

// Port of com.mofang.cubear.MainActivity 中与平台无关的一半:扫描 → 核对 → 引导还原。
//
// 线程:相机线程只把帧变成 DetectedFace;持有扫描状态的一切——跟踪器、采集门、稳定器、
// 装配器、引导——都在主线程上,由 handleFrame 逐帧喂入,从不跨线程共享。组装在后台
// 队列上对池的快照进行。
//
// 相机、语音、震动与绘制由宿主(iOS 的 CubeARViewController)通过 CubeSessionHost 提供,
// 这样整条流程能在 Linux 上用 XCTest 跑通。

protocol CubeSessionHost: AnyObject {
    /// 状态变了,重新渲染(对应安卓 publish())。
    func sessionChanged()
    /// 播报一句话;语音关闭时由宿主忽略。新的一句打断旧的。
    func speak(_ text: String)
    func stopSpeaking()
    /// 短震(采到一面、走了一步)或重震(识别完成、还原完成)。
    func pulse(strong: Bool)
    /// 采到一个新面:叠加层闪一下。
    func faceCaptured()
    /// 还原完成:撒彩纸。
    func celebrate()
    /// 六面组装失败时的观测池,供离线复现。
    func scanFailed(_ observations: [FaceSample])
}

final class CubeSession {
    typealias Phase = CubeUiState.Phase

    /// 四边形最短边占帧短边的比例,低于此不采集。这是防荒谬的下限而非质量门:超过约一臂
    /// 远,每张贴纸只有几个像素,中位数读起来合理却是错的。
    static let MIN_CAPTURE_SIDE_FRACTION: Float = 0.08
    /// 两帧稳定且跨度至少这么长才算一次采集。
    static let CAPTURE_SPAN_NANOS: Double = 90_000_000
    /// 连续这么多次六面核对失败,就认定观测池已被污染。
    static let POISONED_AFTER = 3
    /// 注视持续匹配不上多久(毫秒)才告诉用户可能拧错了。
    static let MISMATCH_GRACE_MS: Double = 2500

    weak var host: CubeSessionHost?

    private let background: (@escaping () -> Void) -> Void
    private let main: (@escaping () -> Void) -> Void
    /// 单调时钟,毫秒。
    private let clock: () -> Double
    /// 相机权限是否已授予:决定重置后进入扫描还是授权页。
    private let cameraAllowed: () -> Bool

    // --- 扫描状态(仅主线程)
    private let stabilizer = FaceStabilizer(framesRequired: 2, minSpanNanos: CAPTURE_SPAN_NANOS)
    private let assembler = CubeStateAssembler()
    private let detectionTracker = DetectionTracker()
    private let gate = CaptureGate()
    private(set) var phase: Phase = .permission
    private var liveDetection: DetectedFace?
    private var liveFace: FaceSample?
    private var tooFar = false
    private var lastSeenAt: Double = 0
    private var session = 0
    private(set) var busy = false
    private var lastAttemptPool = -1
    private var failedAssemblies = 0
    private var lastFailure = ""

    // --- 解法状态
    private var palette: ScanPalette?
    private(set) var cubeState: String?
    private var inferredColor: CubeColor?
    private(set) var solution: [String] = []
    private(set) var guide: GuideSession?
    /// 开始引导前魔方的持法:从最后一次扫描注视和核对页上的稳定注视学来,引导接手后
    /// 由它继续更新。
    private var frame = CubeFrame.default
    /// 在对这颗魔方的注视确立 frame 之前为 false。
    private var frameKnown = false
    /// 扫描时最后一次采集:状态已知后,它说明魔方是怎么拿着的。
    private var lastScanLook: FaceSample?
    private var lastScanLookUpright = false
    private var spokenStep = ""
    /// 注视上一次与期望状态吻合的时刻;转动中途的注视会短暂匹配不上任何状态。
    private var lastMatchAt: Double = 0
    private var guideStartedAt: Double = 0
    private(set) var solvedAfterMillis: Double = 0
    private var errorTitle = "", errorDetail = ""

    /// - Parameters:
    ///   - background: 组装与求解所用的串行后台队列
    ///   - main: 回到主线程
    ///   - clock: 单调时钟(毫秒)
    ///   - cameraAllowed: 相机权限是否已授予
    init(background: @escaping (@escaping () -> Void) -> Void,
         main: @escaping (@escaping () -> Void) -> Void,
         clock: @escaping () -> Double,
         cameraAllowed: @escaping () -> Bool) {
        self.background = background
        self.main = main
        self.clock = clock
        self.cameraAllowed = cameraAllowed
    }

    /// 扫描、核对与引导都在跟踪画面(核对页也看着:点"开始还原"时第一条指令已经知道
    /// 用户举着哪一面)。宿主据此决定要不要对着魔方对焦测光。
    var isTracking: Bool { phase == .scanning || phase == .guiding || phase == .ready }

    // ------------------------------------------------------------------------ frames

    /// 主线程:一帧的检测结果,没看到面时为 nil(必须照样报告)。
    func handleFrame(_ face: DetectedFace?, nanos: Double) {
        liveDetection = detectionTracker.update(face)
        liveFace = face?.sample
        if face != nil { lastSeenAt = clock() }
        tooFar = liveDetection.map { $0.minSideFraction < CubeSession.MIN_CAPTURE_SIDE_FRACTION } ?? false
        if isTracking {
            let admitted = gate.admit(face) && !tooFar
            if let stable = stabilizer.push(admitted ? face?.sample : nil, nowNanos: nanos) {
                stabilizer.resetCandidate()
                let upright = face.map { MoveTracker.rollStable($0.corners) } ?? false
                switch phase {
                case .scanning: onCapture(stable, upright)
                case .ready: learnFrame(stable, cubeState, upright)
                default: onGuideObservation(stable, upright)
                }
            }
        }
        host?.sessionChanged()
    }

    // ------------------------------------------------------------------------ scanning

    private func onCapture(_ face: FaceSample, _ upright: Bool) {
        if face.lab == nil && face.center == .unknown { return }
        lastScanLook = face
        lastScanLookUpright = upright
        if assembler.put(face) {
            host?.faceCaptured()
            host?.pulse(strong: false)
        }
        maybeAssemble()
    }

    /// 池能支撑一次组装、且自上次尝试后有变化时,开始组装。
    private func maybeAssemble() {
        if busy || phase != .scanning { return }
        let pool = assembler.accepted
        // 只认六个扫描面:推算第六面会把误读的贴纸变成一颗错误的魔方,还原到一半才暴露。
        if !assembler.isComplete || pool == lastAttemptPool { return }
        lastAttemptPool = pool
        busy = true
        let generation = session
        let snapshot = assembler.copy()
        let main = self.main
        background { [weak self] in
            let state = snapshot.assemble()
            let moves = state.flatMap(CubeSession.solve)
            let found = snapshot.palette
            let why = snapshot.lastFailure
            let suspect = state == nil ? snapshot.suspect : nil
            let failedPool = state == nil ? snapshot.observations : []
            // 超时的搜索不能证明池有问题。
            let conclusive = !snapshot.lastAttemptTimedOut
            main {
                guard let self = self else { return }
                if state == nil { self.host?.scanFailed(failedPool) }
                // 可疑的面重新读:它的旧注视会压过新注视。
                if generation == self.session, let suspect = suspect { self.assembler.forget(suspect) }
                self.onAssembled(generation, conclusive, state, moves, found, why)
            }
        }
        host?.sessionChanged()
    }

    /// 工作线程。min2phase 拒绝该状态时为 nil。
    static func solve(_ state: String) -> [String]? {
        let result = Search().solution(state, 21, 100_000_000, 0, 0)
            .trimmingCharacters(in: .whitespaces)
        if result.hasPrefix("Error") { return nil }
        return result.split(separator: " ").map(String.init).filter { $0 != "." }
    }

    /// - Parameter conclusive: 搜索到底的六面尝试,其失败计入"池已污染"的判断
    private func onAssembled(_ generation: Int, _ conclusive: Bool, _ state: String?,
                             _ moves: [String]?, _ found: ScanPalette?, _ why: String?) {
        if generation != session { return }
        busy = false
        if phase != .scanning { return }
        guard let state = state, let moves = moves else {
            lastFailure = why ?? ""
            if conclusive {
                failedAssemblies += 1
                if failedAssemblies >= CubeSession.POISONED_AFTER {
                    // 六个都看清了的面仍然互相矛盾:扫描中途魔方变了(拧过某层),
                    // 再多看也救不回混在一起的池。
                    resetSession()
                    lastFailure = "读数互相矛盾，已重新开始。扫描时请不要拧动魔方的层"
                }
            }
            host?.sessionChanged()
            maybeAssemble()
            return
        }
        failedAssemblies = 0
        palette = found
        cubeState = state
        inferredColor = found?.missingColor
        solution = moves
        frame = .default
        frameKnown = false
        if let look = lastScanLook { learnFrame(look, state, lastScanLookUpright) }
        host?.pulse(strong: true)
        if solution.isEmpty {
            phase = .solved
            solvedAfterMillis = 0
            host?.celebrate()
        } else {
            phase = .ready
        }
        host?.speak(solution.isEmpty ? "魔方已经是还原状态" : "识别完成，共 \(solution.count) 步")
        host?.sessionChanged()
    }

    // ------------------------------------------------------------------------ guidance

    func startGuiding() {
        guard let cubeState = cubeState else { return }
        guide = GuideSession(cubeState, solution, frame, frameKnown)
        spokenStep = ""
        lastMatchAt = clock()
        guideStartedAt = clock()
        stabilizer.resetCandidate()
        phase = .guiding
        speakCurrentMove()
        host?.sessionChanged()
    }

    private func onGuideObservation(_ stable: FaceSample, _ upright: Bool) {
        guard let guide = guide else { return }
        palette?.maybeLearn(stable)
        let named = palette?.relabel(stable) ?? stable
        let result = guide.observe(named, upright)
        if result == .advanced || result == .confirmed { lastMatchAt = clock() }
        if result == .advanced {
            onStepChanged(forward: true)
        } else if result == .confirmed {
            respeakIfReworded()
        }
    }

    /// 由核对页上的一次稳定注视更新 frame。
    private func learnFrame(_ stable: FaceSample, _ state: String?, _ upright: Bool) {
        let named = palette?.relabel(stable) ?? stable
        guard let next = GuideSession.fromLook(named, state, upright, frameKnown ? frame : nil) else { return }
        frame = next
        frameKnown = true
    }

    /// 现在要显示的步骤,没有时为 nil。
    var currentStep: GuideStep? { guide?.currentStep }

    /// 上一步(-1)/下一步(+1)按钮。
    func stepBy(_ delta: Int) {
        guard let guide = guide, phase == .guiding else { return }
        if delta > 0 { guide.next() } else { guide.previous() }
        stabilizer.resetCandidate()
        lastMatchAt = clock()
        onStepChanged(forward: delta > 0)
    }

    private func onStepChanged(forward: Bool) {
        guard let guide = guide else { return }
        if guide.isDone {
            phase = .solved
            solvedAfterMillis = clock() - guideStartedAt
            host?.celebrate()
            host?.pulse(strong: true)
            host?.speak("还原完成")
        } else {
            if forward { host?.pulse(strong: false) }
            speakCurrentMove()
        }
        host?.sessionChanged()
    }

    /// 播报当前步骤(语音开关打开时宿主也会调用)。
    func speakCurrentMove() {
        guard let step = currentStep else { return }
        spokenStep = "\(step.first):\(step.caption())"
        host?.speak(CubeSession.stepTitle(step).replacingOccurrences(of: "–", with: " 到 ")
            + "，" + step.caption()
            + (step.seen ? "" : isLastStep(step) ? "，拧完把魔方转个面给镜头看" : "，拧完点下一步"))
    }

    /// 换了握法让指令措辞变了、步骤没变时,重新读一遍。
    private func respeakIfReworded() {
        guard let step = currentStep else { return }
        if spokenStep != "\(step.first):\(step.caption())" { speakCurrentMove() }
    }

    static func stepTitle(_ step: GuideStep) -> String {
        step.count == 1 ? "第 \(step.first + 1) 步"
            : "第 \(step.first + 1)–\(step.first + step.count) 步"
    }

    /// 之后再无步骤。
    private func isLastStep(_ step: GuideStep) -> Bool {
        guard let guide = guide else { return false }
        return step.first + step.count >= guide.trackerValue.size
    }

    /// 链式步骤第一段做完时的魔方,供 3D 小魔方分两段演示;单步为 nil。
    func partwayState(_ step: GuideStep) -> String? {
        guard let guide = guide, step.then != nil else { return nil }
        return guide.trackerValue.states[step.first + step.alone().count]
    }

    // ------------------------------------------------------------------------ actions

    func resetSession() {
        session += 1
        busy = false
        lastAttemptPool = -1
        failedAssemblies = 0
        lastFailure = ""
        assembler.clear()
        stabilizer.resetCandidate()
        detectionTracker.reset()
        gate.reset()
        palette = nil
        cubeState = nil
        inferredColor = nil
        solution = []
        guide = nil
        frame = .default
        frameKnown = false
        lastScanLook = nil
        liveFace = nil
        liveDetection = nil
        tooFar = false
        lastSeenAt = clock()
        phase = cameraAllowed() ? .scanning : .permission
        host?.stopSpeaking()
        host?.sessionChanged()
    }

    func showError(_ title: String, _ detail: String) {
        phase = .error
        errorTitle = title
        errorDetail = detail
        host?.sessionChanged()
    }

    /// 调试与演示:跳过扫描,直接以这颗魔方进入核对页。
    func startPreset(_ preset: String) {
        resetSession()
        phase = .scanning
        busy = true
        let generation = session
        let main = self.main
        background { [weak self] in
            let moves = CubeSession.solve(preset)
            main { self?.onAssembled(generation, false, preset, moves, nil, "") }
        }
        host?.sessionChanged()
    }

    /// 演示:这些注视当作已经采集,停在扫描页。
    func showScanned(_ looks: [FaceSample]) {
        resetSession()
        phase = .scanning
        for look in looks { assembler.put(look) }
        host?.sessionChanged()
    }

    // ------------------------------------------------------------------------ state

    func buildState() -> CubeUiState {
        let b = CubeUiState.builder(phase)
        var display = liveFace
        if phase == .guiding, let palette = palette, let live = liveFace { display = palette.relabel(live) }
        b.detection(phase == .scanning || phase == .guiding ? liveDetection : nil, display)
        b.progress(stabilizer.progress, tooFar)
        b.scan(assembler.establishedColors, assembler.size, assembler.preview())
        b.cube(cubeState, inferredColor)
        b.busy(busy)
        switch phase {
        case .permission:
            b.text("需要相机权限", "相机只用于在本机实时识别魔方，不保存、不上传画面")
        case .scanning:
            let faces = assembler.size
            let title = faces == 0 ? "对准魔方的一个面"
                : faces < 5 ? "转到下一个面"
                : faces == 5 ? "再转出最后一个面" : "正在核对颜色"
            let detail: String
            if !lastFailure.isEmpty && !busy {
                detail = lastFailure
            } else if busy {
                detail = "六个面已集齐，正在校验…"
            } else if faces == 0 {
                detail = "整个面露出来，保持不动，边框走满一圈就采好了"
            } else {
                detail = "已采集 \(faces) 个面，顺序随意"
            }
            b.text(title, detail)
            b.hint(scanHint())
        case .ready:
            b.text(inferredColor == nil ? "识别完成" : "识别完成 · 第六面已推算",
                   "核对一下展开图的颜色，然后开始还原。共 \(solution.count) 步")
        case .guiding:
            guard let guide = guide else { break }
            b.moves(guide.trackerValue.moves, guide.trackerValue.currentIndex)
            let plan = guide.planSteps(5)
            guard let step = plan.first else { break }
            b.guide(step, plan.dropFirst().map { $0.notation() }, stepOnLiveFace(step))
            b.text(CubeSession.stepTitle(step), guideDetail(step, plan.count > 1 ? plan[1] : nil))
            // 拧到一半的注视什么都匹配不上;只有停稳在意料之外的状态才说明拧错了。
            if guide.settledMismatches >= 3 && clock() - lastMatchAt > CubeSession.MISMATCH_GRACE_MS {
                b.hint("画面和预期不一致：拧错了可以点“上一步”")
            } else if !guide.isFrameKnown {
                b.hint("先把魔方任意一面对准镜头，提示会按你手里的朝向来说")
            } else if !step.seen {
                // 写在灰色正文里会被忽略:有人一直拧一层却不前进。要放在视线本来就在的地方。
                b.hint(isLastStep(step) ? "最后一下镜头看不出来：拧完把魔方转到旁边一面给镜头看"
                    : "这一步镜头看不出来，拧完点“下一步”")
            }
        case .solved:
            b.text("还原完成！", solution.isEmpty ? "魔方本来就是还原状态"
                : "共 \(solution.count) 步" + (solvedAfterMillis > 0
                    ? " · 用时 " + CubeSession.formatDuration(solvedAfterMillis) : ""))
        case .error:
            b.text(errorTitle, errorDetail)
        }
        return b.build()
    }

    /// 扫描时的情境提示,最紧要的在前。
    private func scanHint() -> String? {
        if tooFar { return "再靠近一点，色块会读得更准" }
        if liveDetection != nil && gate.struggling() { return "让整个面露出来，手指别挡住色块" }
        if liveDetection == nil && clock() - lastSeenAt > 3000 {
            return "把魔方放在画面中间，离镜头 20–30 厘米"
        }
        return nil
    }

    /// 除了指令本身,手上还要怎么做。
    ///
    /// - Parameter next: 之后的那一步,或 nil
    private func guideDetail(_ step: GuideStep, _ next: GuideStep?) -> String {
        if !step.seen && isLastStep(step) {
            // 魔方出来就是复原的,每一面都看得出来,镜头看到哪面都行。
            return "拧完魔方就复原了，但朝你这面看不出变化。把魔方转到旁边任意一面给镜头看，或点“下一步”"
        }
        if step.then != nil {
            return step.seen ? "第一下镜头看不出来，两下连着拧完，会自动进入下一步"
                : "两下连着拧完点“下一步”"
        }
        if step.isWide {
            return "等于拧后层：捏住最后面那层不动，前面两层一起拧。这样镜头看得到，拧完自动进入下一步"
        }
        if !step.seen {
            // 跟踪器会往前看:下一个看得见的动作也证明这一步做过了。
            let onward = next.map { $0.seen ? "；也可以直接接着做「\($0.caption())」，会自动跟上" : "" } ?? ""
            if !step.isBack { return "这一步镜头看不出变化，拧完点“下一步”" + onward }
            let how = step.isHalfTurn ? "拧半圈"
                : "顶上那排往" + (step.direction() == "向左" ? "左" : "右") + "推"
            return "后层离你最远，" + how + "。镜头看不到后层，拧完点“下一步”" + onward
        }
        if step.isMiddle {
            return (step.viewAxis()[0] != 0 ? "左右两层不动，只拧中间那一列" : "上下两层不动，只拧中间那一行")
                + "。拧完朝你的中心块会换颜色，这是正常的"
        }
        return "保持这一面朝着镜头，按箭头拧，拧完自动进入下一步"
    }

    /// 实时画面上的面能否承载步骤箭头:它是步骤持法的前面,且足够正,角点按学到持法时
    /// 的顺序读取。
    private func stepOnLiveFace(_ step: GuideStep) -> Bool {
        guard let guide = guide, guide.isFrameKnown, let live = liveFace,
              let detection = liveDetection else { return false }
        let named = palette?.relabel(live) ?? live
        return named.center.face == step.frame.frontFace() && MoveTracker.rollStable(detection.corners)
    }

    // ------------------------------------------------------------------------ labels

    var phaseLabel: String {
        switch phase {
        case .scanning: return "扫描"
        case .ready: return "就绪"
        case .guiding: return "还原"
        case .solved: return "完成"
        case .error: return "出错"
        case .permission: return "授权"
        }
    }

    func progressText(_ s: CubeUiState) -> String {
        switch s.phase {
        case .scanning: return "已采集 \(s.scannedCount) / 6 面"
        case .ready: return "共 \(solution.count) 步"
        case .guiding: return "\(s.moveIndex + 1) / \(s.moves.count) 步"
        case .solved: return solvedAfterMillis > 0 ? "用时 " + CubeSession.formatDuration(solvedAfterMillis) : ""
        default: return ""
        }
    }

    static func formatDuration(_ millis: Double) -> String {
        let seconds = Int(millis / 1000)
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
