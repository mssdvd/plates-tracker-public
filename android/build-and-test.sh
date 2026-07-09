#!/usr/bin/env bash
# Headless build/test for the Gate-B spike.
#
# Uses Android Studio's bundled JDK 21 (the system JDK on this box is Java 25, which AGP rejects),
# and invokes the Gradle wrapper's main class directly (no committed `gradlew` script needed).
#
# Examples:
#   ./build-and-test.sh assembleDebug        # build the debug APK
#   ./build-and-test.sh testDebugUnitTest    # run the JVM unit tests (ALPR math vs Python reference)
#   ./build-and-test.sh assembleDebug testDebugUnitTest
#
# Requires once: Android SDK at ~/Android/Sdk with platform 35 + build-tools 35.0.0, and
# android/local.properties pointing sdk.dir there.
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
cd "$(dirname "$0")"
exec "$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar \
    org.gradle.wrapper.GradleWrapperMain --no-daemon "${@:-assembleDebug}"
