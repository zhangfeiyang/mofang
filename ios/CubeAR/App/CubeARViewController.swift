import AVFoundation
import UIKit

// Port of com.mofang.cubear.MainActivity.java:扫描 → 核对 → 引导还原。
//
// 线程:相机线程只把帧变成 DetectedFace(CameraManager → CubeAnalyzer);持有扫描状态的
// CubeSession 在主线程,只取最新一帧、丢掉过期的,从不排队积压。组装与求解在串行的
// 求解队列上对池的快照进行。流程本身在 Core/CubeSession.swift(可在 Linux 上测试),
// 这里是相机、语音、震动与界面。
//
// 安卓用音量键翻步;iOS 不允许 App 接管音量键(审核 2.5.9),只用屏幕按钮。

final class CubeARViewController: UIViewController, CubeSessionHost, AVSpeechSynthesizerDelegate {
    private static let privacyURL = URL(string: "https://zhangfeiyang.github.io/gewu-privacy/cube-ar-privacy.html")!

    // --- 相机与线程
    private let camera = CameraManager()
    private let analyzer = CubeAnalyzer(model: nil)
    private let solver = DispatchQueue(label: "cubear.solver", qos: .userInitiated)
    private let pendingLock = NSLock()
    private var pending: (face: DetectedFace?, nanos: Double)?
    private var frameScheduled = false
    private var session: CubeSession!
    private var lastMeteringAt: Double = 0
    private var lastMeteringPoint: CGPoint?

    // --- 语音
    private let synthesizer = AVSpeechSynthesizer()
    /// 与界面语言一致的系统语音;这种语言没有语音时为 nil,语音按钮隐藏。
    private let voice = AppStrings.speechVoice()
    private var voiceOn = true
    private let defaults = UserDefaults.standard

    /// 截图/演示:启动参数 `-demo 1 -demoScreen N`(1 扫描中,2 待开始,3 引导中)。
    private let demoScreen = UserDefaults.standard.bool(forKey: "demo")
        ? max(1, UserDefaults.standard.integer(forKey: "demoScreen")) : 0
    private var demoGuideStarted = false

    // --- 视图
    private let previewView = PreviewView()
    private let overlay = CubeOverlayView()
    private let safe = UILayoutGuide()
    private var safeConstraints: [NSLayoutConstraint] = []

    private let phaseChip = PaddedLabel()
    private let progressLabel = UILabel()
    private let voiceButton = IconButton(symbol: "speaker.wave.2.fill", label: L10n.t("a11y.voice"))
    private let torchButton = IconButton(symbol: "bolt", label: L10n.t("a11y.torch"))
    private let helpButton = IconButton(symbol: "questionmark.circle", label: L10n.t("a11y.help"))
    private let restartButton = IconButton(symbol: "arrow.clockwise", label: L10n.t("a11y.restart"))

    private let guideCard = GlassView(radius: Theme.radiusPanel)
    private let guideCube = GuideCubeView()

    private let panel = GlassView(radius: Theme.radiusPanel)
    private let hintView = PaddedLabel()
    private let hintRow = UIView()
    private let titleLabel = UILabel()
    private let detailLabel = UILabel()

    private let scanSection = UIStackView()
    private let cubeNet = CubeNetView()
    private let tips = UIStackView()
    /// 扫描页展开图固定 144 宽、靠左;余下的宽度给它。
    private let scanSpacer = UIView()
    private var netWidth: NSLayoutConstraint!
    private var netHeight: NSLayoutConstraint!

    private let guideSection = UIStackView()
    private let stepProgress = ProgressBar()
    private let moveBig = UILabel()
    private let swatch = UIView()
    private let moveDesc = UILabel()
    private let moveNext = UILabel()
    private let prevButton = PillButton.secondary(L10n.t("button.prev"), leadingSymbol: "chevron.left")
    private let nextButton = PillButton.primary(L10n.t("button.next"), trailingSymbol: "chevron.right")

    private let actionSection = UIStackView()
    private let secondaryButton = PillButton.secondary(L10n.t("button.rescan"))
    private let primaryButton = PillButton.primary(L10n.t("button.start"))

    private var helpSheet: HelpSheet?
    private var lastPhase: CubeUiState.Phase?

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.night
        voiceOn = defaults.object(forKey: "voice") as? Bool ?? true
        synthesizer.delegate = self

        let solver = self.solver
        session = CubeSession(
            background: { work in solver.async { work() } },
            main: { work in DispatchQueue.main.async { work() } },
            clock: { CACurrentMediaTime() * 1000 },
            cameraAllowed: { AVCaptureDevice.authorizationStatus(for: .video) == .authorized })
        session.host = self

        buildViews()
        wireCamera()
        // min2phase 首次使用时才建剪枝表;现在建好,第一次求解就快。
        solver.async { Search.initTables() }

        NotificationCenter.default.addObserver(self, selector: #selector(becameActive),
                                               name: UIApplication.didBecomeActiveNotification, object: nil)

        if demoScreen > 0 {
            voiceOn = false
            startDemo()
        } else if let preset = defaults.string(forKey: "state"), preset.count == 54 {
            // 调试:`-state <54 位>` 跳过扫描直接进核对页,不开相机。
            session.startPreset(preset)
        } else {
            // 拒绝过的不自动跳"设置":停在授权页,等用户点按钮。
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized, .notDetermined: requestCamera()
            default: break
            }
            if !defaults.bool(forKey: "help_seen") { showHelp(true) }
        }
        publish()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // 扫描和还原时双手都在魔方上,屏幕不能自己熄灭。
        UIApplication.shared.isIdleTimerDisabled = true
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        UIApplication.shared.isIdleTimerDisabled = false
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // SwiftUI 承载的视图控制器有时拿不到安全区,以窗口的为准。
        let own = view.safeAreaInsets
        let window = view.window?.safeAreaInsets ?? .zero
        let insets = [max(own.top, window.top), max(own.left, window.left),
                      max(own.bottom, window.bottom), max(own.right, window.right)]
        let constants = [insets[0], insets[1], -insets[2], -insets[3]]
        for (constraint, constant) in zip(safeConstraints, constants) where constraint.constant != constant {
            constraint.constant = constant
        }
    }

    @objc private func becameActive() {
        // 从"设置"里打开了相机权限回来。
        if session.phase == .permission && AVCaptureDevice.authorizationStatus(for: .video) == .authorized {
            beginScanning()
        }
    }

    // MARK: - 相机

    private func wireCamera() {
        let analyzer = self.analyzer
        camera.wantsFrame = { nanos in analyzer.wantsFrame(nowNanos: nanos) }
        camera.onFrame = { [weak self] rgba, width, height, nanos in
            let face = analyzer.process(rgba, width: width, height: height)
            self?.deliver(face, nanos)
        }
        // 网络在分析线程上加载并预热,排在第一帧分析之前:不占主线程,也不冻住第一帧。
        camera.analysisQueue.async {
            analyzer.setModel(CubeFaceModel.create())
        }
        previewView.previewLayer.session = camera.session
        previewView.previewLayer.videoGravity = .resizeAspectFill
    }

    private func requestCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            beginScanning()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted { self?.beginScanning() } else { self?.publish() }
                }
            }
        default:
            // 拒绝过一次后系统不再弹窗,只能去"设置"里打开。
            UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
        }
    }

    private func beginScanning() {
        session.resetSession()
        camera.start { [weak self] ok in
            guard let self = self else { return }
            if !ok { self.session.showError(L10n.t("error.camera.title"), L10n.t("error.camera.detail")) }
            self.publish()
        }
    }

    /// 相机线程。把最新的检测交给主线程,从不排队积压。
    private func deliver(_ face: DetectedFace?, _ nanos: Double) {
        pendingLock.lock()
        pending = (face, nanos)
        let schedule = !frameScheduled
        frameScheduled = true
        pendingLock.unlock()
        guard schedule else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.pendingLock.lock()
            let frame = self.pending
            self.pending = nil
            self.frameScheduled = false
            self.pendingLock.unlock()
            guard let frame = frame else { return }
            self.session.handleFrame(frame.face, nanos: frame.nanos)
            if self.session.isTracking { self.meter(on: frame.face) }
        }
    }

    /// 对焦与测光对准魔方。连续对焦偏向画面主体——魔方举得近时常是它身后的背景——
    /// 中心测光又会让亮桌面压暗贴纸。面停到新位置就重新对准,贴纸才清晰、曝光才对,
    /// 读数靠的就是这两样。
    private func meter(on face: DetectedFace?) {
        guard let face = face, view.bounds.width > 0 else { return }
        let now = CACurrentMediaTime() * 1000
        let w = view.bounds.width, h = view.bounds.height
        let iw = CGFloat(face.imageWidth), ih = CGFloat(face.imageHeight)
        let scale = max(w / iw, h / ih)
        let point = CGPoint(x: (w - iw * scale) / 2 + CGFloat(face.centerX) * scale,
                            y: (h - ih * scale) / 2 + CGFloat(face.centerY) * scale)
        let moved = lastMeteringPoint.map { hypot(point.x - $0.x, point.y - $0.y) / w } ?? .greatestFiniteMagnitude
        if now - lastMeteringAt < 1500 || (moved < 0.12 && now - lastMeteringAt < 6000) { return }
        lastMeteringAt = now
        lastMeteringPoint = point
        camera.focus(at: previewView.previewLayer.captureDevicePointConverted(fromLayerPoint: point))
    }

    // MARK: - CubeSessionHost

    func sessionChanged() { publish() }

    func speak(_ text: String) {
        guard voiceOn, let voice = voice else { return }
        let audio = AVAudioSession.sharedInstance()
        // 播报时压低别的 App 的声音,说完再还回去;静音开关下也要能听到指引。
        try? audio.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? audio.setActive(true)
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        synthesizer.speak(utterance)
    }

    func stopSpeaking() {
        synthesizer.stopSpeaking(at: .immediate)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        releaseAudio()
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        releaseAudio()
    }

    private func releaseAudio() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.synthesizer.isSpeaking else { return }
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    /// 采到一面、走了一步是轻震;认出魔方、还原完成是重震。
    func pulse(strong: Bool) {
        UIImpactFeedbackGenerator(style: strong ? .heavy : .light).impactOccurred()
    }

    func faceCaptured() { overlay.onFaceCaptured() }

    func celebrate() { overlay.celebrate() }

    /// 观测池写到 Documents/scan-fail.json(Xcode 下载容器取回),格式与安卓相同。
    func scanFailed(_ observations: [FaceSample]) {
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        DispatchQueue.global(qos: .utility).async {
            ScanDump.write(observations, to: documents.appendingPathComponent("scan-fail.json"))
        }
    }

    // MARK: - 操作

    private func onPrimaryAction() {
        switch session.phase {
        case .ready: session.startGuiding()
        case .solved: session.resetSession()
        case .error, .permission: requestCamera()
        default: break
        }
    }

    private func confirmRestart() {
        let losesProgress = session.phase == .guiding || session.phase == .ready
        if !losesProgress {
            if AVCaptureDevice.authorizationStatus(for: .video) == .authorized {
                session.resetSession()
            } else {
                requestCamera()
            }
            return
        }
        let alert = UIAlertController(title: L10n.t("restart.title"), message: L10n.t("restart.message"), preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: L10n.t("button.cancel"), style: .cancel))
        alert.addAction(UIAlertAction(title: L10n.t("restart.confirm"), style: .destructive) { [weak self] _ in
            self?.session.resetSession()
        })
        present(alert, animated: true)
    }

    private func toggleVoice() {
        voiceOn.toggle()
        if demoScreen == 0 { defaults.set(voiceOn, forKey: "voice") }
        if voiceOn { session.speakCurrentMove() } else { stopSpeaking() }
        publish()
    }

    private func showHelp(_ show: Bool) {
        if show {
            guard helpSheet == nil else { return }
            let sheet = HelpSheet()
            sheet.onClose = { [weak self] in self?.showHelp(false) }
            sheet.onPrivacy = { UIApplication.shared.open(CubeARViewController.privacyURL) }
            sheet.frame = view.bounds
            sheet.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            sheet.alpha = 0
            view.addSubview(sheet)
            UIView.animate(withDuration: 0.2) { sheet.alpha = 1 }
            helpSheet = sheet
        } else if let sheet = helpSheet {
            helpSheet = nil
            defaults.set(true, forKey: "help_seen")
            UIView.animate(withDuration: 0.2, animations: { sheet.alpha = 0 }) { _ in sheet.removeFromSuperview() }
        }
    }

    // MARK: - 演示

    private func startDemo() {
        solver.async { [weak self] in
            // fromScramble 依赖求解器表。
            Search.initTables()
            let state = MTools.fromScramble("R U R' U' F2 D' L B2 R' D F U2 L' D2 B R2 U' F2 L2 D")
            DispatchQueue.main.async { self?.showDemo(state) }
        }
    }

    private func showDemo(_ state: String) {
        if demoScreen == 1 {
            // 扫描中:两个面各看过两次,展开图上亮着这两面。
            let faces: [Character] = ["U", "R", "U", "R"]
            let looks = faces.map { face -> FaceSample in
                let colors = GuideStep.reading(state, CubeFrame.seen(face, 0)).map { CubeColor.fromFace($0) }
                let lab = colors.map { color -> [Float] in
                    let rgb = color.rgb
                    return Lab.fromRgb(Int((rgb >> 16) & 0xFF), Int((rgb >> 8) & 0xFF), Int(rgb & 0xFF))
                }
                return FaceSample(colors, lab, 1)
            }
            session.showScanned(looks)
        } else {
            session.startPreset(state)
        }
    }

    // MARK: - 渲染

    private func publish() {
        let state = session.buildState()
        if demoScreen == 3 && state.phase == .ready && !demoGuideStarted {
            demoGuideStarted = true
            session.startGuiding()
            return
        }
        overlay.setState(state)
        render(state)
    }

    private func render(_ s: CubeUiState) {
        let scanning = s.phase == .scanning, guiding = s.phase == .guiding, ready = s.phase == .ready
        let phaseChanged = s.phase != lastPhase
        lastPhase = s.phase

        setText(phaseChip, session.phaseLabel)
        phaseChip.textColor = s.phase == .error ? Theme.coral : s.busy ? Theme.amber : Theme.mint
        setText(progressLabel, session.progressText(s))

        voiceButton.isHidden = !((guiding || ready) && voice != nil)
        voiceButton.setSymbol(voiceOn ? "speaker.wave.2.fill" : "speaker.slash.fill")
        voiceButton.isActivated = voiceOn
        voiceButton.accessibilityValue = L10n.t(voiceOn ? "a11y.on" : "a11y.off")
        torchButton.isHidden = !camera.hasTorch
        torchButton.setSymbol(camera.torchOn ? "bolt.fill" : "bolt")
        torchButton.isActivated = camera.torchOn
        torchButton.accessibilityValue = L10n.t(camera.torchOn ? "a11y.on" : "a11y.off")

        setText(titleLabel, s.title)
        setText(detailLabel, s.detail)
        hintRow.isHidden = s.hint == nil
        if let hint = s.hint { setText(hintView, hint) }

        scanSection.isHidden = !(scanning || ready)
        tips.isHidden = !(scanning && s.scannedCount < 3)
        sizeNet(large: ready)
        if ready, let cube = s.cubeState {
            cubeNet.setState(cube)
            cubeNet.setInferred(s.inferred?.face ?? "?")
            cubeNet.setHighlight("?")
        } else if scanning {
            cubeNet.setFaces(s.preview)
            cubeNet.setInferred("?")
            let live = s.liveFace
            cubeNet.setHighlight(s.detectedFace != nil && live != nil && live!.center != .unknown
                ? live!.center.face : "?")
        }

        guideSection.isHidden = !guiding
        guideCard.isHidden = !guiding
        if guiding, let step = s.guideStep, let guide = session.guide {
            guideCube.show(guide.trackerValue.state, session.partwayState(step), step)
            stepProgress.progress = CGFloat(s.moveIndex) / CGFloat(max(1, s.moves.count))
            setText(moveBig, step.notation())
            moveBig.font = Theme.font(step.then == nil ? 52 : 34, .bold)
            setText(moveDesc, step.caption() + (step.isHalfTurn ? "" : " 90°"))
            // 要拧那层的中心色:核对 App 与手上对魔方的拿法是否一致。中间层没有。
            swatch.isHidden = step.face == " "
            if step.face != " " { swatch.backgroundColor = UIColor(argb: CubeColor.fromFace(step.face).argb) }
            setText(moveNext, s.upcoming.isEmpty ? L10n.t("guide.last")
                : L10n.t("guide.upcoming") + s.upcoming.map { "   " + $0 }.joined())
            prevButton.isEnabled = s.moveIndex > 0
        }

        let actions = ready || s.phase == .solved || s.phase == .error || s.phase == .permission
        actionSection.isHidden = !actions
        secondaryButton.isHidden = !ready
        PillButton.setTitle(primaryButton, L10n.t(ready ? "button.start" : s.phase == .solved ? "button.again"
            : s.phase == .permission ? "button.grant" : "button.rescan"))

        if phaseChanged && view.window != nil {
            UIView.animate(withDuration: 0.25) { self.view.layoutIfNeeded() }
        }
    }

    private var netLarge = false

    /// 核对页的重点是看展开图,让它占满面板宽度。
    private func sizeNet(large: Bool) {
        if large == netLarge { return }
        netLarge = large
        netWidth.isActive = !large
        scanSpacer.isHidden = large
        netHeight.constant = large ? 200 : 108
    }

    private func setText(_ label: UILabel, _ text: String) {
        if label.text != text { label.text = text }
    }

    // MARK: - 视图搭建

    private func buildViews() {
        previewView.frame = view.bounds
        previewView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(previewView)
        overlay.frame = view.bounds
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(overlay)

        view.addLayoutGuide(safe)
        safeConstraints = [
            safe.topAnchor.constraint(equalTo: view.topAnchor),
            safe.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            safe.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            safe.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ]
        NSLayoutConstraint.activate(safeConstraints)

        buildTopBar()
        buildGuideCard()
        buildPanel()
    }

    private func buildTopBar() {
        let bar = GlassView(radius: Theme.topBarHeight / 2)
        bar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bar)

        phaseChip.insets = UIEdgeInsets(top: 0, left: 10, bottom: 0, right: 12)
        phaseChip.font = Theme.font(13, .medium)
        phaseChip.textAlignment = .center
        phaseChip.backgroundColor = Theme.mintSoft
        phaseChip.layer.cornerRadius = 14
        phaseChip.clipsToBounds = true
        phaseChip.setContentHuggingPriority(.required, for: .horizontal)
        phaseChip.setContentCompressionResistancePriority(.required, for: .horizontal)
        phaseChip.heightAnchor.constraint(equalToConstant: 28).isActive = true

        progressLabel.font = Theme.font(13, .medium)
        progressLabel.textColor = Theme.textPrimary
        progressLabel.lineBreakMode = .byTruncatingTail
        progressLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        progressLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        voiceButton.addAction(UIAction { [weak self] _ in self?.toggleVoice() }, for: .touchUpInside)
        torchButton.addAction(UIAction { [weak self] _ in
            guard let self = self else { return }
            self.camera.setTorch(!self.camera.torchOn)
            self.publish()
        }, for: .touchUpInside)
        torchButton.isHidden = true
        helpButton.addAction(UIAction { [weak self] _ in self?.showHelp(true) }, for: .touchUpInside)
        restartButton.addAction(UIAction { [weak self] _ in self?.confirmRestart() }, for: .touchUpInside)

        let row = UIStackView(arrangedSubviews: [phaseChip, progressLabel, voiceButton, torchButton,
                                                 helpButton, restartButton])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 4
        row.setCustomSpacing(10, after: phaseChip)
        row.translatesAutoresizingMaskIntoConstraints = false
        bar.contentView.addSubview(row)

        NSLayoutConstraint.activate([
            bar.topAnchor.constraint(equalTo: safe.topAnchor, constant: Theme.gutter),
            bar.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: Theme.gutter),
            bar.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -Theme.gutter),
            bar.heightAnchor.constraint(equalToConstant: Theme.topBarHeight),
            row.leadingAnchor.constraint(equalTo: bar.contentView.leadingAnchor, constant: 8),
            row.trailingAnchor.constraint(equalTo: bar.contentView.trailingAnchor, constant: -6),
            row.centerYAnchor.constraint(equalTo: bar.contentView.centerYAnchor),
        ])
        topBar = bar
    }

    private var topBar: UIView!

    private func buildGuideCard() {
        guideCard.translatesAutoresizingMaskIntoConstraints = false
        guideCard.isHidden = true
        view.addSubview(guideCard)
        guideCube.translatesAutoresizingMaskIntoConstraints = false
        guideCard.contentView.addSubview(guideCube)
        NSLayoutConstraint.activate([
            guideCard.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: Theme.gutter),
            guideCard.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -Theme.gutter),
            guideCard.widthAnchor.constraint(equalToConstant: Theme.guideCardSize),
            guideCard.heightAnchor.constraint(equalToConstant: Theme.guideCardSize),
            guideCube.leadingAnchor.constraint(equalTo: guideCard.contentView.leadingAnchor),
            guideCube.trailingAnchor.constraint(equalTo: guideCard.contentView.trailingAnchor),
            guideCube.topAnchor.constraint(equalTo: guideCard.contentView.topAnchor),
            guideCube.bottomAnchor.constraint(equalTo: guideCard.contentView.bottomAnchor),
        ])
    }

    private func buildPanel() {
        panel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(panel)

        // 提示条:薄荷绿字、强玻璃底,左对齐、按内容宽(安卓 bg_hint)。
        hintView.insets = UIEdgeInsets(top: 6, left: 12, bottom: 6, right: 12)
        hintView.font = Theme.font(13, .medium)
        hintView.textColor = Theme.mint
        hintView.numberOfLines = 0
        hintView.backgroundColor = Theme.glassStrong
        hintView.layer.cornerRadius = 16
        hintView.layer.borderWidth = 1
        hintView.layer.borderColor = Theme.mintSoft.cgColor
        hintView.clipsToBounds = true
        hintView.translatesAutoresizingMaskIntoConstraints = false
        hintRow.addSubview(hintView)
        hintRow.isHidden = true
        NSLayoutConstraint.activate([
            hintView.leadingAnchor.constraint(equalTo: hintRow.leadingAnchor),
            hintView.trailingAnchor.constraint(lessThanOrEqualTo: hintRow.trailingAnchor),
            hintView.topAnchor.constraint(equalTo: hintRow.topAnchor),
            hintView.bottomAnchor.constraint(equalTo: hintRow.bottomAnchor),
        ])

        titleLabel.font = Theme.font(21, .bold)
        titleLabel.textColor = Theme.textPrimary
        titleLabel.numberOfLines = 2
        detailLabel.font = Theme.font(14)
        detailLabel.textColor = Theme.textSecondary
        detailLabel.numberOfLines = 3

        buildScanSection()
        buildGuideSection()
        buildActionSection()

        let column = UIStackView(arrangedSubviews: [hintRow, titleLabel, detailLabel, scanSection,
                                                    guideSection, actionSection])
        column.axis = .vertical
        column.spacing = 0
        column.setCustomSpacing(12, after: hintRow)
        column.setCustomSpacing(4, after: titleLabel)
        column.setCustomSpacing(16, after: detailLabel)
        column.setCustomSpacing(16, after: scanSection)
        column.setCustomSpacing(16, after: guideSection)
        column.translatesAutoresizingMaskIntoConstraints = false
        panel.contentView.addSubview(column)

        let pad = Theme.panelPadding
        NSLayoutConstraint.activate([
            panel.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: Theme.gutter),
            panel.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -Theme.gutter),
            panel.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -Theme.gutter),
            column.leadingAnchor.constraint(equalTo: panel.contentView.leadingAnchor, constant: pad),
            column.trailingAnchor.constraint(equalTo: panel.contentView.trailingAnchor, constant: -pad),
            column.topAnchor.constraint(equalTo: panel.contentView.topAnchor, constant: pad),
            column.bottomAnchor.constraint(equalTo: panel.contentView.bottomAnchor, constant: -pad),
        ])
    }

    private func buildScanSection() {
        netWidth = cubeNet.widthAnchor.constraint(equalToConstant: 144)
        netHeight = cubeNet.heightAnchor.constraint(equalToConstant: 108)
        NSLayoutConstraint.activate([netWidth, netHeight])
        cubeNet.setContentHuggingPriority(.defaultLow, for: .horizontal)

        for text in [L10n.t("tip.1"), L10n.t("tip.2"), L10n.t("tip.3")] {
            let label = UILabel()
            label.text = text
            label.font = Theme.font(12)
            label.textColor = Theme.textTertiary
            label.numberOfLines = 0
            tips.addArrangedSubview(label)
        }
        tips.axis = .vertical
        tips.spacing = 6
        tips.setContentHuggingPriority(.defaultHigh, for: .horizontal)

        scanSpacer.setContentHuggingPriority(UILayoutPriority(1), for: .horizontal)
        scanSection.addArrangedSubview(cubeNet)
        scanSection.addArrangedSubview(tips)
        scanSection.addArrangedSubview(scanSpacer)
        scanSection.axis = .horizontal
        scanSection.alignment = .center
        scanSection.spacing = 16
    }

    private func buildGuideSection() {
        stepProgress.heightAnchor.constraint(equalToConstant: 6).isActive = true

        moveBig.font = Theme.font(52, .bold)
        moveBig.textColor = Theme.mint
        moveBig.adjustsFontSizeToFitWidth = true
        moveBig.minimumScaleFactor = 0.6
        moveBig.setContentHuggingPriority(.required, for: .horizontal)
        moveBig.setContentCompressionResistancePriority(.required, for: .horizontal)
        moveBig.widthAnchor.constraint(greaterThanOrEqualToConstant: 84).isActive = true

        swatch.layer.cornerRadius = 7
        swatch.layer.borderWidth = 1
        swatch.layer.borderColor = UIColor(argb: 0x66FF_FFFF).cgColor
        swatch.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            swatch.widthAnchor.constraint(equalToConstant: 14),
            swatch.heightAnchor.constraint(equalToConstant: 14),
        ])
        moveDesc.font = Theme.font(17, .medium)
        moveDesc.textColor = Theme.textPrimary
        moveDesc.numberOfLines = 2
        let descRow = UIStackView(arrangedSubviews: [swatch, moveDesc])
        descRow.axis = .horizontal
        descRow.alignment = .center
        descRow.spacing = 8

        moveNext.font = Theme.font(12)
        moveNext.textColor = Theme.textTertiary
        moveNext.lineBreakMode = .byTruncatingTail

        let words = UIStackView(arrangedSubviews: [descRow, moveNext])
        words.axis = .vertical
        words.alignment = .leading
        words.spacing = 4

        let moveRow = UIStackView(arrangedSubviews: [moveBig, words])
        moveRow.axis = .horizontal
        moveRow.alignment = .center
        moveRow.spacing = 12

        prevButton.addAction(UIAction { [weak self] _ in self?.session.stepBy(-1) }, for: .touchUpInside)
        nextButton.addAction(UIAction { [weak self] _ in self?.session.stepBy(+1) }, for: .touchUpInside)
        let buttons = UIStackView(arrangedSubviews: [prevButton, nextButton])
        buttons.axis = .horizontal
        buttons.spacing = 10
        nextButton.widthAnchor.constraint(equalTo: prevButton.widthAnchor, multiplier: 1.5).isActive = true

        guideSection.addArrangedSubview(stepProgress)
        guideSection.addArrangedSubview(moveRow)
        guideSection.addArrangedSubview(buttons)
        guideSection.axis = .vertical
        guideSection.setCustomSpacing(10, after: stepProgress)
        guideSection.setCustomSpacing(16, after: moveRow)
        guideSection.isHidden = true
    }

    private func buildActionSection() {
        secondaryButton.addAction(UIAction { [weak self] _ in self?.session.resetSession() }, for: .touchUpInside)
        primaryButton.addAction(UIAction { [weak self] _ in self?.onPrimaryAction() }, for: .touchUpInside)
        actionSection.addArrangedSubview(secondaryButton)
        actionSection.addArrangedSubview(primaryButton)
        actionSection.axis = .horizontal
        actionSection.spacing = 10
        let ratio = primaryButton.widthAnchor.constraint(equalTo: secondaryButton.widthAnchor, multiplier: 1.5)
        // 次按钮隐藏时主按钮独占一行,比例约束要能让路。
        ratio.priority = .defaultHigh
        ratio.isActive = true
        actionSection.isHidden = true
    }
}

/// 以 AVCaptureVideoPreviewLayer 为底层图层的视图,随视图自动铺满。
final class PreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        // layerClass 保证了类型。
        layer as! AVCaptureVideoPreviewLayer
    }
}
