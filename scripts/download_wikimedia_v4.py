#!/usr/bin/env python3
"""
单文件模式：逐个查询 imageinfo，每个查询强制 15s 超时。
这样即使某个 title 出问题也不会卡住整个批次。
"""
import json
import sys
import time
import urllib.parse
from pathlib import Path

import requests

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/wikimedia")
LOG_PATH = OUT_DIR.parent / "wikimedia_v4_log.json"

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
    r = session.get(url, params=params, timeout=20)
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
                print(f"    {cat} page {page}, total {len(titles)}", flush=True)
                time.sleep(0.4)
            except Exception as e:
                print(f"    {cat} page fail: {e}", flush=True)
                break
        print(f"  {cat}: {len(titles)}", flush=True)
        time.sleep(0.5)
    return titles


def single_imageinfo(title):
    url = "https://commons.wikimedia.org/w/api.php"
    params = {
        "action": "query",
        "titles": title,
        "prop": "imageinfo",
        "iiprop": "url|mime|size",
        "format": "json",
    }
    for attempt in range(3):
        try:
            r = session.get(url, params=params, timeout=15)
            if r.status_code == 429:
                wait = 5 * (2 ** attempt)
                time.sleep(wait)
                continue
            r.raise_for_status()
            data = r.json()
            for page in data.get("query", {}).get("pages", {}).values():
                infos = page.get("imageinfo", [])
                if not infos:
                    return None
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
        except Exception as e:
            if attempt == 2:
                return None
            time.sleep(2)
    return None


def download(url, dest):
    if dest.exists() and dest.stat().st_size > 1000:
        return "exists"
    for attempt in range(3):
        try:
            with session.get(url, stream=True, timeout=120) as r:
                if r.status_code == 429:
                    time.sleep(15)
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
    print("== Wikimedia Commons v4 ==", flush=True)

    log = []
    done = set()
    if LOG_PATH.exists():
        try:
            log = json.loads(LOG_PATH.read_text())
            done = {r["title"] for r in log if r.get("status", "").startswith("ok") or r.get("status") == "exists"}
            print(f"Resuming from {len(done)} done", flush=True)
        except Exception:
            pass

    categories = ["Category:Rubik's Cube", "Category:Magic cubes"]
    titles = get_all_titles(categories)
    print(f"Total titles: {len(titles)}", flush=True)

    bm_titles = sorted(t for t in titles if t not in done)
    print(f"To process: {len(bm_titles)}", flush=True)

    new_log = []
    for i, t in enumerate(bm_titles):
        if i % 10 == 0:
            print(f"[{i}/{len(bm_titles)}] {t}", flush=True)
        info = single_imageinfo(t)
        if not info or not info.get("url"):
            new_log.append({"title": t, "status": "no-info"})
            continue
        if info.get("mime") not in ("image/jpeg", "image/png"):
            new_log.append({**info, "status": "skip-mime"})
            continue
        w, h = info.get("width", 0), info.get("height", 0)
        if w < 250 or h < 250:
            new_log.append({**info, "status": "skip-size"})
            continue
        if w * h > 50_000_000:
            new_log.append({**info, "status": "skip-huge"})
            continue
        fname = safe_name(info["title"])
        dest = OUT_DIR / fname
        status = download(info["url"], dest)
        new_log.append({**info, "saved_to": str(dest), "status": status})
        tag = "ok" if status.startswith("ok") else status.split(":")[0][:6]
        print(f"  [{tag}] {fname} ({w}x{h})", flush=True)
        time.sleep(0.5)
        if len(new_log) % 25 == 0:
            (LOG_PATH).write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))

    (LOG_PATH).write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
    ok = sum(1 for r in new_log if r["status"].startswith("ok") or r["status"] == "exists")
    print(f"\nDone: {ok}/{len(new_log)}", flush=True)


if __name__ == "__main__":
    main()
