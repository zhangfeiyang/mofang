#!/usr/bin/env python3
"""由 ios/l10n/<语言>.json 生成 App 的多语言资源,并做一致性检查。

    python3 ios/tools/gen_strings.py          # 检查 + 生成
    python3 ios/tools/gen_strings.py --check  # 只检查

JSON 是唯一来源(键 → 文案,占位符 {0}{1}…):
- 普通键  → CubeAR/Resources/<语言>.lproj/Localizable.strings
- plist.* → InfoPlist.strings(主屏名称、相机权限说明)
- store.* → 只给 App Store 商品页用(asc_fill_locales.py),不进 App

检查项:每种语言的键与英文一致;占位符集合与英文一致;Swift 代码里用到的键都存在;
商店文案不超过 App Store Connect 的长度上限。生成后要重跑 gen_xcodeproj.py(语言列表变了时)。
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # ios/
L10N = os.path.join(ROOT, "l10n")
RESOURCES = os.path.join(ROOT, "CubeAR", "Resources")
BASE = "en"
PLACEHOLDER = re.compile(r"\{\d+\}")
STORE_LIMITS = {"store.name": 30, "store.subtitle": 30, "store.keywords": 100,
                "store.promo": 170, "store.description": 4000}
# 代码里拼出来的键(GuideStep.placeKey/directionKey、CubeColor、caption 的 .next 后缀)
DYNAMIC = (["place." + k for k in "R L U D F B Rw Lw Uw Dw Fw Bw M E S".split()]
           + ["dir." + k for k in "left right up down cw ccw half".split()]
           + ["color." + k for k in "white red green yellow orange blue unknown".split()]
           + ["turn.quarter", "turn.quarter.next", "turn.half", "turn.half.next", "turn.then"])


def load(language):
    with open(os.path.join(L10N, language + ".json"), encoding="utf-8") as f:
        return json.load(f)


def languages():
    return sorted(os.path.splitext(os.path.basename(p))[0] for p in glob.glob(os.path.join(L10N, "*.json")))


def keys_in_code():
    keys = set(DYNAMIC)
    for path in glob.glob(os.path.join(ROOT, "Core", "*.swift")) + glob.glob(os.path.join(ROOT, "CubeAR", "*", "*.swift")):
        text = open(path, encoding="utf-8").read()
        keys.update(re.findall(r'L10n\.t\(\s*"([^"]+)"', text))
        # L10n.t(cond ? "a" : "b") 这类三元写法
        for call in re.findall(r'L10n\.t\(([^()]*(?:\([^()]*\)[^()]*)*)\)', text):
            keys.update(k for k in re.findall(r'"([a-z0-9]+(?:\.[A-Za-z0-9]+)+)"', call))
    # 拼接用的前缀("place." + …)由 DYNAMIC 覆盖
    return {k for k in keys if not k.endswith(".") and "\\(" not in k}


def check():
    base = load(BASE)
    errors = []
    for key in sorted(keys_in_code()):
        if key not in base:
            errors.append(f"{BASE}: code uses missing key {key}")
    for language in languages():
        table = load(language)
        for key in base:
            if key not in table:
                errors.append(f"{language}: missing {key}")
            elif sorted(PLACEHOLDER.findall(table[key])) != sorted(PLACEHOLDER.findall(base[key])):
                errors.append(f"{language}: placeholders differ in {key}")
        for key in table:
            if key not in base:
                errors.append(f"{language}: unknown key {key}")
        for key, limit in STORE_LIMITS.items():
            if key in table and len(table[key]) > limit:
                errors.append(f"{language}: {key} is {len(table[key])} > {limit} characters")
    return errors


def escape(value):
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def write_strings(path, pairs):
    with open(path, "w", encoding="utf-8") as f:
        f.write("/* 由 ios/tools/gen_strings.py 从 ios/l10n 生成,勿手改 */\n")
        for key, value in pairs:
            f.write(f'"{escape(key)}" = "{escape(value)}";\n')


def generate():
    for language in languages():
        table = load(language)
        folder = os.path.join(RESOURCES, language + ".lproj")
        os.makedirs(folder, exist_ok=True)
        app = [(k, v) for k, v in sorted(table.items()) if not k.startswith(("store.", "plist."))]
        write_strings(os.path.join(folder, "Localizable.strings"), app)
        write_strings(os.path.join(folder, "InfoPlist.strings"), [
            ("CFBundleDisplayName", table["plist.name"]),
            ("NSCameraUsageDescription", table["plist.camera"]),
        ])
    print(f"wrote {len(languages())} languages: {' '.join(languages())}")


if __name__ == "__main__":
    problems = check()
    for problem in problems:
        print(problem)
    if problems:
        sys.exit(1)
    if "--check" not in sys.argv:
        generate()
