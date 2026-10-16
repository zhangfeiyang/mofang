#!/usr/bin/env python3
"""Ensure a distribution signing profile exists for the app bundle.

1. 注册 bundle id(如未注册);
2. 复用现有 Apple Distribution 证书(62RDFCGTWC);
3. 创建/复用 App Store 描述文件并下载到 --out(供 mac_build_remote.sh 手动签名)。

用法: python3 asc_create_profile.py --bundle com.mofang.cubear --out ~/buildkeys/cubear_appstore.mobileprovision
环境变量:AC_API_KEY_ID / AC_API_ISSUER_ID / AC_API_KEY_P8(base64) 或 ASC_KEY_PATH(读文件转 base64)
"""
import argparse
import base64
import json
import os
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

KEY_PATH = os.environ.get("ASC_KEY_PATH", "/home/zhangfy/AuthKey_X8P2F2J3V8.p8")
KEY_ID = os.environ["AC_API_KEY_ID"]
ISSUER = os.environ["AC_API_ISSUER_ID"]
DISTRIBUTION_CERT_ID = os.environ.get("DISTRIBUTION_CERT_ID", "62RDFCGTWC")


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_token() -> str:
    raw = open(os.path.expanduser(KEY_PATH), "rb").read()
    try:
        pem = base64.b64decode(raw)
        key = serialization.load_pem_private_key(pem, password=None)
    except Exception:
        key = serialization.load_pem_private_key(raw, password=None)
    header = {"alg": "ES256", "kid": KEY_ID, "typ": "JWT"}
    now = int(time.time())
    payload = {"iss": ISSUER, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"}
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--name", default="CubeAR AppStore")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    # 1. bundle id
    code, resp = api("GET", f"/v1/bundleIds?filter[identifier]={args.bundle}")
    if code == 200 and resp.get("data"):
        bundle_id = resp["data"][0]["id"]
        print(f"bundle id exists: {args.bundle} ({bundle_id})")
    else:
        code, resp = api("POST", "/v1/bundleIds",
                         {"data": {"type": "bundleIds",
                                   "attributes": {"identifier": args.bundle, "platform": "IOS"}}})
        if code != 201:
            raise SystemExit(f"bundle id create failed: {code} {json.dumps(resp)[:300]}")
        bundle_id = resp["data"]["id"]
        print(f"bundle id created: {args.bundle} ({bundle_id})")

    # 2. 证书
    code, resp = api("GET", "/v1/certificates?limit=50")
    cert = next((c for c in resp.get("data", []) if c["id"] == DISTRIBUTION_CERT_ID), None)
    if cert is None:
        cert = next((c for c in resp.get("data", [])
                     if c["attributes"]["certificateType"] in ("DISTRIBUTION", "IOS_DISTRIBUTION")), None)
    if cert is None:
        raise SystemExit("没有可用的分发证书")
    print(f"cert: {cert['id']} {cert['attributes']['displayName']} (expires {cert['attributes']['expirationDate']})")

    # 3. profile(复用或新建)
    code, resp = api("GET", "/v1/profiles?limit=50")
    profile = next((p for p in resp.get("data", [])
                    if p["attributes"]["profileType"] == "IOS_APP_STORE"
                    and p["attributes"]["name"] == args.name), None)
    if profile is None:
        code, resp = api("POST", "/v1/profiles",
                         {"data": {"type": "profiles",
                                   "attributes": {"name": args.name, "profileType": "IOS_APP_STORE"},
                                   "relationships": {
                                       "bundleId": {"data": {"type": "bundleIds", "id": bundle_id}},
                                       "certificates": {"data": [{"type": "certificates", "id": cert["id"]}]},
                                       "devices": {"data": []}}}})
        if code != 201:
            raise SystemExit(f"profile create failed: {code} {json.dumps(resp)[:400]}")
        profile = resp["data"]
    print(f"profile: {profile['attributes']['name']} ({profile['id']}) expires {profile['attributes']['expirationDate']}")

    # 4. 下载内容
    req = urllib.request.Request(f"https://api.appstoreconnect.apple.com/v1/profiles/{profile['id']}/profileContent")
    req.add_header("Authorization", f"Bearer {TOKEN}")
    with urllib.request.urlopen(req) as r:
        content = r.read()
    out = os.path.expanduser(args.out)
    with open(out, "wb") as f:
        f.write(content)
    print(f"saved: {out} ({len(content)} bytes)")


if __name__ == "__main__":
    main()
