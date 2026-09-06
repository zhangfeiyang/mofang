#!/usr/bin/env python3
"""
下载魔方图片到本地
来源：Wikimedia Commons（CC 授权）、StockSnap.io（CC0）、Open Images（CC BY）
"""
import json
import os
import sys
import time
import urllib.parse
from pathlib import Path

import requests

OUT_BASE = Path("/home/zhangfy/mofang/data/cube_images")
UA = "CubeARDatasetCollector/1.0 (research; contact: local)"

session = requests.Session()
session.headers.update({"User-Agent": UA})


def commons_search(query: str, limit: int = 50, offset: int = 0):
    """搜索 Wikimedia Commons 上的图片文件，返回 (title, mime) 列表"""
    url = "https://commons.wikimedia.org/w/api.php"
    params = {
        "action": "query",
        "list": "search",
        "srsearch": query,
        "srnamespace": 6,  # File namespace
        "srlimit": limit,
        "sroffset": offset,
        "format": "json",
    }
    r = session.get(url, params=params, timeout=30)
    r.raise_for_status()
    data = r.json()
    results = []
    for hit in data.get("query", {}).get("search", []):
        title = hit["title"]  # e.g. "File:Rubiks_cube_by_keqs.jpg"
        # 过滤掉 svg 矢量图
        if title.lower().endswith((".svg", ".ogv", ".webm", ".tif", ".tiff")):
            continue
        results.append(title)
    return results, data.get("continue", {}).get("sroffset")


def commons_image_url(title: str, max_retries: int = 4):
    """获取 Wikimedia Commons 文件的直链和 mime 信息，含重试和退避"""
    url = "https://commons.wikimedia.org/w/api.php"
    params = {
        "action": "query",
        "titles": title,
        "prop": "imageinfo",
        "iiprop": "url|mime|size|extmetadata",
        "format": "json",
    }
    for attempt in range(max_retries):
        try:
            r = session.get(url, params=params, timeout=30)
            if r.status_code == 429:
                wait = min(60, 5 * (2 ** attempt))
                print(f"    429 hit, sleeping {wait}s …")
                time.sleep(wait)
                continue
            r.raise_for_status()
            data = r.json()
            pages = data.get("query", {}).get("pages", {})
            for page in pages.values():
                infos = page.get("imageinfo", [])
                if not infos:
                    continue
                info = infos[0]
                return {
                    "url": info.get("url"),
                    "mime": info.get("mime"),
                    "width": info.get("width"),
                    "height": info.get("height"),
                    "size": info.get("size"),
                    "license": info.get("extmetadata", {}).get("LicenseShortName", {}).get("value"),
                    "artist": info.get("extmetadata", {}).get("Artist", {}).get("value"),
                }
            return None
        except Exception as e:
            if attempt == max_retries - 1:
                print(f"    api error: {e}")
                return None
            time.sleep(2 * (2 ** attempt))
    return None


def download(url: str, dest: Path, timeout: int = 60, max_retries: int = 3):
    """下载单个文件，含重试"""
    if dest.exists() and dest.stat().st_size > 1000:
        return "exists"
    for attempt in range(max_retries):
        try:
            with session.get(url, stream=True, timeout=timeout) as r:
                if r.status_code == 429:
                    wait = min(60, 10 * (2 ** attempt))
                    print(f"    429 on download, sleeping {wait}s …")
                    time.sleep(wait)
                    continue
                r.raise_for_status()
                with open(dest, "wb") as f:
                    for chunk in r.iter_content(chunk_size=64 * 1024):
                        if chunk:
                            f.write(chunk)
                return f"ok ({dest.stat().st_size} bytes)"
        except Exception as e:
            if dest.exists():
                dest.unlink()
            if attempt == max_retries - 1:
                return f"fail: {e}"
            time.sleep(2 * (2 ** attempt))
    return "fail: max retries"


def collect_wikimedia():
    """从 Wikimedia Commons 抓取魔方相关图片"""
    out_dir = OUT_BASE / "wikimedia"
    out_dir.mkdir(parents=True, exist_ok=True)
    log = []

    queries = [
        'rubik%27s+cube+filetype:bitmap',
        'rubik+cube+filetype:bitmap',
        'speedcube',
        'magic+cube+puzzle',
        '魔方+filetype:bitmap',
    ]

    seen_titles = set()
    for q in queries:
        offset = 0
        for page in range(5):  # 每次查询最多 5 页
            titles, offset = commons_search(q, limit=50, offset=offset)
            if not titles:
                break
            for title in titles:
                if title in seen_titles:
                    continue
                seen_titles.add(title)
                # 只关心位图
                low = title.lower()
                if not (low.endswith(".jpg") or low.endswith(".jpeg") or low.endswith(".png")):
                    continue
                info = commons_image_url(title)
                if not info or not info.get("url"):
                    continue
                if info["mime"] not in ("image/jpeg", "image/png"):
                    continue
                # 尺寸太小不要（< 200 px）
                if (info.get("width") or 0) < 200 or (info.get("height") or 0) < 200:
                    continue
                fname = title.replace("File:", "").replace("/", "_")
                fname = urllib.parse.unquote(fname)
                dest = out_dir / fname
                status = download(info["url"], dest)
                rec = {
                    "title": title,
                    "url": info["url"],
                    "mime": info["mime"],
                    "size": info.get("size"),
                    "wh": (info.get("width"), info.get("height")),
                    "license": info.get("license"),
                    "saved_to": str(dest),
                    "status": status,
                }
                log.append(rec)
                tag = status if status.startswith("ok") or status == "exists" else status.split(":")[0]
                print(f"  [{tag[:6]}] {fname} ({info.get('width')}x{info.get('height')})")
                time.sleep(0.5)  # 礼貌延迟
            if not offset:
                break

    log_path = OUT_BASE / "wikimedia_log.json"
    log_path.write_text(json.dumps(log, ensure_ascii=False, indent=2))
    return len([r for r in log if r["status"].startswith("ok") or r["status"] == "exists"])


if __name__ == "__main__":
    print("== Collecting Rubik's cube images from Wikimedia Commons ==")
    n = collect_wikimedia()
    print(f"Done. {n} images in {OUT_BASE/'wikimedia'}")
