#!/usr/bin/env python3
"""
使用 curl 子进程调用 Wikimedia API，避免 requests 会话池卡住。
每个请求都是全新的连接。
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/wikimedia")
LOG_PATH = OUT_DIR.parent / "wikimedia_v5_log.json"

CURL = ["curl", "-s", "-A", "CubeAR-Research/1.0", "--max-time", "30"]


def curl_json(url):
    """用 curl 拿 JSON"""
    try:
        r = subprocess.run(CURL + [url], capture_output=True, text=True, timeout=35)
        if r.returncode != 0:
            return None
        return json.loads(r.stdout)
    except Exception as e:
        return None


def cat_members(category):
    titles = []
    offset = None
    while True:
        params = {
            "action": "query",
            "list": "categorymembers",
            "cmtitle": category,
            "cmtype": "file",
            "cmlimit": 500,
            "format": "json",
        }
        if offset:
            params["cmcontinue"] = offset
        q = urllib.parse.urlencode(params)
        url = f"https://commons.wikimedia.org/w/api.php?{q}"
        data = curl_json(url)
        if not data:
            break
        for m in data.get("query", {}).get("categorymembers", []):
            t = m["title"]
            low = t.lower()
            if low.endswith((".jpg", ".jpeg", ".png")):
                titles.append(t)
        cont = data.get("continue", {})
        if not cont.get("cmcontinue"):
            break
        offset = cont["cmcontinue"]
        time.sleep(0.5)
    return titles


def imageinfo(title):
    params = {
        "action": "query",
        "titles": title,
        "prop": "imageinfo",
        "iiprop": "url|mime|size",
        "format": "json",
    }
    q = urllib.parse.urlencode(params)
    url = f"https://commons.wikimedia.org/w/api.php?{q}"
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


def curl_download(url, dest):
    if dest.exists() and dest.stat().st_size > 1000:
        return "exists"
    cmd = ["curl", "-s", "-A", "CubeAR-Research/1.0",
           "--max-time", "120", "-o", str(dest), url]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=125)
        if r.returncode != 0:
            if dest.exists():
                dest.unlink()
            return f"fail: curl exit {r.returncode}"
        if dest.exists() and dest.stat().st_size > 1000:
            return f"ok ({dest.stat().st_size})"
        if dest.exists():
            dest.unlink()
        return "fail: empty"
    except subprocess.TimeoutExpired:
        if dest.exists():
            dest.unlink()
        return "fail: timeout"
    except Exception as e:
        if dest.exists():
            dest.unlink()
        return f"fail: {e}"


def safe_name(title):
    name = title.replace("File:", "").replace("/", "_")
    return urllib.parse.unquote(name)


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print("== Wikimedia Commons v5 (curl) ==", flush=True)

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
        if i % 20 == 0:
            print(f"[{i}/{len(bm_titles)}]", flush=True)
        info = imageinfo(t)
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
        status = curl_download(info["url"], dest)
        new_log.append({**info, "saved_to": str(dest), "status": status})
        tag = "ok" if status.startswith("ok") else status.split(":")[0][:6]
        print(f"  [{tag}] {fname} ({w}x{h})", flush=True)
        time.sleep(0.4)
        if len(new_log) % 25 == 0:
            (LOG_PATH).write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))

    (LOG_PATH).write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
    ok = sum(1 for r in new_log if r["status"].startswith("ok") or r["status"] == "exists")
    print(f"\nDone: {ok}/{len(new_log)}", flush=True)


if __name__ == "__main__":
    main()
