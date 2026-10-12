#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
case "${1:-}" in
    -h|--help)
        printf 'Uso: scripts/setup.sh\nPrepara la PC para ./soundshare. Usa el binario del paquete o compila la PC.\nInstala las dependencias faltantes en Fedora y Debian/Ubuntu (puede pedir sudo).\nNo compila Android ni instala nada en el teléfono.\n'
        exit 0 ;;
    '') ;;
    *) fail 'Opción desconocida. Usá --help.' ;;
esac
[[ $# -le 1 ]] || fail 'Demasiados argumentos.'
[[ "$(uname -s)" == Linux ]] || fail 'SoundShare requiere una PC Linux con PipeWire.'

binary="$root/bin/soundshare"
if [[ ! -x "$binary" ]]; then
    binary="$root/pc/target/release/soundshare"
fi
build=false
[[ -x "$binary" ]] || build=true

adb_ready=false
if [[ -n "${ADB:-}" && -x "$ADB" ]] || command -v adb >/dev/null 2>&1; then
    adb_ready=true
else
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        if [[ -n "$sdk" && -x "$sdk/platform-tools/adb" ]]; then
            adb_ready=true
            break
        fi
    done
fi

packages=()
if command -v dnf >/dev/null 2>&1; then
    manager=dnf
    $adb_ready || packages+=(android-tools)
    if $build; then
        command -v cc >/dev/null 2>&1 || packages+=(gcc)
        command -v clang >/dev/null 2>&1 || packages+=(clang)
        command -v pkg-config >/dev/null 2>&1 || packages+=(pkgconf-pkg-config)
        if ! pkg-config --atleast-version=1.2 libpipewire-0.3 2>/dev/null; then
            packages+=(pipewire-devel)
        fi
    elif ldd "$binary" 2>&1 | grep -q 'libpipewire-0.3.so.0 => not found'; then
        packages+=(pipewire-libs)
    fi
elif command -v apt-get >/dev/null 2>&1; then
    manager=apt-get
    $adb_ready || packages+=(adb)
    if $build; then
        command -v cc >/dev/null 2>&1 || packages+=(build-essential)
        if ! command -v clang >/dev/null 2>&1 || { [[ -z "${LIBCLANG_PATH:-}" ]] && ! ldconfig -p 2>/dev/null | grep -E 'libclang(-[0-9]+)?\.so' >/dev/null; }; then
            packages+=(clang libclang-dev)
        fi
        command -v pkg-config >/dev/null 2>&1 || packages+=(pkg-config)
        if ! pkg-config --atleast-version=1.2 libpipewire-0.3 2>/dev/null; then
            packages+=(libpipewire-0.3-dev libspa-0.2-dev)
        fi
    elif ldd "$binary" 2>&1 | grep -q 'libpipewire-0.3.so.0 => not found'; then
        # pipewire depende del paquete de bibliotecas correspondiente a esta versión de Debian/Ubuntu.
        packages+=(pipewire)
    fi
else
    manager=''
    $adb_ready || fail 'Instalá adb con el gestor de paquetes de tu distribución y volvé a ejecutar ./soundshare.'
fi

# Instalar PipeWire no cambia ni reinicia la sesión de audio del usuario.
if [[ -n "$manager" ]]; then
    command -v pw-cli >/dev/null 2>&1 || packages+=(pipewire)
    command -v wireplumber >/dev/null 2>&1 || packages+=(wireplumber)
    if $build && ! command -v cargo >/dev/null 2>&1 && [[ ! -x "${CARGO_HOME:-$HOME/.cargo}/bin/cargo" ]]; then
        command -v curl >/dev/null 2>&1 || packages+=(curl)
        [[ -f /etc/ssl/certs/ca-certificates.crt || -f /etc/pki/tls/certs/ca-bundle.crt ]] || packages+=(ca-certificates)
    fi
fi
if (( ${#packages[@]} )); then
    printf 'Instalando dependencias de la PC: %s\n' "${packages[*]}"
    elevate=()
    if (( EUID != 0 )); then
        command -v sudo >/dev/null 2>&1 || fail 'Falta sudo para instalar las dependencias del sistema.'
        elevate=(sudo)
    fi
    if [[ "$manager" == apt-get ]]; then
        "${elevate[@]}" apt-get update
    fi
    "${elevate[@]}" "$manager" install -y "${packages[@]}"
fi

if $build; then
    export PATH="$PATH:${CARGO_HOME:-$HOME/.cargo}/bin"
    if ! command -v cargo >/dev/null 2>&1; then
        command -v curl >/dev/null 2>&1 || fail 'Falta curl para instalar Rust.'
        printf 'Instalando Rust para compilar la PC…\n'
        rust_installer="$(mktemp)"
        trap 'rm -f -- "$rust_installer"' EXIT
        curl --proto '=https' --tlsv1.2 -fsSL https://sh.rustup.rs -o "$rust_installer"
        sh "$rust_installer" -y --profile minimal --no-modify-path
    fi
    pkg-config --atleast-version=1.2 libpipewire-0.3 2>/dev/null || fail 'Para compilar se necesitan cabeceras de PipeWire ≥ 1.2. Actualizá la distribución o usá el paquete precompilado.'
    printf 'Compilando SoundShare para la PC (solo la primera vez)…\n'
    (cd -- "$root/pc" && cargo build --release --locked)
fi

if ldd "$binary" 2>&1 | grep -q 'not found'; then
    ldd "$binary" >&2
    fail 'El ejecutable necesita bibliotecas de una distribución compatible. Usá el código fuente para compilar en esta PC.'
fi
printf 'PC lista. Instalá SoundShare.apk en el teléfono, autorizá la depuración USB y ejecutá ./soundshare.\n'
