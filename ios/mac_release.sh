#!/bin/bash
# Cube AR iOS 发布入口（Linux 侧编排，编译在本家里的 Mac Mini 上完成）：
#   bash ios/mac_release.sh            同步源码 → Mac 编译导出 → IPA 拉回 ios/dist/
#   bash ios/mac_release.sh --upload   上面全部 + 直接上传 App Store Connect 并等待 Apple 处理完成
# 上传只进 TestFlight;提交审核另跑 ios/tools/asc_submit_and_verify.py。
set -euo pipefail

MAC_HOST="${MAC_HOST:-macmini}"      # ~/.ssh/config 中的 Host 别名
MAC_DIR="${MAC_DIR:-cubear-build}"    # Mac 上的工作目录（相对 $HOME）
IOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DO_UPLOAD=0
[ "${1:-}" = "--upload" ] && DO_UPLOAD=1

echo "== 1/4 同步源码到 $MAC_HOST:~/$MAC_DIR/ios/"
ssh "$MAC_HOST" "mkdir -p ~/$MAC_DIR"
rsync -az --delete \
  --exclude '.git' --exclude '.build*' --exclude 'build' --exclude '__pycache__' --exclude 'dist' \
  "$IOS_DIR/" "$MAC_HOST:$MAC_DIR/ios/"

echo "== 2/4 远程编译（首次会下载依赖/签名处理，通常 3-8 分钟）"
ssh -tt "$MAC_HOST" "bash ~/$MAC_DIR/ios/tools/mac_build_remote.sh"

echo "== 3/4 拉回 IPA"
mkdir -p "$IOS_DIR/dist"
rsync -az --update "$MAC_HOST:$MAC_DIR/ios/build/export/" "$IOS_DIR/dist/"
IPA="$(ls "$IOS_DIR"/dist/*.ipa | head -1)"
echo "IPA: $IPA"

if [ "$DO_UPLOAD" = 1 ]; then
  echo "== 4/4 上传 App Store Connect（Build Upload API，等待 Apple 校验完成）"
  : "${AC_API_KEY_ID:?需要 ASC API 环境变量,见 IOS_PORT_NOTES.md}"
  : "${AC_API_ISSUER_ID:?需要 ASC API 环境变量}"
  : "${AC_API_KEY_P8:?需要 ASC API 环境变量}"
  : "${APP_ID:=6820316386}"
  : "${APP_IDENTIFIER:=com.mofang.cubear}"
  export AC_API_KEY_ID AC_API_ISSUER_ID AC_API_KEY_P8 APP_ID APP_IDENTIFIER
  ( cd "$IOS_DIR/tools" && python3 asc_upload_ipa.py "$IPA" )
  echo "上传完成(TestFlight)。提交审核另跑 ios/tools/asc_submit_and_verify.py"
else
  echo "== 4/4 跳过上传（加 --upload 上传到 App Store Connect）"
fi
