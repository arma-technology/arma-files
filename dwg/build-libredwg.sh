#!/bin/sh
# Buduje libredwg.a dla Androida (arm64-v8a, x86_64) ze źródeł 0.14.
set -eu
NDK=${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
WORK=${WORK:-$(mktemp -d)}
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$WORK"
curl -sLO https://github.com/LibreDWG/libredwg/releases/download/0.14/libredwg-0.14.tar.xz
tar xf libredwg-0.14.tar.xz
for pair in aarch64:arm64-v8a x86_64:x86_64; do
  arch=${pair%%:*}; abi=${pair##*:}
  mkdir -p "build-$arch" && cd "build-$arch"
  CC=$TC/bin/${arch}-linux-android26-clang AR=$TC/bin/llvm-ar RANLIB=$TC/bin/llvm-ranlib CFLAGS="-O2 -fPIC" \
    ../libredwg-0.14/configure --host=${arch}-linux-android --disable-python --disable-bindings \
    --disable-docs --disable-json --enable-shared=no --enable-static=yes -q
  make -j4 -C src -s
  cp src/.libs/libredwg.a "$HERE/src/main/cpp/libs/$abi/"
  cd ..
done
