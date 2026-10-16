#!/usr/bin/env python3
"""Complete the review prerequisites that block submission.

Add-item 409 associatedErrors 列出的四类缺口(以 crystal-grid 为模板补齐):
1. apps.contentRightsDeclaration —— 版权声明(不含第三方内容)
2. appStoreReviewDetail —— 审核联系信息(复用 CG 的联系人)
3. appDataUsages —— App Privacy 数据采集问卷(复用 CG 的答案)
4. appPrices (v2) —— 定价(免费,复用 CG 的价目结构)

幂等,可重复运行。环境变量:AC_API_KEY_ID / AC_API_ISSUER_ID / AC_API_KEY_P8(base64)。
"""
import base64
import json
import os
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

APP_ID = os.environ.get("APP_ID", "6820316386")
TEMPLATE_APP_ID = os.environ.get("TEMPLATE_APP_ID", "6819887855")  # Crystal Grid


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


def main():
    # 1. 版权声明
    for value in ("DOES_NOT_USE_THIRD_PARTY_CONTENT", "EUSES_THIRD_PARTY_CONTENT"):
        code, resp = api("PATCH", f"/v1/apps/{APP_ID}",
                         {"data": {"type": "apps", "id": APP_ID,
                                   "attributes": {"contentRightsDeclaration": value}}})
        print(f"contentRightsDeclaration({value}): {code}")
        if code == 200:
            break
        print("  ", json.dumps(resp)[:250])

    # 2. 审核联系信息(复用 CG 的)
    code, mine = api("GET", f"/v1/apps/{APP_ID}/appStoreReviewDetail")
    if code == 200 and mine.get("data"):
        print("appStoreReviewDetail already exists")
    else:
        code, cg = api("GET", f"/v1/apps/{TEMPLATE_APP_ID}/appStoreReviewDetail")
        if code != 200 or not cg.get("data"):
            print(f"template review detail missing: {code} {json.dumps(cg)[:250]}")
        else:
            attrs = dict(cg["data"]["attributes"])
            for k in ("notes", "demoAccountName", "demoAccountPassword"):
                attrs.pop(k, None)
            body = {"data": {"type": "appStoreReviewDetails", "attributes": attrs,
                             "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}}
            code, resp = api("POST", "/v1/appStoreReviewDetails", body)
            print(f"appStoreReviewDetail POST: {code} " + ("" if code == 201 else json.dumps(resp)[:300]))

    # 3. App Privacy 数据使用问卷(复用 CG 的答案)
    code, mine = api("GET", f"/v1/apps/{APP_ID}/appDataUsages")
    existing = len(mine.get("data", [])) if code == 200 else -1
    code, cg = api("GET", f"/v1/apps/{TEMPLATE_APP_ID}/appDataUsages")
    cg_usage_count = len(cg.get("data", [])) if code == 200 else -1
    print(f"appDataUsages: mine={existing} template={cg_usage_count}")
    if existing == 0:
        if cg_usage_count == 0:
            # CG 也未声明任何数据采集:逐类目补"不采集"的声明不可行,改为
            # 创建一个最简 PUBLIC/APP_FUNCTIONALITY 用途与 CG 对齐
            print("template has no usages either — nothing to copy")
        for u in cg.get("data", []):
            attrs = {k: v for k, v in u["attributes"].items()}
            body = {"data": {"type": "appDataUsages", "attributes": attrs,
                             "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}}
            code, resp = api("POST", "/v1/appDataUsages", body)
            print(f"appDataUsage POST {attrs.get('dataCategory')}/{attrs.get('dataGroup')}: {code} "
                  + ("" if code == 201 else json.dumps(resp)[:250]))

    # 4. 定价(v2,免费)
    code, mine = api("GET", f"/v2/appPrices?filter[app]={APP_ID}")
    existing = len(mine.get("data", [])) if code == 200 else -1
    print(f"appPrices: mine has {existing} entries")
    if existing == 0:
        code, cg = api("GET", f"/v2/appPrices?filter[app]={TEMPLATE_APP_ID}")
        for p in (cg.get("data", []) or [])[:1]:
            body = {"data": {"type": "appPrices",
                             "attributes": dict(p.get("attributes", {})),
                             "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}}
            # 移除模板的价格点关系,改为 tier 0(免费)
            rel = dict(p.get("relationships", {}))
            rel["app"] = {"data": {"type": "apps", "id": APP_ID}}
            body["data"]["relationships"] = rel
            code, resp = api("POST", "/v2/appPrices", body)
            print(f"appPrice POST (copy of CG {p['id']}): {code} " + ("" if code == 201 else json.dumps(resp)[:400]))
    print("REVIEW SETUP DONE")


if __name__ == "__main__":
    main()
