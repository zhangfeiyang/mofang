#!/usr/bin/env python3
"""
Open Images V7 — 用 FiftyOne 拉取 Rubik's Cube 类。
默认只下 validation 切片（~36GB metadata, ~600MB actual images for 1 class）。
"""
import os
import sys
from pathlib import Path

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/openimages")

# 先检查 fiftyone 是否可用
try:
    import fiftyone as fo
    import fiftyone.zoo as foz
except ImportError:
    print("fiftyone not installed, skipping")
    sys.exit(0)


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print("Loading Open Images V7 validation split with Rubik's Cube...", flush=True)
    print("This may take a while (downloading the annotation CSVs first)", flush=True)
    try:
        dataset = foz.load_zoo_dataset(
            "open-images-v7",
            split="validation",
            label_types=["detections"],
            classes=["Rubik's Cube"],
            max_samples=300,
        )
        print(f"Loaded {len(dataset)} samples", flush=True)
        # 把图片导出到 OUT_DIR
        for sample in dataset:
            if not sample.filepath:
                continue
            src = Path(sample.filepath)
            dst = OUT_DIR / src.name
            if dst.exists():
                continue
            try:
                # 用 os.link 硬链接而不是复制以节省空间
                os.link(src, dst)
            except Exception:
                # 跨设备时退化为复制
                import shutil
                shutil.copy(src, dst)
        print(f"Linked/copied {len(list(OUT_DIR.glob('*')))} files to {OUT_DIR}", flush=True)
    except Exception as e:
        print(f"Error: {e}", flush=True)
        print("Trying alternative approach...", flush=True)
        # 直接下载 OI 文件
        try:
            import urllib.request
            # Class label mapping
            url = "https://storage.googleapis.com/openimages/v7/class-descriptions-boxable.csv"
            target = "/tmp/class-desc.csv"
            if not Path(target).exists():
                urllib.request.urlretrieve(url, target)
            # 找 Rubik's Cube
            with open(target) as f:
                for line in f:
                    parts = line.strip().split(",", 1)
                    if len(parts) == 2 and "rubik" in parts[1].lower():
                        print(f"Found: {parts}", flush=True)
        except Exception as e2:
            print(f"Fallback also failed: {e2}", flush=True)


if __name__ == "__main__":
    main()
