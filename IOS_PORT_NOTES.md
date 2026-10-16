# Cube AR iOS 移植进度

> 本文件是移植工作的进度载体,每完成一批更新一次。新会话从这里继续。

## 当前状态(2026-10-16)

- **核心逻辑**:39 个 XCTest 全部通过(Linux Swift 6.0.3,`cd ios && swift test`)。
- **模型**:cubeface.mlpackage 已生成并接入工程(ONNX→onnx2torch→CoreML mlprogram,
  数值一致性验证误差 ~1e-6;转换环境 /tmp/mlconv-venv,coremltools 8.3 + torch CPU)。
- **工程**:ios/tools/gen_xcodeproj.py 重写(Core 目录 glob、组结构补全、
  Debug/Release 大小写修正、mlpackage 进 Sources 阶段);project.pbxproj 已重新生成,
  并用 xcodeproj gem(fastlane 同款解析器)验证可解析。
- **CI/发布**:.github/workflows 下 ios-ci.yml(编译验证)、ios-release.yml
  (签名→归档→TestFlight→提审)已指向 CubeAR;bundle com.mofang.cubear,
  ASC App ID 6820316386(记录已建,版本 1.0 PREPARE_FOR_SUBMISSION)。

## 2026-10-16 修复的阻断问题

1. **project.pbxproj 手工改坏**:缺 isa 的对象 + 悬空引用导致 fastlane xcodeproj 解析
   TypeError。已重新生成,禁止手改 pbxproj——改 gen_xcodeproj.py 后重新生成。
2. **SixthFaceSolver.searchRolls**:花括号错位(死代码 sig 块导致 enum 提前闭合),
   按 Java 原版语义修复。此前从未编译通过。
3. **AreaResizer.init**:srcHeight 重复赋值 let。
4. **CubeAnalyzer**:引用了不存在的 CubeFaceModel。新增 Core/FaceDetecting.swift 协议,
   CubeFaceModel(iOS/Vision)适配;按 Java 忠实重移植(锚点兜底、disputed 失效、
   packedRgba 传递、smooth→orderCorners)。
5. **CubeARViewController**:管线重写为安卓同款
   (CubeAnalyzer → CaptureGate → FaceStabilizer → assembler/guide);
   移除从未编译通过的死代码(analyzer_ 闭包、不存在的 assembler.lastState);
   增加 demo 模式(`-demo 1 -demoScreen N`,供截图工作流使用)。
6. **Info.plist**:显示名 Crystal Grid→Cube AR;删除虚假的 24 语言声明;
   补 NSCameraUsageDescription(缺失会被 App Store 拒审);开发区域 zh-Hans。
7. **ios-ci.yml**:CrystalGrid→CubeAR、master→main(此前从未真正运行过)。

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
8. **Java null vs 枚举**:`[CubeColor?]` + nil(待定)与 .unknown(不可靠)的条件不能写反。
9. **属性与方法名冲突**:CubeFrame 的 `front()` 方法改名 `frontFace()`。
10. **release 的负数范围**:`0..<(-1)` 在 Java 是空循环,Swift 崩溃,要 guard。

## 待办

- [ ] CI 编译验证通过(推送后先看 ios-ci 的 macos 构建作业)
- [ ] 发布流水线跑通:签名→归档→TestFlight→提审(ios-release workflow_dispatch)
- [ ] ASC 元数据:zh-Hans 描述/关键词/截图/隐私政策 URL(参考 crystal-grid 的
      ios/tools/asc_fill_listing.py 模式;隐私页需新建 cube-ar-site 或复用现有站点)
- [ ] 提审后人工跟踪审核结果

## 验证命令

```bash
export PATH=/opt/swift/usr/bin:$PATH   # Linux 上的 Swift 6.0.3
cd ios
swiftc -parse Core/*.swift                       # 语法
swift test --scratch-path /tmp/mofang-build      # 单元测试(XCTest)
python3 ios/tools/gen_xcodeproj.py               # 重新生成工程(改过文件列表后)
ruby -e "require 'xcodeproj'; Xcodeproj::Project.open('ios/CubeAR.xcodeproj')"  # fastlane 同款解析
```
