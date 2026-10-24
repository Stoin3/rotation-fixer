#!/usr/bin/env bash
# Builds a signed APK without the Android SDK or Gradle.
#
# Needs: a JDK (javac, keytool) and the Debian/Ubuntu packages
#   aapt dalvik-exchange zipalign apksigner
# The Android framework is taken from Robolectric's android-all jar on Maven Central.
set -euo pipefail

cd "$(dirname "$0")"
ROOT=$PWD
SRC=app/src/main
OUT=build
SDK=$OUT/sdk
# Android 14 (API 34). aapt cannot read the newer resource table format of the API 35 jar.
ANDROID_ALL_URL=https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/14-robolectric-10818077-i7/android-all-instrumented-14-robolectric-10818077-i7.jar
APK_NAME=rotation-fixer.apk

mkdir -p "$SDK"
if [ ! -f "$SDK/framework-res.apk" ] || [ ! -f "$SDK/android-api.jar" ]; then
    echo "==> Fetching Android framework"
    jar="$SDK/android-all.jar"
    [ -f "$jar" ] || curl -fSL --retry 5 --retry-delay 10 -o "$jar" "$ANDROID_ALL_URL"
    unzip -tq "$jar" >/dev/null
    tmp=$(mktemp -d)
    (cd "$tmp" && unzip -q "$ROOT/$jar" resources.arsc AndroidManifest.xml 'android/*')
    # Robolectric's instrumented classes implement these marker interfaces.
    mkdir -p "$tmp/stubs/org/robolectric/internal/bytecode"
    for name in InstrumentedInterface ShadowedObject; do
        echo "package org.robolectric.internal.bytecode; public interface $name {}" \
            > "$tmp/stubs/org/robolectric/internal/bytecode/$name.java"
    done
    javac -nowarn -d "$tmp" "$tmp"/stubs/org/robolectric/internal/bytecode/*.java
    (cd "$tmp" && zip -q -0 framework-res.apk resources.arsc AndroidManifest.xml \
        && zip -qr android-api.jar android org)
    mv "$tmp/framework-res.apk" "$tmp/android-api.jar" "$SDK/"
    rm -rf "$tmp" "$jar"
fi

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/apk"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/apk"

echo "==> Packaging resources"
aapt package -f -m \
    -M "$SRC/AndroidManifest.xml" \
    -S "$SRC/res" \
    -I "$SDK/framework-res.apk" \
    -J "$OUT/gen" \
    -F "$OUT/apk/unaligned.apk" \
    --min-sdk-version 26 --target-sdk-version 35 2>&1 | grep -v 'ResTable_entry at' || true
[ -f "$OUT/apk/unaligned.apk" ] || { echo "aapt failed" >&2; exit 1; }

echo "==> Compiling Java"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# The framework jar is an implementation jar; hide warnings about its internal annotations.
javac -nowarn -Xlint:none --release 8 -encoding UTF-8 \
    -cp "$SDK/android-api.jar" \
    -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
    | { grep -v -e 'Cannot find annotation method' -e 'warning' -e '^Note:' || true; }

echo "==> Dexing"
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/apk/classes.dex" "$OUT/classes"
(cd "$OUT/apk" && aapt add -f unaligned.apk classes.dex >/dev/null)

echo "==> Aligning and signing"
zipalign -f -p 4 "$OUT/apk/unaligned.apk" "$OUT/apk/aligned.apk"
apksigner sign \
    --ks keystore/rotation-fixer.jks --ks-pass pass:rotationfixer \
    --ks-key-alias rotationfixer --key-pass pass:rotationfixer \
    --out "$OUT/$APK_NAME" "$OUT/apk/aligned.apk"
apksigner verify "$OUT/$APK_NAME"

echo "==> Built $OUT/$APK_NAME"
