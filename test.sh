#!/bin/sh
# Runs the plain-Java unit tests (no Android needed) with the JUnit 4 that Gradle has already downloaded.
set -e
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
JUNIT=$(find ~/.gradle/caches -name junit-4.13.2.jar | head -1)
HAMCREST=$(find ~/.gradle/caches -name hamcrest-core-1.3.jar | head -1)
cd "$(dirname "$0")"; rm -rf build/test; mkdir -p build/test
javac -nowarn --release 17 -cp "$JUNIT" -d build/test src/orbithook/NumpadBrailleKeyboard.java $(find test -name '*.java')
java -cp "build/test:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore orbithook.NumpadBrailleKeyboardTest
