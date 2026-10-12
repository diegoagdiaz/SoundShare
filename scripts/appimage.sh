#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# != 0 ]]; then
    printf 'Uso: scripts/appimage.sh\nCompila y empaqueta la PC en un contenedor Debian 12 (Podman o Docker).\nRequiere una APK debug ya compilada; no instala herramientas en el sistema anfitrión.\n' >&2
    [[ $# == 1 && ( "$1" == --help || "$1" == -h ) ]] && exit 0
    exit 1
fi
[[ "$(uname -m)" == x86_64 ]] || { printf 'Por ahora, el AppImage se compila para x86_64.\n' >&2; exit 1; }
[[ -f "$root/android/app/build/outputs/apk/debug/app-debug.apk" ]] || { printf 'Primero compilá la APK con ./scripts/build.sh --android.\n' >&2; exit 1; }
if command -v podman >/dev/null 2>&1; then
    container=podman
elif command -v docker >/dev/null 2>&1; then
    container=docker
else
    printf 'Para crear el AppImage necesitás Podman o Docker. Para usarlo, solo descargalo de GitHub Releases.\n' >&2
    exit 1
fi

build_dir="$root/dist/appimage-build"
mkdir -p -- "$build_dir"
image=soundshare-appimage-builder:bookworm
"$container" build -t "$image" -f "$root/packaging/appimage/Dockerfile" "$root/packaging/appimage"
options=(--rm --security-opt label=disable --user "$(id -u):$(id -g)")
if [[ "$container" == podman ]]; then
    options+=(--userns=keep-id)
fi
"$container" run "${options[@]}" -v "$root:/source:ro" -v "$build_dir:/build" \
    "$image" bash /source/packaging/appimage/build.sh
cp -- "$build_dir/SoundShare-x86_64.AppImage" "$root/dist/SoundShare-x86_64.AppImage"
cp -- "$root/android/app/build/outputs/apk/debug/app-debug.apk" "$root/dist/SoundShare.apk"
"$root/scripts/checksums.sh"
printf 'AppImage listo: %s/dist/SoundShare-x86_64.AppImage\n' "$root"
