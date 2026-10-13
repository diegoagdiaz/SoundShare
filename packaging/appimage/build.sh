#!/usr/bin/env bash
set -euo pipefail

export CARGO_HOME=/build/cargo
export CARGO_TARGET_DIR=/build/target
mkdir -p /build/pc
cp /source/pc/Cargo.toml /source/pc/Cargo.lock /build/pc/
rm -rf /build/pc/src
cp -r /source/pc/src /build/pc/src
# El test de versión compara la constante de PC con la configuración de Android.
mkdir -p /build/android/app
cp /source/android/app/build.gradle.kts /build/android/app/
cd /build/pc
cargo test --locked
cargo build --release --locked
python3 /source/packaging/appimage/bundle.py /build/SoundShare.AppDir /build/target/release/soundshare
desktop-file-validate /build/SoundShare.AppDir/soundshare.desktop

ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 /opt/appimagetool.AppImage \
    --no-appstream --runtime-file /opt/appimage-runtime \
    /build/SoundShare.AppDir /build/SoundShare-x86_64.AppImage

/build/SoundShare-x86_64.AppImage --appimage-extract-and-run --help
printf 'AppImage generado: /build/SoundShare-x86_64.AppImage\n'
