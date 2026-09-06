#!/usr/bin/env python3
"""
Wikimedia v9 — 用 thumbnail URL 而不是原图。
thumb URL 通过 imageinfo 的 iiurlwidth 参数指定，比如 1024px 缩略图。
Wikimedia 缩略图服务器 (upload.wikimedia.org/thumb/...) 通常不被严格限流。
"""
import json
import subprocess
import time
import urllib.parse
from pathlib import Path

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/wikimedia")
LOG_PATH = OUT_DIR.parent / "wikimedia_v9_log.json"

UA = "CubeAR-Research/1.0 (zhangfeiyang; contact via local)"


def curl_json(url, retries=3):
    cmd = ["curl", "-s", "-A", UA, "--noproxy", "*", "--max-time", "30", url]
    for attempt in range(retries):
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=35)
            if r.returncode == 0:
                return json.loads(r.stdout)
            time.sleep(2)
        except Exception:
            time.sleep(2)
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
                wait = 20 * (2 ** attempt)
                return False, f"429", sz
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
        params = {"action": "query", "list": "categorymembers",
                  "cmtitle": category, "cmtype": "file", "cmlimit": 500, "format": "json"}
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


def imageinfo_thumb(title, width=1024):
    """拿缩略图 URL"""
    params = {"action": "query", "titles": title, "prop": "imageinfo",
              "iiprop": "url|mime|size", "iiurlwidth": str(width), "format": "json"}
    url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params)
    data = curl_json(url)
    if not data:
        return None
    for page in data.get("query", {}).get("pages", {}).values():
        infos = page.get("imageinfo", [])
        if not infos:
            continue
        info = infos[0]
        # thumburl 是 iiurlwidth 请求的缩略图
        return {
            "title": page["title"],
            "url": info.get("thumburl") or info.get("url"),
            "mime": info.get("mime"),
            "width": info.get("thumbwidth") or info.get("width", 0),
            "height": info.get("thumbheight") or info.get("height", 0),
            "size": info.get("size", 0),
        }
    return None


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print("== Wikimedia v9 (thumb URLs) ==", flush=True)

    log = []
    done = set()
    if LOG_PATH.exists():
        try:
            log = json.loads(LOG_PATH.read_text())
            done = {r["title"] for r in log if r.get("status") == "ok"}
            print(f"Resuming from {len(done)} done", flush=True)
        except Exception:
            pass

    categories = ["Category:Rubik's Cube", "Category:Magic cubes", "Category:Rubik's Cube patterns"]
    titles = set()
    for cat in categories:
        try:
            t = cat_members(cat)
            print(f"  {cat}: {len(t)}", flush=True)
            titles.update(t)
        except Exception as e:
            print(f"  {cat} fail: {e}", flush=True)
        time.sleep(1)

    print(f"Total: {len(titles)}", flush=True)
    bm_titles = sorted(t for t in titles if t not in done)
    print(f"To process: {len(bm_titles)}", flush=True)

    new_log = []
    fail_streak = 0
    for i, t in enumerate(bm_titles):
        if i % 25 == 0:
            print(f"[{i}/{len(bm_titles)}] streak={fail_streak}", flush=True)
        info = imageinfo_thumb(t, width=1024)
        if not info or not info.get("url"):
            new_log.append({"title": t, "status": "no-info"})
            fail_streak += 1
            time.sleep(0.3)
        else:
            w, h = info.get("width", 0), info.get("height", 0)
            if w < 300 or h < 300:
                new_log.append({**info, "status": "skip-size"})
                fail_streak = 0
                continue
            fname = safe_name(info["title"])
            dest = OUT_DIR / fname
            if dest.exists() and dest.stat().st_size > 5000:
                new_log.append({**info, "saved_to": str(dest), "status": "ok"})
                fail_streak = 0
                continue
            ok, ct, sz = curl_get(info["url"], dest)
            if ok:
                new_log.append({**info, "saved_to": str(dest), "status": "ok", "size": sz})
                print(f"  [ok {sz:>8}] {fname} ({w}x{h})", flush=True)
                fail_streak = 0
            else:
                new_log.append({**info, "status": ct})
                print(f"  [{ct[:14]:<14}] {fname}", flush=True)
                fail_streak += 1
                # 失败 streak 长就睡一下
                if fail_streak >= 5:
                    wait = min(120, 10 * fail_streak)
                    print(f"  fail streak {fail_streak}, sleep {wait}s", flush=True)
                    time.sleep(wait)
            time.sleep(0.4)
        if len(new_log) % 25 == 0:
            LOG_PATH.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))

    LOG_PATH.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
    ok_count = sum(1 for r in new_log if r["status"] == "ok")
    print(f"\nDone: {ok_count}/{len(new_log)}", flush=True)


if __name__ == "__main__":
    main()
