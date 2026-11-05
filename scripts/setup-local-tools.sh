#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="$(find "$PWD/.tools" -maxdepth 3 -type d -name Home | head -1)"
export ANDROID_HOME="$PWD/.tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.tools/gradle-home"
export ANDROID_USER_HOME="$PWD/.tools/android-user"
export ANDROID_AVD_HOME="$PWD/.tools/android-user/avd"
exec "$@"
