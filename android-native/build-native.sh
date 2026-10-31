#!/usr/bin/env bash
# 原生版打包：aapt2 -> javac -> d8 -> zipalign -> apksigner（不依赖 Gradle）
#
# 用法：./build-native.sh [服务器地址]
# 产物：build/watch-music-native.apk
set -euo pipefail

# 一律以脚本所在目录为工作目录，任何位置执行都一致
cd "$(dirname "$0")"


# 用法：
#   ./build-native.sh            普通打包，版本号不变（调试用）
#   ./build-native.sh --release  交付打包，成功后版本号 +1
BUMP=0
if [ "${1:-}" = "--release" ]; then BUMP=1; fi

SDK="${ANDROID_SDK:-/root/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
SRC="app/src/main"
OUT="build"
PKG_DIR="com/henmoran/watchmusic"

# 签名密钥：必须固定且长期保存。
# 放在 build/ 里会被每次 rm -rf 清掉 -> 每次都换新密钥 -> 覆盖安装报
# INSTALL_FAILED_UPDATE_INCOMPATIBLE（只能卸载重装）。
KS_DIR="keystore"
KS="$KS_DIR/watch.jks"

[ -x "$BT/aapt2" ] || { echo "找不到 aapt2，检查 ANDROID_SDK"; exit 1; }

# 版本号：打包用当前值，成功后写回下一号
VC=$(grep '^versionCode=' version.properties | cut -d= -f2 | tr -d '\r ')
VN=$(grep '^versionName=' version.properties | cut -d= -f2 | tr -d '\r ')
[ -z "$VC" ] && VC=1
[ -z "$VN" ] && VN=1.0.0

rm -rf "$OUT"
mkdir -p "$OUT/gen/$PKG_DIR" "$OUT/classes" "$OUT/dex"

echo "[1/7] 生成 BuildConfig（v$VN / $VC）"
cat > "$OUT/gen/$PKG_DIR/BuildConfig.java" <<EOF
package com.henmoran.watchmusic;

public final class BuildConfig {
    public static final String VERSION_NAME = "$VN";
    public static final int VERSION_CODE = $VC;
}
EOF

echo "[2/7] 编译资源"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "[3/7] 链接资源并生成 R.java"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code "$VC" \
  --version-name "$VN" \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "[4/7] 编译 Java（$(find "$SRC/java" -name '*.java' | wc -l | tr -d ' ') 个文件）"
javac -source 8 -target 8 -encoding UTF-8 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  $(find "$OUT/gen" -name "*.java") \
  $(find "$SRC/java" -name "*.java") 2> "$OUT/javac.log" || { cat "$OUT/javac.log"; exit 1; }

echo "[5/7] 转 dex"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")

echo "[6/7] 组装 APK"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
ZIP_ABS="$(cd "$OUT" && pwd)/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$ZIP_ABS" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[7/7] 签名"
if [ ! -f "$KS" ]; then
  mkdir -p "$KS_DIR"
  keytool -genkeypair -v \
    -keystore "$KS" \
    -alias watchmusic \
    -storepass watchmusic \
    -keypass watchmusic \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=WatchMusic, OU=Personal, O=Personal, L=CN, C=CN" >/dev/null 2>&1
  echo "  已生成签名密钥 $KS —— 请务必备份，丢失后只能卸载重装"
fi

"$BT/apksigner" sign \
  --ks "$KS" \
  --ks-pass pass:watchmusic \
  --key-pass pass:watchmusic \
  --out "$OUT/watch-music-native.apk" \
  "$OUT/aligned.apk"

"$BT/apksigner" verify "$OUT/watch-music-native.apk"

# 打印指纹：和上一次对比一致，就说明能覆盖安装
FP=$("$BT/apksigner" verify --print-certs "$OUT/watch-music-native.apk" \
     | grep -i "SHA-256 digest" | head -1 | sed 's/.*: //')
echo "  签名指纹: $FP"

echo "完成: v$VN ($VC) -> $OUT/watch-music-native.apk ($(du -h "$OUT/watch-music-native.apk" | cut -f1))"
echo "覆盖安装: adb install -r $OUT/watch-music-native.apk（保留登录态与收藏）"

if [ "$BUMP" = "1" ]; then
  NEXT_VC=$((VC + 1))
  NEXT_VN=$(echo "$VN" | awk -F. '{ if (NF>=3) printf "%s.%s.%d", $1, $2, $3+1; else printf "%s.%d", $1, $2+1 }')
  cat > version.properties <<EOF
# 每次交付自动 +1，构建脚本读完后写回下一号
versionCode=$NEXT_VC
versionName=$NEXT_VN
EOF
  echo "已发布，下一版: v$NEXT_VN ($NEXT_VC)"
else
  echo "（调试构建，版本号未变；交付请加 --release）"
fi
