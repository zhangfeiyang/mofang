#!/usr/bin/env python3
"""
综合下载器：Wikimedia Commons + Bing 图片搜索。
- 所有 curl 走 --noproxy '*' 直连（绕过 HTTP_PROXY）
- 遇到 429 指数退避（30/60/120s）
- 验证 content-type 和最小文件大小
- 增量日志，断点续传
"""
import json
import re
import subprocess
import time
import urllib.parse
from pathlib import Path

import requests
from bs4 import BeautifulSoup

DATA_ROOT = Path("/home/zhangfy/mofang/data/cube_images")
WM_DIR = DATA_ROOT / "wikimedia"
BING_DIR = DATA_ROOT / "bing"
WM_LOG = DATA_ROOT / "wikimedia_v8_log.json"
BING_LOG = DATA_ROOT / "bing_v4_log.json"

UA = "CubeAR-Research/1.0 (zhangfeiyang; contact via local)"
CHROME_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

session = requests.Session()
session.headers.update({"User-Agent": CHROME_UA, "Accept-Language": "en-US,en;q=0.9"})


def curl_json(url, retries=3):
    cmd = ["curl", "-s", "-A", UA, "--noproxy", "*", "--max-time", "30", url]
    for attempt in range(retries):
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=35)
            if r.returncode == 0:
                return json.loads(r.stdout)
            time.sleep(2 * (attempt + 1))
        except Exception:
            time.sleep(2 * (attempt + 1))
    return None


def curl_get(url, dest):
    cmd = ["curl", "-s", "-A", UA, "--noproxy", "*", "--max-time", "120", "-L",
           "-w", "%{http_code}\\t%{content_type}\\t%{size_download}\\n",
           "-o", str(dest), url]
    for attempt in range(3):
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=130)
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
                wait = 30 * (2 ** attempt)
                print(f"      [429] sleep {wait}s", flush=True)
                time.sleep(wait)
                continue
            if code.startswith("3") and "image" not in code:
                if dest.exists():
                    dest.unlink()
                return False, f"redirect-{code}", 0
            if not (code.startswith("2")):
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


def safe_name(title):
    name = title.replace("File:", "").replace("/", "_")
    return urllib.parse.unquote(name)


def cat_members(category):
    titles = []
    offset = None
    while True:
        params = {
            "action": "query", "list": "categorymembers",
            "cmtitle": category, "cmtype": "file", "cmlimit": 500, "format": "json",
        }
        if offset:
            params["cmcontinue"] = offset
        url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params)
        data = curl_json(url)
        if not data:
            break
        for m in data.get("query", {}).get("categorymembers", []):
            t = m["title"]
            if t.lower().endswith((".jpg", ".jpeg", ".png")):
                titles.append(t)
        cont = data.get("continue", {})
        if not cont.get("cmcontinue"):
            break
        offset = cont["cmcontinue"]
        time.sleep(0.5)
    return titles


def wm_imageinfo(title):
    params = {"action": "query", "titles": title, "prop": "imageinfo",
              "iiprop": "url|mime|size", "format": "json"}
    url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params)
    data = curl_json(url)
    if not data:
        return None
    for page in data.get("query", {}).get("pages", {}).values():
        infos = page.get("imageinfo", [])
        if not infos:
            continue
        info = infos[0]
        return {
            "title": page["title"],
            "url": info.get("url"),
            "mime": info.get("mime"),
            "width": info.get("width", 0),
            "height": info.get("height", 0),
            "size": info.get("size", 0),
        }
    return None


def collect_wikimedia():
    WM_DIR.mkdir(parents=True, exist_ok=True)
    print("\n== WIKIMEDIA COMMONS ==", flush=True)

    log = []
    done = set()
    if WM_LOG.exists():
        try:
            log = json.loads(WM_LOG.read_text())
            done = {r["title"] for r in log if r.get("status") == "ok"}
            print(f"Resuming from {len(done)} done", flush=True)
        except Exception:
            pass

    categories = [
        "Category:Rubik's Cube",
        "Category:Magic cubes",
        "Category:Speedcubes",
        "Category:Rubik's Cube patterns",
    ]
    titles = set()
    for cat in categories:
        try:
            t = cat_members(cat)
            print(f"  {cat}: {len(t)}", flush=True)
            titles.update(t)
        except Exception as e:
            print(f"  {cat} fail: {e}", flush=True)
        time.sleep(1)

    print(f"Total titles: {len(titles)}", flush=True)
    bm_titles = sorted(t for t in titles if t not in done)
    print(f"To process: {len(bm_titles)}", flush=True)

    new_log = []
    for i, t in enumerate(bm_titles):
        if i % 25 == 0:
            print(f"[{i}/{len(bm_titles)}]", flush=True)
        info = wm_imageinfo(t)
        if not info or not info.get("url"):
            new_log.append({"title": t, "status": "no-info"})
            time.sleep(0.3)
            continue
        if info.get("mime") not in ("image/jpeg", "image/png"):
            new_log.append({**info, "status": "skip-mime"})
            continue
        w, h = info.get("width", 0), info.get("height", 0)
        if w < 300 or h < 300:
            new_log.append({**info, "status": "skip-size"})
            continue
        if w * h > 50_000_000:
            new_log.append({**info, "status": "skip-huge"})
            continue
        fname = safe_name(info["title"])
        dest = WM_DIR / fname
        if dest.exists() and dest.stat().st_size > 5000:
            new_log.append({**info, "saved_to": str(dest), "status": "ok"})
            continue
        ok, ct, sz = curl_get(info["url"], dest)
        if ok:
            new_log.append({**info, "saved_to": str(dest), "status": "ok", "size": sz})
            print(f"  [ok {sz:>8}] {fname} ({w}x{h})", flush=True)
        else:
            new_log.append({**info, "status": ct})
            print(f"  [{ct[:14]:<14}] {fname}", flush=True)
        time.sleep(0.5)
        if len(new_log) % 25 == 0:
            WM_LOG.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))

    WM_LOG.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
    ok_count = sum(1 for r in new_log if r["status"] == "ok")
    print(f"Wikimedia done: {ok_count}/{len(new_log)}", flush=True)
    return ok_count


# ---------- Bing ----------
STRONG = re.compile(r"(rubik|cubie|moyu|qiyi|gan3|cuber|cubing|speedcub|tangle|cfop|f2l|pll|oll|魔方)", re.I)
WEAK = re.compile(r"(cube|puzzle)", re.I)
BAD_KEY = re.compile(r"(disney|disneyland|magical[- ]?dream|theme[- ]park|amusement|castle|^\s*cat\s|^\s*dog\s|breed|iphone|samsung|cartoon|kid|wallpaper|abstract|background|pattern|texture|template|logo|icon|sticker|svg|emoji|mascot|bracelet|earring|gift|diy|3d[- ]print|tumblr|t-shirt)", re.I)
BAD_DOMAIN = re.compile(r"(amazon\.|aliexpress\.|alibaba\.|pinterest\.com/pin|twitter\.|facebook\.|instagram\.|reddit\.|youtube\.|tiktok\.|shopee\.|lazada\.|wish\.|dhgate\.)")


def bing_search(query, count=50, first=0):
    url = "https://www.bing.com/images/async"
    params = {"q": f'"{query}"', "first": first, "count": count, "qft": "+filterui:photo-photo"}
    r = session.get(url, params=params, timeout=30)
    r.raise_for_status()
    return r.text


def parse_bing(html):
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


def bing_keep(item):
    url = item["url"]
    title = item.get("title", "")
    text = (url + " " + title).lower()
    if BAD_DOMAIN.search(url):
        return False
    if BAD_KEY.search(text):
        return False
    # 至少命中一个强信号词
    if STRONG.search(text):
        pass
    elif WEAK.search(text):
        # 弱信号也可以，但要求短边更大
        if item["w"] and item["h"]:
            if min(item["w"], item["h"]) < 500:
                return False
        else:
            return False
    else:
        return False
    if item["w"] and item["h"]:
        if min(item["w"], item["h"]) < 350:
            return False
    return True


def safe_bing_name(url, prefix):
    p = urllib.parse.urlparse(url)
    base = Path(p.path).name or "img"
    base = re.sub(r"[^\w.\-]", "_", base)
    if "." not in base:
        base += ".jpg"
    return f"{prefix}_{base}"[:200]


def collect_bing():
    BING_DIR.mkdir(parents=True, exist_ok=True)
    print("\n== BING IMAGE SEARCH ==", flush=True)

    log = []
    seen = set()
    if BING_LOG.exists():
        try:
            log = json.loads(BING_LOG.read_text())
            seen = {r["url"] for r in log}
            print(f"Resuming from {len(seen)} urls seen", flush=True)
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
        "rubik's cube mixed up",
        "rubik's cube twisted",
        "rubik's cube competition",
        "rubik's cube solver",
        "rubik's cube world record",
        "speedcube",
        "rubik's cube beginner",
        "rubik's cube exploded view",
        "rubik's cube mid rotation",
        "rubik's cube top view",
        "rubik's cube front view",
        "rubik's cube sticker colors",
    ]

    new_log = []
    for q in queries:
        print(f"\n--- {q}", flush=True)
        for first in [0, 50, 100, 150]:
            try:
                html = bing_search(q, first=first)
            except Exception as e:
                print(f"  search fail: {e}", flush=True)
                time.sleep(5)
                continue
            items = parse_bing(html)
            kept = [it for it in items if bing_keep(it)]
            print(f"  first={first} got {len(items)} kept {len(kept)}", flush=True)
            for it in kept:
                url = it["url"]
                if url in seen:
                    continue
                seen.add(url)
                prefix = re.sub(r"\W+", "_", q)[:25]
                fname = safe_bing_name(url, prefix)
                dest = BING_DIR / fname
                ok, ct, sz = curl_get(url, dest)
                if ok:
                    new_log.append({**it, "query": q, "saved_to": str(dest), "status": "ok", "size": sz})
                    print(f"  [ok {sz:>8}] {fname}", flush=True)
                else:
                    new_log.append({**it, "query": q, "saved_to": str(dest), "status": ct})
                    print(f"  [{ct[:14]:<14}] {fname}", flush=True)
                time.sleep(0.4)
                if len(new_log) % 25 == 0:
                    BING_LOG.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
            time.sleep(2)

    BING_LOG.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
    ok_count = sum(1 for r in new_log if r["status"] == "ok")
    print(f"\nBing done: {ok_count}/{len(new_log)}", flush=True)
    return ok_count


if __name__ == "__main__":
    print("== Cube image collector ==", flush=True)
    wm_ok = collect_wikimedia()
    bg_ok = collect_bing()
    print(f"\n=== TOTAL ===", flush=True)
    print(f"Wikimedia: {wm_ok}", flush=True)
    print(f"Bing: {bg_ok}", flush=True)
