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
cp -- "$root/scripts/bluetooth.sh" "$staging/SoundShare/scripts/bluetooth.sh"
cp -- "$root/README.md" "$staging/SoundShare/README.md"
cp -- "$root/docs/PROTOCOLO.md" "$staging/SoundShare/docs/PROTOCOLO.md"
cp -- "$root/docs/INALAMBRICO.md" "$staging/SoundShare/docs/INALAMBRICO.md"
cp -- "$root/docs/MEDICION_WIFI.md" "$staging/SoundShare/docs/MEDICION_WIFI.md"
cat > "$staging/SoundShare/LEEME.txt" <<'EOF'
1. Activá Depuración USB en las opciones de desarrollador del teléfono.
2. En esta carpeta de la PC, ejecutá: ./soundshare
3. Conectá el cable USB y aceptá la autorización de depuración e instalación si aparece.
4. SoundShare instala automáticamente la app requerida y la abre: elegí el sentido del audio.
5. Para WiFi nativo, desconectá el cable después del primer emparejamiento y abrí
   SoundShare en el teléfono, conectado a la misma red que la PC.

El inicio instala adb si falta (puede pedir sudo). No necesita Rust, Java ni Android SDK.
Para preparar las dependencias por separado: ./scripts/setup.sh
Instalación manual: ./soundshare install [ruta.apk]
Para desactivar la instalación automática: SOUNDSHARE_NO_AUTO_INSTALL=1 ./soundshare
PC → Móvil directamente: ./soundshare pc2phone
Móvil → PC directamente: ./soundshare phone2pc
Salir: q + Enter o Ctrl+C.

WiFi nativo: TCP 47101 y UDP 47101; --no-wifi desactiva los puertos de red.
Móvil → PC por WiFi requiere Android 10+ y autorización de captura de audio.
Bluetooth Móvil → PC: emparejá desde Linux y Android y usá
./soundshare bluetooth list y ./soundshare bluetooth connect MAC.
En Android: Bluetooth · Móvil → PC → Usar Bluetooth selecciona esa ruta y detiene
el audio USB/WiFi. Ajustes Bluetooth permite elegir la PC como salida multimedia.
Más detalles: docs/INALAMBRICO.md.

Requiere Linux con PipeWire y bibliotecas compatibles con la PC que compiló el paquete.
El nombre del archivo indica la distribución y arquitectura de compilación.
Móvil → PC usa APIs internas de Android; captura verificada en Android 11 y Android 16.
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
# La copia versionada en la raíz es la que instala ./soundshare desde el repo.
cp -- "$apk" "$root/SoundShare.apk"
"$root/scripts/checksums.sh"
printf 'Paquete PC + Android: %s\nAPK para instalar en el teléfono: %s/dist/SoundShare.apk\n' "$archive" "$root"
