#!/bin/sh
# Builds out/orbithook.apk (run ./fetch-libs.sh once first). The Xposed API stubs are compiled against but not packaged.
# DexKit (Kotlin + a per-ABI libdexkit.so) is bundled so the chord pass-through can locate Orbit's
# obfuscated keypad router at runtime. The native libs MUST be STORED (uncompressed) and page-aligned,
# because LSPosed's module classloader loads them straight from inside the APK without extracting.
set -e
SDK=$HOME/Library/Android/sdk; BT=$SDK/build-tools/36.0.0; JAR=$SDK/platforms/android-37.0/android.jar
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
L=libs
cd "$(dirname "$0")"; rm -rf build; mkdir -p build/stubs build/gen build/classes build/dex out
# Orbit's screen reader interface and Backtalk's braille display interface, for the Orbit display service
$BT/aidl --lang=java -p $SDK/platforms/android-37.0/framework.aidl -I aidl -o build/gen $(find aidl -name '*.aidl')
javac -nowarn --release 17 -cp "$JAR" -d build/stubs $(find stubs -name '*.java')
javac -nowarn --release 17 -cp "$JAR:build/stubs:$L/x/classes.jar:$L/kotlin-stdlib.jar:$L/annotations.jar" -d build/classes $(find src build/gen -name '*.java')
# dex my classes + dexkit + kotlin-stdlib + annotations (multidex auto)
$BT/d8 --min-api 30 --lib "$JAR" --output build/dex \
  $(find build/classes -name '*.class') "$L/x/classes.jar" "$L/kotlin-stdlib.jar" "$L/annotations.jar" "$L/flatbuffers-java.jar"
$BT/aapt2 link -I "$JAR" --manifest AndroidManifest.xml -A assets -o build/base.apk
(cd build/dex && zip -q ../base.apk classes*.dex)
# native libs: STORED so LSPosed's classloader can load them from inside the APK
mkdir -p build/lib/arm64-v8a build/lib/armeabi-v7a
cp "$L/x/jni/arm64-v8a/libdexkit.so" build/lib/arm64-v8a/
cp "$L/x/jni/armeabi-v7a/libdexkit.so" build/lib/armeabi-v7a/
(cd build && zip -q -r -0 base.apk lib)
# -p page-aligns the uncompressed .so entries
$BT/zipalign -p -f 4 build/base.apk build/aligned.apk
$BT/apksigner sign --ks $HOME/.android/debug.keystore --ks-pass pass:android --out out/orbithook.apk build/aligned.apk 2>/dev/null
echo built out/orbithook.apk
