#!/usr/bin/env python3
"""Revoke orphan Apple Distribution certificates via the App Store Connect API.

CI runner 是临时的，每次运行产生的证书私钥不会留存；在 fastlane cert 之前调用
本脚本吊销"孤立"的分发证书，避免撞上 Apple 的证书数量上限。

被活跃描述文件引用的证书受到保护(如 Mac mini 长期发布用的 62RDFCGTWC),
不会被吊销——吊销它们会弄坏本地发布管线。
环境变量：AC_API_KEY_ID / AC_API_ISSUER_ID / AC_API_KEY_P8 (base64)。
可选:PROTECTED_CERT_IDS(逗号分隔,额外保护)。
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils as asym_utils

KEY_ID = os.environ["AC_API_KEY_ID"]
ISSUER = os.environ["AC_API_ISSUER_ID"]
KEY_PEM = base64.b64decode(os.environ["AC_API_KEY_P8"])
PROTECTED = set(filter(None, os.environ.get("PROTECTED_CERT_IDS", "").split(",")))


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_token() -> str:
    key = serialization.load_pem_private_key(KEY_PEM, password=None)
    header = {"alg": "ES256", "kid": KEY_ID, "typ": "JWT"}
    now = int(time.time())
    payload = {"iss": ISSUER, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"}
    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(payload).encode())}"
    der = key.sign(signing_input.encode(), ec.ECDSA(hashes.SHA256()))
    r, s = asym_utils.decode_dss_signature(der)
    return f"{signing_input}.{b64url(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


def api(method: str, path: str):
    req = urllib.request.Request(f"https://api.appstoreconnect.apple.com{path}", method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read()
            return resp.status, json.loads(body) if body.strip() else {}
    except urllib.error.HTTPError as e:
        body = e.read()
        return e.code, json.loads(body) if body.strip() else {}


TOKEN = make_token()

# 保护规则:
#   1. 显式清单 PROTECTED_CERT_IDS;
#   2. 被"其他应用"的 App Store profile 引用的证书(吊销会弄坏别的 App 的本地发布);
# 本应用旧 profile 引用的证书不保护——CI 每轮签发新证书,必须回收旧槽位。
THIS_BUNDLE = os.environ.get("APP_IDENTIFIER", "com.mofang.cubear")
used_cert_ids = set(PROTECTED)
code, resp = api("GET", "/v1/profiles?limit=200&filter[profileType]=IOS_APP_STORE&include=certificates,bundleId")
if code == 200:
    bundle_names = {i["id"]: i["attributes"].get("identifier", "")
                    for i in resp.get("included", []) if i.get("type") == "bundleIds"}
    for p in resp.get("data", []):
        rel = p.get("relationships", {})
        bid = (rel.get("bundleId", {}) or {}).get("data", {}).get("id")
        bundle = bundle_names.get(bid, "")
        cert_data = (rel.get("certificates", {}) or {}).get("data", []) or []
        if not cert_data:
            continue  # 悬空 profile,保守起见不动它引用的(也无法定位)
        if bundle and bundle != THIS_BUNDLE:
            for c in cert_data:
                used_cert_ids.add(c["id"])
print(f"受保护证书(其他应用引用/显式清单): {sorted(used_cert_ids)}")

code, resp = api("GET", "/v1/certificates?limit=200")
if code != 200:
    print(json.dumps(resp)[:400]); sys.exit(1)

certs = [c for c in resp.get("data", []) if "DISTRIBUTION" in (c["attributes"].get("certificateType") or "")]
print(f"发现 {len(certs)} 张分发证书")
for c in certs:
    if c["id"] in used_cert_ids:
        print(f"  跳过(受保护): {c['attributes']['displayName']} ({c['id']})")
        continue
    a = c["attributes"]
    code, _ = api("DELETE", f"/v1/certificates/{c['id']}")
    print(f"  {'吊销成功' if code in (204, 200) else '吊销失败 ' + str(code)}: {a['displayName']} ({a['certificateType']}, 到期 {a['expirationDate']})")
print("DONE")
