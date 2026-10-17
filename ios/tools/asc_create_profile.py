#!/usr/bin/env python3
"""确保有一份绑定 Mac mini 持久分发证书的 App Store 描述文件,并下载下来。

1. 注册 bundle id(如未注册);
2. 用 Mac mini 钥匙串里那张持久分发证书(62RDFCGTWC,~/buildkeys/distribution.*);
   CI 每次新建的证书私钥随 runner 销毁,绑它的描述文件在 Mac 上签不了名;
3. 复用同名且 ACTIVE 的描述文件,否则新建;内容取自 profileContent 属性。

用法: python3 asc_create_profile.py --out cubear_appstore.mobileprovision
然后: scp cubear_appstore.mobileprovision macmini:buildkeys/   (mac_build_remote.sh 从那里安装)
环境变量: AC_API_KEY_ID / AC_API_ISSUER_ID / AC_API_KEY_P8(base64),见 asc_api.py
"""
import argparse
import base64
import os
import urllib.parse

from asc_api import api

DISTRIBUTION_CERT_ID = os.environ.get("DISTRIBUTION_CERT_ID", "62RDFCGTWC")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", default="com.mofang.cubear")
    ap.add_argument("--name", default="CubeAR AppStore (mac mini)")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    # 1. bundle id
    found = api("GET", f"/v1/bundleIds?filter[identifier]={args.bundle}").get("data", [])
    bundle = next((b for b in found if b["attributes"]["identifier"] == args.bundle), None)
    if bundle is None:
        bundle = api("POST", "/v1/bundleIds", {"data": {"type": "bundleIds", "attributes": {
            "identifier": args.bundle, "name": args.bundle.replace(".", " "), "platform": "IOS"}}})["data"]
        print(f"bundle id created: {args.bundle} ({bundle['id']})")
    else:
        print(f"bundle id exists: {args.bundle} ({bundle['id']})")

    # 2. 证书
    cert = api("GET", f"/v1/certificates/{DISTRIBUTION_CERT_ID}")["data"]
    print(f"cert: {cert['id']} {cert['attributes']['displayName']} "
          f"(expires {cert['attributes']['expirationDate'][:10]})")

    # 3. 描述文件(复用或新建)
    name = urllib.parse.quote(args.name)
    profiles = api("GET", f"/v1/profiles?filter[name]={name}&limit=50").get("data", [])
    profile = next((p for p in profiles if p["attributes"]["name"] == args.name
                    and p["attributes"]["profileType"] == "IOS_APP_STORE"
                    and p["attributes"]["profileState"] == "ACTIVE"), None)
    if profile is None:
        profile = api("POST", "/v1/profiles", {"data": {
            "type": "profiles",
            "attributes": {"name": args.name, "profileType": "IOS_APP_STORE"},
            "relationships": {
                "bundleId": {"data": {"type": "bundleIds", "id": bundle["id"]}},
                "certificates": {"data": [{"type": "certificates", "id": cert["id"]}]}}}})["data"]
        print(f"profile created: {args.name} ({profile['id']})")
    else:
        print(f"profile exists: {args.name} ({profile['id']})")
    print(f"expires {profile['attributes']['expirationDate'][:10]}")

    # 4. 内容
    content = base64.b64decode(profile["attributes"]["profileContent"])
    out = os.path.expanduser(args.out)
    with open(out, "wb") as f:
        f.write(content)
    print(f"saved: {out} ({len(content)} bytes)")


if __name__ == "__main__":
    main()
