#!/usr/bin/env bash
set -euo pipefail
module_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
android_root=$(cd -- "$module_dir/../.." && pwd)
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export GRADLE_USER_HOME="/c/Users/abhir/.gradle"
export ANDROID_USER_HOME="$(cygpath -m "$module_dir/build/android-user-home")"
cd "$android_root"
exec ./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
