#!/usr/bin/env bash
# 一键覆盖安装到手表（保留登录态、收藏、离线缓存）
#
# 用法：
#   ./install.sh                 # 自动找 adb 和唯一已连接设备
#   ./install.sh 192.168.1.50    # 指定手表 IP（会先 adb connect）
set -euo pipefail
cd "$(dirname "$0")"

APK="watch-music.apk"
[ -f "$APK" ] || { echo "找不到 $APK，先跑 ./android-native/build-native.sh --release"; exit 1; }

# 找 adb：PATH 里没有就试常见安装位置
ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  for c in \
    "${ANDROID_SDK:-/root/android-sdk}/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "$HOME/Android/Sdk/platform-tools/adb" \
    "/c/Users/${USER:-user}/AppData/Local/Android/Sdk/platform-tools/adb"; do
    [ -x "$c" ] && ADB="$c" && break
  done
fi
[ -n "$ADB" ] || { echo "找不到 adb。装一个 platform-tools，或直接用系统安装器侧载 $APK"; exit 1; }

if [ "${1:-}" != "" ]; then
  echo "连接 $1:5555 ..."
  "$ADB" connect "$1:5555" || true
fi

DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
COUNT=$(echo "$DEVICES" | grep -c . || true)
if [ "$COUNT" = "0" ]; then
  echo "没有已连接的设备。"
  echo "  无线调试：先在手表开发者选项里打开「无线调试」，与电脑同一 Wi-Fi"
  echo "  然后：./install.sh <手表IP>"
  exit 1
fi

if [ "$COUNT" -gt 1 ]; then
  echo "检测到多台设备，请指定序列号："
  echo "$DEVICES"
  exit 1
fi

echo "安装到 $DEVICES ..."
if OUT=$("$ADB" install -r "$APK" 2>&1); then
  echo "$OUT"
  echo "完成。登录态与收藏都保留。"
  exit 0
fi

echo "$OUT"
case "$OUT" in
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    echo
    echo "签名与已安装版本不一致。这一版起签名已固定，正常情况下不会再出现；"
    echo "如果看到这个提示，说明装的是更早的包，需要卸载一次："
    echo "  $ADB uninstall com.henmoran.watchmusic"
    echo "之后再安装就都能覆盖了（但卸载会清掉登录态和离线缓存）。" ;;
  *INSTALL_FAILED_VERSION_DOWNGRADE*)
    echo
    echo "要装的版本号比手表上的低。用最新构建产物，或加 -d 强制降级："
    echo "  $ADB install -r -d $APK" ;;
  *)
    echo
    echo "安装失败，把上面的输出发我。" ;;
esac
exit 1
