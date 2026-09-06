#!/usr/bin/env python3
"""
Wikimedia v10 — 极保守的下载策略。
- 每个文件下载后 sleep 2 秒
- 429 后退避 60s 重试，最多 5 次
- 单文件模式（避免批量 API 卡住）
"""
import json
import subprocess
import time
import urllib.parse
from pathlib import Path

OUT_DIR = Path("/home/zhangfy/mofang/data/cube_images/wikimedia")
LOG_PATH = OUT_DIR.parent / "wikimedia_v10_log.json"

UA = "CubeAR-Research/1.0 (zhangfeiyang; contact via local)"


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
    for attempt in range(5):
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
                wait = 30 * (attempt + 1)
                print(f"      [429 retry {attempt+1}/5, sleep {wait}s]", flush=True)
                time.sleep(wait)
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
        time.sleep(0.4)
    return titles


def imageinfo_thumb(title, width=1024):
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
    print("== Wikimedia v10 (conservative) ==", flush=True)

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
    for i, t in enumerate(bm_titles):
        if i % 10 == 0:
            print(f"[{i}/{len(bm_titles)}]", flush=True)
        info = imageinfo_thumb(t, width=1024)
        if not info or not info.get("url"):
            new_log.append({"title": t, "status": "no-info"})
            time.sleep(0.3)
            continue
        w, h = info.get("width", 0), info.get("height", 0)
        if w < 300 or h < 300:
            new_log.append({**info, "status": "skip-size"})
            time.sleep(0.3)
            continue
        fname = safe_name(info["title"])
        dest = OUT_DIR / fname
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
        # 每次都 sleep 2s，加上可能的重试退避
        time.sleep(2)
        if len(new_log) % 10 == 0:
            LOG_PATH.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))

    LOG_PATH.write_text(json.dumps(log + new_log, ensure_ascii=False, indent=2))
    ok_count = sum(1 for r in new_log if r["status"] == "ok")
    print(f"\nDone: {ok_count}/{len(new_log)}", flush=True)


if __name__ == "__main__":
    main()
