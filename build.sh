#!/bin/bash
# Hand-rolled APK build (no gradle): aapt2 + javac + d8 + zipalign + apksigner
set -e
PROJ="$(cd "$(dirname "$0")" && pwd)"
ALPS=/dfb/zhuyang/share/mtk_x/alps
BT=$ALPS/prebuilts/sdk/tools/linux/bin
ANDROID_JAR=$ALPS/prebuilts/sdk/current/public/android.jar
JDK=$ALPS/prebuilts/jdk/jdk21/linux-x86/bin
export PATH=$JDK:$PATH

OUT=$PROJ/out
rm -rf "$OUT"; mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/compiled/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --min-sdk-version 23 --target-sdk-version 36 \
  "$OUT/compiled/res.zip"

echo "[3/6] javac"
find "$PROJ/src" -name '*.java' > "$OUT/sources.txt"
"$JDK/javac" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -nowarn \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
D8_JAR=$ALPS/prebuilts/r8/r8.jar
"$JDK/java" -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 23 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" $(cat "$OUT/classes.txt")

echo "[5/6] add dex + zipalign"
(cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
KS="$PROJ/demo.keystore"
if [ ! -f "$KS" ]; then
  "$JDK/keytool" -genkeypair -keystore "$KS" -alias demo -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android -dname "CN=NfcTapDemo" >/dev/null
fi
APKSIGNER_JAR=$ALPS/prebuilts/sdk/tools/linux/lib/apksigner.jar
"$JDK/java" -jar "$APKSIGNER_JAR" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias demo --out "$PROJ/NfcTapDemo.apk" "$OUT/aligned.apk"
"$JDK/java" -jar "$APKSIGNER_JAR" verify --print-certs "$PROJ/NfcTapDemo.apk" | head -2
ls -l "$PROJ/NfcTapDemo.apk"
echo "DONE: $PROJ/NfcTapDemo.apk"
