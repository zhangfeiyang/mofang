#!/usr/bin/env python3
"""把 refiner-parity.json 引用的真机帧解成原始 RGB,供 Linux 上的 RefinerParityTests 使用。

用法(仓库根目录): python3 ios/tools/decode_parity_frames.py <输出目录>
然后: cd ios && CUBEAR_PARITY_FRAMES=<输出目录> swift test --filter RefinerParityTests
需要 Pillow 和 data/real_cubes/images。输出约 600 MB,用完可删。
"""
import json
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FIXTURE = os.path.join(ROOT, "app", "src", "test", "resources", "refiner-parity.json")
IMAGES = os.path.join(ROOT, "data", "real_cubes", "images")


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    decoded = 0
    for entry in json.load(open(FIXTURE)):
        src = os.path.join(IMAGES, entry["file"])
        if not os.path.exists(src):
            continue
        image = Image.open(src).convert("RGB")
        width, height = image.size
        with open(os.path.join(out, f"{entry['file']}.{width}x{height}.rgb"), "wb") as f:
            f.write(image.tobytes())
        decoded += 1
    print(f"decoded {decoded} frames into {out}")


if __name__ == "__main__":
    main()
