#!/usr/bin/env python3
"""Submit the App Store version for review and VERIFY the final state.

发布流水线的收尾步骤,消除"卡在可供审核却误报完成"的风险:
1. 若版本还在 PREPARE_FOR_SUBMISSION:自动挂最新构建 + 创建提交;
2. 轮询 appStoreState,直到进入 WAITING_FOR_REVIEW / IN_REVIEW;
3. 超时仍停在 READY_FOR_REVIEW 则以非零退出——流水线会明确失败,
   绝不会把"卡住"当成"完成"。

环境变量:AC_API_KEY_ID / AC_API_ISSUER_ID / AC_API_KEY_P8(base64)
可选:APP_ID(默认 6819887855)、POLL_SECONDS(默认 900)
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

APP_ID = os.environ.get("APP_ID", "6819887855")
POLL_SECONDS = int(os.environ.get("POLL_SECONDS", "900"))
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


def main():
    ver = get_version()
    vid = ver["id"]
    state = ver["attributes"]["appStoreState"]
    print(f"version {ver['attributes']['versionString']} initial state: {state}")

    if state == "PREPARE_FOR_SUBMISSION":
        # 1. 挂最新有效构建
        _, v = api("GET", f"/v1/appStoreVersions/{vid}/builds")
        if not v.get("data"):
            bid, bver = latest_valid_build_id()
            if not bid:
                raise SystemExit("没有可用构建,无法提交")
            code, resp = api("PATCH", f"/v1/appStoreVersions/{vid}/relationships/build",
                             {"data": {"type": "builds", "id": bid}})
            print(f"attach build {bver}: {code}")
            if code not in (200, 204):
                raise SystemExit(f"挂构建失败: {json.dumps(resp)[:300]}")
        # 2. 创建提交
        code, resp = api("POST", "/v1/appStoreVersionSubmissions",
                         {"data": {"type": "appStoreVersionSubmissions",
                                   "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": vid}}}}})
        print(f"create submission: {code}")
        if code not in (201, 409):  # 409 = 已在审核中,视为成功
            raise SystemExit(f"提交失败: {json.dumps(resp)[:300]}")

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
