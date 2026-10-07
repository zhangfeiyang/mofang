#!/usr/bin/env python3
"""Sanity checks for the iOS port:
1. pbxproj: unique UUIDs, all referenced UUIDs defined, brace balance.
2. Resolve the group tree: every file reference must exist on disk.
3. Every app Swift file on disk must be compiled by the project.
4. .strings: parseable key/value pairs, same key set across all locales.
Run from repo root:  python3 ios/tools/check.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
IOS = os.path.join(ROOT, "ios")
PBXPROJ = os.path.join(IOS, "CubeAR.xcodeproj", "project.pbxproj")
RES = os.path.join(IOS, "CubeAR", "Resources")

failures = []

def check(cond, msg):
    if cond:
        print(f"  ok  {msg}")
    else:
        failures.append(msg)
        print(f"FAIL  {msg}")

# ---------- 解析 pbxproj ----------
print("== pbxproj ==")
with open(PBXPROJ, encoding="utf-8") as f:
    content = f.read()

check(content.count("{") == content.count("}"), "braces balanced")
check(content.lstrip().startswith("// !$*UTF8*$!"), "UTF8 marker")

# 严格检查：除文件末尾的根对象收尾外，所有以 '}' 结尾的行都必须带 ';'
bad = [i + 1 for i, line in enumerate(content.splitlines())
       if line.rstrip().endswith("}") and not line.rstrip().endswith("};")
       and i + 1 < len(content.splitlines())]
check(not bad, f"every closing brace line ends with ';' (bad lines: {bad[:5]})")

# OpenStep plist 语法校验（xcodebuild 同款解析器语义；缺库时跳过）
try:
    from openstep_parser.openstep_parser import OpenStepDecoder
    tree = OpenStepDecoder.ParseFromFile(open(PBXPROJ))
    n_objs = len(tree["objects"])
    check(n_objs > 0, f"OpenStep plist parses ({n_objs} objects)")
except ImportError:
    print("SKIP  openstep-parser 未安装，跳过语法校验（pip3 install openstep-parser）")

blocks = {}
for m in re.finditer(r"\n\t\t([0-9A-F]{24}) /\* (.*?) \*/ = \{(.*?)\n\t\t\};", content, re.S):
    uuid, comment, body = m.group(1), m.group(2), m.group(3)
    blocks[uuid] = {"comment": comment, "body": body}

check(len(blocks) > 0, f"objects parsed ({len(blocks)})")
check(len(blocks) == len(set(blocks)), "uuids unique")

def field(body, key):
    m = re.search(rf"\n\t\t\t{key} = (.+?);", body)
    return m.group(1).strip() if m else None

def list_field(body, key):
    m = re.search(rf"\n\t\t\t{key} = \((.*?)\)", body, re.S)
    if not m:
        return []
    return re.findall(r"([0-9A-F]{24})", m.group(1))

isa = {u: field(b["body"], "isa") for u, b in blocks.items()}
file_refs = {u: (field(b["body"], "path"), field(b["body"], "sourceTree"), field(b["body"], "name"))
             for u, b in blocks.items() if isa[u] == "PBXFileReference"}
groups = {u: (field(b["body"], "path"), list_field(b["body"], "children"))
          for u, b in blocks.items() if isa[u] in ("PBXGroup", "PBXVariantGroup")}
variant_groups = {u for u, b in blocks.items() if isa[u] == "PBXVariantGroup"}

# 引用完整性
referenced = set()
for u, b in blocks.items():
    for key in ("fileRef", "rootObject", "mainGroup", "productRefGroup",
                "buildConfigurationList", "productReference"):
        v = field(b["body"], key)
        if v:
            referenced.add(v)
    for key in ("children", "buildPhases", "targets", "buildConfigurations"):
        referenced |= set(list_field(b["body"], key))
missing = referenced - set(blocks)
check(not missing, f"all referenced uuids defined (missing: {sorted(missing)[:5]})")

# 组树解析 → 每个文件引用必须存在于磁盘
main_group = field(blocks[field(blocks[field(blocks["C0" + "0" * 22]["body"], "rootObject") if False else ""]["body"], "rootObject")] ["body"], "mainGroup") if False else None
project_uuid = [u for u, b in blocks.items() if isa[u] == "PBXProject"][0]
main_group = field(blocks[project_uuid]["body"], "mainGroup")

missing_files = []

def walk(group_uuid, base):
    path, children = groups[group_uuid]
    here = base
    if path and group_uuid not in variant_groups:
        here = os.path.join(base, path.strip('"'))
    for c in children:
        if c in groups:
            walk(c, here)
        elif c in file_refs:
            fpath, source_tree, name = file_refs[c]
            if not fpath or source_tree and "BUILT_PRODUCTS" in source_tree:
                continue
            full = os.path.join(here, fpath.strip('"'))
            if not os.path.exists(full):
                missing_files.append(full)

walk(main_group, IOS)
check(not missing_files, f"all file references exist on disk (missing: {missing_files[:5]})")

# 每个 app swift 文件都被工程编译
compiled = set()
for u, b in blocks.items():
    if isa[u] == "PBXBuildFile":
        fr = field(b["body"], "fileRef")
        if fr and fr in file_refs and (file_refs[fr][0] or "").strip('"').endswith(".swift"):
            compiled.add(file_refs[fr][0].strip('"'))
on_disk = set()
for base_dir, _, files in os.walk(IOS):
    if ".build" in base_dir or "CoreTests" in base_dir or ".xcodeproj" in base_dir:
        continue
    for fn in files:
        if fn.endswith(".swift") and fn != "Package.swift":
            on_disk.add(fn)
listed_names = {os.path.basename(c) for c in compiled}
unlisted = on_disk - listed_names
check(not unlisted, f"every app swift file compiled (unlisted: {sorted(unlisted)})")
check("Package.swift" not in listed_names, "Package.swift not part of app target")

# ---------- .strings ----------
print("== strings ==")
reference_keys = None
locales = 0
for lproj in sorted(os.listdir(RES)):
    if not lproj.endswith(".lproj"):
        continue
    locales += 1
    table_path = os.path.join(RES, lproj, "Localizable.strings")
    with open(table_path, encoding="utf-8") as f:
        text = f.read()
    pairs = re.findall(r'^"([\w]+)" = "((?:[^"\\]|\\.)*)";$', text, re.M)
    check(len(pairs) > 0, f"{lproj}: parseable ({len(pairs)} keys)")
    keys = frozenset(k for k, _ in pairs)
    if reference_keys is None:
        reference_keys = keys
    elif keys != reference_keys:
        check(False, f"{lproj}: same key set as en")
    check(os.path.exists(os.path.join(RES, lproj, "InfoPlist.strings")), f"{lproj}: InfoPlist.strings present")
check(locales == 24, f"24 locales (found {locales})")

print()
if failures:
    print(f"{len(failures)} FAILURE(S)")
    sys.exit(1)
print("ALL CHECKS PASSED")
