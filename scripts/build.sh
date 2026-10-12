#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Uso: scripts/build.sh [--pc | --android] [--install] [--bundle]
  Sin flags: compila la PC en release y la APK debug.
  --pc       Compila solo la PC.
  --android  Compila solo la APK.
  --install  Después de compilar, ejecuta soundshare install.
             Si omitís una parte, sus artefactos deben existir previamente.
  --bundle   Genera dist/SoundShare.apk y un .tar.gz listo para usar en la PC.
             Si omitís una parte, sus artefactos deben existir previamente.
  -h, --help Muestra esta ayuda.
EOF
}

build_pc=true
build_android=true
selection=''
install_app=false
bundle=false
for arg in "$@"; do
    case "$arg" in
        --pc|--android)
            if [[ -n "$selection" && "$selection" != "$arg" ]]; then
                fail '--pc y --android son excluyentes.'
            fi
            selection="$arg"
            ;;
        --install) install_app=true ;;
        --bundle) bundle=true ;;
        -h|--help) usage; exit 0 ;;
        *) fail "Opción desconocida: $arg. Usá --help para ver la ayuda." ;;
    esac
done
case "$selection" in
    --pc) build_android=false ;;
    --android) build_pc=false ;;
esac

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

# javac distingue un JDK de un JRE; también permite leer su versión mayor.
valid_jdk() {
    local candidate="$1" version major
    [[ -x "$candidate/bin/java" && -x "$candidate/bin/javac" ]] || return 1
    version="$("$candidate/bin/javac" -version 2>&1)" || return 1
    if [[ "$version" =~ javac[[:space:]]+([0-9]+)(\.([0-9]+))? ]]; then
        major="${BASH_REMATCH[1]}"
        if [[ "$major" == 1 ]]; then
            major="${BASH_REMATCH[3]:-0}"
        fi
        (( major >= 17 ))
    else
        return 1
    fi
}

if $build_pc; then
    command -v cargo >/dev/null 2>&1 || fail 'Falta Cargo. Instalá Rust estable actualizado.'
    if ! command -v pkg-config >/dev/null 2>&1 || ! pkg-config --exists libpipewire-0.3; then
        fail 'Faltan pkg-config o las cabeceras de PipeWire. En Fedora: sudo dnf install pipewire-devel clang pkgconf-pkg-config'
    fi
fi

if $build_android; then
    if [[ -z "${JAVA_HOME:-}" ]]; then
        shopt -s nullglob
        jdk_candidates=("$HOME"/.gradle/jdks/* "$HOME"/.gradle/jdks/*/Contents/Home /usr/lib/jvm/*)
        shopt -u nullglob
        for candidate in "${jdk_candidates[@]}"; do
            if valid_jdk "$candidate"; then
                export JAVA_HOME="$candidate"
                break
            fi
        done
        [[ -n "${JAVA_HOME:-}" ]] || fail 'No encontré un JDK con javac ≥ 17. Instalalo y configurá JAVA_HOME.'
        printf 'JDK encontrado: %s\n' "$JAVA_HOME"
    elif ! valid_jdk "$JAVA_HOME"; then
        fail 'JAVA_HOME debe apuntar a un JDK con java y javac de versión 17 o superior.'
    fi
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"

    if [[ ! -e "$repo_root/android/local.properties" ]]; then
        sdk_dir=''
        if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
            sdk_dir="$ANDROID_HOME"
        elif [[ -d "$HOME/Android/Sdk" ]]; then
            sdk_dir="$HOME/Android/Sdk"
        fi
        [[ -n "$sdk_dir" ]] || fail 'No encontré el Android SDK. Configurá ANDROID_HOME o android/local.properties (sdk.dir).'
        sdk_dir="$(cd -- "$sdk_dir" && pwd)"
        # Escapes de java.util.Properties para rutas con barras inversas o espacios.
        sdk_property="${sdk_dir//\\/\\\\}"
        sdk_property="${sdk_property// /\\ }"
        sdk_property="${sdk_property//:/\\:}"
        printf 'sdk.dir=%s\n' "$sdk_property" > "$repo_root/android/local.properties"
        printf 'Configuración del SDK creada: %s\n' "$sdk_dir"
    fi
fi

if $build_pc; then
    printf 'Compilando SoundShare para la PC (release)…\n'
    (cd -- "$repo_root/pc" && cargo build --release --locked)
    printf 'Ejecutable: %s/pc/target/release/soundshare\n' "$repo_root"
fi

if $build_android; then
    printf 'Compilando la app Android (debug)…\n'
    (cd -- "$repo_root/android" && ./gradlew assembleDebug)
    printf 'APK: %s/android/app/build/outputs/apk/debug/app-debug.apk\n' "$repo_root"
fi

if $install_app; then
    [[ -x "$repo_root/pc/target/release/soundshare" ]] || fail 'Falta el ejecutable de la PC. Compilalo sin --android o con --pc.'
    printf 'Instalando la app en el teléfono…\n'
    "$repo_root/pc/target/release/soundshare" install
fi

if $bundle; then
    "$repo_root/scripts/package.sh"
fi

printf 'Listo.\n'
