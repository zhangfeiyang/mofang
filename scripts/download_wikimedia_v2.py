#!/usr/bin/env python3
"""
Wikimedia Commons Category API 下载器
通过 categorymembers 拿到全部分类下的图片，比 search 更稳定。
"""
import json
import sys
import time
import urllib.parse
from pathlib import Path

import requests

OUT_BASE = Path("/home/zhangfy/mofang/data/cube_images/wikimedia")
LOG_PATH = OUT_BASE.parent / "wikimedia_log.json"

UA = "CubeARDatasetCollector/1.0 (research; contact: local)"
session = requests.Session()
session.headers.update({
    "User-Agent": UA,
    "Api-User-Agent": UA,
})


def cat_members(category: str, limit: int = 500, offset: str = None):
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


def all_in_category(category: str):
    out, offset = [], None
    while True:
        data = cat_members(category, offset=offset)
        out.extend(m["title"] for m in data.get("query", {}).get("categorymembers", []))
        if "continue" not in data:
            break
        offset = data["continue"]["cmcontinue"]
        time.sleep(0.3)
    return out


def image_info(titles, batch=20):
    """批量查询 imageinfo，比逐个更省请求"""
    url = "https://commons.wikimedia.org/w/api.php"
    results = []
    for i in range(0, len(titles), batch):
        chunk = titles[i:i + batch]
        params = {
            "action": "query",
            "titles": "|".join(chunk),
            "prop": "imageinfo",
            "iiprop": "url|mime|size",
            "format": "json",
        }
        for attempt in range(5):
            try:
                r = session.get(url, params=params, timeout=30)
                if r.status_code == 429:
                    wait = min(60, 5 * (2 ** attempt))
                    print(f"    429, sleeping {wait}s")
                    time.sleep(wait)
                    continue
                r.raise_for_status()
                data = r.json()
                for page in data.get("query", {}).get("pages", {}).values():
                    infos = page.get("imageinfo", [])
                    if not infos:
                        continue
                    info = infos[0]
                    results.append({
                        "title": page.get("title"),
                        "url": info.get("url"),
                        "mime": info.get("mime"),
                        "width": info.get("width"),
                        "height": info.get("height"),
                        "size": info.get("size"),
                    })
                break
            except Exception as e:
                if attempt == 4:
                    print(f"    api fail: {e}")
                time.sleep(5)
        time.sleep(0.5)
    return results


def download(url, dest, max_retries=3):
    if dest.exists() and dest.stat().st_size > 1000:
        return "exists"
    for attempt in range(max_retries):
        try:
            with session.get(url, stream=True, timeout=120) as r:
                if r.status_code == 429:
                    time.sleep(20)
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
            if attempt == max_retries - 1:
                return f"fail: {e}"
            time.sleep(5)
    return "fail: max retries"


def safe_name(title):
    name = title.replace("File:", "").replace("/", "_")
    return urllib.parse.unquote(name)


def main():
    OUT_BASE.mkdir(parents=True, exist_ok=True)
    print("== Wikimedia Commons Category API ==", flush=True)

    categories = [
        "Category:Rubik's cubes",
        "Category:Speedcubes",
        "Category:Rubik's Cube",
        "Category:Combination puzzles",
        "Category:Magic cubes",
        "Category:魔方",
    ]

    titles = set()
    for cat in categories:
        try:
            members = all_in_category(cat)
            print(f"  {cat}: {len(members)} files", flush=True)
            titles.update(members)
        except Exception as e:
            print(f"  {cat} fail: {e}", flush=True)
        time.sleep(1)

    print(f"Total unique titles: {len(titles)}", flush=True)

    # 过滤位图
    bm_titles = []
    for t in titles:
        low = t.lower()
        if low.endswith((".jpg", ".jpeg", ".png")):
            bm_titles.append(t)
    print(f"Bitmap titles: {len(bm_titles)}", flush=True)

    # 读取已有日志，避免重下
    log = []
    if LOG_PATH.exists():
        try:
            log = json.loads(LOG_PATH.read_text())
        except Exception:
            log = []
    done = {r["title"] for r in log if r.get("status", "").startswith("ok") or r.get("status") == "exists"}
    print(f"Already done: {len(done)}", flush=True)

    to_query = [t for t in bm_titles if t not in done]
    print(f"To query: {len(to_query)}", flush=True)

    # 分批查询 imageinfo
    BATCH = 50
    new_log = []
    for i in range(0, len(to_query), BATCH):
        chunk = to_query[i:i + BATCH]
        infos = image_info(chunk)
        for info in infos:
            if not info.get("url"):
                continue
            mime = info.get("mime", "")
            if mime not in ("image/jpeg", "image/png"):
                continue
            w = info.get("width") or 0
            h = info.get("height") or 0
            # 跳过太小或太大的
            if w < 200 or h < 200:
                continue
            if w * h > 50_000_000:  # > 50MP, 文件太大
                continue
            fname = safe_name(info["title"])
            dest = OUT_BASE / fname
            status = download(info["url"], dest)
            new_log.append({
                **info,
                "saved_to": str(dest),
                "status": status,
            })
            tag = status[:6] if status.startswith("ok") or status == "exists" else status.split(":")[0]
            print(f"  [{tag}] {fname} ({w}x{h})", flush=True)
            time.sleep(0.3)
        # 每批写一次日志
        (LOG_PATH).write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
        time.sleep(1)

    ok = sum(1 for r in new_log if r["status"].startswith("ok") or r["status"] == "exists")
    print(f"\nThis run: {ok}/{len(new_log)} downloaded", flush=True)
    print(f"Total in folder: {len(list(OUT_BASE.glob('*.jpg'))) + len(list(OUT_BASE.glob('*.jpeg'))) + len(list(OUT_BASE.glob('*.png')))}", flush=True)


if __name__ == "__main__":
    main()
