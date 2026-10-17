# Cube AR iOS 移植进度

> 本文件是移植工作的进度载体,每完成一批更新一次。新会话从这里继续。

## 当前状态(2026-10-17)

- **核心逻辑**:68 个 XCTest,67 个通过(Linux Swift 6.0.3,`cd ios && swift test`);
  RefinerParityTests 需要先解码真机帧(见下),默认跳过。
- **App 层已按安卓版重做**(2026-10-17,见下节)。Mac mini 上 Xcode 27.0 编译通过
  (Debug 模拟器 + Release 真机架构,新文件零警告),模拟器里三个演示画面与首次启动截图正常。
  尚未在真机上跑过相机。
- **模型**:cubeface.mlpackage(ONNX→onnx2torch→CoreML mlprogram,数值一致误差 ~1e-6)。
- **已提审**(2026-10-17 17:33):1.0 (build 8) WAITING_FOR_REVIEW,批准后自动发布,175 个国家和地区
  (含中国大陆,未填 ICP 备案号——晶彩方块同样没填,2026-10-12 起在大陆正常销售)。
  商店名 魔方AR还原(zh-Hans,主语言),另有 38 个商店语言;截图 4 张取自用户真机录屏 1017_1.mp4;
  审核附件为同一录屏的压缩版(CubeAR_1017_demo.mp4,26 MB)。
- **CI/发布**:ios-ci.yml(编译验证)、ios-release.yml(签名→归档→TestFlight→**提审**,还会吊销证书、
  往 main 推标记——只想进 TestFlight 时别用它);bundle com.mofang.cubear,ASC App ID 6820316386。

## 多语言(2026-10-17,34 种)

- 文案唯一来源:`ios/l10n/<语言>.json`(App 文案 + `plist.*` 主屏名/相机说明 + `store.*` 商品页)。
  改完跑 `python3 ios/tools/gen_strings.py`(检查键、占位符、商店字数上限,并生成各 .lproj),
  语言列表变了再跑 `gen_xcodeproj.py`。Core 经 `L10n.t("键", 参数…)` 取文案,占位符 `{0}{1}`。
- 指令句子由各语言模板拼:`turn.quarter`/`turn.half`/`turn.then` × `place.*`(层位,含该语言需要的冠词/格)
  × `dir.*`;句首自动大写。新增语言时用 `L10nTests` 和 gen_strings 的检查兜底。
- 商品页:`python3 ios/tools/asc_fill_locales.py`(39 个 ASC 语言,映射见脚本;新建 App 信息本地化时
  ASC 会自动建同语言的版本本地化)。截图只传主语言,其余语言沿用。
- 关键词/名称别用 Rubik(商标):曾在 ja/ko/th/vi/id 里误用,已删。

## 提审的坑

- API 的 `POST /v1/reviewSubmissionItems` 2026-10-17 返回 500(UNEXPECTED_ERROR);网页版本页
  「添加以供审核」→ 选已有草稿 →「提交以供审核」一次成功。网页也会列出缺失项,比 API 好查。
- 首个版本不能写 whatsNew(PATCH 409),asc_submit_and_verify.py 里那条报错可忽略。

## 从 Mac mini 发 TestFlight(2026-10-17 起的做法,与 Crystal Grid 同一套签名材料)

```bash
# 0) ios/tools/gen_xcodeproj.py 里 _build_number +1,重新生成工程(已上传过 3、4、5、6)
# 1) 编译 + 导出 IPA(落在 ios/dist/,已 gitignore)
bash ios/mac_release.sh
# 2) 上传(只进 TestFlight,等 Apple 处理完)
export AC_API_KEY_ID=X8P2F2J3V8 AC_API_ISSUER_ID=a22499f7-085a-434f-a6c7-4cc995f1cec6
export AC_API_KEY_P8=$(base64 -w0 /home/zhangfy/secrets-backup/AuthKey_X8P2F2J3V8.p8)
bash ios/mac_release.sh --upload
```

- 签名:Mac 上 `cgbuild.keychain` 里的持久分发证书 62RDFCGTWC(2027-10-08 到期)+ 描述文件
  "CubeAR AppStore (mac mini)"(AV9JM8BA6X,`~/buildkeys/cubear_appstore.mobileprovision`)。
  失效时 `python3 ios/tools/asc_create_profile.py --out x.mobileprovision` 重建再 scp 过去。
  ASC 上那些 "com.mofang.cubear AppStore 17921…" 描述文件是 CI 建的,绑的证书私钥已随 runner 销毁,Mac 上用不了。
- "内部测试"组不会自动拿到新构建,要把构建加进组(POST /v1/betaGroups/{id}/relationships/builds)。
- 本机走代理访问 ASC 偶尔 SSL EOF,重跑即可;只读请求失败不会留下半成品。

## 2026-10-17 对齐安卓版(App 层重做)

旧 iOS 版只是安卓 MainActivity 的骨架,差距和修法:

| 问题 | 修法 |
|---|---|
| 相机给 1920×1080 **横屏**帧,模型按 720×1280 竖屏训练 → 检测几乎全废 | CameraManager:720p + 连接旋转 90°(竖屏 720×1280);vImage 做 BGRA→RGBA |
| 每帧(30fps)先转像素再节流 | 先问 `CubeAnalyzer.wantsFrame`,被跳过的帧不拷贝 |
| 没检测到面的帧不上报 → 跟踪/稳定器永远不知道面丢了 | `process()` 每帧都报(含 nil),主线程只取最新一帧(安卓 pendingFrame) |
| 工作线程直接对活池调 `assembleLegalState()`(数据竞争、无时间预算、无可疑面重扫) | 状态机整体移到 `Core/CubeSession.swift`(MainActivity 的平台无关部分):快照组装、`assemble()` 预算、suspect→forget、连续 3 次失败判池污染 |
| `CubeColor.rgb` 不含 alpha,视图当 ARGB 用 → 展开图/小魔方颜色全透明 | 新增 `CubeColor.argb`,`preview()` 输出不透明 ARGB |
| 没有"核对"阶段、不在核对页学持法、求解参数不同 | READY 阶段 + learnFrame;求解参数与安卓一致(21, 1e8) |
| 3D 小魔方只画一个静态已还原展开角 | `Core/GuideCubeModel.swift`(GuideCube 几何)+ GuideCubeView 绘制:按持法摆放、转动层动画、箭头/圈/×2、链式两段 |
| 叠加层:非等比映射、无滑动/对勾/箭头/彩纸 | CubeOverlayView 全量移植,用 CAShapeLayer + CAEmitterLayer(不整屏重绘) |
| 界面:固定坐标、emoji 按钮 | 玻璃 HUD(HudViews.swift,对应 res/values 与 drawable):顶栏、引导卡、底部面板、提示条、帮助页(首次启动)、重扫确认 |
| 缺:对焦测光、震动、常亮、语音设置持久化、隐私链接 | 全部补上;语音用 .playback + duckOthers,播完释放 |

与安卓的有意差异:没有音量键翻步(App Store 2.5.9 不允许);语音开关存在 UserDefaults "voice";
失败观测池写到 Documents/scan-fail.json(Xcode 下载容器取回,格式与安卓相同)。
启动参数:`-demo 1 -demoScreen 1|2|3`(截图)、`-state <54位>`(跳到核对页,不开相机)。

验证:
- GuideTests 的随机还原模拟与安卓同口径:2391 步、手动"下一步"0 次(安卓记录 2376 步/0 次)。
- CubeSessionTests 端到端:模拟相机六面各举两次 → 识别 → 照引导做到复原。
- RefinerParityTests(真机 103 帧):精定位成功一致 103/103,格栅 0.08 间距内 85/88,
  采样平均 ΔE 0.15。跑法:`python3 ios/tools/decode_parity_frames.py <目录>`,
  再 `CUBEAR_PARITY_FRAMES=<目录> swift test -c release --filter RefinerParityTests`。

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

- [x] 2026-10-17 新 App 层 Xcode 27 编译通过(Mac mini)
- [ ] 新 App 层真机试用(相机、对焦、语音、震动);推送后看 ios-ci(Xcode 26)
- [x] CI 编译验证通过(2026-10-16 旧 App 层)
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

## 2026-10-16 下午:发布管线实战记录(新会话从这里继续)

当前 ASC 状态:App "Cube AR"(6820316386) v1.0 PREPARE_FOR_SUBMISSION,
build 5 已上传 TestFlight,listing 全部就绪(描述/关键词/截图3张/年龄分级/分类/版权声明/审核联系信息)。

### 阻塞提审的三件事(需在 ASC 网页手动完成,无 API)
1. App Privacy(App 隐私 → 数据采集问卷):选"不收集数据"并发布(此节无公开 API)
2. 定价与销售范围:设为"免费"(API 密钥角色无 pricing 权限)
3. 审核联系信息电话是占位号 +8613800138000,请改成真实号码
   (ASC → App 信息 → App 审核信息)

### 完成后提审(二选一)
- Actions 页手动 Run "iOS Release (App Store)"(归档会因 build 5 已占用而失败,
  但 Submit 步骤 if:always() 会照跑并完成提审),或
- 推送任何触碰 .github/workflows/ios-release.yml 的提交(push 触发器已配),或
- 找 zhangfeiyang 重新触发。

### 提审要点(2025+ 新 API)
- 旧 POST /v1/appStoreVersionSubmissions 已废弃(403 CREATE 不允许)。
  现用 Review Submissions 流程:GET /v1/apps/{id}/reviewSubmissions(复用打开的
  DRAFT/READY_FOR_REVIEW 提交——挂构建时 ASC 会自动创建!)→
  POST /v1/reviewSubmissionItems → PATCH /v1/reviewSubmissions/{id} {submitted:true}。
- add item 409 "cannot be reviewed" = 版本有 associatedErrors,查 meta.associatedErrors
  逐项补齐(版权声明/审核信息/隐私问卷/定价都是这么发现的)。
- iOS 截图与元数据已全部由 CI 写入 ASC(asc_fill_listing.py / asc-metadata workflow)。
- 证书回收脚本已改为保护其他 App 引用的证书(62RDFCGTWC = mini 长期证书,勿动)。

### 教训
- 快速排查 ASC 校验错误:把 POST reviewSubmissionItems 的完整 409 body 打出来,
  meta.associatedErrors 会列全所有缺口。
- GitHub API 断连时用 git push 触发器 + workflow 回写 marker 文件轮询(git 通道独立)。
- Mac mini:`ssh zhangfeiyang@192.168.1.17`,有线 en0 已固定 192.168.1.17(手动地址 + DHCP 路由器,
  2026-10-17)。开着 FileVault:断电重启后停在解锁界面、没有网络,要有人登录一次才连得上。
  构建目录 `~/cubear-build/ios`(rsync 过去,别动它的 `~/mofang`):
  `xcodebuild -project CubeAR.xcodeproj -scheme CubeAR -destination "generic/platform=iOS Simulator" -configuration Debug -derivedDataPath build/sim build CODE_SIGNING_ALLOWED=NO`。
  远程脚本用 `ssh … 'bash -s'`,它的登录 shell 是 zsh(空通配符会报错)。
