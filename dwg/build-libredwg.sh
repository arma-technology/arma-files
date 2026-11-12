#!/bin/sh
# Buduje libredwg.a dla Androida (arm64-v8a, x86_64) i kopiuje nagłówki
# potrzebne mostowi JNI. Wersja 0.14.8612 lub nowsza: wcześniejsze 0.14
# czytały niektóre napisy poza ich końcem (śmieci w MTEXT).
set -eu
VERSION=${VERSION:-0.14.8612}
# Suma źródeł domyślnej wersji; inna wersja podaje własną: SHA256=...
SHA256=${SHA256:-8518443d9c5473c64ae8b90b7f380c45abebfb08a25b71145923020b2a66c4fe}
NDK=${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
WORK=${WORK:-$(mktemp -d)}
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$WORK"
curl -fsSLO "https://github.com/LibreDWG/libredwg/releases/download/$VERSION/libredwg-$VERSION.tar.xz"
echo "$SHA256  libredwg-$VERSION.tar.xz" | sha256sum -c -
tar xf "libredwg-$VERSION.tar.xz"
SRC="$WORK/libredwg-$VERSION"
for pair in aarch64:arm64-v8a x86_64:x86_64; do
  arch=${pair%%:*}; abi=${pair##*:}
  mkdir -p "build-$arch" && cd "build-$arch"
  CC=$TC/bin/${arch}-linux-android26-clang AR=$TC/bin/llvm-ar RANLIB=$TC/bin/llvm-ranlib CFLAGS="-O2 -fPIC" \
    "$SRC/configure" --host=${arch}-linux-android --disable-python --disable-bindings \
    --disable-docs --disable-json --enable-shared=no --enable-static=yes -q
  make -j4 -C src -s
  cp src/.libs/libredwg.a "$HERE/src/main/cpp/libs/$abi/"
  cd ..
done
INC="$HERE/src/main/cpp/include"
cp "$SRC/include/dwg.h" "$INC/"
for h in bits.h codepages.h common.h logging.h out_dxf.h; do cp "$SRC/src/$h" "$INC/"; done
cp "build-aarch64/src/config.h" "$INC/config.h"
echo "LibreDWG $VERSION" > "$HERE/src/main/cpp/libs/VERSION"
