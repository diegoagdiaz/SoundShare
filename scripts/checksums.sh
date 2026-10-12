#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$root/dist"
shopt -s nullglob
files=(SoundShare.apk SoundShare-*.tar.gz SoundShare-*.AppImage)
sha256sum -- "${files[@]}" > SHA256SUMS
