#!/usr/bin/env python3
"""Fill the Cube AR App Store product page: zh-Hans metadata + screenshots via ASC API.

前置:App 记录已建(6820316386),版本 1.0 处于 PREPARE_FOR_SUBMISSION。
数据:
- 描述/关键词:下方常量(中文为主,单语言 App)
- 截图:SHOTS_DIR(默认 /tmp/shots)下的 01/02/03.png(1320×2868,6.9 英寸)
- 隐私政策:https://zhangfeiyang.github.io/cube-ar-site/

环境变量:AC_API_KEY_ID / AC_API_ISSUER_ID / AC_API_KEY_P8(base64)
"""
import base64
import hashlib
import json
import os
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

APP_ID = "6820316386"
KEY_PATH = "/home/zhangfy/AuthKey_X8P2F2J3V8.p8"
KEY_ID = os.environ["AC_API_KEY_ID"]
ISSUER = os.environ["AC_API_ISSUER_ID"]
SITE = "https://zhangfeiyang.github.io/cube-ar-site/"
SHOTS_DIR = os.environ.get("SHOTS_DIR", "/tmp/shots")
SHOTS = [("01_scanning.png", "01_scanning.png"),
         ("02_ready.png", "02_ready.png"),
         ("03_guiding.png", "03_guiding.png")]

NAME = "Cube AR"
SUBTITLE = "拍照识别魔方,逐步指导还原"
KEYWORDS = "魔方,还原,教程,速拧,三阶,魔方公式,解法,益智,离线,cube"
PROMO = "拍照扫描魔方,自动给出还原步骤,3D 演示+中文语音指导。"
DESCRIPTION = """Cube AR 是一款魔方扫描与还原指导应用:用相机"看"你的魔方,自动算出还原步骤,再用 3D 视图和中文语音一步步带你完成还原。

【扫描识别】
• 把魔方的面对准相机,自动识别每个贴纸的颜色
• 依次扫五个面即可,第六面自动推断
• 识别结果实时显示在展开图上,扫错一目了然

【还原指导】
• 内置专业两阶段求解算法,任意可还原状态通常 20 步左右搞定
• 3D 魔方实时演示当前这一步转哪个面
• 中文语音播报步骤,不用一直盯着屏幕
• 每转完一步自动识别确认,再进入下一步

【特性】
• 完全离线运行:识别与求解全部在本机完成
• 相机画面绝不离开设备,隐私无忧
• 轻量快速,无广告、无内购

拿起你的三阶魔方,打开 Cube AR 跟着提示转,还原魔方没那么难!"""
WHATS_NEW = "首次发布。"
# 年龄分级:全否(4+),与 Crystal Grid 相同
AGE_RATING_SAFE = {
    "advertising": False,
    "alcoholTobaccoOrDrugUseOrReferences": "NONE",
    "contests": "NONE",
    "gambling": False,
    "gamblingSimulated": "NONE",
    "gunsOrOtherWeapons": "NONE",
    "healthOrWellnessTopics": False,
    "lootBox": False,
    "medicalOrTreatmentInformation": "NONE",
    "messagingAndChat": False,
    "parentalControls": False,
    "profanityOrCrudeHumor": "NONE",
    "ageAssurance": False,
    "sexualContentGraphicAndNudity": "NONE",
    "sexualContentOrNudity": "NONE",
    "socialMedia": False,
    "socialMediaAgeRestricted": False,
    "horrorOrFearThemes": "NONE",
    "matureOrSuggestiveThemes": "NONE",
    "unrestrictedWebAccess": False,
}


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_token() -> str:
    key = serialization.load_pem_private_key(open(KEY_PATH, "rb").read(), password=None)
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
    _, apps = api("GET", f"/v1/apps/{APP_ID}/appInfos")
    app_info_id = apps["data"][0]["id"]
    _, vers = api("GET", f"/v1/apps/{APP_ID}/appStoreVersions?filter[appStoreState]=PREPARE_FOR_SUBMISSION")
    assert vers.get("data"), "没有 PREPARE_FOR_SUBMISSION 状态的版本"
    version_id = vers["data"][0]["id"]
    print(f"appInfo={app_info_id} version={version_id}")

    # 1. 年龄分级(全部否 → 4+)
    code, decl = api("GET", f"/v1/appInfos/{app_info_id}/ageRatingDeclaration")
    decl_id = decl["data"]["id"]
    body = {"data": {"type": "ageRatingDeclarations", "id": decl_id,
                     "attributes": dict(AGE_RATING_SAFE)}}
    code, resp = api("PATCH", f"/v1/appInfos/{app_info_id}/ageRatingDeclaration", body)
    print(f"ageRating PATCH: {code}" + ("" if code == 200 else " " + json.dumps(resp)[:300]))

    # 2. 主分类:教育
    _, cats = api("GET", "/v1/appCategories?limit=200")
    edu = next((c for c in cats.get("data", []) if c["id"] == "EDUCATION"
                or c.get("attributes", {}).get("name") == "Education"), None)
    if edu:
        code, resp = api("PATCH", f"/v1/appInfos/{app_info_id}",
                         {"data": {"type": "appInfos", "id": app_info_id,
                                   "relationships": {"primaryCategory": {"data": {"type": "appCategories", "id": edu["id"]}}}}})
        print(f"category PATCH ({edu['id']}): {code}" + ("" if code == 200 else " " + json.dumps(resp)[:300]))

    # 3. appInfoLocalizations zh-Hans(名称/副标题/隐私政策)
    _, existing = api(f"/v1/appInfos/{app_info_id}/appInfoLocalizations?limit=200")
    by_locale = {d["attributes"]["locale"]: d["id"] for d in existing.get("data", [])}
    attrs = {"name": NAME, "subtitle": SUBTITLE, "privacyPolicyUrl": SITE}
    if "zh-Hans" in by_locale:
        code, resp = api("PATCH", f"/v1/appInfoLocalizations/{by_locale['zh-Hans']}",
                         {"data": {"type": "appInfoLocalizations", "id": by_locale["zh-Hans"], "attributes": attrs}})
        print(f"appInfo PATCH zh-Hans: {code}" + ("" if code == 200 else " " + json.dumps(resp)[:300]))
    else:
        code, resp = api("POST", "/v1/appInfoLocalizations",
                         {"data": {"type": "appInfoLocalizations", "attributes": dict(attrs, locale="zh-Hans"),
                                   "relationships": {"appInfo": {"data": {"type": "appInfos", "id": app_info_id}}}}})
        print(f"appInfo POST zh-Hans: {code}" + ("" if code == 201 else " " + json.dumps(resp)[:200]))

    # 4. 版本:版权
    code, resp = api("PATCH", f"/v1/appStoreVersions/{version_id}",
                     {"data": {"type": "appStoreVersions", "id": version_id,
                               "attributes": {"copyright": "zhangfeiyang"}}})
    print(f"copyright PATCH: {code}" + ("" if code == 200 else " " + json.dumps(resp)[:300]))

    # 5. versionLocalization zh-Hans(描述/关键词/推广/更新说明)
    _, vlocs = api(f"/v1/appStoreVersions/{version_id}/appStoreVersionLocalizations?limit=200")
    vby_locale = {d["attributes"]["locale"]: d["id"] for d in vlocs.get("data", [])}
    vattrs = {"description": DESCRIPTION, "keywords": KEYWORDS,
              "promotionalText": PROMO, "whatsNew": WHATS_NEW,
              "supportUrl": SITE, "marketingUrl": None, "description": DESCRIPTION}
    vattrs = {k: v for k, v in vattrs.items() if v is not None}
    if "zh-Hans" in vby_locale:
        code, resp = api("PATCH", f"/v1/appStoreVersionLocalizations/{vby_locale['zh-Hans']}",
                         {"data": {"type": "appStoreVersionLocalizations", "id": vby_locale["zh-Hans"], "attributes": vattrs}})
        print(f"version PATCH zh-Hans: {code}" + ("" if code == 200 else " " + json.dumps(resp)[:300]))
    else:
        code, resp = api("POST", "/v1/appStoreVersionLocalizations",
                         {"data": {"type": "appStoreVersionLocalizations", "attributes": dict(vattrs, locale="zh-Hans"),
                                   "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": version_id}}}}})
        print(f"version POST zh-Hans: {code}" + ("" if code == 201 else " " + json.dumps(resp)[:200]))

    # 6. 截图:zh-Hans,6.9 英寸(APP_IPHONE_67,与 Crystal Grid 相同做法)
    _, vlocs = api(f"/v1/appStoreVersions/{version_id}/appStoreVersionLocalizations?limit=200")
    vby_locale = {d["attributes"]["locale"]: d["id"] for d in vlocs.get("data", [])}
    loc_id = vby_locale.get("zh-Hans")
    if not loc_id:
        raise SystemExit("zh-Hans version localization 不存在,先完成第 5 步")
    _, sets = api(f"/v1/appStoreVersionLocalizations/{loc_id}/appScreenshotSets?filter[screenshotDisplayType]=APP_IPHONE_67")
    if sets.get("data"):
        set_id = sets["data"][0]["id"]
    else:
        code, resp = api("POST", "/v1/appScreenshotSets",
                         {"data": {"type": "appScreenshotSets", "attributes": {"screenshotDisplayType": "APP_IPHONE_67"},
                                   "relationships": {"appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations", "id": loc_id}}}}})
        if code != 201:
            raise SystemExit(f"screenshot set create failed: {code} {json.dumps(resp)[:400]}")
        set_id = resp["data"]["id"]

    # 已有截图先清掉,保证产物与本次上传一致
    _, existing_shots = api(f"/v1/appScreenshotSets/{set_id}/appScreenshots")
    for s in existing_shots.get("data", []):
        api("DELETE", f"/v1/appScreenshots/{s['id']}")
        print(f"shot delete old: {s['id']}")

    for path, fname in SHOTS:
        full = os.path.join(SHOTS_DIR, path)
        if not os.path.isfile(full):
            print(f"shot missing, skip: {full}")
            continue
        data = open(full, "rb").read()
        code, resp = api("POST", "/v1/appScreenshots",
                         {"data": {"type": "appScreenshots",
                                   "attributes": {"fileName": fname, "fileSize": len(data)},
                                   "relationships": {"appScreenshotSet": {"data": {"type": "appScreenshotSets", "id": set_id}}}}})
        if code != 201:
            print(f"shot create {fname}: {code} {json.dumps(resp)[:200]}")
            continue
        shot_id = resp["data"]["id"]
        for op in resp["data"]["attributes"]["uploadOperations"]:
            req = urllib.request.Request(op["url"], method=op["method"],
                                         data=data[op["offset"]:op["offset"] + op["length"]])
            req.add_header("Content-Type", "image/png")
            with urllib.request.urlopen(req) as r:
                pass
        md5 = hashlib.md5(data).hexdigest()
        code, _ = api("PATCH", f"/v1/appScreenshots/{shot_id}",
                      {"data": {"type": "appScreenshots", "id": shot_id,
                                "attributes": {"uploaded": True, "sourceFileChecksum": md5}}})
        print(f"shot {fname}: commit {code}")
    print("ALL DONE")


if __name__ == "__main__":
    main()
