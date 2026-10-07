# Cube AR iOS 移植进度

> 本文件是移植工作的进度载体,每完成一批更新一次。新会话从这里继续。

## 已完成(2026-10-07)

- `ios/Core/*.swift` — 已移植并实测通过:
  - 基础:CubeColor, CubeRules, CubeMoves, Lab, FaceSample, Hungarian, CornerSmoother,
    ColorAssignment, ScanPalette, AreaResizer, FaceStabilizer, FaceSampler, Homography
  - min2phase 求解器:MUtil, CubieCube, CoordCube(+CoordCubeNode), MTools, Search
  - **求解器冒烟测试通过**:`fromScramble("R U R' U'")` → 解 `U R U' R'`,应用后回到恒等态。

## Java→Swift 移植坑位(务必遵守)

1. **移位优先级**:Swift `<<` 优先级**高于** `%` 和 `+`/`-`(Java 相反)。
   Java `a % 3 << 3` 必须写成 `((a % 3) << 3)`;Java `1 << 2 + 3` 是 `1 << 5`。
2. **for→while 的 continue**:Java for 循环 `continue` 仍执行更新表达式(m++)。
   `0x42 >> m & 3` 和 `NEXT_AXIS_MAGIC >> m & 3` 在部分 m 下为 0,丢自增 = 死循环。
3. **Java int 移位自动掩码移位数(&31)**:`value << (index << 2)` 在 Java 等价
   `(index & 7) << 2`;Swift 必须显式掩码(见 CoordCube.setPruning/getPruning)。
4. **64 位位技巧**:Util.getNPerm/setNPerm 用 UInt64 位模式 + `&-` 回绕,逻辑右移与
   Java 算术右移在本用法等价(只取低 4 位)。
5. **Java byte 数组**:min2phase 的 ca/ea 用 [Int](取值 0..31 非负)。
6. **Range**:Java `for(j=1;j<i;j++)` 在 i=0 时空区间;Swift `1..<0` 崩溃,用 stride 或 if。
7. Swift 文件不允许顶层执行语句:Java static{} 改为 `Search.bootstrapped` 一次性引导。

## 待移植

- [ ] 核心:CubeStateAssembler(1160 行,最大)、SixthFaceSolver、GuideSession、GuideStep、
      GuideCube、MoveTracker、FaceRefiner、AnchorSearch、DetectionTracker、CaptureGate、
      DetectionDump、ScanDump、ReplaySource、CubeFrame、DetectedFace、CubeUiState
- [ ] 测试:16 个 JTest → XCTest(2.5k 行),`swift test` 验证
- [ ] App 层:MainActivity(1007 行)→ SwiftUI/UIKit;视图 CubeOverlayView/CubeNetView/
      GuideCubeView → Swift UIView;相机 CameraX → AVFoundation
- [ ] ONNX:CubeFaceModel 用 onnxruntime-objc(SPM: https://github.com/microsoft/onnxruntime-objc)
- [ ] TTS:AVSpeechSynthesizer;音量键:监听 UIFeedbackGenerator?（安卓用音量键翻步,iOS 无
      等价,改屏幕按钮)
- [ ] Xcode 工程:复用 crystal-grid 的 tools/gen_xcodeproj.py 模式
- [ ] CI/发布:复制 crystal-grid 的 .github/workflows/ios-*.yml + fastlane + asc 脚本,
      改 Bundle ID(com.mofang.cubear)、APP_ID(ASC 建记录后)
- [ ] ASC:注册 Bundle ID、建 App 记录(名称?立方 AR / Cube AR)、提审

## 验证命令

```bash
export PATH=/opt/swift/usr/bin:$PATH   # Linux 上的 Swift 6.0.3
cd ios
swiftc -parse Core/*.swift                       # 语法
swift test --scratch-path /tmp/mofang-build      # 单元测试(XCTest)
```
