#!/bin/bash
# 在 Mac Mini 上编译 Cube AR 并导出 App Store IPA。
# 由本机的 ios/mac_release.sh 经 SSH 调用，也可在 Mac 上直接运行。
# 一次性前置条件(Xcode、~/buildkeys 签名材料、cgbuild keychain)与 Crystal Grid 共用,见 IOS_PORT_NOTES.md。
set -euo pipefail

IOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$IOS_DIR"
mkdir -p build

# ---- 1. 定位 Xcode：优先 DEVELOPER_DIR，其次 /Applications 下最新版（无需 sudo xcode-select）
if [ -z "${DEVELOPER_DIR:-}" ]; then
  latest="$(ls -d /Applications/Xcode*.app 2>/dev/null | sort -V | tail -1 || true)"
  if [ -z "$latest" ]; then
    echo "错误: 未找到 /Applications/Xcode*.app，请先在 Mac 上安装 Xcode(见 IOS_PORT_NOTES.md)" >&2
    exit 1
  fi
  DEVELOPER_DIR="$latest/Contents/Developer"
fi
export DEVELOPER_DIR
echo "== $(xcodebuild -version | head -1)"

# 首次使用时若许可未接受，尝试用户级接受；仍不行则报错提示手动处理
if ! xcodebuild -checkFirstLaunchStatus >/dev/null 2>&1; then
  xcodebuild -license accept >/dev/null 2>&1 || true
fi

# ---- 2. 解锁专用签名 keychain（随机密码在 ~/buildkeys/.passwords：第1行 p12、第2行 keychain）
KC_PASS="$(sed -n 2p "$HOME/buildkeys/.passwords")"
security unlock-keychain -p "$KC_PASS" "$HOME/Library/Keychains/cgbuild.keychain"
security list-keychain -d user -s \
  "$HOME/Library/Keychains/cgbuild.keychain-db" "$HOME/Library/Keychains/login.keychain-db" >/dev/null

SIGN_IDENTITY="$(security find-identity -v -p codesigning | awk -F'"' '/iPhone Distribution|Apple Distribution/ {print $2; exit}')"
[ -n "$SIGN_IDENTITY" ] || { echo "错误: keychain 中没有有效的分发证书身份" >&2; exit 1; }
echo "== 签名身份: $SIGN_IDENTITY"

# ---- 3. 安装描述文件到 Xcode 用户目录（Xcode 16+ 路径）
PROFILE_SRC="$HOME/buildkeys/cubear_appstore.mobileprovision"
[ -f "$PROFILE_SRC" ] || { echo "错误: 缺少 $PROFILE_SRC(用 ios/tools/asc_create_profile.py 生成后 scp 过来)" >&2; exit 1; }
PROFILE_PLIST="$(security cms -D -i "$PROFILE_SRC")"
PROFILE_UUID="$(printf '%s' "$PROFILE_PLIST" | plutil -extract UUID raw -o - -)"
PROFILE_NAME="$(printf '%s' "$PROFILE_PLIST" | plutil -extract Name raw -o - -)"
PROFILE_DIR="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
mkdir -p "$PROFILE_DIR"
cp "$PROFILE_SRC" "$PROFILE_DIR/$PROFILE_UUID.mobileprovision"
echo "== 描述文件: $PROFILE_NAME ($PROFILE_UUID)"

# ---- 4. 导出选项（手动签名：只用持久分发证书，不依赖开发证书）
TMP_PLIST="$(mktemp)"
printf '%s' "$PROFILE_PLIST" > "$TMP_PLIST"
BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' "$TMP_PLIST" | sed 's/^[A-Z0-9]*\.//')"
TEAM_ID="$(/usr/libexec/PlistBuddy -c 'Print :TeamIdentifier:0' "$TMP_PLIST")"
rm -f "$TMP_PLIST"
cat > build/exportOptions.plist <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>app-store-connect</string>
    <key>teamID</key>
    <string>$TEAM_ID</string>
    <key>signingStyle</key>
    <string>manual</string>
    <key>signingCertificate</key>
    <string>$SIGN_IDENTITY</string>
    <key>provisioningProfiles</key>
    <dict>
        <key>$BUNDLE_ID</key>
        <string>$PROFILE_NAME</string>
    </dict>
    <key>stripSwiftSymbols</key>
    <true/>
</dict>
</plist>
PLIST
echo "== bundle id: $BUNDLE_ID, team: $TEAM_ID"

# ---- 5. 归档（iphoneos 真机架构）
xcodebuild -project CubeAR.xcodeproj -scheme CubeAR \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath build/CubeAR.xcarchive archive \
  CODE_SIGN_STYLE=Manual \
  DEVELOPMENT_TEAM="$TEAM_ID" \
  CODE_SIGN_IDENTITY="$SIGN_IDENTITY" \
  PROVISIONING_PROFILE_SPECIFIER="$PROFILE_NAME" \
  OTHER_CODE_SIGN_FLAGS="--keychain $HOME/Library/Keychains/cgbuild.keychain-db"

# ---- 6. 导出 IPA
rm -rf build/export
xcodebuild -exportArchive \
  -archivePath build/CubeAR.xcarchive \
  -exportOptionsPlist build/exportOptions.plist \
  -exportPath build/export

IPA="$(ls build/export/*.ipa | head -1)"
echo "== IPA: $IOS_DIR/$IPA"
/usr/libexec/PlistBuddy \
  -c 'Print :ApplicationProperties:CFBundleShortVersionString' \
  -c 'Print :ApplicationProperties:CFBundleVersion' \
  build/CubeAR.xcarchive/Info.plist
echo "== BUILD OK"
