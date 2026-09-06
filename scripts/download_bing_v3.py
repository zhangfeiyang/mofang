#!/usr/bin/env python3
"""
Bing v3 — 中等严格的过滤：
- 必须命中至少一个核心词 (rubik / cube / puzzle / 魔方)
- 显式排除 disney / castle
- 排除明显是商品的域名
"""
import json
import re
import time
import urllib.parse
from pathlib import Path

import requests
from bs4 import BeautifulSoup

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/bing")
LOG_PATH = OUT_DIR.parent / "bing_v3_log.json"

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
session = requests.Session()
session.headers.update({"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})

# 强信号：rubik 类（出现就基本是魔方）
STRONG = re.compile(r"\b(rubik|rubiks|rubik's|cubie|moyu|qiyi|gan3|cubing|cuber|speedcub|tangle|cfop|f2l|pll|oll|3x3|4x4|5x5|2x2|6x6|7x7|wca|twisty|pocket\s?cube|魔方|方块|打乱)", re.I)
# 弱信号（必须与强信号共存）
WEAK = re.compile(r"\b(cube|puzzle)\b", re.I)
BAD_KEY = re.compile(r"(disney|disneyland|magical[- ]dream|castle|dogs?\s|breed|^\s*cat\s|iphone|samsung|cartoon|kid|wallpaper|abstract|background|pattern|texture|template|logo|icon|sticker|svg|emoji|mascot|bracelet|earring|gift|diy|3d[- ]print|tumblr|t-shirt)", re.I)
BAD_DOMAIN = re.compile(r"(amazon\.|aliexpress\.|alibaba\.|pinterest\.com/pin|twitter\.|facebook\.|instagram\.|reddit\.|youtube\.|tiktok\.|shopee\.|lazada\.|wish\.|dhgate\.)")


def search(query, count=50, first=0):
    url = "https://www.bing.com/images/async"
    params = {"q": f'"{query}"', "first": first, "count": count, "qft": "+filterui:photo-photo"}
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
    if BAD_KEY.search(text):
        return False
    # 强信号：rubik 类词命中即可
    if STRONG.search(text):
        pass
    # 弱信号：必须和强信号共存
    elif WEAK.search(text):
        pass
    else:
        return False
    if item["w"] and item["h"]:
        if min(item["w"], item["h"]) < 300:
            return False
    return True


def download(url, dest):
    if dest.exists() and dest.stat().st_size > 5000:
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
                if dest.stat().st_size < 5000:
                    dest.unlink()
                    return "too-small"
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
    print("== Bing Image Search v3 ==", flush=True)

    queries = [
        "rubik's cube",
        "rubik cube solved",
        "rubik's cube scrambled",
        "speedcube",
        "3x3 rubik's cube",
        "rubik's cube close up",
        "rubik's cube hand",
        "rubik's cube tilted",
        "rubik's cube tabletop",
        "rubik's cube beginner",
        "rubik's cube twisted",
        "rubik's cube in hand",
        "rubik's cube white background",
        "rubik's cube color",
        "rubik's cube 9 stickers",
        "rubik's cube corner",
        "rubik's cube sticker",
        "rubik's cube solver",
        "rubik's cube world record",
        "rubik's cube competition",
    ]

    log = []
    seen = set()
    for q in queries:
        print(f"\n--- {q}", flush=True)
        for first in [0, 50, 100, 150]:
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
                if len(log) % 25 == 0:
                    LOG_PATH.write_text(json.dumps(log, ensure_ascii=False, indent=2))
            time.sleep(2)

    LOG_PATH.write_text(json.dumps(log, ensure_ascii=False, indent=2))
    ok = sum(1 for r in log if r["status"].startswith("ok") or r["status"] == "exists")
    print(f"\nTotal: {ok}/{len(log)}", flush=True)


if __name__ == "__main__":
    main()
