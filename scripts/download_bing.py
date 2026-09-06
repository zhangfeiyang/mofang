#!/usr/bin/env python3
"""
通过 Bing 图片搜索拉取魔方图片。
免费、无需 API key。抓取首页 HTML 即可拿到 murl（原始图链接）。
"""
import json
import re
import sys
import time
import urllib.parse
from pathlib import Path

import requests
from bs4 import BeautifulSoup

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/bing")
LOG_PATH = OUT_DIR.parent / "bing_log.json"

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
session = requests.Session()
session.headers.update({
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml",
    "Accept-Language": "en-US,en;q=0.9",
})


def search(query, count=35):
    """Bing 图片搜索第一页"""
    url = "https://www.bing.com/images/async"
    params = {"q": query, "first": 0, "count": count, "qft": "+filterui:photo-photo"}
    r = session.get(url, params=params, timeout=30)
    r.raise_for_status()
    return r.text


def parse_image_urls(html):
    """从 Bing 异步结果页面抽取 murl（原始图片 URL）"""
    soup = BeautifulSoup(html, "html.parser")
    urls = []
    for a in soup.find_all("a", {"class": "iusc"}):
        m = a.get("m")
        if not m:
            continue
        try:
            data = json.loads(m)
            url = data.get("murl")
            if url and url.startswith(("http://", "https://")):
                urls.append(url)
        except Exception:
            continue
    return urls


def download(url, dest):
    if dest.exists() and dest.stat().st_size > 1000:
        return "exists"
    for attempt in range(3):
        try:
            with session.get(url, stream=True, timeout=60, allow_redirects=True) as r:
                if r.status_code == 429:
                    time.sleep(15)
                    continue
                r.raise_for_status()
                ct = r.headers.get("content-type", "")
                if "image" not in ct:
                    return f"bad-ct:{ct}"
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


def safe_name(url, prefix):
    """从 URL 生成文件名"""
    p = urllib.parse.urlparse(url)
    base = Path(p.path).name or "image"
    base = re.sub(r"[^\w.\-]", "_", base)
    if "." not in base:
        base += ".jpg"
    return f"{prefix}_{base}"


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print("== Bing Image Search ==", flush=True)

    queries = [
        "rubik's cube real photo",
        "rubik cube hand",
        "rubik's cube solved",
        "rubik's cube scrambled",
        "speedcube",
        "3x3 rubik's cube",
        "rubik's cube close up",
        "rubik's cube on table",
    ]

    log = []
    seen_urls = set()
    for q in queries:
        print(f"\n--- query: {q}", flush=True)
        for offset in [0, 35, 70]:
            try:
                html = search(f"{q} &first={offset}&count=35")
            except Exception as e:
                print(f"  search fail: {e}", flush=True)
                time.sleep(5)
                continue
            urls = parse_image_urls(html)
            print(f"  offset={offset} got {len(urls)} urls", flush=True)
            for u in urls:
                if u in seen_urls:
                    continue
                seen_urls.add(u)
                fname = safe_name(u, q.replace(" ", "_")[:20])
                dest = OUT_DIR / fname
                status = download(u, dest)
                log.append({"query": q, "url": u, "saved_to": str(dest), "status": status})
                tag = "ok" if status.startswith("ok") else status.split(":")[0][:8]
                print(f"  [{tag}] {fname}", flush=True)
                time.sleep(0.4)
                # 每 20 张保存一次
                if len(log) % 20 == 0:
                    LOG_PATH.write_text(json.dumps(log, ensure_ascii=False, indent=2))
            time.sleep(2)
        LOG_PATH.write_text(json.dumps(log, ensure_ascii=False, indent=2))

    ok = sum(1 for r in log if r["status"].startswith("ok") or r["status"] == "exists")
    print(f"\nTotal: {ok}/{len(log)}", flush=True)


if __name__ == "__main__":
    main()
