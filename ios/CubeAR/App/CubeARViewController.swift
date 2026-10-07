import UIKit
import AVFoundation

// Port of com.mofang.cubear.MainActivity.java — 主控制器:相机 + 分析 + 引导 + UI。
// 安卓端使用音量键翻步;iOS 无等价物理键,改为屏幕大按钮。

final class CubeARViewController: UIViewController {
    enum Phase { case permission, scanning, ready, guiding, solved }

    private let cameraManager = CameraManager()
    private let captureGate = CaptureGate()
    private let assembler = CubeStateAssembler()
    private let analyzer: CubeAnalyzer
    private let detectionTracker = DetectionTracker()
    private let captureGateRef = CaptureGate()

    private var phase: Phase = .scanning
    private var solutionMoves: [String] = []
    private var guideSession: GuideSession?
    private var solveWorker: DispatchQueue = DispatchQueue(label: "solve", qos: .userInitiated)

    // UI
    private let previewLayer = AVCaptureVideoPreviewLayer()
    private let overlayView = CubeOverlayView()
    private let netView = CubeNetView()
    private let guideCubeView = GuideCubeView()
    private let statusLabel = UILabel()
    private let detailLabel = UILabel()
    private let nextButton = UIButton(type: .system)
    private let prevButton = UIButton(type: .system)
    private let startButton = UIButton(type: .system)
    private let rescanButton = UIButton(type: .system)
    private let toggleVoiceButton = UIButton(type: .system)
    private let torchButton = UIButton(type: .system)
    private let helpButton = UIButton(type: .system)

    private let analyzer_ = CubeAnalyzer { face in
        // 分析回调由 CameraManager 驱动
    }
    private var model: CubeFaceModel?
    private let speech = AVSpeechSynthesizer()
    private var voiceEnabled = true

    // 引导状态
    private var currentIndex = 0
    private var cubeState = ""
    private var lastStepTime: Double = 0

    init() {
        analyzer = CubeAnalyzer { _ in }
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupUI()
        setupCamera()
        model = CubeFaceModel.create()
        // 预热求解器表
        solveWorker.async {
            Search.initTables()
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        checkCameraPermission()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        cameraManager.stop()
    }

    // MARK: - UI 设置

    private func setupUI() {
        // 相机预览层
        previewLayer.frame = view.bounds
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)

        // AR 叠加层
        overlayView.frame = view.bounds
        overlayView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(overlayView)

        // 展开图
        let netSize: CGFloat = min(UIScreen.main.bounds.width, UIScreen.main.bounds.height) * 0.35
        netView.frame = CGRect(x: 8, y: 80, width: netSize, height: netSize * 0.75)
        netView.autoresizingMask = [.flexibleTopMargin]
        view.addSubview(netView)

        // 3D 引导魔方
        guideCubeView.frame = CGRect(x: UIScreen.main.bounds.width - netSize - 8, y: 80,
                                     width: netSize, height: netSize * 0.75)
        guideCubeView.isHidden = true
        view.addSubview(guideCubeView)

        // 状态标签
        statusLabel.frame = CGRect(x: 16, y: 50, width: view.bounds.width - 32, height: 30)
        statusLabel.textColor = .white
        statusLabel.font = .boldSystemFont(ofSize: 18)
        statusLabel.textAlignment = .center
        view.addSubview(statusLabel)

        detailLabel.frame = CGRect(x: 16, y: 82, width: view.bounds.width - 32, height: 24)
        detailLabel.textColor = .lightGray
        detailLabel.font = .systemFont(ofSize: 14)
        detailLabel.textAlignment = .center
        view.addSubview(detailLabel)

        // 按钮
        let buttonSize: CGFloat = 56
        let bottomY = view.bounds.height - buttonSize - 32

        nextButton.frame = CGRect(x: view.bounds.width / 2 - buttonSize / 2, y: bottomY,
                                  width: buttonSize * 1.6, height: buttonSize)
        nextButton.setTitle("下一步 ▶", for: .normal)
        nextButton.setTitleColor(.white, for: .normal)
        nextButton.backgroundColor = UIColor.systemBlue.withAlphaComponent(0.7)
        nextButton.layer.cornerRadius = 12
        nextButton.isHidden = true
        nextButton.addTarget(self, action: #selector(nextTapped), for: .touchUpInside)
        view.addSubview(nextButton)

        prevButton.frame = CGRect(x: nextButton.frame.minX - buttonSize * 1.8 - 8, y: bottomY,
                                  width: buttonSize * 1.6, height: buttonSize)
        prevButton.setTitle("◀ 上一步", for: .normal)
        prevButton.setTitleColor(.white, for: .normal)
        prevButton.backgroundColor = UIColor.systemGray.withAlphaComponent(0.7)
        prevButton.layer.cornerRadius = 12
        prevButton.isHidden = true
        prevButton.addTarget(self, action: #selector(prevTapped), for: .touchUpInside)
        view.addSubview(prevButton)

        startButton.frame = CGRect(x: view.bounds.width / 2 - 80, y: bottomY - 60,
                                   width: 160, height: 48)
        startButton.setTitle("开始还原", for: .normal)
        startButton.setTitleColor(.white, for: .normal)
        startButton.backgroundColor = UIColor.systemGreen.withAlphaComponent(0.8)
        startButton.layer.cornerRadius = 12
        startButton.isHidden = true
        startButton.addTarget(self, action: #selector(startTapped), for: .touchUpInside)
        view.addSubview(startButton)

        rescanButton.frame = CGRect(x: view.bounds.width / 2 + 80 + 8, y: bottomY - 60,
                                    width: 80, height: 44)
        rescanButton.setTitle("重扫", for: .normal)
        rescanButton.setTitleColor(.white, for: .normal)
        rescanButton.backgroundColor = UIColor.systemOrange.withAlphaComponent(0.7)
        rescanButton.layer.cornerRadius = 8
        rescanButton.isHidden = true
        rescanButton.addTarget(self, action: #selector(rescanTapped), for: .touchUpInside)
        view.addSubview(rescanButton)

        // 右上角工具按钮
        torchButton.frame = CGRect(x: view.bounds.width - 52, y: 8, width: 44, height: 44)
        torchButton.setTitle("💡", for: .normal)
        torchButton.addTarget(self, action: #selector(torchTapped), for: .touchUpInside)
        view.addSubview(torchButton)

        toggleVoiceButton.frame = CGRect(x: view.bounds.width - 100, y: 8, width: 44, height: 44)
        toggleVoiceButton.setTitle("🔊", for: .normal)
        toggleVoiceButton.addTarget(self, action: #selector(voiceTapped), for: .touchUpInside)
        view.addSubview(toggleVoiceButton)

        helpButton.frame = CGRect(x: view.bounds.width - 148, y: 8, width: 44, height: 44)
        helpButton.setTitle("?", for: .normal)
        helpButton.addTarget(self, action: #selector(helpTapped), for: .touchUpInside)
        view.addSubview(helpButton)
    }

    private func setupCamera() {
        cameraManager.onFrame = { [weak self] rgba, width, height in
            self?.processFrame(rgba, width: width, height: height)
        }
        cameraManager.start(in: previewLayer)
    }

    private func checkCameraPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            break
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                if granted { self?.cameraManager.start(in: self!.previewLayer) }
            }
        default:
            statusLabel.text = "需要相机权限"
        }
    }

    // MARK: - 帧处理管线

    private func processFrame(_ rgba: [UInt8], width: Int, height: Int) {
        guard let model = model else { return }
        let detection = model.detect(rgba, width: width, height: height)
        guard let result = detection else { return }

        let refiner = FaceRefiner()
        let lattice = result.toResult != nil
            ? refiner.refine(rgba, width: width, height: height, quad: result.toResult!.corners)
            : nil

        guard let lattice = lattice else { return }
        let sample = FaceSampler.sample(rgba, width: width, height: height,
                                        quad: lattice.quad, refined: true,
                                        quality: Float(lattice.support / 9))
        guard let sample = sample else { return }

        DispatchQueue.main.async { [weak self] in
            self?.updateUI(sample: sample, quad: lattice.quad, width: width, height: height)
        }
    }

    @objc private func processFrame(rgba: [UInt8], width: Int, height: Int) {
        // 由 CameraManager 回调
    }

    private func updateUI(sample: FaceSample, quad: [Double], width: Int, height: Int) {
        switch phase {
        case .scanning:
            let grew = assembler.put(sample)
            netView.setState(assembler.lastState ?? "")
            statusLabel.text = "扫描魔方 (\(assembler.size)/6 面)"
            if assembler.isComplete {
                phase = .ready
                solveWorker.async { [weak self] in
                    let result = self?.assembler.assembleLegalState()
                    DispatchQueue.main.async {
                        self?.onAssembled(result)
                    }
                }
            }
        case .guiding:
            let result = guideSession?.observe(sample, true)
            if result == .advanced {
                speakCurrentStep()
                if guideSession?.isDone == true {
                    phase = .solved
                    statusLabel.text = "还原完成!🎉"
                }
            }
        default:
            break
        }

        // 更新叠加层
        let corners: [Float] = quad.enumerated().map { i, v in
            i % 2 == 0 ? Float(v) * Float(bounds.width) / Float(width)
                : Float(v) * Float(bounds.height) / Float(height)
        }
        overlayView.detectedFace = DetectedFace(sample, corners, width, height, 1)
        overlayView.stabilizeProgress = 0
    }

    private func onAssembled(_ result: String?) {
        guard let state = result else {
            statusLabel.text = assembler.lastFailureValue
            return
        }
        cubeState = state
        phase = .ready
        statusLabel.text = "扫描完成!"

        // 求解
        solveWorker.async { [weak self] in
            let search = Search()
            let solution = search.solution(state, 24, 1_000_000, 200, 0)
            DispatchQueue.main.async {
                self?.onSolved(solution: solution, state: state)
            }
        }
    }

    private func onSolved(solution: String, state: String) {
        guard !solution.hasPrefix("Error") else {
            statusLabel.text = solution
            return
        }
        solutionMoves = solution.split(separator: " ").map { String($0) }
        statusLabel.text = "共 \(solutionMoves.count) 步"
        guideSession = GuideSession(state, solutionMoves, nil, false)
        phase = .guiding
        startButton.isHidden = false
        guideCubeView.isHidden = false
        speakCurrentStep()
    }

    private func speakCurrentStep() {
        guard voiceEnabled, let step = guideSession?.currentStep else { return }
        let utterance = AVSpeechUtterance(string: step.caption())
        utterance.language = "zh-CN"
        speech.speak(utterance)
        detailLabel.text = step.caption()
        guideCubeView.step = step
    }

    // MARK: - 按钮事件

    @objc private func nextTapped() {
        guideSession?.next()
        speakCurrentStep()
        if guideSession?.isDone == true {
            phase = .solved
            statusLabel.text = "还原完成!🎉"
        }
    }

    @objc private func prevTapped() {
        guideSession?.previous()
        speakCurrentStep()
    }

    @objc private func startTapped() {
        startButton.isHidden = true
        nextButton.isHidden = false
        prevButton.isHidden = false
        phase = .guiding
        speakCurrentStep()
    }

    @objc private func rescanTapped() {
        assembler.clear()
        phase = .scanning
        startButton.isHidden = true
        nextButton.isHidden = true
        prevButton.isHidden = true
        statusLabel.text = "扫描魔方"
    }

    @objc private func torchTapped() {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return }
        try? device.lockForConfiguration()
        device.torchMode = device.torchMode == .on ? .off : .on
        device.unlockForConfiguration()
    }

    @objc private func voiceTapped() {
        voiceEnabled.toggle()
        toggleVoiceButton.setTitle(voiceEnabled ? "🔊" : "🔇", for: .normal)
    }

    @objc private func helpTapped() {
        let alert = UIAlertController(title: "使用说明", message: """
        1. 扫描:把魔方的一个面正对镜头,整个面露出来。
        2. 依次露出其他面,顺序不限。五个面即可。
        3. 扫描完成后点"开始还原"。
        4. 跟随语音和屏幕指引一步步还原。
        """, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        present(alert, animated: true)
    }
}
