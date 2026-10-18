#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.gradle-user-home}"
exec ./gradlew test --no-daemon "$@"
