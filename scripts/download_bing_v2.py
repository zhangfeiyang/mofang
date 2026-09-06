#!/usr/bin/env python3
"""
Bing 图片搜索 v2 — 严格过滤。
Bing 异步结果含 murl（原始 URL）和 turl（缩略图 URL）。通过以下规则过滤：
1. URL 路径必须包含 cube/rubik/puzzle/magic 等关键词之一
2. 跳过明显的非图片域（amazon、aliexpress、pinterest pin 等）
3. 跳过含 dog/cat/car/shoe/dress 等无关词的 URL
4. 尺寸过滤：>= 400px 短边
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
LOG_PATH = OUT_DIR.parent / "bing_v2_log.json"

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
session = requests.Session()
session.headers.update({"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})

# 必须命中以下至少一个核心词才算魔方相关（去掉 magic 避免命中 disney）
GOOD_KEY = re.compile(r"\b(rubik|rubik's|cubie|moyu|qiyi|gans?\s|cfop|f2l|oll|pll|cuber|cubing|speedcub|tangle|puzzle|魔方)\b", re.I)
# 题目里含魔方词，但页面/URL 同时含以下则排除
BAD_KEY = re.compile(r"(disney|disneyland|magical[- ]dream|castle|dog|breed|cat|car|shoe|dress|shirt|phone|iphone|samsung|cartoon|kid|baby|wallpaper|abstract|background|pattern|texture|frame|border|template|logo|icon|sticker|svg|emoji|mascot|pepsi|bracelet|earring|gift|amazon|aliexpress|alibaba|wish\.|dhgate|flipkart|snapdeal|diy|3d[- ]print)", re.I)
BAD_DOMAIN = re.compile(r"(amazon\.|aliexpress\.|alibaba\.|pinterest\.com/pin|tumblr\.|twitter\.|facebook\.|instagram\.|reddit\.|youtube\.|tiktok\.|shopee\.|lazada\.)")


def search(query, count=50, first=0):
    url = "https://www.bing.com/images/async"
    params = {"q": f'"{query}"', "first": first, "count": count, "qft": "+filterui:photo-photo+filterui:imagesize-large"}
    r = session.get(url, params=params, timeout=30)
    r.raise_for_status()
    return r.text


def parse_image_urls(html):
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
                urls.append({
                    "url": url,
                    "w": data.get("mw", 0),
                    "h": data.get("mh", 0),
                    "title": data.get("t", ""),
                })
        except Exception:
            continue
    return urls


def keep(item):
    url = item["url"]
    title = item.get("title", "")
    text = url + " " + title
    if BAD_DOMAIN.search(url):
        return False
    if not GOOD_KEY.search(text):
        return False
    if BAD_KEY.search(text):
        return False
    # 太小跳过
    if item["w"] and item["h"]:
        if min(item["w"], item["h"]) < 300:
            return False
    return True


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
    p = urllib.parse.urlparse(url)
    base = Path(p.path).name or "img"
    base = re.sub(r"[^\w.\-]", "_", base)
    if "." not in base:
        base += ".jpg"
    return f"{prefix}_{base}"


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print("== Bing Image Search v2 ==", flush=True)

    queries = [
        "rubik's cube",
        "rubik cube solved",
        "rubik's cube scrambled",
        "speedcube",
        "3x3 rubik's cube",
        "rubik's cube close up",
        "rubik's cube hand",
        "rubik's cube on table",
        "rubik's cube tilted",
        "rubik's cube perspective",
        "rubik's cube tabletop",
        "rubik's cube mixed",
        "rubik's cube beginner",
        "rubik's cube twisted",
        "魔方 实物",
    ]

    log = []
    seen = set()
    for q in queries:
        print(f"\n--- {q}", flush=True)
        for first in [0, 50, 100]:
            try:
                html = search(q, first=first)
            except Exception as e:
                print(f"  search fail: {e}", flush=True)
                time.sleep(5)
                continue
            items = parse_image_urls(html)
            kept = [it for it in items if keep(it)]
            print(f"  first={first} got {len(items)} kept {len(kept)}", flush=True)
            for it in kept:
                url = it["url"]
                if url in seen:
                    continue
                seen.add(url)
                prefix = re.sub(r"\W+", "_", q)[:25]
                fname = safe_name(url, prefix)
                dest = OUT_DIR / fname
                status = download(url, dest)
                log.append({**it, "query": q, "saved_to": str(dest), "status": status})
                tag = "ok" if status.startswith("ok") else status.split(":")[0][:8]
                print(f"  [{tag}] {fname}", flush=True)
                time.sleep(0.4)
                if len(log) % 20 == 0:
                    LOG_PATH.write_text(json.dumps(log, ensure_ascii=False, indent=2))
            time.sleep(2)

    LOG_PATH.write_text(json.dumps(log, ensure_ascii=False, indent=2))
    ok = sum(1 for r in log if r["status"].startswith("ok") or r["status"] == "exists")
    print(f"\nTotal: {ok}/{len(log)}", flush=True)


if __name__ == "__main__":
    main()
