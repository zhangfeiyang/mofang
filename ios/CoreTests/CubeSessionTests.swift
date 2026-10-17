import XCTest
@testable import CubeARCore

// CubeSession(MainActivity 的平台无关部分)端到端:模拟相机把六个面各举两次,
// 识别、求解,再照着引导一步步做到复原;以及授权、预设、提示与重置。

final class CubeSessionTests: XCTestCase {
    /// 记录会话对宿主做了什么。
    final class Host: CubeSessionHost {
        var changes = 0, captures = 0, celebrations = 0, strongPulses = 0, weakPulses = 0
        var spoken: [String] = []
        var failures: [[FaceSample]] = []
        func sessionChanged() { changes += 1 }
        func speak(_ text: String) { spoken.append(text) }
        func stopSpeaking() {}
        func pulse(strong: Bool) { if strong { strongPulses += 1 } else { weakPulses += 1 } }
        func faceCaptured() { captures += 1 }
        func celebrate() { celebrations += 1 }
        func scanFailed(_ observations: [FaceSample]) { failures.append(observations) }
    }

    private var now: Double = 1_000
    private var allowed = true
    private let host = Host()
    private var session: CubeSession!

    override func setUp() {
        super.setUp()
        TestL10n.use("zh-Hans")
        now = 1_000
        allowed = true
        // 后台与主线程都同步执行:组装在 handleFrame 返回前就完成。
        session = CubeSession(background: { $0() }, main: { $0() },
                              clock: { [unowned self] in self.now }, cameraAllowed: { [unowned self] in self.allowed })
        session.host = host
    }

    /// 镜头里的一面:按标准色给出 Lab 读数,角点是正放的大方块(720×1280 竖屏帧)。
    private func detection(_ reading: String, side: Float = 300) -> DetectedFace {
        var stickers = [CubeColor]()
        var lab = [[Float]]()
        for letter in reading {
            let color = CubeColor.fromFace(letter)
            stickers.append(color)
            let rgb = color.rgb
            lab.append(Lab.fromRgb(Int((rgb >> 16) & 0xFF), Int((rgb >> 8) & 0xFF), Int(rgb & 0xFF)))
        }
        let sample = FaceSample(stickers, lab, 1)
        let x: Float = 210, y: Float = 400
        return DetectedFace(sample, [x, y, x + side, y, x + side, y + side, x, y + side], 720, 1280, 0.95, true, false)
    }

    /// 两帧相隔 100ms,足够稳定器放行一次采集。
    private func show(_ face: DetectedFace?) {
        for _ in 0..<2 {
            now += 100
            session.handleFrame(face, nanos: now * 1_000_000)
        }
    }

    private func scan(_ state: String, lastFace: Character = "F") {
        let faces = Array("URFDLB".filter { $0 != lastFace }) + [lastFace]
        for _ in 0..<2 {
            for face in faces { show(detection(GuideStep.reading(state, CubeFrame.seen(face, 0)))) }
        }
    }

    func testScanSolveAndFollowTheGuideToTheEnd() {
        session.resetSession()
        XCTAssertEqual(.scanning, session.phase)
        XCTAssertEqual("对准魔方的一个面", session.buildState().title)

        var random = SeededRandom(42)
        let state = GuideTests.scrambled(&random)
        scan(state)
        XCTAssertEqual(.ready, session.phase, session.buildState().detail)
        XCTAssertEqual(6, host.captures)
        XCTAssertEqual(state, session.cubeState)
        XCTAssertTrue(host.spoken.last!.hasPrefix("识别完成，共 "))
        let ready = session.buildState()
        XCTAssertEqual("识别完成", ready.title)
        XCTAssertEqual("共 \(session.solution.count) 步", session.progressText(ready))

        session.startGuiding()
        XCTAssertEqual(.guiding, session.phase)
        // 最后扫的是绿面、正放:第一条指令已经按这个持法说。
        XCTAssertNil(session.buildState().hint)
        var held = CubeFrame.default
        let states = session.guide!.trackerValue.states
        var guardCount = 0
        while session.phase == .guiding {
            guardCount += 1
            if guardCount > 60 { return XCTFail("stuck at \(session.guide!.trackerValue.currentIndex)") }
            let step = session.currentStep!
            let ui = session.buildState()
            XCTAssertEqual(CubeSession.stepTitle(step), ui.title)
            XCTAssertEqual(step.notation(), ui.guideStep!.notation())
            show(detection(GuideStep.reading(states[step.first], held)))
            XCTAssertTrue(session.buildState().stepOnLiveFace == (step.frame.frontFace() == held.frontFace()))
            held = step.after
            show(detection(GuideStep.reading(states[step.first + step.count], held)))
            if step.seen { continue }
            if step.first + step.count == session.solution.count {
                // 最后一下看不出来:转到旁边一面给镜头看。
                show(detection(GuideStep.reading(states[step.first + step.count],
                                                  CubeFrame.seen(held.faceAt(GuideStep.VIEW_RIGHT), 0))))
            } else {
                XCTAssertNotNil(session.buildState().hint)
                session.stepBy(+1)
            }
        }
        XCTAssertEqual(.solved, session.phase)
        XCTAssertEqual(1, host.celebrations)
        XCTAssertEqual("还原完成", host.spoken.last)
        XCTAssertEqual("还原完成！", session.buildState().title)
        XCTAssertTrue(session.progressText(session.buildState()).hasPrefix("用时 "))
    }

    func testTheReviewScreenLearnsHowTheCubeIsHeld() {
        session.resetSession()
        var random = SeededRandom(9)
        let state = GuideTests.scrambled(&random)
        scan(state, lastFace: "R")
        XCTAssertEqual(.ready, session.phase)
        // 核对页上用户把白面转向镜头、转了一下:开始还原时引导已经知道。
        let white = CubeFrame.seen("U", 1)
        show(detection(GuideStep.reading(state, white)))
        session.startGuiding()
        XCTAssertEqual(white, session.guide!.currentFrame)
        XCTAssertTrue(session.guide!.isFrameKnown)
    }

    func testPresetJumpsToReview() {
        var random = SeededRandom(3)
        let state = GuideTests.scrambled(&random)
        session.startPreset(state)
        XCTAssertEqual(.ready, session.phase)
        XCTAssertFalse(session.busy)
        XCTAssertEqual(state, session.buildState().cubeState)
        session.startGuiding()
        // 没看过魔方:先请用户把一面对准镜头。
        XCTAssertEqual("先把魔方任意一面对准镜头，提示会按你手里的朝向来说", session.buildState().hint)
    }

    func testASolvedCubeNeedsNoGuide() {
        session.startPreset(GuideCubeModel.solved())
        XCTAssertEqual(.solved, session.phase)
        XCTAssertEqual(1, host.celebrations)
        XCTAssertEqual("魔方本来就是还原状态", session.buildState().detail)
    }

    func testWithoutCameraPermissionTheSessionWaits() {
        allowed = false
        session.resetSession()
        XCTAssertEqual(.permission, session.phase)
        XCTAssertEqual("需要相机权限", session.buildState().title)
        session.handleFrame(detection(GuideStep.reading(GuideCubeModel.solved(), .default)), nanos: 1)
        XCTAssertEqual(0, host.captures)
    }

    func testScanHintsAskToComeCloserAndToShowTheCube() {
        session.resetSession()
        show(detection(GuideStep.reading(GuideCubeModel.solved(), .default), side: 40))
        XCTAssertEqual("再靠近一点，色块会读得更准", session.buildState().hint)
        XCTAssertEqual(0, host.captures)
        // 足够多的空帧之后跟踪丢失,三秒没看到魔方就提示怎么举。
        for _ in 0..<8 { show(nil) }
        now += 3_500
        session.handleFrame(nil, nanos: now * 1_000_000)
        XCTAssertNil(session.buildState().detectedFace)
        XCTAssertEqual("把魔方放在画面中间，离镜头 20–30 厘米", session.buildState().hint)
    }

    func testResetForgetsEverything() {
        session.resetSession()
        var random = SeededRandom(11)
        scan(GuideTests.scrambled(&random))
        XCTAssertEqual(.ready, session.phase)
        session.resetSession()
        XCTAssertEqual(.scanning, session.phase)
        XCTAssertNil(session.cubeState)
        XCTAssertEqual(0, session.buildState().scannedCount)
        XCTAssertTrue(session.buildState().preview.isEmpty)
    }

    func testScanPreviewIsOpaque() {
        session.resetSession()
        let state = GuideCubeModel.solved()
        show(detection(GuideStep.reading(state, .default)))
        show(detection(GuideStep.reading(state, .default)))
        let preview = session.buildState().preview
        XCTAssertEqual(1, preview.count)
        for argb in preview["F"]! { XCTAssertEqual(0xFF, (argb >> 24) & 0xFF) }
    }

    func testScanDumpIsValidJson() throws {
        var random = SeededRandom(1)
        let state = GuideTests.scrambled(&random)
        let pool = "URFDLB".map { detection(GuideStep.reading(state, CubeFrame.seen($0, 0))).sample }
        let parsed = try JSONSerialization.jsonObject(with: Data(ScanDump.json(pool).utf8)) as? [[String: Any]]
        XCTAssertEqual(6, parsed?.count)
        XCTAssertEqual("WHITE", parsed?[0]["center"] as? String)
        XCTAssertEqual(9, (parsed?[0]["lab"] as? [[Double]])?.count)
    }

    func testDurationsReadAsMinutesAndSeconds() {
        XCTAssertEqual("0:07", CubeSession.formatDuration(7_400))
        XCTAssertEqual("2:23", CubeSession.formatDuration(143_000))
    }
}
