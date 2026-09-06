#!/usr/bin/env python3
"""
Wikimedia Commons v6 — 严格的下载验证。
下载时检查 content-type 和最小文件大小。
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/wikimedia")
LOG_PATH = OUT_DIR.parent / "wikimedia_v6_log.json"

UA = "CubeAR-Research/1.0 (zhangfeiyang; contact via local)"
CURL_META = ["curl", "-s", "-A", UA, "--max-time", "20", "-I"]  # head
CURL_GET = ["curl", "-s", "-A", UA, "--max-time", "60", "-L"]


def curl_json(url):
    try:
        r = subprocess.run(CURL_META[:2] + CURL_META[2:5] + ["-o", "/tmp/_curl_out", url],
                           capture_output=True, timeout=25)
        # Use proper command
        r = subprocess.run(["curl", "-s", "-A", UA, "--max-time", "20", url],
                           capture_output=True, text=True, timeout=25)
        if r.returncode != 0:
            return None
        return json.loads(r.stdout)
    except Exception:
        return None


def curl_get(url, dest):
    """下载并返回 (success, content_type, size)"""
    try:
        r = subprocess.run(
            ["curl", "-s", "-A", UA, "--max-time", "120", "-L",
             "-w", "%{http_code}\t%{content_type}\t%{size_download}",
             "-o", str(dest), url],
            capture_output=True, text=True, timeout=130,
        )
        if r.returncode != 0:
            return False, "error", 0
        # 解析最后一行 (因为有 stderr 错误输出时 \r 可能错位, 但 -w 总是最后一行到 stdout)
        last = r.stdout.strip().split("\n")[-1].strip()
        parts = last.split("\t")
        if len(parts) != 3:
            return False, "bad-format", 0
        http_code, ct, sz = parts
        sz = int(sz)
        if not http_code.startswith("200") and not http_code.startswith("2"):
            if dest.exists():
                dest.unlink()
            return False, f"http{http_code}", 0
        if "image" not in ct:
            if dest.exists():
                dest.unlink()
            return False, f"bad-ct:{ct}", sz
        if sz < 5000:  # < 5KB likely an error page
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
        time.sleep(0.4)
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


def safe_name(title):
    name = title.replace("File:", "").replace("/", "_")
    return urllib.parse.unquote(name)


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print("== Wikimedia Commons v6 (strict) ==", flush=True)

    log = []
    done = set()
    if LOG_PATH.exists():
        try:
            log = json.loads(LOG_PATH.read_text())
            done = {r["title"] for r in log if r.get("status") == "ok"}
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
        dest = OUT_DIR / fname
        # 已存在且大小合理：跳过
        if dest.exists() and dest.stat().st_size > 5000:
            new_log.append({**info, "saved_to": str(dest), "status": "ok"})
            print(f"  [exists] {fname} ({w}x{h})", flush=True)
            continue
        ok, ct, sz = curl_get(info["url"], dest)
        if ok:
            new_log.append({**info, "saved_to": str(dest), "status": "ok", "size": sz})
            print(f"  [ok {sz}] {fname} ({w}x{h})", flush=True)
        else:
            new_log.append({**info, "saved_to": str(dest), "status": ct})
            print(f"  [{ct[:10]}] {fname} ({w}x{h})", flush=True)
        time.sleep(0.6)
        if len(new_log) % 25 == 0:
            (LOG_PATH).write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))

    (LOG_PATH).write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
    ok_count = sum(1 for r in new_log if r["status"] == "ok")
    print(f"\nDone: {ok_count}/{len(new_log)}", flush=True)


if __name__ == "__main__":
    main()
