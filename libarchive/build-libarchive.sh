#!/bin/sh
# Buduje libarchive.a dla Androida (arm64-v8a, x86_64) ze źródeł 3.8.9.
# Tylko odczyt i tylko to, czego nie ma w części napisanej w Javie: RAR,
# RAR5, ISO 9660, CAB, LHA, XAR. Kompresja zlib z NDK, bez OpenSSL i iconv.
set -eu
VERSION=3.8.9
SHA256=888c934f9d95648ecb9163dc8e23ab80a476ecb81a8f1154704a227b5b676dde
NDK=${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}
CMAKE=${CMAKE:-$HOME/Android/Sdk/cmake/3.31.6/bin/cmake}
WORK=${WORK:-$(mktemp -d)}
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$WORK"
curl -fsSLO https://github.com/libarchive/libarchive/releases/download/v$VERSION/libarchive-$VERSION.tar.xz
echo "$SHA256  libarchive-$VERSION.tar.xz" | sha256sum -c -
tar xf libarchive-$VERSION.tar.xz
for abi in arm64-v8a x86_64; do
  rm -rf "build-$abi"
  "$CMAKE" -S libarchive-$VERSION -B "build-$abi" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="${NINJA:-$(dirname "$CMAKE")/ninja}" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=MinSizeRel \
    -DCMAKE_C_FLAGS="-fPIC -ffunction-sections -fdata-sections" \
    -DBUILD_SHARED_LIBS=OFF -DENABLE_TEST=OFF -DENABLE_TAR=OFF -DENABLE_CPIO=OFF \
    -DENABLE_CAT=OFF -DENABLE_UNZIP=OFF -DENABLE_OPENSSL=OFF -DENABLE_MBEDTLS=OFF \
    -DENABLE_NETTLE=OFF -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_ICONV=OFF \
    -DENABLE_LZMA=OFF -DENABLE_ZSTD=OFF -DENABLE_BZip2=OFF -DENABLE_LZ4=OFF \
    -DENABLE_LZO=OFF -DENABLE_LIBB2=OFF -DENABLE_CNG=OFF -DENABLE_ACL=OFF \
    -DENABLE_XATTR=OFF -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF -DENABLE_WERROR=OFF >/dev/null
  "$CMAKE" --build "build-$abi" --target archive_static >/dev/null
  cp "build-$abi/libarchive/libarchive.a" "$HERE/src/main/cpp/libs/$abi/"
done
cp libarchive-$VERSION/libarchive/archive.h libarchive-$VERSION/libarchive/archive_entry.h "$HERE/src/main/cpp/include/"
cp libarchive-$VERSION/COPYING "$HERE/COPYING"
