#!/usr/bin/env python3
"""
StockSnap.io CC0 魔方图片抓取器。
StockSnap.io 是 CC0 stock photo 网站，单页 + 分页机制。
所有图片可商用，无需署名。
"""
import json
import re
import time
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/stocksnap")
UA = "CubeARDatasetCollector/1.0 (research; contact: local)"

session = requests.Session()
session.headers.update({
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml",
})


def fetch_html(url: str):
    r = session.get(url, timeout=30)
    r.raise_for_status()
    return r.text


def find_image_urls(html: str):
    """从 StockSnap 页面提取图片下载链接 / 图片 src"""
    soup = BeautifulSoup(html, "html.parser")
    urls = []
    # StockSnap 在 photo detail 页用 .jpg 链接图
    # 这里我们先列出所有 img 标签里有 stocksnap 域名的，再尝试找下载链接
    for img in soup.find_all("img"):
        src = img.get("src") or img.get("data-src") or ""
        if not src:
            continue
        if "stocksnap.io" not in src:
            continue
        # 缩略图通常是 _NNN_NN_NNNN.jpg 形式，去掉 size 修饰得到原图
        # 形如 https://cdn.stocksnap.io/img-thumbs/960w/...jpg
        # 原图形如 https://cdn.stocksnap.io/img-full/...jpg 或 https://cdn.stocksnap.io/img-thumbs/2800w/...jpg
        if "/img-thumbs/" in src:
            # 尝试构造原图 URL
            # 把 /img-thumbs/<size>w/ 改成 /img-full/
            orig = re.sub(r"/img-thumbs/\d+w/", "/img-full/", src)
            urls.append(orig)
        urls.append(src)
    # 抓 download 链接（详情页有 download 按钮）
    for a in soup.find_all("a", href=True):
        href = a["href"]
        if "/download/" in href or "/img/" in href:
            if href.startswith("http"):
                urls.append(href)
    return list(set(urls))


def looks_like_cube(url: str) -> bool:
    """用 URL 关键词过滤非魔方图片"""
    bad = ["trophy", "people", "person", "logo", "icon", "abstract", "background", "pattern", "texture"]
    low = url.lower()
    return not any(b in low for b in bad)


def collect_listing():
    """抓取搜索结果列表页"""
    out = []
    for page in range(1, 6):  # 最多 5 页
        url = f"https://stocksnap.io/search/rubiks%20cube?page={page}"
        print(f"  listing page {page}: {url}")
        try:
            html = fetch_html(url)
        except Exception as e:
            print(f"    fail: {e}")
            break
        # 从 listing 页抽取详情页链接
        soup = BeautifulSoup(html, "html.parser")
        details = []
        for a in soup.find_all("a", href=True):
            href = a["href"]
            # 详情页：/photo/xxxx
            if re.match(r"^/photo/[A-Za-z0-9-]+$", href):
                details.append(urljoin("https://stocksnap.io", href))
        if not details:
            print(f"    no more pages at {page}")
            break
        out.extend(details)
        time.sleep(2)
    return list(dict.fromkeys(out))  # 去重保序


def download(url: str, dest: Path):
    if dest.exists() and dest.stat().st_size > 1000:
        return "exists"
    for attempt in range(3):
        try:
            with session.get(url, stream=True, timeout=60) as r:
                if r.status_code == 429:
                    time.sleep(10)
                    continue
                r.raise_for_status()
                with open(dest, "wb") as f:
                    for chunk in r.iter_content(64 * 1024):
                        if chunk:
                            f.write(chunk)
                return f"ok ({dest.stat().st_size})"
        except Exception as e:
            if dest.exists():
                dest.unlink()
            if attempt == 2:
                return f"fail: {e}"
            time.sleep(5)
    return "fail: max retries"


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print("== Collecting StockSnap.io Rubik's cube images ==")
    details = collect_listing()
    print(f"Found {len(details)} detail pages")
    log = []
    seen_dest = set()
    for d in details:
        try:
            html = fetch_html(d)
        except Exception as e:
            print(f"  detail fail {d}: {e}")
            continue
        imgs = find_image_urls(html)
        # 取第一张看起来像原图的
        for u in imgs:
            if not looks_like_cube(u):
                continue
            # 跳过太小的缩略图
            if "_thumbs_" in u or "/thumbs/" in u:
                continue
            # 跳过 SVG / webp
            if u.endswith((".svg", ".webp")):
                continue
            parsed = urlparse(u)
            fname = Path(parsed.path).name
            if not fname:
                continue
            dest = OUT_DIR / fname
            if dest.name in seen_dest:
                continue
            seen_dest.add(dest.name)
            status = download(u, dest)
            log.append({"url": u, "saved_to": str(dest), "status": status})
            print(f"  [{status[:6]}] {fname}")
            time.sleep(1)
            break  # 一个详情页只取一张
        time.sleep(1)
    (OUT_DIR.parent / "stocksnap_log.json").write_text(json.dumps(log, ensure_ascii=False, indent=2))
    ok = sum(1 for r in log if r["status"].startswith("ok") or r["status"] == "exists")
    print(f"Done. {ok}/{len(log)} downloaded")


if __name__ == "__main__":
    main()
