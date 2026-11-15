#!/bin/sh
# Builds libvoicerepsmp3.so (LAME 3.100 + JNI) for arm64-v8a with 16 KB page alignment.
# Usage: NDK=/path/to/android-ndk-r27c LAME_SRC=/path/to/lame-3.100 native/mp3/build_mp3.sh
# lame-3.100.tar.gz sha256 ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang
OUT=$HERE/../../app/src/main/jniLibs/arm64-v8a/libvoicerepsmp3.so
SRCS=$(ls $LAME_SRC/libmp3lame/*.c | grep -v -e mpglib_interface)
$CC -shared -fPIC -O2 -DHAVE_STDINT_H=1 -DSTDC_HEADERS=1 -DHAVE_MPGLIB=0 -DNOANALYSIS -Dieee754_float32_t=float \
  -I$LAME_SRC/include -I$LAME_SRC/libmp3lame -I$LAME_SRC \
  $SRCS $HERE/mp3_jni.c -o $OUT \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--build-id=none -Wl,-s -lm
