#!/bin/sh
# Downloads the libraries build.sh needs into libs/ from Maven Central: DexKit (its AAR, unpacked into libs/x for its
# classes and per-ABI libdexkit.so) and the runtime libraries it needs. Run once before the first build.
set -e
cd "$(dirname "$0")"
M=https://repo1.maven.org/maven2
DEXKIT=2.0.6
mkdir -p libs
fetch() { curl -fsSL -o "libs/$2" "$M/$1"; }
fetch org/luckypray/dexkit/$DEXKIT/dexkit-$DEXKIT.aar dexkit.aar
fetch org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar kotlin-stdlib.jar
fetch org/jetbrains/annotations/23.0.0/annotations-23.0.0.jar annotations.jar
# DexKit 2.0.6 needs FlatBuffers at runtime but doesn't bundle it.
fetch com/google/flatbuffers/flatbuffers-java/23.5.26/flatbuffers-java-23.5.26.jar flatbuffers-java.jar
rm -rf libs/x && mkdir libs/x && (cd libs/x && unzip -q ../dexkit.aar)
echo "libraries in libs/"
