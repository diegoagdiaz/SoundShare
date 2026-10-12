#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# != 0 ]]; then
    printf 'Uso: scripts/package.sh\nEmpaqueta el ejecutable release y la APK debug ya compilados en dist/.\n' >&2
    [[ $# == 1 && ( "$1" == --help || "$1" == -h ) ]] && exit 0
    exit 1
fi
binary="$root/pc/target/release/soundshare"
apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
[[ -x "$binary" && -f "$apk" ]] || { printf 'Faltan artefactos. Ejecutá ./scripts/build.sh --bundle.\n' >&2; exit 1; }

mkdir -p -- "$root/dist"
staging="$(mktemp -d)"
trap 'rm -rf -- "$staging"' EXIT
mkdir -p -- "$staging/SoundShare/bin" "$staging/SoundShare/scripts" "$staging/SoundShare/docs"
cp -- "$binary" "$staging/SoundShare/bin/soundshare"
cp -- "$apk" "$staging/SoundShare/SoundShare.apk"
cp -- "$root/soundshare" "$staging/SoundShare/soundshare"
cp -- "$root/scripts/setup.sh" "$staging/SoundShare/scripts/setup.sh"
cp -- "$root/README.md" "$staging/SoundShare/README.md"
cp -- "$root/docs/PROTOCOLO.md" "$staging/SoundShare/docs/PROTOCOLO.md"
cat > "$staging/SoundShare/LEEME.txt" <<'EOF'
1. Instalá SoundShare.apk en el teléfono.
2. Activá Depuración USB en las opciones de desarrollador del teléfono.
3. En esta carpeta de la PC, ejecutá: ./soundshare
4. Conectá el cable USB, aceptá la autorización y elegí el sentido en la app.

El inicio instala adb si falta (puede pedir sudo). No necesita Rust, Java ni Android SDK.
Para preparar las dependencias por separado: ./scripts/setup.sh
PC → Móvil directamente: ./soundshare pc2phone
Móvil → PC directamente: ./soundshare phone2pc
Salir: q + Enter o Ctrl+C.

Requiere Linux con PipeWire y bibliotecas compatibles con la PC que compiló el paquete.
El nombre del archivo indica la distribución y arquitectura de compilación.
Móvil → PC usa APIs internas de Android y fue probado en Android 11.
EOF
os_id=linux
os_version=''
if [[ -r /etc/os-release ]]; then
    source /etc/os-release
    os_id="${ID:-linux}"
    os_version="${VERSION_ID:-}"
fi
archive="$root/dist/SoundShare-${os_id}${os_version}-$(uname -m).tar.gz"
tar -czf "$archive" -C "$staging" SoundShare
cp -- "$apk" "$root/dist/SoundShare.apk"
(cd -- "$root/dist" && sha256sum "${archive##*/}" SoundShare.apk > SHA256SUMS)
printf 'Paquete PC + Android: %s\nAPK para instalar en el teléfono: %s/dist/SoundShare.apk\n' "$archive" "$root"
