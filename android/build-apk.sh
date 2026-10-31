#!/usr/bin/env bash
# 不依赖 Gradle 的打包脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner
#
# 用法：
#   ./build-apk.sh                       # 用默认地址打包
#   ./build-apk.sh http://192.168.1.50:3000
#
# 产物：build/watch-music.apk
set -euo pipefail

SERVER_URL="${1:-http://192.168.1.100:3000}"
SDK="${ANDROID_SDK:-/root/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
SRC="app/src/main"
OUT="build"
PKG_DIR="com/henmoran/watchmusic"

[ -x "$BT/aapt2" ] || { echo "找不到 aapt2，检查 ANDROID_SDK"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen/$PKG_DIR" "$OUT/classes" "$OUT/dex"

echo "[1/7] 生成 BuildConfig（服务器地址: $SERVER_URL）"
cat > "$OUT/gen/$PKG_DIR/BuildConfig.java" <<EOF
package com.henmoran.watchmusic;

public final class BuildConfig {
    public static final String SERVER_URL = "$SERVER_URL";
}
EOF

echo "[2/7] 编译资源"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "[3/7] 链接资源并生成 R.java"
# aapt2 要求 manifest 带 package 属性（AGP 8 用 namespace 就不再写了），这里注入一份临时清单
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.henmoran.watchmusic">|' \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name 1.0 \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "[4/7] 编译 Java"
javac -source 8 -target 8 -encoding UTF-8 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  "$OUT/gen/$PKG_DIR/R.java" \
  "$OUT/gen/$PKG_DIR/BuildConfig.java" \
  "$SRC/java/$PKG_DIR/MainActivity.java" 2> "$OUT/javac.log" || { cat "$OUT/javac.log"; exit 1; }

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
if [ ! -f "$OUT/watch.jks" ]; then
  keytool -genkeypair -v \
    -keystore "$OUT/watch.jks" \
    -alias watchmusic \
    -storepass watchmusic \
    -keypass watchmusic \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=WatchMusic, OU=Personal, O=Personal, L=CN, C=CN" >/dev/null 2>&1
fi

"$BT/apksigner" sign \
  --ks "$OUT/watch.jks" \
  --ks-pass pass:watchmusic \
  --key-pass pass:watchmusic \
  --out "$OUT/watch-music.apk" \
  "$OUT/aligned.apk"

"$BT/apksigner" verify "$OUT/watch-music.apk"
echo "完成: $OUT/watch-music.apk ($(du -h "$OUT/watch-music.apk" | cut -f1))"
