import XCTest
@testable import CubeARCore

// Port of com.mofang.cubear.CubeCoreTest.java

final class CubeCoreTests: XCTestCase {
    private static let SOLVED =
        "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB"

    private func rgbOf(_ color: CubeColor) -> [Int] {
        [Int((color.rgb >> 16) & 0xFF), Int((color.rgb >> 8) & 0xFF), Int(color.rgb & 0xFF)]
    }

    private func clamp(_ value: Int) -> Int { max(0, min(255, value)) }

    private func uniformFace(_ color: CubeColor, _ wobble: Int) -> FaceSample {
        var stickers = [CubeColor](repeating: color, count: 9)
        var lab = [[Float]](repeating: [], count: 9)
        let rgb = rgbOf(color)
        for i in 0..<9 {
            lab[i] = Lab.fromRgb(clamp(rgb[0] + wobble), clamp(rgb[1] + wobble), clamp(rgb[2] + wobble))
        }
        _ = stickers
        return FaceSample([CubeColor](repeating: color, count: 9), lab, 1)
    }

    private func driftingFace(_ color: CubeColor, _ drift: Float) -> FaceSample {
        let rgb = rgbOf(color)
        let base = Lab.fromRgb(rgb[0], rgb[1], rgb[2])
        var lab = [[Float]](repeating: [], count: 9)
        for i in 0..<9 {
            lab[i] = [base[0] + drift, base[1], base[2]]
        }
        return FaceSample([CubeColor](repeating: color, count: 9), lab, 1)
    }

    private func canonicalFace(_ state: String, _ letter: Character, _ rolls: Int) -> FaceSample {
        let base = CubeFrame.FACES.distance(from: CubeFrame.FACES.startIndex,
            to: CubeFrame.FACES.firstIndex(of: letter)!) * 9
        var stickers = [CubeColor](repeating: .unknown, count: 9)
        var lab = [[Float]](repeating: [], count: 9)
        let chars = Array(state)
        for cell in 0..<9 {
            stickers[cell] = CubeColor.fromFace(chars[base + cell])
            let rgb = rgbOf(stickers[cell])
            lab[cell] = Lab.fromRgb(rgb[0], rgb[1], rgb[2])
        }
        var sample = FaceSample(stickers, lab, 1)
        for _ in 0..<rolls { sample = sample.rotateClockwise() }
        return sample
    }

    private func bandedFace() -> FaceSample {
        var stickers = [CubeColor](repeating: .red, count: 9)
        var lab = [[Float]](repeating: [], count: 9)
        let rgb = rgbOf(.red)
        for cell in 0..<9 {
            let shade = cell < 6 ? 0 : 24
            stickers[cell] = .red
            lab[cell] = Lab.fromRgb(clamp(rgb[0] + shade), clamp(rgb[1] + shade), clamp(rgb[2] + shade))
        }
        return FaceSample(stickers, lab, [Bool](repeating: true, count: 9), 1)
    }

    private func quad(_ x: Double, _ y: Double, _ size: Double) -> [Double] {
        [x, y, x + size, y, x + size, y + size, x, y + size]
    }

    // ----------------------------------------------------------------

    func testStickerCenterRejectsBlackPlasticAndKeepsWhite() {
        XCTAssertTrue(Lab.isStickerCenter(Lab.fromRgb(240, 240, 235)))
        XCTAssertTrue(Lab.isStickerCenter(Lab.fromRgb(225, 52, 44)))
        XCTAssertTrue(Lab.isStickerCenter(Lab.fromRgb(245, 145, 35)))
        XCTAssertFalse(Lab.isStickerCenter([29, -4, 6]))
        XCTAssertFalse(Lab.isStickerCenter([30, -5, 8]))
        XCTAssertTrue(Lab.isStickerCenter([70, 0.8, 5]),
                      "a white centre in ordinary indoor light (L~70) must pass")
        XCTAssertFalse(Lab.isStickerCenter([58, 0.8, 5]), "mid grey is not a white centre")
    }

    func testJunkGreyCentresDoNotCreateAFakeSixthFace() {
        let assembler = CubeStateAssembler()
        let palette: [CubeColor] = [.white, .red, .green, .yellow, .orange, .blue]
        for color in palette {
            assembler.put(uniformFace(color, 0))
            assembler.put(uniformFace(color, 4))
        }
        let grey = [[Float]](repeating: [29, -4, 6], count: 9)
        let unknown = [CubeColor](repeating: .unknown, count: 9)
        let reliable = [Bool](repeating: true, count: 9)
        XCTAssertFalse(assembler.put(FaceSample(unknown, grey, reliable, 0.55)),
                       "grey plastic must not enter the pool")
        XCTAssertEqual(assembler.size, 6)
        XCTAssertTrue(assembler.isComplete)
    }

    func testColorClassifierHandlesStickerPalette() {
        XCTAssertEqual(CubeColor.classify(red: 240, green: 240, blue: 235), .white)
        XCTAssertEqual(CubeColor.classify(red: 225, green: 52, blue: 44), .red)
        XCTAssertEqual(CubeColor.classify(red: 245, green: 145, blue: 35), .orange)
        XCTAssertEqual(CubeColor.classify(red: 240, green: 225, blue: 32), .yellow)
        XCTAssertEqual(CubeColor.classify(red: 73, green: 190, blue: 65), .green)
        XCTAssertEqual(CubeColor.classify(red: 45, green: 105, blue: 205), .blue)
        XCTAssertEqual(CubeColor.classify(red: 30, green: 30, blue: 30), .unknown)
    }

    func testEveryFaceMoveHasOrderFourAndStaysLegal() {
        for face in CubeFrame.FACES {
            var state = CubeCoreTests.SOLVED
            for _ in 0..<4 { state = CubeMoves.apply(state, String(face)) }
            XCTAssertEqual(state, CubeCoreTests.SOLVED, String(face))
        }
        var state = CubeCoreTests.SOLVED
        for move in "R U R' U' F2 D L B'".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }
        XCTAssertEqual(MTools.verify(state), 0)
    }

    func testAssemblerResolvesIndependentFaceRotations() {
        var state = CubeCoreTests.SOLVED
        for move in "R U2 F' L D B2 R'".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }
        let assembler = CubeStateAssembler()
        for face in 0..<6 {
            var colors = [CubeColor](repeating: .unknown, count: 9)
            let chars = Array(state)
            for i in 0..<9 { colors[i] = CubeColor.fromFace(chars[face * 9 + i]) }
            var sample = FaceSample(colors, 1)
            for _ in 0..<(face % 4) { sample = sample.rotateClockwise() }
            assembler.put(sample)
        }
        let assembled = assembler.assembleLegalState()
        XCTAssertNotNil(assembled)
        XCTAssertEqual(MTools.verify(assembled!), 0)
    }

    func testStabilizerOnlyEmitsOncePerStableSignature() {
        let stabilizer = FaceStabilizer(framesRequired: 3)
        let face = FaceSample([CubeColor](repeating: .green, count: 9), 1)
        XCTAssertNil(stabilizer.push(face))
        XCTAssertNil(stabilizer.push(face))
        XCTAssertTrue(stabilizer.push(face) === face)
        XCTAssertNil(stabilizer.push(face))
    }

    func testHungarianFindsTheMinimumCostAssignment() {
        let cost: [[Double]] = [[4, 1, 3], [2, 0, 5], [3, 2, 2]]
        let assignment = Hungarian.solve(cost)
        var total = 0.0
        for row in 0..<3 { total += cost[row][assignment[row]] }
        XCTAssertEqual(total, 5.0, accuracy: 1e-9)
        XCTAssertEqual(Set(assignment).count, 3)
    }

    func testHungarianHandlesMoreColumnsThanRows() {
        let cost: [[Double]] = [[9, 1, 9, 9], [9, 9, 9, 2]]
        let assignment = Hungarian.solve(cost)
        XCTAssertEqual(assignment[0], 1)
        XCTAssertEqual(assignment[1], 3)
    }

    func testLabSeparatesRedFromOrangeByLightness() {
        let red = Lab.fromRgb(216, 65, 47)
        let orange = Lab.fromRgb(240, 140, 42)
        // 固定色相阈值无法分割的这一对,在 Lab 中相距 25+ 单位。
        XCTAssertTrue(Lab.distance(red, orange) > 25)
        XCTAssertTrue(orange[0] > red[0] + 15)
        XCTAssertTrue(Lab.chroma(Lab.fromRgb(244, 243, 238)) < 12)
    }

    /// 暖光下的红贴纸,录制帧上色相为 14。色相阈值叫它橙色;Lab 里它显然还是红。
    /// 这正是曾污染扫描的确切情况。
    private static let DRIFTED_RED = [233, 95, 53]

    func testDriftedRedFoolsTheThresholdButNotLab() {
        let r = CubeCoreTests.DRIFTED_RED
        XCTAssertEqual(CubeColor.classify(red: r[0], green: r[1], blue: r[2]), .orange)
        let drifted = Lab.fromRgb(r[0], r[1], r[2])
        let red = rgbOf(.red), orange = rgbOf(.orange)
        XCTAssertTrue(Lab.distance(drifted, Lab.fromRgb(red[0], red[1], red[2]))
            < Lab.distance(drifted, Lab.fromRgb(orange[0], orange[1], orange[2])))
    }

    func testAssignmentRecoversStickersThatDriftIntoTheWrongColor() {
        var state = CubeCoreTests.SOLVED
        for move in "R U2 F' L D B2 R'".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }

        var faces: [FaceSample] = []
        var corrupted = 0
        let chars = Array(state)
        for face in 0..<6 {
            var provisional = [CubeColor](repeating: .unknown, count: 9)
            var lab = [[Float]](repeating: [], count: 9)
            for cell in 0..<9 {
                let truth = CubeColor.fromFace(chars[face * 9 + cell])
                var rgb = rgbOf(truth)
                if cell != 4 && truth == .red && corrupted < 2 {
                    rgb = CubeCoreTests.DRIFTED_RED
                    corrupted += 1
                }
                lab[cell] = Lab.fromRgb(rgb[0], rgb[1], rgb[2])
                provisional[cell] = CubeColor.classify(red: rgb[0], green: rgb[1], blue: rgb[2])
            }
            faces.append(FaceSample(provisional, lab, 1))
        }
        XCTAssertEqual(corrupted, 2, "test needs two drifted stickers")

        let result = ColorAssignment.assign(faces, 0)
        XCTAssertNotNil(result)
        var counts: [CubeColor: Int] = [:]
        for face in 0..<6 {
            for cell in 0..<9 {
                let resolved = result!.colors[face][cell]
                counts[resolved, default: 0] += 1
                XCTAssertEqual(resolved, CubeColor.fromFace(chars[face * 9 + cell]),
                               "face \(face) cell \(cell)")
            }
        }
        for (color, count) in counts {
            XCTAssertEqual(count, 9, color.chinese)
        }
    }

    func testAssemblerSolvesFromLabReadingsDespiteDriftedStickers() {
        var state = CubeCoreTests.SOLVED
        for move in "L' U F2 R D' B".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }

        let assembler = CubeStateAssembler()
        var corrupted = 0
        let chars = Array(state)
        for face in 0..<6 {
            var provisional = [CubeColor](repeating: .unknown, count: 9)
            var lab = [[Float]](repeating: [], count: 9)
            for cell in 0..<9 {
                let truth = CubeColor.fromFace(chars[face * 9 + cell])
                var rgb = rgbOf(truth)
                if cell != 4 && truth == .red && corrupted < 3 {
                    rgb = CubeCoreTests.DRIFTED_RED
                    corrupted += 1
                }
                lab[cell] = Lab.fromRgb(rgb[0], rgb[1], rgb[2])
                provisional[cell] = CubeColor.classify(red: rgb[0], green: rgb[1], blue: rgb[2])
            }
            var sample = FaceSample(provisional, lab, 1)
            for _ in 0..<(face % 4) { sample = sample.rotateClockwise() }
            // 一次注视还不是面;第二次确认它。
            XCTAssertFalse(assembler.put(sample), "a single look is unconfirmed")
            XCTAssertTrue(assembler.put(sample.rotateClockwise()), "face \(face) should be new")
        }
        XCTAssertEqual(assembler.size, 6)

        let assembled = assembler.assembleLegalState()
        XCTAssertNotNil(assembled, "drifted stickers must not block a legal state")
        XCTAssertEqual(MTools.verify(assembled!), 0)
    }

    func testRepeatedLooksAtOneFaceCollapseOnceAllSixColorsArePresent() {
        let assembler = CubeStateAssembler()
        for color in [CubeColor.white, .yellow, .green, .blue, .red, .orange] {
            assembler.put(uniformFace(color, 0))
            assembler.put(uniformFace(color, 2))
        }
        XCTAssertEqual(assembler.size, 6)
        for wobble in [3, 6, 9] { assembler.put(uniformFace(.green, wobble)) }
        XCTAssertEqual(assembler.size, 6, "rescans must not invent a seventh face")
        XCTAssertTrue(assembler.isComplete)
    }

    func testAllSixFacesSurviveEvenWhenTwoCentresAreClose() {
        let assembler = CubeStateAssembler()
        for color in [CubeColor.white, .yellow, .green, .blue, .red, .orange] {
            assembler.put(uniformFace(color, 0))
            assembler.put(uniformFace(color, 2))
        }
        XCTAssertEqual(assembler.size, 6, "all six centres must stay distinct")
        XCTAssertTrue(assembler.isComplete)
    }

    func testCubeRulesRejectImpossiblePieces() {
        XCTAssertTrue(CubeRules.areOpposite("U", "D"))
        XCTAssertTrue(CubeRules.areOpposite("R", "L"))
        XCTAssertFalse(CubeRules.areOpposite("U", "R"))
        XCTAssertFalse(CubeRules.areOpposite("U", "U"), "a face is not opposite itself")

        XCTAssertTrue(CubeRules.piecesArePlausible(CubeCoreTests.SOLVED))
        var scrambled = CubeCoreTests.SOLVED
        for move in "R U R' F2 D".split(separator: " ") {
            scrambled = CubeMoves.apply(scrambled, String(move))
        }
        XCTAssertTrue(CubeRules.piecesArePlausible(scrambled),
                      "a legally scrambled cube breaks no piece rule")
    }

    func testCubeRulesBlameTheStickerThatBreaksACorner() {
        // 角块 URF 是贴纸 8、9、20;把 9 强设为 20 的对面即不可能。
        var broken = Array(CubeCoreTests.SOLVED)
        broken[9] = "B"
        let state = String(broken)
        XCTAssertFalse(CubeRules.piecesArePlausible(state))
        let blame = CubeRules.violations(state)
        XCTAssertTrue(blame[8] > 0 && blame[9] > 0 && blame[20] > 0,
                      "the broken corner's stickers take the blame")
        XCTAssertEqual(blame[6], 0, "an untouched corner stays clean")
    }

    func testCubeRulesRejectDuplicatedCenters() {
        var broken = Array(CubeCoreTests.SOLVED)
        broken[4] = "R" // U 中心现在宣称也是红
        XCTAssertFalse(CubeRules.centersAreDistinct(String(broken)))
        XCTAssertTrue(CubeRules.centersAreDistinct(CubeCoreTests.SOLVED))
    }

    func testStabilizerHoldsThroughFlickeringColorNames() {
        let stabilizer = FaceStabilizer(framesRequired: 3)
        // 每帧相同的物理贴块,但一张贴纸的临时名翻转。
        let a = driftingFace(.red, 0)
        let b = driftingFace(.red, 3)
        let c = driftingFace(.red, 5)
        XCTAssertNil(stabilizer.push(a))
        XCTAssertNil(stabilizer.push(b))
        XCTAssertNotNil(stabilizer.push(c), "steady readings must capture despite unstable labels")
    }

    func testStabilizerEmitsTheAverageOfTheSteadyRun() {
        let stabilizer = FaceStabilizer(framesRequired: 3)
        XCTAssertNil(stabilizer.push(driftingFace(.red, 0)))
        XCTAssertNil(stabilizer.push(driftingFace(.red, 6)))
        let emitted = stabilizer.push(driftingFace(.red, 12))
        XCTAssertNotNil(emitted)
        let rgb = rgbOf(.red)
        let base = Lab.fromRgb(rgb[0], rgb[1], rgb[2])[0]
        // 读数漂移 0、6、12;放出的样本必须带均值而非最后一个。
        XCTAssertEqual(emitted!.lab![4][0], base + 6, accuracy: 0.01)
    }

    func testStabilizerSurvivesTheOccasionalDroppedFrame() {
        let stabilizer = FaceStabilizer(framesRequired: 3)
        XCTAssertNil(stabilizer.push(driftingFace(.green, 0)))
        XCTAssertNil(stabilizer.push(nil), "a dropped frame must not restart the run")
        XCTAssertNil(stabilizer.push(driftingFace(.green, 2)))
        XCTAssertNotNil(stabilizer.push(driftingFace(.green, 4)))
    }

    func testStabilizerGivesUpAfterASustainedGap() {
        let stabilizer = FaceStabilizer(framesRequired: 3)
        XCTAssertNil(stabilizer.push(driftingFace(.green, 0)))
        XCTAssertNil(stabilizer.push(driftingFace(.green, 1)))
        for _ in 0..<3 { stabilizer.push(nil) }
        XCTAssertNil(stabilizer.push(driftingFace(.green, 2)),
                     "the cube moved away, so the run must restart")
    }

    func testStabilizerSurvivesAQuarterTurnOfTheDetectorOrdering() {
        let stabilizer = FaceStabilizer(framesRequired: 2)
        // 两条水平带:低分裂(通过跨界门)但对旋转敏感。
        let upright = bandedFace()
        XCTAssertNil(stabilizer.push(upright))
        let emitted = stabilizer.push(
            upright.rotateClockwise().rotateClockwise().rotateClockwise())
        XCTAssertNotNil(emitted, "a quarter-turn of the ordering must not reset the run")
        // 放出的样本处于运行的规范滚转坐标系。
        XCTAssertEqual(emitted!.stickers, upright.stickers)
    }

    func testStabilizerRejectsAnUnreliableCentre() {
        let stabilizer = FaceStabilizer(framesRequired: 2)
        let face = driftingFace(.green, 0)
        var reliable = [Bool](repeating: true, count: 9)
        reliable[4] = false
        let blocked = FaceSample(face.stickers, face.lab, reliable, 1)
        XCTAssertNil(stabilizer.push(blocked))
        XCTAssertNil(stabilizer.push(blocked))
    }

    func testPaletteNamesLiveFacesWithoutAbsoluteThresholds() {
        var state = CubeCoreTests.SOLVED
        for move in "F R U2 B' L".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }
        var faces: [FaceSample] = []
        let chars = Array(state)
        for face in 0..<6 {
            var provisional = [CubeColor](repeating: .unknown, count: 9)
            var lab = [[Float]](repeating: [], count: 9)
            for cell in 0..<9 {
                let truth = CubeColor.fromFace(chars[face * 9 + cell])
                let rgb = (truth == .red && cell != 4) ? CubeCoreTests.DRIFTED_RED : rgbOf(truth)
                lab[cell] = Lab.fromRgb(rgb[0], rgb[1], rgb[2])
                provisional[cell] = CubeColor.classify(red: rgb[0], green: rgb[1], blue: rgb[2])
            }
            faces.append(FaceSample(provisional, lab, 1))
        }
        let palette = ScanPalette.from(ColorAssignment.assign(faces, 0))
        XCTAssertNotNil(palette)

        // 漂移的红贴块被阈值分类器叫橙,但被调色板叫红。
        let relabelled = palette!.relabel(faces[0])!
        for cell in 0..<9 {
            XCTAssertEqual(relabelled.stickers[cell], CubeColor.fromFace(chars[cell]),
                           "cell \(cell)")
        }
    }

    func testPaletteLeavesUnreadablePatchesUnknown() {
        let face = driftingFace(.blue, 0)
        var reliable = [Bool](repeating: true, count: 9)
        reliable[2] = false
        let palette: [CubeColor] = [.white, .red, .green, .yellow, .orange, .blue]
        var faces: [FaceSample] = []
        for i in 0..<6 { faces.append(uniformFace(palette[i], 0)) }
        let scanPalette = ScanPalette.from(ColorAssignment.assign(faces, 0))!
        let relabelled = scanPalette.relabel(
            FaceSample(face.stickers, face.lab, reliable, 1))!
        XCTAssertEqual(relabelled.stickers[2], .unknown)
        XCTAssertEqual(relabelled.stickers[4], .blue)
    }

    func testMoveMatcherIgnoresUnreadablePatchesButNotWrongOnes() {
        let face = "UUUUUUUUU"
        var stickers = [CubeColor](repeating: .white, count: 9)
        stickers[3] = .unknown
        XCTAssertEqual(MoveTracker.matchMask(face, FaceSample(stickers, 1)), 0b1111,
                       "one unreadable patch must not stall the walkthrough")

        stickers[3] = .red
        XCTAssertEqual(MoveTracker.matchMask(face, FaceSample(stickers, 1)), 0,
                       "a genuinely different sticker must still fail")

        var tooFew = [CubeColor](repeating: .unknown, count: 9)
        tooFew[0] = .white
        XCTAssertEqual(MoveTracker.matchMask(face, FaceSample(tooFew, 1)), 0,
                       "a mostly unreadable face proves nothing")
    }

    func testMinSideFractionMeasuresTheShortestEdgeAgainstTheShortFrameEdge() {
        let sample = FaceSample([CubeColor](repeating: .blue, count: 9), 1)
        // 200x400 帧上任意位置的 100px 方块读作短边的一半。
        let square = DetectedFace(sample, [40, 40, 140, 40, 140, 140, 40, 140], 200, 400, 1)
        XCTAssertEqual(square.minSideFraction, 0.5, accuracy: 1e-4)
        // 斜四边形必须按真实最短边测量,而不是包围盒。
        let slanted = DetectedFace(sample, [0, 0, 96, 28, 68, 124, -28, 96], 400, 400, 1)
        XCTAssertTrue(slanted.minSideFraction < 0.26)
        let tiny = DetectedFace(sample, [0, 0, 3, 0, 3, 3, 0, 3], 400, 800, 1)
        XCTAssertTrue(tiny.minSideFraction < 0.02, "a distant face must fall under the capture gate")
    }

    func testStabilizerProgressTracksTheSteadyRun() {
        let stabilizer = FaceStabilizer(framesRequired: 3)
        XCTAssertEqual(stabilizer.progress, 0, accuracy: 1e-6)
        XCTAssertNil(stabilizer.push(driftingFace(.red, 0)))
        XCTAssertEqual(stabilizer.progress, 1.0 / 3.0, accuracy: 1e-4)
        XCTAssertNil(stabilizer.push(driftingFace(.red, 4)))
        XCTAssertEqual(stabilizer.progress, 2.0 / 3.0, accuracy: 1e-4)
        XCTAssertNotNil(stabilizer.push(driftingFace(.red, 8)))
        XCTAssertEqual(stabilizer.progress, 0, accuracy: 1e-6, "a capture consumed the run")
    }

    func testStabilizerProgressSurvivesDroppedFramesButNotSustainedOnes() {
        let stabilizer = FaceStabilizer(framesRequired: 3)
        XCTAssertNil(stabilizer.push(driftingFace(.green, 0)))
        XCTAssertNil(stabilizer.push(nil))
        XCTAssertEqual(stabilizer.progress, 1.0 / 3.0, accuracy: 1e-4,
                       "one dropped frame must not reset the ring")
        for _ in 0..<3 { stabilizer.push(nil) }
        XCTAssertEqual(stabilizer.progress, 0, accuracy: 1e-6,
                       "the cube moved away, so the ring empties")
    }

    /// 缺一个面的扫描必须仍能完成:第六面由结构推出。
    func testAssemblerInfersTheUnscannedFace() {
        var state = CubeCoreTests.SOLVED
        for move in "R U2 F' L D B2 R'".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }
        let assembler = CubeStateAssembler()
        var face = 0
        for letter in CubeFrame.FACES {
            if letter == "D" { continue }
            assembler.put(canonicalFace(state, letter, face % 4))
            assembler.put(canonicalFace(state, letter, (face + 2) % 4))
            face += 1
        }
        XCTAssertEqual(assembler.size, 5)
        XCTAssertFalse(assembler.isComplete)

        let assembled = assembler.assembleFromFiveFaces()
        print("DBG result=\(assembled ?? "nil") failure=\(assembler.lastFailureValue)")
        XCTAssertNotNil(assembled, "five faces must be enough to assemble")
        XCTAssertEqual(assembled!, state)
        XCTAssertEqual(MTools.verify(assembled!), 0)
        XCTAssertNotNil(assembler.palette)
        XCTAssertEqual(assembler.palette!.missingColor, CubeColor.fromFace("D"))
    }

    func testSixthFaceSolverDirect() {
        var state = CubeCoreTests.SOLVED
        for move in "R U2 F' L D B2 R'".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }
        var faces: [FaceSample?] = []
        var face = 0
        for letter in "URFDL" {
            faces.append(canonicalFace(state, letter, face % 4))
            face += 1
        }
        let result = SixthFaceSolver.solve(faces)
        XCTAssertNotNil(result, " SixthFaceSolver must infer the D face")
        XCTAssertEqual(result!.state, state)
    }

    func testInferredPaletteLeavesTheUnseenColourUnnamedUntilItIsSeen() {
        var state = CubeCoreTests.SOLVED
        for move in "L' U F2 R D' B".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }
        let assembler = CubeStateAssembler()
        var face = 0
        for letter in CubeFrame.FACES {
            if letter == "B" { continue }
            assembler.put(canonicalFace(state, letter, face % 4))
            face += 1
        }
        XCTAssertNotNil(assembler.assembleFromFiveFaces())
        let palette = assembler.palette!
        XCTAssertEqual(palette.missingColor, CubeColor.fromFace("B"))

        let unseen = canonicalFace(state, "B", 0)
        let relabelled = palette.relabel(unseen)!
        let chars = Array(state)
        for cell in 0..<9 {
            let truth = CubeColor.fromFace(chars[45 + cell])
            if truth == .blue {
                XCTAssertEqual(relabelled.stickers[cell], .unknown, "unseen blue cell \(cell)")
            } else {
                XCTAssertEqual(relabelled.stickers[cell], truth,
                               "scanned colour on the unseen face, cell \(cell)")
            }
        }

        // 缺失色的第一次稳定注视补全调色板。
        palette.learnMissing(unseen.centerLab)
        XCTAssertNil(palette.missingColor)
        let renamed = palette.relabel(unseen)!
        for cell in 0..<9 {
            XCTAssertEqual(renamed.stickers[cell], CubeColor.fromFace(chars[45 + cell]),
                           "cell \(cell)")
        }
    }

    func testFiveFacesAreNotForceSplitIntoSix() {
        var state = CubeCoreTests.SOLVED
        for move in "R U2 F' L D B2 R'".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }
        let assembler = CubeStateAssembler()
        var face = 0
        for letter in CubeFrame.FACES {
            if letter == "D" { continue }
            assembler.put(canonicalFace(state, letter, face % 4))
            assembler.put(canonicalFace(state, letter, (face + 1) % 4))
            face += 1
        }
        XCTAssertEqual(assembler.size, 5)
        XCTAssertNil(assembler.assembleLegalState(),
                     "five real faces must not be split into a fake sixth")
        XCTAssertEqual(assembler.assembleFromFiveFaces(), state)
    }

    func testPaletteLearnsTheMissingColourFromAnOutlierSticker() {
        var state = CubeCoreTests.SOLVED
        for move in "L' U F2 R D' B".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }
        let assembler = CubeStateAssembler()
        var face = 0
        for letter in CubeFrame.FACES {
            if letter == "B" { continue }
            assembler.put(canonicalFace(state, letter, face % 4))
            face += 1
        }
        XCTAssertNotNil(assembler.assembleFromFiveFaces())
        let palette = assembler.palette!
        XCTAssertEqual(palette.missingColor, CubeColor.fromFace("B"))

        // 仍带蓝贴纸的已扫面无需等蓝中心转向镜头即可补全调色板。
        var learned = false
        for letter in "URFDL" {
            if palette.maybeLearn(canonicalFace(state, letter, 0)) {
                learned = true
                break
            }
        }
        XCTAssertTrue(learned, "a scrambled cube shows the missing colour on the faces already scanned")
        XCTAssertNil(palette.missingColor)
        let unseen = canonicalFace(state, "B", 0)
        let renamed = palette.relabel(unseen)!
        XCTAssertEqual(renamed.stickers[4], .blue)
    }

    func testPaletteDoesNotLearnGlareAsTheMissingColour() {
        var state = CubeCoreTests.SOLVED
        for move in "L' U F2 R D' B".split(separator: " ") {
            state = CubeMoves.apply(state, String(move))
        }
        let assembler = CubeStateAssembler()
        var face = 0
        for letter in CubeFrame.FACES {
            if letter == "B" { continue }
            assembler.put(canonicalFace(state, letter, face % 4))
            face += 1
        }
        XCTAssertNotNil(assembler.assembleFromFiveFaces())
        let palette = assembler.palette!
        // 洗白的灰贴块离所有原型都远,但它不是蓝。
        XCTAssertFalse(palette.maybeLearn(uniformFace(.unknown, 0)))
        XCTAssertEqual(palette.missingColor, CubeColor.fromFace("B"))
    }

    func testRotatingAFaceCarriesItsReadingsAlong() {
        var stickers = [CubeColor](repeating: .unknown, count: 9)
        var lab = [[Float]](repeating: [], count: 9)
        for i in 0..<9 {
            stickers[i] = CubeColor.allCases[i % 6]
            lab[i] = [Float(i), Float(i), Float(i)]
        }
        let rotated = FaceSample(stickers, lab, 1).rotateClockwise()
        XCTAssertEqual(rotated.stickers[0], stickers[6])
        XCTAssertEqual(rotated.lab![0][0], 6, accuracy: 1e-6)
        XCTAssertEqual(rotated.centerLab![0], 4, accuracy: 1e-6)
    }

    func testDetectionTrackerBridgesShortDropouts() {
        let sample = FaceSample([CubeColor](repeating: .blue, count: 9), 1)
        let tracker = DetectionTracker()
        let first = DetectedFace(sample, [10, 10, 110, 10, 110, 110, 10, 110], 200, 300, 1)
        let second = DetectedFace(sample, [20, 20, 120, 20, 120, 120, 20, 120], 200, 300, 1)
        XCTAssertTrue(tracker.update(first) === first)
        XCTAssertTrue(tracker.update(second) === second,
                      "smoothing belongs upstream now")
        XCTAssertTrue(tracker.update(nil) === second)
        tracker.update(nil); tracker.update(nil); tracker.update(nil)
        XCTAssertNil(tracker.update(nil))
    }

    func testCornerSmootherAveragesSmallMotionAndSnapsOnLargeMotion() {
        let smoother = CornerSmoother()
        let first = quad(0, 0, 100)
        let nudged = quad(4, 4, 100)
        XCTAssertEqual(smoother.update(first)![0], 0.0, accuracy: 1e-6)

        let blended = smoother.update(nudged)!
        XCTAssertTrue(blended[0] > 0.0 && blended[0] < 4.0,
                      "a small nudge is averaged, not followed exactly")

        // 转动魔方使角点远移;跟踪必须吸附而不是滞后。
        let jumped = quad(90, 90, 100)
        XCTAssertEqual(smoother.update(jumped)![0], 90.0, accuracy: 1e-6)

        smoother.reset()
        XCTAssertEqual(smoother.update(first)![0], 0.0, accuracy: 1e-6)
        XCTAssertNil(smoother.update(nil))
    }

    func testCornerSmootherMatchesARotatedCornerOrder() {
        let smoother = CornerSmoother()
        smoother.update(quad(0, 0, 100))
        // 同一个方块,从第二个角点列出:不得读作跳变。
        let q = quad(2, 2, 100)
        let rolled = [q[2], q[3], q[4], q[5], q[6], q[7], q[0], q[1]]
        let out = smoother.update(rolled)!
        XCTAssertTrue(out[0] > 0.0 && out[0] < 2.0)
        XCTAssertTrue(out[1] > 0.0 && out[1] < 2.0)
    }
}
