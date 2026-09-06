#!/usr/bin/env python3
"""
Bing v4 — 简化过滤，去掉 \b 边界，命中即收。
核心关键词：rubik, cube, 魔方, cfop, speedcube, moyu, qiyi, gan, cuber
排除：disney, castle, dog, iphone 等
"""
import json
import re
import time
import urllib.parse
from pathlib import Path

import requests
from bs4 import BeautifulSoup

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/bing")
LOG_PATH = OUT_DIR.parent / "bing_v4_log.json"

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
session = requests.Session()
session.headers.update({"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})

# 简单包含判断（不做 word 边界，匹配 URL 子串）
GOOD = re.compile(r"(rubik|cubie|moyu|qiyi|speedcub|tangle|cfop|f2l|pll|oll|魔方|方块|打乱|魔尺|cuber|cubing)", re.I)
BAD = re.compile(r"(disney|disneyland|magical[- ]?dream|theme[- ]park|amusement|castle|breed|^\s*cat\s|cartoon|kid|wallpaper|abstract|background|pattern|texture|template|logo|icon|sticker|svg|emoji|mascot|bracelet|earring|gift|diy|3d[- ]print|tumblr|t-shirt|comic)", re.I)
BAD_DOMAIN = re.compile(r"(amazon\.|aliexpress\.|alibaba\.|pinterest\.com/pin|twitter\.|facebook\.|instagram\.|reddit\.|youtube\.|tiktok\.|shopee\.|lazada\.|wish\.|dhgate\.|ebay\.)")


def search(query, count=50, first=0):
    url = "https://www.bing.com/images/async"
    params = {"q": f'"{query}"', "first": first, "count": count, "qft": "+filterui:photo-photo"}
    r = session.get(url, params=params, timeout=30)
    r.raise_for_status()
    return r.text


def parse(html):
    soup = BeautifulSoup(html, "html.parser")
    items = []
    for a in soup.find_all("a", {"class": "iusc"}):
        m = a.get("m")
        if not m:
            continue
        try:
            data = json.loads(m)
            url = data.get("murl")
            if url and url.startswith(("http://", "https://")):
                items.append({
                    "url": url,
                    "w": data.get("mw", 0),
                    "h": data.get("mh", 0),
                    "title": data.get("t", ""),
                })
        except Exception:
            continue
    return items


def keep(item):
    url = item["url"].lower()
    title = item.get("title", "").lower()
    if BAD_DOMAIN.search(item["url"]):
        return False
    if BAD.search(url) or BAD.search(title):
        return False
    # GOOD 必须命中
    if not GOOD.search(url) and not GOOD.search(title):
        return False
    # 尺寸过滤
    if item["w"] and item["h"]:
        if min(item["w"], item["h"]) < 300:
            return False
    return True


def curl_get(url, dest):
    """用 curl 直连，避免 proxy 限流"""
    import subprocess
    cmd = ["curl", "-s", "-A", UA, "--noproxy", "*", "--max-time", "60", "-L",
           "-w", "%{http_code}\\t%{content_type}\\t%{size_download}\\n",
           "-o", str(dest), url]
    for attempt in range(3):
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=70)
            if r.returncode != 0:
                if dest.exists():
                    dest.unlink()
                return False, f"curl-fail:{r.returncode}", 0
            last = r.stdout.strip().split("\n")[-1].strip()
            parts = last.split("\t")
            if len(parts) != 3:
                if dest.exists():
                    dest.unlink()
                return False, "bad-format", 0
            code, ct, sz = parts
            sz = int(sz)
            if code == "429":
                if dest.exists():
                    dest.unlink()
                time.sleep(15 * (2 ** attempt))
                continue
            if not code.startswith("2"):
                if dest.exists():
                    dest.unlink()
                return False, f"http{code}", sz
            if "image" not in ct:
                if dest.exists():
                    dest.unlink()
                return False, f"bad-ct:{ct}", sz
            if sz < 5000:
                if dest.exists():
                    dest.unlink()
                return False, f"too-small:{sz}", sz
            return True, ct, sz
        except subprocess.TimeoutExpired:
            if dest.exists():
                dest.unlink()
            return False, "timeout", 0
        except Exception as e:
            if dest.exists():
                dest.unlink()
            return False, f"err:{e}", 0
    return False, "max-retries", 0


def safe_name(url, prefix):
    p = urllib.parse.urlparse(url)
    base = Path(p.path).name or "img"
    base = re.sub(r"[^\w.\-]", "_", base)
    if "." not in base:
        base += ".jpg"
    return f"{prefix}_{base}"[:200]


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print("== Bing v4 ==", flush=True)

    log = []
    seen = set()
    if LOG_PATH.exists():
        try:
            log = json.loads(LOG_PATH.read_text())
            seen = {r["url"] for r in log}
            print(f"Resuming: {len(seen)} urls seen, {len(log)} entries", flush=True)
        except Exception:
            pass

    queries = [
        "rubik's cube",
        "rubik's cube solved",
        "rubik's cube scrambled",
        "rubik's cube 3x3",
        "rubik's cube white background",
        "rubik's cube on table",
        "rubik's cube hand",
        "rubik's cube tilted",
        "rubik's cube mixed",
        "rubik's cube twisted",
        "rubik's cube competition",
        "rubik's cube world record",
        "speedcube",
        "rubik's cube beginner",
        "rubik's cube exploded view",
        "rubik's cube mid rotation",
        "rubik's cube top view",
        "rubik's cube sticker",
        "rubik's cube solver",
        "魔方 实物",
        "魔方 打乱",
        "魔方 已完成",
        "魔方 还原",
    ]

    new_log = []
    for q in queries:
        print(f"\n--- {q}", flush=True)
        for first in [0, 50, 100, 150, 200]:
            try:
                html = search(q, first=first)
            except Exception as e:
                print(f"  search fail: {e}", flush=True)
                time.sleep(5)
                continue
            items = parse(html)
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
                ok, ct, sz = curl_get(url, dest)
                rec = {**it, "query": q, "saved_to": str(dest)}
                if ok:
                    rec["status"] = "ok"
                    rec["size"] = sz
                    print(f"  [ok {sz:>8}] {fname}", flush=True)
                else:
                    rec["status"] = ct
                    print(f"  [{ct[:14]:<14}] {fname}", flush=True)
                new_log.append(rec)
                time.sleep(0.4)
                if len(new_log) % 20 == 0:
                    LOG_PATH.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
            time.sleep(2)
        LOG_PATH.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))

    LOG_PATH.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
    ok = sum(1 for r in new_log if r.get("status") == "ok")
    print(f"\nTotal: {ok}/{len(new_log)}", flush=True)


if __name__ == "__main__":
    main()
