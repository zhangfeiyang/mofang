import Foundation

// Port of com.mofang.cubear.CubeUiState.java
// 屏幕某一刻要显示的全部内容,由主控制器构建。

enum CubeUiPhase {
    case permission, scanning, ready, guiding, solved, error
}

final class CubeUiState {
    enum Phase { case permission, scanning, ready, guiding, solved, error }

    let phase: Phase
    let title: String
    let detail: String
    /// 简短的情境提示("再靠近一点"),或 nil。
    let hint: String?
    /// 跟踪的面(可能桥接了丢帧),或 nil。
    let detectedFace: DetectedFace?
    /// 带显示名的实时读数,或 nil。
    let liveFace: FaceSample?
    /// 0..1,当前稳定过程距采集的进度。
    let stabilizeProgress: Float
    /// 面被跟踪但距离太远、读数不可信时为 true。
    let tooFarToCapture: Bool
    /// 已收集的颜色。
    let scanned: Set<CubeColor>
    /// 已收集面数 0..6。
    let scannedCount: Int
    /// 每面字母 (URFDLB) → 九个 ARGB 预览色,0 = 未读。
    let preview: [Character: [Int]]
    /// 推算而非扫描的面颜色,或 nil。
    let inferred: CubeColor?
    /// 魔方已知后的 54-facelet URFDLB 串,否则 nil。
    let cubeState: String?
    let moves: [String]
    let moveIndex: Int
    /// 后台组装运行中为 true。
    let busy: Bool
    /// 引导:当前步骤(以持法命名),或 nil。
    let guideStep: GuideStep?
    /// 引导:当前步骤之后的记号。
    let upcoming: [String]
    /// 引导:视野中的面是当前步骤 frame 的前面、正立读取,其格栅可承载步骤箭头。
    let stepOnLiveFace: Bool

    init(_ builder: Builder) {
        phase = builder.phase
        title = builder.title
        detail = builder.detail
        hint = builder.hint
        detectedFace = builder.detectedFace
        liveFace = builder.liveFace
        stabilizeProgress = builder.stabilizeProgress
        tooFarToCapture = builder.tooFar
        scanned = builder.scanned ?? []
        scannedCount = builder.scannedCount
        preview = builder.preview ?? [:]
        inferred = builder.inferred
        cubeState = builder.cubeState
        moves = builder.moves ?? []
        moveIndex = builder.moveIndex
        busy = builder.busy
        guideStep = builder.guideStep
        upcoming = builder.upcoming ?? []
        stepOnLiveFace = builder.stepOnLiveFace
    }

    static func builder(_ phase: Phase) -> Builder { Builder(phase) }

    final class Builder {
        let phase: Phase
        var title = ""
        var detail = ""
        var hint: String?
        var detectedFace: DetectedFace?
        var liveFace: FaceSample?
        var stabilizeProgress: Float = 0
        var tooFar = false, busy = false, stepOnLiveFace = false
        var guideStep: GuideStep?
        var upcoming: [String]?
        var scanned: Set<CubeColor>?
        var scannedCount = 0
        var preview: [Character: [Int]]?
        var inferred: CubeColor?
        var cubeState: String?
        var moves: [String]?
        var moveIndex = -1

        init(_ phase: Phase) { self.phase = phase }

        func text(_ title: String, _ detail: String) -> Builder {
            self.title = title
            self.detail = detail
            return self
        }

        func hint(_ hint: String?) -> Builder {
            self.hint = hint
            return self
        }

        func detection(_ face: DetectedFace?, _ live: FaceSample?) -> Builder {
            detectedFace = face
            liveFace = live
            return self
        }

        func progress(_ progress: Float, _ tooFar: Bool) -> Builder {
            stabilizeProgress = progress
            self.tooFar = tooFar
            return self
        }

        func scan(_ scanned: Set<CubeColor>?, _ count: Int, _ preview: [Character: [Int]]?) -> Builder {
            self.scanned = scanned
            scannedCount = count
            self.preview = preview
            return self
        }

        func cube(_ state: String?, _ inferred: CubeColor?) -> Builder {
            cubeState = state
            self.inferred = inferred
            return self
        }

        func moves(_ moves: [String]?, _ index: Int) -> Builder {
            self.moves = moves
            moveIndex = index
            return self
        }

        func guide(_ step: GuideStep?, _ upcoming: [String]?, _ onLiveFace: Bool) -> Builder {
            guideStep = step
            self.upcoming = upcoming
            stepOnLiveFace = onLiveFace
            return self
        }

        func busy(_ busy: Bool) -> Builder {
            self.busy = busy
            return self
        }

        func build() -> CubeUiState { CubeUiState(self) }
    }
}
