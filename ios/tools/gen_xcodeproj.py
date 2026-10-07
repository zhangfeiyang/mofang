#!/usr/bin/env python3
"""Generate ios/CubeAR.xcodeproj (classic pbxproj, Xcode 14+) from a fixed file list.

Deterministic output; safe to re-run. Run from repo root:  python3 ios/tools/gen_xcodeproj.py
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
IOS = os.path.join(ROOT, "ios")
PROJ_DIR = os.path.join(IOS, "CubeAR.xcodeproj")

SOURCES = [
    "Core/Board.swift",
    "Core/CubeAnalyzer.swift",
    "Core/CubeColor.swift",
    "Core/CubeFrame.swift",
    "Core/CubeMoves.swift",
    "Core/CubeRules.swift",
    "Core/CubeStateAssembler.swift",
    "Core/CubeUiState.swift",
    "Core/Detection.swift",
    "Core/FaceRefiner.swift",
    "Core/FaceSample.swift",
    "Core/FaceSampler.swift",
    "Core/FaceStabilizer.swift",
    "Core/GuideSession.swift",
    "Core/GuideStep.swift",
    "Core/Homography.swift",
    "Core/Hungarian.swift",
    "Core/Lab.swift",
    "Core/MTools.swift",
    "Core/MoveTracker.swift",
    "Core/MUtil.swift",
    "Core/CubieCube.swift",
    "Core/CoordCube.swift",
    "Core/Search.swift",
    "Core/ScanPalette.swift",
    "Core/ColorAssignment.swift",
    "Core/AreaResizer.swift",
    "Core/SixthFaceSolver.swift",
    "Core/Tutorial.swift",
    "CubeAR/App/CubeARApp.swift",
    "CubeAR/App/CubeARViewController.swift",
    "CubeAR/Vision/CubeFaceModel.swift",
    "CubeAR/Vision/CameraManager.swift",
    "CubeAR/Views/CubeOverlayView.swift",
    "CubeAR/Views/CubeNetView.swift",
    "CubeAR/Views/GuideCubeView.swift",
]

LPROJS = ["en", "ar", "bn", "de", "es", "fa", "fr", "hi", "id", "it", "ja", "ko",
          "ms", "nl", "pl", "pt", "ru", "sv", "th", "tr", "uk", "vi", "zh-Hans", "zh-Hant"]

_bundle_id = "com.mofang.cubear"  # 与安卓 applicationId 一致
_marketing_version = "1.0.0"               # 与安卓 versionName 一致
_build_number = "2"                        # TestFlight 每次上传需递增

_counter = [0]
_prefix = "C0"
_cache = {}

def uid(tag):
    if tag in _cache:
        return _cache[tag]
    _counter[0] += 1
    u = f"{_prefix}{_counter[0]:022X}"
    _cache[tag] = u
    return u

def comment_for(u):
    names = _names
    return names.get(u, u)

_names = {}

class Obj:
    def __init__(self, isa, **fields):
        self.isa = isa
        self.fields = fields
        self.uuid = fields.pop("uuid")
        _names[self.uuid] = fields.pop("comment", self.uuid)

    def render(self):
        lines = [f"\t\t{self.uuid} = {{"]
        lines.append(f"\t\t\tisa = {self.isa};")
        for k, v in self.fields.items():
            lines.append(f"\t\t\t{k} = {v};")
        lines.append("\t\t};")
        return "\n".join(lines)

def q(s):
    return f'"{s}"'

def lst(items, indent=6):
    pad = "\t" * indent
    inner = ",\n".join(f"{pad}{i}" for i in items)
    return "(\n" + inner + "\n" + "\t" * (indent - 1) + ")"

def main():
    objects = []

    # ---- 文件引用 ----
    app_ref = Obj("PBXFileReference", uuid=uid("app"), comment="CubeAR.app",
                  explicitFileType="wrapper.application", includeInIndex=0,
                  path="CubeAR.app", sourceTree="BUILT_PRODUCTS_DIR")
    objects.append(app_ref)

    file_refs = {}
    groups = {}

    def add_group(name, path, children, parent_tag):
        g = Obj("PBXGroup", uuid=uid(parent_tag), comment=name, children=lst(children), name=q(name) if name else '""',
                path=q(path) if path else None, sourceTree='"<group>"')
        # 移除 None 字段
        g.fields = {k: v for k, v in g.fields.items() if v is not None}
        objects.append(g)
        return g

    for rel in SOURCES:
        base = os.path.basename(rel)
        fr = Obj("PBXFileReference", uuid=uid("fr:" + rel), comment=base,
                 lastKnownFileType="sourcecode.swift", path=q(base), sourceTree='"<group>"')
        objects.append(fr)
        file_refs[rel] = fr

    info_ref = Obj("PBXFileReference", uuid=uid("info-plist"), comment="Info.plist",
                   lastKnownFileType="text.plist.xml", path=q("Info.plist"), sourceTree='"<group>"')
    objects.append(info_ref)

    assets_ref = Obj("PBXFileReference", uuid=uid("assets"), comment="Assets.xcassets",
                     lastKnownFileType="folder.assetcatalog", path=q("Assets.xcassets"), sourceTree='"<group>"')
    objects.append(assets_ref)

    # 本地化资源：PBXVariantGroup + 各 lproj 的 PBXFileReference
    variant_groups = {}
    for table in ("Localizable.strings", "InfoPlist.strings"):
        children = []
        for lproj in LPROJS:
            rel = f"CubeAR/Resources/{lproj}.lproj/{table}"
            fr = Obj("PBXFileReference", uuid=uid("fr:" + rel), comment=lproj,
                     lastKnownFileType="text.plist.strings", name=q(lproj),
                     path=q(f"{lproj}.lproj/{table}"), sourceTree='"<group>"')
            objects.append(fr)
            children.append(fr.uuid)
        vg = Obj("PBXVariantGroup", uuid=uid("vg:" + table), comment=table,
                 children=lst(children), name=q(table), sourceTree='"<group>"')
        objects.append(vg)
        variant_groups[table] = vg

    # ---- 分组 ----
    g_core = add_group("Core", "Core", [file_refs[rel].uuid for rel in SOURCES if rel.startswith("Core/")], "grp:Core")
    g_app = Obj("PBXGroup", uuid=uid("grp:App"), comment="App",
                children=lst([file_refs["CubeAR/App/CubeARApp.swift"].uuid, info_ref.uuid]),
                name=q("App"), path=q("App"), sourceTree='"<group>"')
    objects.append(g_app)
    g_audio = add_group("Audio", "Audio",
                        [file_refs[rel].uuid for rel in SOURCES if rel.startswith("CubeAR/Audio/")], "grp:Audio")
    g_support = add_group("Support", "Support",
                          [file_refs[rel].uuid for rel in SOURCES if rel.startswith("CubeAR/Support/")], "grp:Support")
    g_ui = add_group("UI", "UI",
                     [file_refs[rel].uuid for rel in SOURCES if rel.startswith("CubeAR/UI/")], "grp:UI")
    g_res = add_group("Resources", "Resources",
                      [assets_ref.uuid, variant_groups["Localizable.strings"].uuid,
                       variant_groups["InfoPlist.strings"].uuid], "grp:Resources")
    g_game = add_group("CubeAR", "CubeAR",
                       [g_app.uuid, g_audio.uuid, g_support.uuid, g_ui.uuid, g_res.uuid], "grp:CubeAR")
    g_products = add_group("Products", None, [app_ref.uuid], "grp:Products")
    g_main = Obj("PBXGroup", uuid=uid("grp:main"), comment="main",
                 children=lst([g_core.uuid, g_game.uuid, g_products.uuid]),
                 sourceTree='"<group>"')
    objects.append(g_main)

    # ---- 构建阶段 ----
    src_build_files = []
    for rel in SOURCES:
        bf = Obj("PBXBuildFile", uuid=uid("bf:" + rel), comment=os.path.basename(rel) + " in Sources",
                 fileRef=file_refs[rel].uuid)
        objects.append(bf)
        src_build_files.append(bf.uuid)

    sources_phase = Obj("PBXSourcesBuildPhase", uuid=uid("phase:sources"), comment="Sources",
                        buildActionMask=2147483647, files=lst(src_build_files),
                        runOnlyForDeploymentPostprocessing=0)
    objects.append(sources_phase)
    frameworks_phase = Obj("PBXFrameworksBuildPhase", uuid=uid("phase:frameworks"), comment="Frameworks",
                           buildActionMask=2147483647, files=lst([]), runOnlyForDeploymentPostprocessing=0)
    objects.append(frameworks_phase)
    res_build_files = [
        Obj("PBXBuildFile", uuid=uid("bf:assets"), comment="Assets.xcassets in Resources",
            fileRef=assets_ref.uuid),
        Obj("PBXBuildFile", uuid=uid("bf:vg-local"), comment="Localizable.strings in Resources",
            fileRef=variant_groups["Localizable.strings"].uuid),
        Obj("PBXBuildFile", uuid=uid("bf:vg-info"), comment="InfoPlist.strings in Resources",
            fileRef=variant_groups["InfoPlist.strings"].uuid),
    ]
    for bf in res_build_files:
        objects.append(bf)
    resources_phase = Obj("PBXResourcesBuildPhase", uuid=uid("phase:resources"), comment="Resources",
                          buildActionMask=2147483647,
                          files=lst([bf.uuid for bf in res_build_files]),
                          runOnlyForDeploymentPostprocessing=0)
    objects.append(resources_phase)

    # ---- 配置 ----
    common = {
        "ASSETCATALOG_COMPILER_APPICON_NAME": q("AppIcon"),
        "CODE_SIGN_STYLE": "Automatic",
        "CURRENT_PROJECT_VERSION": q(_build_number),
        "DEVELOPMENT_TEAM": q(""),
        "ENABLE_PREVIEWS": "YES",
        "GENERATE_INFOPLIST_FILE": "NO",
        "INFOPLIST_FILE": q("CubeAR/App/Info.plist"),
        "IPHONEOS_DEPLOYMENT_TARGET": q("15.0"),
        "LD_RUNPATH_SEARCH_PATHS": q("$(inherited) @executable_path/Frameworks"),
        "MARKETING_VERSION": q(_marketing_version),
        "PRODUCT_BUNDLE_IDENTIFIER": q(_bundle_id),
        "PRODUCT_NAME": q("$(TARGET_NAME)"),
        "SDKROOT": q("iphoneos"),
        "SWIFT_EMIT_LOC_STRINGS": "NO",
        "SWIFT_VERSION": q("5.0"),
        "TARGETED_DEVICE_FAMILY": q("1"),
    }
    debug_extra = {
        "DEBUG_INFORMATION_FORMAT": q("dwarf"),
        "ENABLE_TESTABILITY": "YES",
        "GCC_OPTIMIZATION_LEVEL": q("0"),
        "ONLY_ACTIVE_ARCH": "YES",
        "SWIFT_ACTIVE_COMPILATION_CONDITIONS": q("DEBUG"),
        "SWIFT_OPTIMIZATION_LEVEL": q("-Onone"),
    }
    release_extra = {
        "DEBUG_INFORMATION_FORMAT": q("dwarf-with-dsym"),
        "ENABLE_NS_ASSERTIONS": "NO",
        "SWIFT_COMPILATION_MODE": q("wholemodule"),
        "SWIFT_OPTIMIZATION_LEVEL": q("-O"),
        "VALIDATE_PRODUCT": "YES",
    }

    def render_settings(d):
        # OpenStep plist 的字典条目必须以 ';' 结尾（数组才用逗号）
        return "{\n" + ";\n".join(f"\t\t\t\t{k} = {v}" for k, v in sorted(d.items())) + ";\n\t\t\t}"

    def build_config(name, tag, extra):
        fields = dict(common)
        fields.update(extra)
        return Obj("XCBuildConfiguration", uuid=uid(tag), comment=name,
                   buildSettings=render_settings(fields), name=q(name))

    cfg_debug = build_config("Debug", "cfg:debug", debug_extra)
    cfg_release = build_config("release", "cfg:release", release_extra)
    objects.append(cfg_debug)
    objects.append(cfg_release)

    target = Obj("PBXNativeTarget", uuid=uid("target"), comment="CubeAR",
                 buildConfigurationList=uid("cfglist:target"),
                 buildPhases=lst([frameworks_phase.uuid, sources_phase.uuid, resources_phase.uuid]),
                 buildRules=lst([]), dependencies=lst([]), name=q("CubeAR"),
                 productName=q("CubeAR"), productReference=app_ref.uuid,
                 productType=q("com.apple.product-type.application"))
    objects.append(target)

    cfglist_target = Obj("XCConfigurationList", uuid=uid("cfglist:target"), comment="CubeAR",
                         buildConfigurations=lst([cfg_debug.uuid, cfg_release.uuid]),
                         defaultConfigurationIsVisible=0, defaultConfigurationName=q("Release"))
    objects.append(cfglist_target)

    known_regions = lst([q(r) for r in ["en"] + [l for l in LPROJS if l != "en"]], indent=6)

    # OpenStep 格式里每个字典条目都必须以 ';' 结尾——嵌套字典最容易漏
    attributes_value = (
        "{\n"
        "\t\t\t\tBuildIndependentTargetsInParallel = 1;\n"
        "\t\t\t\tLastSwiftUpdateCheck = 1500;\n"
        "\t\t\t\tLastUpgradeCheck = 1500;\n"
        f"\t\t\t\tTargetAttributes = {{\n"
        f"\t\t\t\t\t{target.uuid} = {{\n"
        "\t\t\t\t\t\tCreatedOnToolsVersion = 15.0;\n"
        "\t\t\t\t\t};\n"
        "\t\t\t\t};\n"
        "\t\t\t}"
    )
    project = Obj("PBXProject", uuid=uid("project"), comment="Project object",
                  attributes=attributes_value,
                  buildConfigurationList=uid("cfglist:project"),
                  compatibilityVersion=q("Xcode 14.0"),
                  developmentRegion=q("en"), hasScannedForEncodings=0,
                  knownRegions=known_regions, mainGroup=g_main.uuid,
                  productRefGroup=g_products.uuid, projectDirPath=q(""),
                  projectRoot=q(""), targets=lst([target.uuid]))
    objects.append(project)
    cfglist_project = Obj("XCConfigurationList", uuid=uid("cfglist:project"), comment="Project object",
                          buildConfigurations=lst([cfg_debug.uuid, cfg_release.uuid]),
                          defaultConfigurationIsVisible=0, defaultConfigurationName=q("Release"))
    objects.append(cfglist_project)

    # ---- 输出 ----
    sections = {
        "PBXBuildFile": [], "PBXFileReference": [], "PBXFrameworksBuildPhase": [],
        "PBXGroup": [], "PBXNativeTarget": [], "PBXProject": [], "PBXResourcesBuildPhase": [],
        "PBXSourcesBuildPhase": [], "PBXVariantGroup": [], "XCBuildConfiguration": [],
        "XCConfigurationList": [],
    }
    for o in objects:
        sections[o.isa].append(o)

    out = []
    out.append("// !$*UTF8*$!\n{")
    out.append("\tarchiveVersion = 1;")
    out.append("\tclasses = {};")
    out.append("\tobjectVersion = 56;")
    out.append("\tobjects = {\n")
    for isa, objs in sections.items():
        out.append(f"/* Begin {isa} section */")
        for o in objs:
            c = comment_for(o.uuid)
            out.append(f"\t\t{o.uuid} /* {c} */ = {{")
            out.append(f"\t\t\tisa = {o.isa};")
            for k, v in o.fields.items():
                out.append(f"\t\t\t{k} = {v};")
            out.append("\t\t};")
        out.append(f"/* End {isa} section */\n")
    out.append("\t};")
    out.append(f"\trootObject = {project.uuid} /* Project object */;")
    out.append("}")
    os.makedirs(PROJ_DIR, exist_ok=True)
    with open(os.path.join(PROJ_DIR, "project.pbxproj"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print(f"wrote {os.path.join(PROJ_DIR, 'project.pbxproj')} ({len(objects)} objects)")

    # ---- 共享 scheme（fastlane gym / xcodebuild -scheme 必需） ----
    scheme_dir = os.path.join(PROJ_DIR, "xcshareddata", "xcschemes")
    os.makedirs(scheme_dir, exist_ok=True)
    buildable = f"""            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "{target.uuid}"
               BuildableName = "CubeAR.app"
               BlueprintName = "CubeAR"
               ReferencedContainer = "container:CubeAR.xcodeproj">
            </BuildableReference>"""
    scheme = f"""<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "1500"
   version = "1.7">
   <BuildAction
      parallelizeBuildables = "YES"
      buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "YES"
            buildForArchiving = "YES"
            buildForAnalyzing = "YES">
{buildable}
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <LaunchAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      launchStyle = "0"
      useCustomWorkingDirectory = "NO"
      ignoresPersistentStateOnLaunch = "NO"
      debugDocumentVersioning = "YES"
      debugServiceExtension = "internal"
      allowLocationSimulation = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
{buildable}
      </BuildableProductRunnable>
   </LaunchAction>
   <ArchiveAction
      buildConfiguration = "Release"
      revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
"""
    scheme_path = os.path.join(scheme_dir, "CubeAR.xcscheme")
    with open(scheme_path, "w", encoding="utf-8") as f:
        f.write(scheme)
    print(f"wrote {scheme_path}")

    # ---- Info.plist ----
    info_plist = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>en</string>
	<key>CFBundleDisplayName</key>
	<string>Crystal Grid</string>
	<key>CFBundleExecutable</key>
	<string>$(EXECUTABLE_NAME)</string>
	<key>CFBundleIdentifier</key>
	<string>$(PRODUCT_BUNDLE_IDENTIFIER)</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>$(PRODUCT_NAME)</string>
	<key>CFBundlePackageType</key>
	<string>$(PRODUCT_BUNDLE_PACKAGE_TYPE)</string>
	<key>CFBundleShortVersionString</key>
	<string>$(MARKETING_VERSION)</string>
	<key>CFBundleVersion</key>
	<string>$(CURRENT_PROJECT_VERSION)</string>
	<key>CFBundleLocalizations</key>
	<array>
{chr(10).join('\t\t<string>' + l + '</string>' for l in LPROJS)}
	</array>
	<key>LSRequiresIPhoneOS</key>
	<true/>
	<key>ITSAppUsesNonExemptEncryption</key>
	<false/>
	<key>UILaunchScreen</key>
	<dict/>
	<key>UIRequiresFullScreen</key>
	<true/>
	<key>UISupportedInterfaceOrientations</key>
	<array>
		<string>UIInterfaceOrientationPortrait</string>
	</array>
</dict>
</plist>
"""
    plist_path = os.path.join(IOS, "CubeAR", "App", "Info.plist")
    with open(plist_path, "w", encoding="utf-8") as f:
        f.write(info_plist)
    print(f"wrote {plist_path}")

    # ---- Assets.xcassets / AppIcon ----
    assets = os.path.join(IOS, "CubeAR", "Resources", "Assets.xcassets")
    os.makedirs(assets, exist_ok=True)
    with open(os.path.join(assets, "Contents.json"), "w") as f:
        json.dump({"info": {"author": "xcode", "version": 1}}, f, indent=2)
    iconset = os.path.join(assets, "AppIcon.appiconset")
    os.makedirs(iconset, exist_ok=True)
    with open(os.path.join(iconset, "Contents.json"), "w") as f:
        json.dump({
            "images": [
                {"filename": "icon1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}
            ],
            "info": {"author": "xcode", "version": 1},
        }, f, indent=2)
    print(f"wrote {iconset}/Contents.json (icon1024.png 由 make_icon.py 生成)")


if __name__ == "__main__":
    main()
