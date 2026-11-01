#!/usr/bin/env bash
# Runs the protocol unit tests from the android/ root.
set -euo pipefail
cd "$(dirname -- "${BASH_SOURCE[0]}")/../../.."
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
exec ./gradlew --no-daemon :core:protocol:test
