import XCTest
@testable import CubeARCore

// 每种语言的指令都能拼成完整的句子:没有漏掉的键、没有剩下的占位符。

final class L10nTests: XCTestCase {
    override func tearDown() {
        TestL10n.use("zh-Hans")
        super.tearDown()
    }

    /// 一次随机还原里出现的所有步骤(含链式、前两层、中间层)。
    private func sampleSteps() -> [GuideStep] {
        var random = SeededRandom(77)
        var steps: [GuideStep] = []
        for _ in 0..<12 {
            let state = GuideTests.scrambled(&random)
            let solution = GuideTests.solve(state)
            let tracker = MoveTracker(state, solution)
            let held = CubeFrame.seen(Array("URFDLB")[random.next(6)], random.next(4))
            steps += GuideStep.plan(solution, tracker.states, 0, held, 40)
        }
        return steps
    }

    func testEnglishCaptionsReadAsSentences() {
        TestL10n.use("en")
        let held = CubeFrame.default
        XCTAssertEqual("Turn the top layer left", GuideStep.outerStep("U", 0, held).caption())
        XCTAssertEqual("Turn the right layer up", GuideStep.outerStep("R", 0, held).caption())
        XCTAssertEqual("Turn the front two layers clockwise", GuideStep.wideStep("B", 0, held).caption())
        XCTAssertEqual("Turn the right layer halfway", GuideStep.outerStep("R2", 0, held).caption())
        XCTAssertEqual("Turn the middle column down", GuideStep.plan(["R", "L'"], nil, 0, held, 1)[0].caption())
        XCTAssertEqual("Move 3", CubeSession.stepTitle(GuideStep.outerStep("U", 2, held)))
    }

    func testEveryLanguageBuildsCompleteInstructions() {
        let steps = sampleSteps()
        XCTAssertTrue(steps.contains { $0.then != nil } && steps.contains { $0.isWide } && steps.contains { $0.isMiddle })
        XCTAssertGreaterThan(TestL10n.languages.count, 1)
        for language in TestL10n.languages {
            L10n.use(TestL10n.table(language))   // 不退回英文:缺键会原样露出来
            for step in steps {
                let caption = step.caption()
                XCTAssertFalse(caption.contains("{") || caption.contains("place.") || caption.contains("dir.")
                    || caption.contains("turn."), "\(language): \(caption)")
            }
            for color in CubeColor.allCases {
                XCTAssertFalse(color.localizedName.hasPrefix("color."), "\(language) \(color)")
            }
        }
    }

    func testSessionTextsAreTranslatedEverywhere() {
        var random = SeededRandom(5)
        let state = GuideTests.scrambled(&random)
        for language in TestL10n.languages {
            L10n.use(TestL10n.table(language))
            let session = CubeSession(background: { $0() }, main: { $0() }, clock: { 0 }, cameraAllowed: { true })
            session.resetSession()
            var texts = [session.buildState().title, session.buildState().detail, session.phaseLabel]
            session.startPreset(state)
            texts += [session.buildState().title, session.buildState().detail]
            session.startGuiding()
            let ui = session.buildState()
            texts += [ui.title, ui.detail, ui.hint ?? "", session.progressText(ui)]
            for text in texts {
                XCTAssertFalse(text.contains("{") || text.range(of: #"^[a-z]+\.[A-Za-z.]+$"#, options: .regularExpression) != nil,
                               "\(language): \(text)")
            }
        }
    }
}
