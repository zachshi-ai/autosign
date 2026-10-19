#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-/opt/android-sdk}
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-34/android.jar

rm -rf build
mkdir -p build/gen build/obj build/dex build/apk

echo "[1/6] compile resources"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/6] link resources"
"$BT/aapt2" link -o build/apk/base.apk -I "$PLAT" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  build/res.zip

echo "[3/6] javac"
javac -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
  -classpath "$PLAT" \
  -d build/obj $(find src build/gen -name '*.java')

echo "[4/6] d8"
CLASSES=$(find build/obj -name '*.class')
"$BT/d8" --lib "$PLAT" --min-api 26 --output build/dex $CLASSES

echo "[5/6] package"
cp build/apk/base.apk build/unsigned.apk
(cd build && zip -q -j unsigned.apk dex/classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

echo "[6/6] sign"
# 签名密钥与口令不入库：口令取 KS_PASS，未设置时自动生成并打印一次
if [ ! -f keystore.jks ]; then
  if [ -z "${KS_PASS:-}" ]; then KS_PASS=$(openssl rand -hex 12); echo "[info] 本次生成的 keystore 口令：$KS_PASS（请自行保存）"; fi
  keytool -genkeypair -keystore keystore.jks -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias autosign -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=AutoSign, OU=Personal, O=zachshi-ai, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks keystore.jks --ks-pass "pass:${KS_PASS:?请设置签名口令 KS_PASS}" --key-pass "pass:${KEY_PASS:-$KS_PASS}" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out build/AutoSign.apk build/aligned.apk
"$BT/apksigner" verify --print-certs build/AutoSign.apk | head -5
ls -lh build/AutoSign.apk
echo done
