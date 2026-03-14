#!/usr/bin/env bash
set -e

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <NDK_PATH> [ABIs...]"
    echo "Example: $0 /path/to/android-ndk-r26b arm64-v8a armeabi-v7a x86_64"
    exit 1
fi

NDK_PATH=$1
shift
ABIS=${@:-"arm64-v8a armeabi-v7a x86_64"}
MIN_SDK=26 # Typical minSdk for modern apps

HOST_OS=$(uname -s | tr '[:upper:]' '[:lower:]')
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_OS-x86_64"

BUILD_DIR="$(pwd)/build_native_work"
OUT_DIR="$(pwd)/player/xr/src/main/jniLibs"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

FREETYPE_VER="2.13.3"
FRIBIDI_VER="1.0.16"
HARFBUZZ_VER="10.0.1"
LIBASS_VER="0.17.3"

[ ! -d freetype ] && wget -qO- https://download.savannah.gnu.org/releases/freetype/freetype-${FREETYPE_VER}.tar.xz | tar xJ && mv freetype-${FREETYPE_VER} freetype
[ ! -d fribidi ] && wget -qO- https://github.com/fribidi/fribidi/releases/download/v${FRIBIDI_VER}/fribidi-${FRIBIDI_VER}.tar.xz | tar xJ && mv fribidi-${FRIBIDI_VER} fribidi
[ ! -d harfbuzz ] && wget -qO- https://github.com/harfbuzz/harfbuzz/releases/download/${HARFBUZZ_VER}/harfbuzz-${HARFBUZZ_VER}.tar.xz | tar xJ && mv harfbuzz-${HARFBUZZ_VER} harfbuzz
[ ! -d libass ] && wget -qO- https://github.com/libass/libass/releases/download/${LIBASS_VER}/libass-${LIBASS_VER}.tar.xz | tar xJ && mv libass-${LIBASS_VER} libass

for ABI in $ABIS; do
    echo "Building for $ABI..."
    
    case $ABI in
        arm64-v8a)
            TARGET="aarch64-linux-android"
            CPU_FAMILY="aarch64"
            CPU="armv8-a"
            ;;
        armeabi-v7a)
            TARGET="armv7a-linux-androideabi"
            CPU_FAMILY="arm"
            CPU="armv7-a"
            ;;
        x86_64)
            TARGET="x86_64-linux-android"
            CPU_FAMILY="x86_64"
            CPU="x86_64"
            ;;
    esac

    PREFIX="$BUILD_DIR/prefix_$ABI"
    mkdir -p "$PREFIX"
    
    cat > cross_file_$ABI.txt <<EOF
[binaries]
c = '$TOOLCHAIN/bin/${TARGET}${MIN_SDK}-clang'
cpp = '$TOOLCHAIN/bin/${TARGET}${MIN_SDK}-clang++'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = '$CPU_FAMILY'
cpu = '$CPU'
endian = 'little'

[built-in options]
c_args = ['--sysroot=$TOOLCHAIN/sysroot']
cpp_args = ['--sysroot=$TOOLCHAIN/sysroot']
c_link_args = ['--sysroot=$TOOLCHAIN/sysroot']
cpp_link_args = ['--sysroot=$TOOLCHAIN/sysroot']

[properties]
pkg_config_libdir = '$PREFIX/lib/pkgconfig'
EOF

    # FreeType
    meson setup freetype/build_$ABI freetype --cross-file cross_file_$ABI.txt --prefix="$PREFIX" -Ddefault_library=static -Dzlib=disabled -Dpng=disabled -Dharfbuzz=disabled -Dbrotli=disabled -Dbzip2=disabled
    ninja -C freetype/build_$ABI install
    
    # FriBidi
    meson setup fribidi/build_$ABI fribidi --cross-file cross_file_$ABI.txt --prefix="$PREFIX" -Ddefault_library=static -Ddocs=false -Dtests=false
    ninja -C fribidi/build_$ABI install
    
    # HarfBuzz
    meson setup harfbuzz/build_$ABI harfbuzz --cross-file cross_file_$ABI.txt --prefix="$PREFIX" -Ddefault_library=static -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dicu=disabled -Dtests=disabled -Ddocs=disabled --pkg-config-path="$PREFIX/lib/pkgconfig"
    ninja -C harfbuzz/build_$ABI install
    
    # Libass
    meson setup libass/build_$ABI libass --cross-file cross_file_$ABI.txt --prefix="$PREFIX" -Ddefault_library=static -Dfontconfig=disabled -Dasm=auto -Drequire-system-font-provider=false --pkg-config-path="$PREFIX/lib/pkgconfig"
    ninja -C libass/build_$ABI install

    # Compile libass_jni.so
    mkdir -p "$OUT_DIR/$ABI"
    $TOOLCHAIN/bin/${TARGET}${MIN_SDK}-clang \
        -shared -fPIC -O3 \
        -I$PREFIX/include \
        -L$PREFIX/lib \
        ../player/xr/src/main/cpp/libass_jni.c \
        -lass -lharfbuzz -lfribidi -lfreetype \
        -landroid -llog -lm -lz \
        -o "$OUT_DIR/$ABI/libass_jni.so"
done

echo "Done."
