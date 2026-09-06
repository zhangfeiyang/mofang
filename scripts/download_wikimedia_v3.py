#!/usr/bin/env python3
"""
更稳定的方式：用 Wikimedia Commons imageinfo 批量查询 + 直接下载。
更保守的速率：每个请求之间 1.5s sleep，遇到 429 自动指数退避。
"""
import json
import sys
import time
import urllib.parse
from pathlib import Path

import requests

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/wikimedia")
LOG_PATH = OUT_DIR.parent / "wikimedia_v2_log.json"

UA = "CubeAR-Research/1.0 (zhangfeiyang; contact via local)"
session = requests.Session()
session.headers.update({"User-Agent": UA})


def cat_members(category, limit=500, offset=None):
    url = "https://commons.wikimedia.org/w/api.php"
    params = {
        "action": "query",
        "list": "categorymembers",
        "cmtitle": category,
        "cmtype": "file",
        "cmlimit": limit,
        "format": "json",
    }
    if offset:
        params["cmcontinue"] = offset
    r = session.get(url, params=params, timeout=30)
    r.raise_for_status()
    return r.json()


def get_all_titles(categories):
    titles = set()
    for cat in categories:
        offset = None
        page = 0
        while True:
            try:
                data = cat_members(cat, offset=offset)
                members = data.get("query", {}).get("categorymembers", [])
                for m in members:
                    t = m["title"]
                    low = t.lower()
                    if low.endswith((".jpg", ".jpeg", ".png")):
                        titles.add(t)
                cont = data.get("continue", {})
                if not cont.get("cmcontinue"):
                    break
                offset = cont["cmcontinue"]
                page += 1
                print(f"    {cat} page {page} done, total {len(titles)}", flush=True)
                time.sleep(0.5)
            except Exception as e:
                print(f"    {cat} fail: {e}", flush=True)
                break
        print(f"  {cat}: {len(titles)} unique", flush=True)
        time.sleep(1)
    return titles


def batch_imageinfo(titles, batch=10):
    """分批查询，每批最多 10 个标题"""
    out = []
    for i in range(0, len(titles), batch):
        chunk = titles[i:i + batch]
        params = {
            "action": "query",
            "titles": "|".join(chunk),
            "prop": "imageinfo",
            "iiprop": "url|mime|size",
            "format": "json",
        }
        url = "https://commons.wikimedia.org/w/api.php"
        for attempt in range(6):
            try:
                r = session.get(url, params=params, timeout=30)
                if r.status_code == 429:
                    wait = 10 * (2 ** attempt)
                    print(f"      429, sleep {wait}s", flush=True)
                    time.sleep(wait)
                    continue
                r.raise_for_status()
                data = r.json()
                for page in data.get("query", {}).get("pages", {}).values():
                    infos = page.get("imageinfo", [])
                    if not infos:
                        continue
                    info = infos[0]
                    out.append({
                        "title": page["title"],
                        "url": info.get("url"),
                        "mime": info.get("mime"),
                        "width": info.get("width", 0),
                        "height": info.get("height", 0),
                        "size": info.get("size", 0),
                    })
                break
            except Exception as e:
                print(f"      api err: {e}", flush=True)
                if attempt == 5:
                    continue
                time.sleep(5)
        time.sleep(1.0)  # 每批之间 1s
    return out


def download(url, dest):
    if dest.exists() and dest.stat().st_size > 1000:
        return "exists"
    for attempt in range(3):
        try:
            with session.get(url, stream=True, timeout=120) as r:
                if r.status_code == 429:
                    time.sleep(20)
                    continue
                if r.status_code == 404:
                    return "404"
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


def safe_name(title):
    name = title.replace("File:", "").replace("/", "_")
    return urllib.parse.unquote(name)


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print("== Wikimedia Commons v3 ==", flush=True)

    # 已下载的
    done = set()
    log = []
    if LOG_PATH.exists():
        try:
            log = json.loads(LOG_PATH.read_text())
            done = {r["title"] for r in log if r.get("status", "").startswith("ok") or r.get("status") == "exists"}
            print(f"Resuming from {len(done)} already done", flush=True)
        except Exception:
            pass

    categories = [
        "Category:Rubik's Cube",
        "Category:Magic cubes",
    ]
    titles = get_all_titles(categories)
    print(f"Total titles: {len(titles)}", flush=True)

    bm_titles = sorted(t for t in titles if t not in done)
    print(f"To process: {len(bm_titles)}", flush=True)

    # 分批查询 imageinfo
    BATCH = 10
    new_log = []
    for i in range(0, len(bm_titles), BATCH):
        chunk = bm_titles[i:i + BATCH]
        print(f"[{i}/{len(bm_titles)}] querying {len(chunk)}...", flush=True)
        infos = batch_imageinfo(chunk)
        for info in infos:
            url = info.get("url")
            if not url:
                continue
            if info.get("mime") not in ("image/jpeg", "image/png"):
                continue
            w, h = info.get("width", 0), info.get("height", 0)
            if w < 250 or h < 250:
                continue
            if w * h > 50_000_000:
                continue
            fname = safe_name(info["title"])
            dest = OUT_DIR / fname
            status = download(url, dest)
            new_log.append({**info, "saved_to": str(dest), "status": status})
            tag = "ok" if status.startswith("ok") else status.split(":")[0][:6]
            print(f"  [{tag}] {fname} ({w}x{h})", flush=True)
            time.sleep(0.3)
        # 增量写日志
        all_log = log + new_log
        LOG_PATH.write_text(json.dumps(all_log, ensure_ascii=False, indent=2))
        time.sleep(0.5)

    ok = sum(1 for r in new_log if r["status"].startswith("ok") or r["status"] == "exists")
    fail = len(new_log) - ok
    print(f"\nThis run: {ok} ok, {fail} failed", flush=True)


if __name__ == "__main__":
    main()
