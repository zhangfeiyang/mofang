#!/usr/bin/env python3
"""Submit the App Store version for review and VERIFY the final state.

发布流水线的收尾步骤:
1. 等待最新构建处理完成(VALID)并挂到版本上;
2. 用 Review Submissions API 提交审核(旧 appStoreVersionSubmissions 端点已废弃,
   POST 会 403 "does not allow CREATE");
3. 轮询 appStoreState 直到 WAITING_FOR_REVIEW 等最终状态;
4. 超时未提交则以非零退出,绝不把"卡住"当成"完成"。

环境变量:AC_API_KEY_ID / AC_API_ISSUER_ID / AC_API_KEY_P8(base64)
可选:APP_ID、POLL_SECONDS(构建等待+状态轮询共用,默认 2400)
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

APP_ID = os.environ.get("APP_ID", "6820316386")
POLL_SECONDS = int(os.environ.get("POLL_SECONDS", "2400"))
FINAL_STATES = {"WAITING_FOR_REVIEW", "IN_REVIEW", "ACCEPTED", "PENDING_DEVELOPER_RELEASE"}


def b64url(d: bytes) -> str:
    return base64.urlsafe_b64encode(d).rstrip(b"=").decode()


def make_token() -> str:
    key = serialization.load_pem_private_key(
        base64.b64decode(os.environ["AC_API_KEY_P8"]), password=None)
    now = int(time.time())
    header = {"alg": "ES256", "kid": os.environ["AC_API_KEY_ID"], "typ": "JWT"}
    payload = {"iss": os.environ["AC_API_ISSUER_ID"], "iat": now,
               "exp": now + 1200, "aud": "appstoreconnect-v1"}
    si = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(payload).encode())}"
    der = key.sign(si.encode(), ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    return f"{si}.{b64url(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


TOKEN = make_token()


def api(method: str, path: str, body=None):
    req = urllib.request.Request(f"https://api.appstoreconnect.apple.com{path}", method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw.strip() else {}


def get_version():
    code, resp = api("GET", f"/v1/apps/{APP_ID}/appStoreVersions")
    if code != 200 or not resp.get("data"):
        raise SystemExit(f"获取版本失败: {code} {json.dumps(resp)[:300]}")
    return resp["data"][0]  # 最近的版本


def latest_valid_build_id():
    code, resp = api("GET", f"/v1/builds?filter[app]={APP_ID}&sort=-uploadedDate&limit=10")
    for b in resp.get("data", []):
        if b["attributes"]["processingState"] == "VALID":
            return b["id"], b["attributes"]["version"]
    return None, None


def wait_valid_build(timeout_s: int):
    """轮询等待最新构建处理完成(上传后 Apple 需几分钟校验,期间 state=PROCESSING)。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        bid, bver = latest_valid_build_id()
        if bid:
            print(f"build {bver} is VALID")
            return bid, bver
        print(f"  build processing... {int(deadline - time.time())}s left")
        time.sleep(30)
    raise SystemExit("等待构建处理超时——请人工在 ASC 检查构建状态")


def whats_new(vid):
    """whatsNew 仅在构建挂上后可编辑;失败不阻断(首次发布可不填)。"""
    _, vlocs = api("GET", f"/v1/appStoreVersions/{vid}/appStoreVersionLocalizations")
    for l in vlocs.get("data", []):
        if l["attributes"]["locale"] == "zh-Hans":
            code, resp = api("PATCH", f"/v1/appStoreVersionLocalizations/{l['id']}",
                             {"data": {"type": "appStoreVersionLocalizations", "id": l["id"],
                                       "attributes": {"whatsNew": "首次发布。"}}})
            print(f"whatsNew PATCH: {code}" + ("" if code == 200 else " " + json.dumps(resp)[:150]))


def ensure_review_submission(vid: str):
    """复用打开的(DRAFT/READY_FOR_REVIEW)提交,否则创建。

    注意:挂载构建时 ASC 可能已自动创建包含本版本的 DRAFT 提交,
    所以不能只看 READY_FOR_REVIEW,也要检查 item 是否已包含本版本。
    """
    code, resp = api("GET", f"/v1/apps/{APP_ID}/reviewSubmissions")
    open_states = {"DRAFT", "READY_FOR_REVIEW", "UNRESOLVED_ISSUES"}
    if code == 200:
        for sub in resp.get("data", []):
            if sub["attributes"].get("state") not in open_states:
                continue
            sub_id = sub["id"]
            code2, items = api("GET", f"/v1/reviewSubmissions/{sub_id}/items")
            has_version = False
            if code2 == 200:
                for it in items.get("data", []):
                    rel = (it.get("relationships", {}).get("appStoreVersion", {}) or {}).get("data", {}) or {}
                    if rel.get("id") == vid:
                        has_version = True
            print(f"open submission {sub_id} (state {sub['attributes'].get('state')}, contains version: {has_version})")
            if has_version:
                return sub_id
            return sub_id  # 打开提交即使暂无 item 也直接用它
    body = {"data": {"type": "reviewSubmissions",
                     "attributes": {"platform": "IOS"},
                     "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}}
    code, resp = api("POST", "/v1/reviewSubmissions", body)
    print(f"create review submission: {code} {json.dumps(resp)[:250]}")
    if code != 201:
        raise SystemExit(f"review submission create failed: {json.dumps(resp)[:400]}")
    return resp["data"]["id"]


def add_item(sub_id: str, vid: str):
    # 先查现有 items(版本可能已被 ASC 自动加入)
    code, resp = api("GET", f"/v1/reviewSubmissions/{sub_id}/items")
    if code == 200:
        for it in resp.get("data", []):
            rel = (it.get("relationships", {}).get("appStoreVersion", {}) or {}).get("data", {}) or {}
            if rel.get("id") == vid:
                print(f"item already present: {it['id']}")
                return it["id"]
    body = {"data": {"type": "reviewSubmissionItems",
                     "relationships": {"reviewSubmission": {"data": {"type": "reviewSubmissions", "id": sub_id}},
                                        "appStoreVersion": {"data": {"type": "appStoreVersions", "id": vid}}}}}
    code, resp = api("POST", "/v1/reviewSubmissionItems", body)
    print(f"add review item: {code} {json.dumps(resp)[:250]}")
    if code == 201:
        return resp["data"]["id"]
    raise SystemExit(f"add item failed: {json.dumps(resp)[:400]}")


def submit_for_review(sub_id: str):
    body = {"data": {"type": "reviewSubmissions", "id": sub_id,
                     "attributes": {"submitted": True}}}
    code, resp = api("PATCH", f"/v1/reviewSubmissions/{sub_id}", body)
    state = (resp.get("data", {}).get("attributes", {}) or {}).get("state")
    print(f"submit review submission: {code} state={state}")
    if code not in (200, 204):
        print(f"submit body: {json.dumps(resp)[:400]}")
    return code in (200, 204)


def main():
    ver = get_version()
    vid = ver["id"]
    state = ver["attributes"]["appStoreState"]
    print(f"version {ver['attributes']['versionString']} initial state: {state}")

    if state == "PREPARE_FOR_SUBMISSION":
        # 1. 等待并挂最新有效构建
        _, v = api("GET", f"/v1/appStoreVersions/{vid}/builds")
        if not v.get("data"):
            bid, bver = wait_valid_build(POLL_SECONDS)
            code, resp = api("PATCH", f"/v1/appStoreVersions/{vid}/relationships/build",
                             {"data": {"type": "builds", "id": bid}})
            print(f"attach build {bver}: {code}")
            if code not in (200, 204):
                raise SystemExit(f"挂构建失败: {json.dumps(resp)[:300]}")
            whats_new(vid)
        # 2. Review Submissions 流程(旧 appStoreVersionSubmissions 端点已废弃)
        sub_id = ensure_review_submission(vid)
        add_item(sub_id, vid)
        if not submit_for_review(sub_id):
            raise SystemExit("提审失败——见上方响应")
    elif state in FINAL_STATES:
        print(f"FINAL STATE REACHED: {state}")
        return

    # 3. 轮询到最终状态为止
    deadline = time.time() + POLL_SECONDS
    while True:
        code, resp = api("GET", f"/v1/appStoreVersions/{vid}")
        state = resp["data"]["attributes"]["appStoreState"]
        print(f"  poll: {state}")
        if state in FINAL_STATES:
            print(f"FINAL STATE REACHED: {state}")
            return
        if time.time() > deadline:
            raise SystemExit(f"TIMEOUT: {POLL_SECONDS}s 后仍停留在 {state} —— 流水线判定失败,需要人工检查")
        time.sleep(60)


if __name__ == "__main__":
    main()
