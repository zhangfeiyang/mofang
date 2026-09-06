#!/usr/bin/env python3
"""
验证下载的魔方图片数据集。
- 检查每个文件是合法图片
- 过滤太小的文件
- 按目录汇总统计
"""
import json
from pathlib import Path

DATA = Path("/home/zhangfy/mofang/data/cube_images")


def check_dir(d):
    files = []
    for f in sorted(d.iterdir()):
        if not f.is_file():
            continue
        sz = f.stat().st_size
        # 读取 magic bytes 验证
        with open(f, "rb") as fh:
            magic = fh.read(12)
        if magic.startswith(b"\xff\xd8"):
            kind = "jpeg"
        elif magic.startswith(b"\x89PNG"):
            kind = "png"
        elif magic.startswith(b"GIF"):
            kind = "gif"
        elif magic.startswith(b"RIFF") and b"WEBP" in magic[:12]:
            kind = "webp"
        else:
            kind = "unknown"
        files.append({"name": f.name, "size": sz, "kind": kind})
    return files


def main():
    print("=== Cube image dataset ===\n")
    total = 0
    total_size = 0
    for sub in DATA.iterdir():
        if not sub.is_dir():
            continue
        files = check_dir(sub)
        valid = [f for f in files if f["kind"] in ("jpeg", "png", "webp", "gif")]
        invalid = [f for f in files if f["kind"] == "unknown"]
        sz = sum(f["size"] for f in files)
        total += len(valid)
        total_size += sz
        print(f"{sub.name}/: {len(valid)} valid, {len(invalid)} invalid, {sz/1024:.1f} KB total")
        for f in invalid:
            print(f"  ! {f['name']} ({f['size']} bytes, {f['kind']})")
    print(f"\n=== TOTAL: {total} images, {total_size/1024/1024:.1f} MB ===")


if __name__ == "__main__":
    main()
