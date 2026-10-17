import XCTest
@testable import CubeARCore

// Ports of GuideStepTest / GuideSessionTest / GuideCubeTest (Java):引导指令按手上朝向说、
// 模拟用户照着做能一路走完、3D 小魔方的几何不越界。

/// 可复现的伪随机数(SplitMix64)。与 java.util.Random 序列不同,但同样确定。
struct SeededRandom {
    private var state: UInt64

    init(_ seed: UInt64) { state = seed }

    mutating func next(_ bound: Int) -> Int {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        return Int(z % UInt64(bound))
    }
}

final class GuideTests: XCTestCase {
    override func setUp() {
        super.setUp()
        TestL10n.use("zh-Hans")
    }

    static let SOLVED = GuideCubeModel.solved()
    static let MOVES = ["U", "U2", "U'", "R", "R2", "R'", "F", "F2", "F'",
                        "D", "D2", "D'", "L", "L2", "L'", "B", "B2", "B'"]

    /// 镜头从朝向它的那面读到的样子。
    static func look(_ state: String, _ held: CubeFrame) -> FaceSample {
        FaceSample(GuideStep.reading(state, held).map { CubeColor.fromFace($0) }, 1)
    }

    static func solve(_ state: String) -> [String] {
        let moves = CubeSession.solve(state)
        XCTAssertNotNil(moves, state)
        return moves ?? []
    }

    static func scrambled(_ random: inout SeededRandom, _ length: Int = 25) -> String {
        var state = SOLVED
        for _ in 0..<length { state = CubeMoves.apply(state, MOVES[random.next(MOVES.count)]) }
        return state
    }

    private func assertStep(_ caption: String, _ notation: String, _ step: GuideStep,
                            file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(caption, step.caption(), file: file, line: line)
        XCTAssertEqual(notation, step.notation(), file: file, line: line)
    }

    // ------------------------------------------------------------------ GuideStep

    func testCaptionsSpeakFromTheHolder() {
        let held = CubeFrame.default
        assertStep("右层向上拧", "R", GuideStep.outerStep("R", 0, held))
        assertStep("右层向下拧", "R′", GuideStep.outerStep("R'", 0, held))
        assertStep("左层向下拧", "L", GuideStep.outerStep("L", 0, held))
        assertStep("顶层向左拧", "U", GuideStep.outerStep("U", 0, held))
        assertStep("底层向右拧", "D", GuideStep.outerStep("D", 0, held))
        assertStep("前层顺时针拧", "F", GuideStep.outerStep("F", 0, held))
        assertStep("前层逆时针拧", "F′", GuideStep.outerStep("F'", 0, held))
        assertStep("后层向左拧", "B", GuideStep.outerStep("B", 0, held))
        assertStep("右层拧半圈", "R2", GuideStep.outerStep("R2", 0, held))
        XCTAssertFalse(GuideStep.outerStep("B", 0, held).seen)
        XCTAssertTrue(GuideStep.outerStep("F", 0, held).seen)
        XCTAssertTrue(GuideStep.outerStep("D'", 0, held).seen)
        // 捏住后层、前两层一起拧:朝镜头的面在转。
        assertStep("前两层顺时针拧", "Fw", GuideStep.wideStep("B", 0, held))
        assertStep("前两层逆时针拧", "Fw′", GuideStep.wideStep("B'", 0, held))
        assertStep("前两层拧半圈", "Fw2", GuideStep.wideStep("B2", 0, held))
        XCTAssertTrue(GuideStep.wideStep("B", 0, held).seen)

        // 白面朝镜头、蓝面朝上:绿面成了底层。
        let white = CubeFrame.seen("U", 0)
        XCTAssertEqual("B", white.faceAt(GuideStep.VIEW_UP))
        assertStep("底层拧半圈", "D2", GuideStep.outerStep("F2", 0, white))
        assertStep("前层顺时针拧", "F", GuideStep.outerStep("U", 0, white))
        assertStep("顶层向右拧", "U′", GuideStep.outerStep("B'", 0, white))
    }

    func testOppositePairsBecomeOneMiddleTurn() {
        let m = GuideStep.plan(["R", "L'"], nil, 0, .default, 1)[0]
        XCTAssertEqual(2, m.count)
        assertStep("中间竖层向下拧", "M", m)
        // 中间竖层把白色中心带到前面、蓝色带到顶上。
        XCTAssertEqual("U", m.after.frontFace())
        XCTAssertEqual("B", m.after.faceAt(GuideStep.VIEW_UP))

        let e = GuideStep.plan(["U", "D'"], nil, 0, .default, 1)[0]
        XCTAssertEqual(2, e.count)
        assertStep("中间横层向右拧", "E", e)

        // 前后之间的夹层难握又看不见:拆成两次外层转。
        XCTAssertEqual(1, GuideStep.plan(["F", "B'"], nil, 0, .default, 1)[0].count)
        let white = GuideStep.plan(["U", "D'"], nil, 0, CubeFrame.seen("U", 0), 5)
        XCTAssertEqual(2, white.count)
        XCTAssertTrue(white[0].isFront)
        XCTAssertTrue(white[1].isWide)

        XCTAssertFalse(GuideStep.pairsIntoMiddle("R", "L"))
        XCTAssertFalse(GuideStep.pairsIntoMiddle("R", "R'"))
        XCTAssertFalse(GuideStep.pairsIntoMiddle("U2", "D"))
    }

    func testAMiddleTurnIsUsedOnlyWhenItPaysForItself() {
        let moves = ["L2", "R2", "F", "R'", "F", "U2"]
        let plan = GuideStep.plan(moves, nil, 0, .default, 10)
        XCTAssertEqual(moves.count, plan.count)
        for step in plan { XCTAssertTrue(step.seen && !step.isMiddle, step.caption()) }
        // 绿面在后时,同一个中间层半圈把前层动作转到镜头前。
        let first = GuideStep.plan(moves, nil, 0, CubeFrame.seen("B", 0), 1)[0]
        XCTAssertTrue(first.isMiddle)
        XCTAssertEqual("F", first.after.frontFace())
    }

    func testPlanWalksTheFrameThroughMiddleTurns() {
        let plan = GuideStep.plan(["R", "L'", "U"], nil, 0, .default, 5)
        XCTAssertEqual(2, plan.count)
        XCTAssertEqual(plan[0].after, plan[1].frame)
        // 拧完 M,白面朝镜头:白色那层成了前层。
        assertStep("前层顺时针拧", "F", plan[1])
    }

    func testChainedCaptionsUseChinesePunctuation() {
        var random = SeededRandom(7)
        for _ in 0..<40 {
            let state = GuideTests.scrambled(&random)
            let solution = GuideTests.solve(state)
            let tracker = MoveTracker(state, solution)
            for step in GuideStep.plan(solution, tracker.states, 0, .default, 40) where step.then != nil {
                XCTAssertTrue(step.caption().contains("，再"), step.caption())
                return
            }
        }
        XCTFail("no chained step in 40 solves")
    }

    // ------------------------------------------------------------------ GuideSession

    /// 模拟一个人照着引导做:看一眼、照做、再看一眼。每个镜头看得见的步骤都必须自己确认,
    /// 引导也必须始终知道魔方怎么拿着——包括中间层和前两层转动之后。
    func testFollowingTheInstructionsWalksThroughRandomSolves() {
        var random = SeededRandom(20261004)
        var steps = 0, presses = 0, sideLooks = 0, backsWithoutTricks = 0
        for trial in 0..<120 {
            let state = GuideTests.scrambled(&random)
            let solution = GuideTests.solve(state)
            var held = CubeFrame.seen(Array("URFDLB")[random.next(6)], random.next(4))
            for move in solution where GuideStep.outerStep(move, 0, held).isBack { backsWithoutTricks += 1 }

            let session = GuideSession(state, solution, held, true)
            let states = session.trackerValue.states
            var guardCount = 0
            while !session.isDone {
                guardCount += 1
                XCTAssertLessThan(guardCount, 60, "stuck")
                if guardCount >= 60 { return }
                let step = session.currentStep!
                XCTAssertEqual(held, session.currentFrame)
                _ = session.observe(GuideTests.look(states[step.first], held), true)
                held = step.after
                var result = session.observe(GuideTests.look(states[step.first + step.count], held), true)
                steps += 1
                if step.seen {
                    XCTAssertEqual(.advanced, result, "\(trial): \(step.caption())")
                    XCTAssertEqual(step.first + step.count, session.trackerValue.currentIndex,
                                   "\(trial): \(step.caption()) at \(step.first) in \(solution)")
                } else if step.first + step.count == solution.count {
                    // 还原的最后一下,前面已是纯色:请用户转个面给镜头看,复原的魔方会确认。
                    XCTAssertNotEqual(.advanced, result)
                    let side = CubeFrame.seen(held.faceAt(GuideStep.VIEW_RIGHT), 0)
                    result = session.observe(GuideTests.look(states[solution.count], side), true)
                    XCTAssertEqual(.advanced, result, "\(trial): \(step.caption())")
                    sideLooks += 1
                    break
                } else {
                    XCTAssertNotEqual(.advanced, result)
                    session.next()
                    presses += 1
                }
                XCTAssertEqual(held, session.currentFrame, "\(trial): \(step.caption())")
            }
        }
        print("steps \(steps), presses \(presses), last turn shown on a side \(sideLooks), "
            + "back turns without any of it \(backsWithoutTricks)")
        // 中途看不见的转动接到下一步,最后一下转个面给镜头看:几乎不需要按"下一步"。
        XCTAssertLessThan(presses * 20, backsWithoutTricks, "presses \(presses) of \(backsWithoutTricks)")
    }

    /// "上一步"跨过中间层转动,恢复当时的持法。
    func testPreviousUndoesAMiddleTurnAndItsHold() {
        var state = GuideTests.SOLVED
        for move in ["L", "R'"] { state = CubeMoves.apply(state, move) }
        let session = GuideSession(state, ["R", "L'"], .default, true)
        let m = session.currentStep!
        XCTAssertTrue(m.isMiddle)
        session.next()
        XCTAssertTrue(session.isDone)
        XCTAssertEqual(m.after, session.currentFrame)
        session.previous()
        XCTAssertEqual(0, session.trackerValue.currentIndex)
        XCTAssertEqual(CubeFrame.default, session.currentFrame)
    }

    /// 步骤说前两层,用户还是拧了后层:镜头看不出,"下一步"按转过算,下一眼把持法纠正回来。
    func testALookCorrectsTheHoldAfterThePlainBackTurn() {
        var random = SeededRandom(5)
        let state = GuideTests.scrambled(&random)
        let start = CubeMoves.apply(state, "B'")
        let session = GuideSession(start, ["B"], .default, true)
        XCTAssertTrue(session.currentStep!.isWide)
        session.next()
        XCTAssertNotEqual(CubeFrame.default, session.currentFrame)
        XCTAssertTrue(session.learn(GuideTests.look(state, .default), state, true))
        XCTAssertEqual(CubeFrame.default, session.currentFrame)
    }

    // ------------------------------------------------------------------ GuideCube

    func testSolvedCentersMatchWesternPalette() {
        let s = GuideTests.SOLVED
        XCTAssertEqual(CubeColor.red.argb, GuideCubeModel.cubieColors(s, 1, 0, 0)[0])
        XCTAssertEqual(CubeColor.orange.argb, GuideCubeModel.cubieColors(s, -1, 0, 0)[1])
        XCTAssertEqual(CubeColor.white.argb, GuideCubeModel.cubieColors(s, 0, 1, 0)[2])
        XCTAssertEqual(CubeColor.yellow.argb, GuideCubeModel.cubieColors(s, 0, -1, 0)[3])
        XCTAssertEqual(CubeColor.green.argb, GuideCubeModel.cubieColors(s, 0, 0, 1)[4])
        XCTAssertEqual(CubeColor.blue.argb, GuideCubeModel.cubieColors(s, 0, 0, -1)[5])
    }

    func testStickerMapMatchesCubeMovesLayout() {
        XCTAssertEqual([-1, 1, -1, 0, 1, 0], GuideCubeModel.stickerPos(0))
        XCTAssertEqual([0, 0, 1, 0, 0, 1], GuideCubeModel.stickerPos(22))
        XCTAssertEqual([1, 0, 0, 1, 0, 0], GuideCubeModel.stickerPos(13))
    }

    func testCornerCubieCarriesThreeStickers() {
        let ufr = GuideCubeModel.cubieColors(GuideTests.SOLVED, 1, 1, 1)
        XCTAssertEqual(CubeColor.red.argb, ufr[0])
        XCTAssertEqual(CubeColor.white.argb, ufr[2])
        XCTAssertEqual(CubeColor.green.argb, ufr[4])
        XCTAssertEqual(0, ufr[1])
        XCTAssertEqual(0, ufr[3])
        XCTAssertEqual(0, ufr[5])
    }

    func testRightMoveBringsDfrCubieOntoUfr() {
        let ufr = GuideCubeModel.cubieColors(CubeMoves.apply(GuideTests.SOLVED, "R"), 1, 1, 1)
        XCTAssertEqual(CubeColor.red.argb, ufr[0])
        XCTAssertEqual(CubeColor.green.argb, ufr[2])
        XCTAssertEqual(CubeColor.yellow.argb, ufr[4])
    }

    /// 可见的三面各九个小块,每块一层塑料壳加一张色块;转动层还会多露出一些,切口也算。
    /// 无论怎么拿、转哪层,都必须装得进四边形缓冲。
    func testEveryVisibleFaceFitsTheQuadBuffers() {
        let model = GuideCubeModel()
        var scrambled = GuideTests.SOLVED
        for move in ["R", "U", "F'", "L2", "D", "B'"] { scrambled = CubeMoves.apply(scrambled, move) }
        for (i, front) in "URFDLB".enumerated() {
            let held = CubeFrame.seen(front, i % 4)
            for moves in ["U", "R", "F", "D", "L", "B", "R L'", "U D'"] {
                let list = moves.split(separator: " ").map(String.init)
                let step = GuideStep.plan(list, nil, 0, held, 1)[0]
                let wide = GuideStep.wideStep(list[0], 0, held)
                var twist: Float = 0
                while twist > -3.2 {
                    for turning in [step, wide] {
                        let quads = model.collectFaces(scrambled, held, turning.axis, turning.layer, twist)
                        XCTAssertTrue(quads >= 36 && quads < GuideCubeModel.MAX_QUADS,
                                      "\(front) \(moves) twist \(twist): \(quads)")
                    }
                    twist -= 0.4
                }
            }
        }
    }

    /// 深度排序后远的在前;正面的色块最后画。
    func testQuadsAreDrawnBackToFront() {
        let model = GuideCubeModel()
        let count = model.collectFaces(GuideTests.SOLVED, .default, [0, 0, 1], GuideStep.OUTER, 0)
        for i in 1..<count {
            XCTAssertLessThanOrEqual(model.depth[model.order[i - 1]], model.depth[model.order[i]])
        }
        // 默认持法绿面朝前:最近的色块是绿色(带光照,只比色相)。
        let nearest = model.order[count - 1]
        XCTAssertFalse(model.isPlastic[nearest])
        let c = model.colors[nearest]
        XCTAssertGreaterThan((c >> 8) & 0xFF, (c >> 16) & 0xFF)
        XCTAssertGreaterThan((c >> 8) & 0xFF, c & 0xFF)
    }

    func testTwistEasesInAndHolds() {
        XCTAssertEqual(0, GuideCubeModel.twistAngle(0.1, 1))
        XCTAssertEqual(Float.pi / 2, GuideCubeModel.twistAngle(0.9, 1), accuracy: 1e-6)
        XCTAssertEqual(-Float.pi, GuideCubeModel.twistAngle(0.8, -2), accuracy: 1e-6)
        let mid = GuideCubeModel.twistAngle(0.46, 1)
        XCTAssertEqual(Float.pi / 4, mid, accuracy: 1e-3)
    }
}
