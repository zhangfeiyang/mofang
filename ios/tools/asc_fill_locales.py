#!/usr/bin/env python3
"""把 ios/l10n/*.json 里的 store.* 文案写到 App Store Connect 各语言的商品页。

每个 App Store Connect 语言:
- App 信息本地化:名称、副标题、隐私政策网址;
- 当前可编辑版本的本地化:描述、关键词、推广文本、技术支持网址。
没有就建,有就改。名称在商店里必须唯一,被占用时改用后备写法并在输出里标出。

用法(仓库根目录):
    export AC_API_KEY_ID=… AC_API_ISSUER_ID=… AC_API_KEY_P8=$(base64 -w0 AuthKey.p8)
    python3 ios/tools/asc_fill_locales.py [--only zh-Hans,en-US]
截图只传主语言(zh-Hans),其余语言自动沿用。
"""
import argparse
import json
import os
import time

from asc_api import api

APP_ID = os.environ.get("APP_ID", "6820316386")
SITE = "https://zhangfeiyang.github.io/cube-ar-site/"
L10N = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "l10n")
# App Store Connect 的语言代码 → ios/l10n 的文件名
LOCALES = {
    "ar-SA": "ar", "ca": "ca", "cs": "cs", "da": "da", "de-DE": "de", "el": "el",
    "en-AU": "en", "en-CA": "en", "en-GB": "en", "en-US": "en", "es-ES": "es", "es-MX": "es",
    "fi": "fi", "fr-CA": "fr", "fr-FR": "fr", "he": "he", "hi": "hi", "hr": "hr", "hu": "hu",
    "id": "id", "it": "it", "ja": "ja", "ko": "ko", "ms": "ms", "nl-NL": "nl", "no": "nb",
    "pl": "pl", "pt-BR": "pt-BR", "pt-PT": "pt-PT", "ro": "ro", "ru": "ru", "sk": "sk",
    "sv": "sv", "th": "th", "tr": "tr", "uk": "uk", "vi": "vi", "zh-Hans": "zh-Hans",
    "zh-Hant": "zh-Hant",
}


def call(method, path, body=None):
    """经代理偶有 SSL EOF;只读与幂等写入重试几次。"""
    for attempt in range(4):
        try:
            return api(method, path, body)
        except RuntimeError:
            raise
        except Exception as error:  # 网络层错误
            if attempt == 3:
                raise
            print(f"  retry {method} {path}: {error}")
            time.sleep(3 * (attempt + 1))


def name_taken(error):
    text = str(error)
    return "409" in text and ("already" in text or "used" in text or "DUPLICATE" in text.upper())


def upsert_info(info_id, existing, locale, table):
    attributes = {"name": table["store.name"], "subtitle": table["store.subtitle"], "privacyPolicyUrl": SITE}
    candidates = [table["store.name"], table["store.name"] + " 3x3", "Cube AR · " + table["store.subtitle"][:20]]
    for name in candidates:
        attributes["name"] = name[:30]
        try:
            if locale in existing:
                call("PATCH", f"/v1/appInfoLocalizations/{existing[locale]}", {"data": {
                    "type": "appInfoLocalizations", "id": existing[locale], "attributes": attributes}})
            else:
                call("POST", "/v1/appInfoLocalizations", {"data": {
                    "type": "appInfoLocalizations", "attributes": dict(attributes, locale=locale),
                    "relationships": {"appInfo": {"data": {"type": "appInfos", "id": info_id}}}}})
            return attributes["name"]
        except RuntimeError as error:
            if not name_taken(error):
                raise
            print(f"  {locale}: name “{attributes['name']}” is taken, trying another")
    raise SystemExit(f"{locale}: no available name")


def upsert_version(version_id, existing, locale, table):
    attributes = {"description": table["store.description"], "keywords": table["store.keywords"],
                  "promotionalText": table["store.promo"], "supportUrl": SITE}
    if locale not in existing:
        # 新建 App 信息本地化时,ASC 会顺手给可编辑版本建同语言的本地化,所以先重新查一遍。
        existing.update({l["attributes"]["locale"]: l["id"] for l in call(
            "GET", f"/v1/appStoreVersions/{version_id}/appStoreVersionLocalizations?limit=50")["data"]})
    if locale in existing:
        call("PATCH", f"/v1/appStoreVersionLocalizations/{existing[locale]}", {"data": {
            "type": "appStoreVersionLocalizations", "id": existing[locale], "attributes": attributes}})
    else:
        call("POST", "/v1/appStoreVersionLocalizations", {"data": {
            "type": "appStoreVersionLocalizations", "attributes": dict(attributes, locale=locale),
            "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": version_id}}}}})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="逗号分隔的语言代码,只处理这些")
    args = ap.parse_args()
    only = set(args.only.split(",")) if args.only else None

    infos = call("GET", f"/v1/apps/{APP_ID}/appInfos")["data"]
    info = next(i for i in infos if i["attributes"].get("appStoreState") != "READY_FOR_SALE") if len(infos) > 1 else infos[0]
    versions = call("GET", f"/v1/apps/{APP_ID}/appStoreVersions?limit=10")["data"]
    version = next(v for v in versions if v["attributes"]["appStoreState"] in
                   ("PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED"))
    info_locs = {l["attributes"]["locale"]: l["id"] for l in
                 call("GET", f"/v1/appInfos/{info['id']}/appInfoLocalizations?limit=50")["data"]}
    version_locs = {l["attributes"]["locale"]: l["id"] for l in
                    call("GET", f"/v1/appStoreVersions/{version['id']}/appStoreVersionLocalizations?limit=50")["data"]}
    print(f"version {version['attributes']['versionString']}: {len(info_locs)} info / {len(version_locs)} version locales before")

    for locale, language in LOCALES.items():
        if only and locale not in only:
            continue
        with open(os.path.join(L10N, language + ".json"), encoding="utf-8") as f:
            table = json.load(f)
        name = upsert_info(info["id"], info_locs, locale, table)
        upsert_version(version["id"], version_locs, locale, table)
        print(f"{locale:8} {name} | {table['store.subtitle']}")


if __name__ == "__main__":
    main()
