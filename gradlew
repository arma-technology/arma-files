#!/bin/sh
set -eu
APP_HOME=$(cd "${0%/*}" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://github.com/gradle/gradle/raw/refs/tags/v8.11.1/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_SHA256="2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046"

if [ ! -f "$CLASSPATH" ]; then
  echo "Gradle wrapper JAR not found; downloading verified Gradle 8.11.1 wrapper..." >&2
  TMP="$CLASSPATH.tmp"
  mkdir -p "$(dirname "$CLASSPATH")"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$WRAPPER_URL" -o "$TMP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$TMP" "$WRAPPER_URL"
  else
    echo "Error: install curl or wget, then retry." >&2
    exit 1
  fi
  ACTUAL=$(sha256sum "$TMP" | awk '{print $1}')
  if [ "$ACTUAL" != "$WRAPPER_SHA256" ]; then
    rm -f "$TMP"
    echo "Error: Gradle wrapper checksum mismatch." >&2
    exit 1
  fi
  mv "$TMP" "$CLASSPATH"
fi

exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
