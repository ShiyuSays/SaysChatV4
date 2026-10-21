#!/bin/bash
# ============================================================
#  SaysChatV4 安卓 APK 手动构建 (Linux / macOS，无需 Gradle)
#  要求: JDK 8+、Android SDK 的 build-tools 与 platform
#        build-tools 31+ 的 d8 / apksigner 需要 JDK 11+（推荐 17）
#  用法:
#    export ANDROID_SDK_ROOT=/path/to/android-sdk
#    ./build-apk.sh
#  产出: dist/SaysChatV4.apk（debug 签名，可直接安装）
# ============================================================
set -e
cd "$(dirname "$0")"

if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
    ANDROID_SDK_ROOT="$ANDROID_HOME"
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ] || [ ! -d "$ANDROID_SDK_ROOT" ]; then
    echo "错误: 请先设置 ANDROID_SDK_ROOT 指向 Android SDK 目录"
    exit 1
fi

BT=$(ls -d "$ANDROID_SDK_ROOT"/build-tools/* 2>/dev/null | sort -V | tail -1)
PF=$(ls -d "$ANDROID_SDK_ROOT"/platforms/android-* 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] || { echo "错误: 未找到 build-tools"; exit 1; }
[ -n "$PF" ] || { echo "错误: 未找到 platforms/android-*"; exit 1; }
AAPT2="$BT/aapt2"
D8="$BT/d8"
APKSIGNER="$BT/apksigner"
ZIPALIGN="$BT/zipalign"
for tool in "$AAPT2" "$D8" "$APKSIGNER" "$ZIPALIGN"; do
    [ -x "$tool" ] || { echo "错误: 缺少工具 $tool"; exit 1; }
done

echo "使用 build-tools: $BT"
echo "使用 platform:    $PF"

APP=android/app
OUT=dist
rm -rf build-apk
mkdir -p build-apk/gen build-apk/classes build-apk/dex "$OUT"
rm -f "$OUT/SaysChatV4.apk" "$OUT/SaysChatV4-unsigned.apk" "$OUT/SaysChatV4-aligned.apk"

echo "[1/5] 编译 Android 资源 (aapt2)..."
"$AAPT2" compile --dir "$APP/src/main/res" -o build-apk/res.zip
"$AAPT2" link -I "$PF/android.jar" \
    --manifest "$APP/src/main/AndroidManifest.xml" \
    --min-sdk-version 21 --target-sdk-version 30 \
    --version-code 4 --version-name 4.0 \
    --java build-apk/gen -o "$OUT/SaysChatV4-unsigned.apk" build-apk/res.zip

echo "[2/5] 编译 Java 源码 (javac)..."
find "$APP/src/main/java" src/common build-apk/gen -name '*.java' -print > build-apk/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$PF/android.jar" -classpath "$PF/android.jar" \
    -d build-apk/classes @build-apk/sources.txt

echo "[3/5] dex 转换 (d8)..."
"$D8" --release --lib "$PF/android.jar" --min-api 21 \
    --output build-apk/dex $(find build-apk/classes -name '*.class')

echo "[4/5] 打包 + 对齐..."
if command -v zip >/dev/null 2>&1; then
    (cd build-apk/dex && zip -q -X "../../$OUT/SaysChatV4-unsigned.apk" classes.dex)
else
    (cd build-apk/dex && jar uf "../../$OUT/SaysChatV4-unsigned.apk" classes.dex)
fi
"$ZIPALIGN" -f 4 "$OUT/SaysChatV4-unsigned.apk" "$OUT/SaysChatV4-aligned.apk"

echo "[5/5] 签名 (debug 密钥)..."
KEYSTORE=android/signing/debug.keystore
mkdir -p android/signing
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storetype JKS \
        -storepass android -keypass android -alias androiddebugkey \
        -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
fi
"$APKSIGNER" sign --ks "$KEYSTORE" \
    --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/SaysChatV4.apk" "$OUT/SaysChatV4-aligned.apk"
"$APKSIGNER" verify "$OUT/SaysChatV4.apk"
rm -f "$OUT/SaysChatV4-unsigned.apk" "$OUT/SaysChatV4-aligned.apk" "$OUT/SaysChatV4.apk.idsig"

echo "构建完成: $OUT/SaysChatV4.apk"
