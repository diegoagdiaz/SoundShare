"""Build the AppDir, including dlopen plugins and non-glibc dependencies."""

from pathlib import Path
import re
import shutil
import subprocess
import sys


# Use the host's glibc/loader; copying them would break its ABI and NSS setup.
HOST_LIBS = re.compile(r"^(?:ld-linux.*|lib(?:c|m|pthread|dl|rt|resolv|util|anl)\.so\..*)$")


def dependencies(binary):
    output = subprocess.check_output(["ldd", str(binary)], text=True)
    if "not found" in output:
        raise RuntimeError(f"Missing dependency of {binary}:\n{output}")
    for line in output.splitlines():
        match = re.match(r"\s*(\S+)\s+=>\s+(/\S+)\s+\(", line)
        if match and not HOST_LIBS.fullmatch(match[1]):
            yield match[1], Path(match[2])


def bundle(app_dir, binary):
    source = Path(__file__).resolve().parent
    if app_dir.exists():
        shutil.rmtree(app_dir)
    lib_dir = app_dir / "usr/lib"
    bin_dir = app_dir / "usr/bin"
    licenses = app_dir / "usr/share/doc/soundshare/licenses"
    for directory in (lib_dir, bin_dir, licenses):
        directory.mkdir(parents=True)
    shutil.copy2(binary, bin_dir / "soundshare")
    shutil.copy2("/usr/bin/adb", bin_dir / "adb")
    for name in ("AppRun", "soundshare.desktop", "soundshare.svg"):
        shutil.copy2(source / name, app_dir / name)
    shutil.copy2("/source/README.md", app_dir / "README.md")
    # The included APK also makes `./SoundShare-x86_64.AppImage install` work.
    shutil.copy2("/source/android/app/build/outputs/apk/debug/app-debug.apk", app_dir / "SoundShare.apk")
    shutil.copytree("/opt/pipewire/licenses", licenses / "pipewire")
    for name in ("pipewire-0.3", "spa-0.2"):
        shutil.copytree(Path("/opt/pipewire/lib") / name, lib_dir / name)
    shutil.copytree("/opt/pipewire/share/pipewire", app_dir / "usr/share/pipewire")
    # Recursively include dependencies of adb, the client and its plugins.
    queue = [bin_dir / "soundshare", bin_dir / "adb", *lib_dir.rglob("*.so")]
    seen = set()
    dependency_paths = [Path("/usr/bin/adb")]
    while queue:
        for name, path in dependencies(queue.pop()):
            if name in seen:
                continue
            seen.add(name)
            destination = lib_dir / name
            shutil.copy2(path.resolve(), destination)
            dependency_paths.append(path)
            queue.append(destination)
    # Debian copyright files contain the licenses for the packaged shared libraries.
    packages = set()
    for path in dependency_paths:
        # Debian 12 uses usrmerge, while dpkg can record the older /lib path.
        candidates = {str(path), str(path.resolve())}
        candidates.update(value.removeprefix("/usr") for value in list(candidates) if value.startswith("/usr/lib/"))
        for candidate in candidates:
            owner = subprocess.run(["dpkg-query", "-S", candidate], capture_output=True, text=True)
            if owner.returncode == 0:
                packages.add(owner.stdout.split(": ", 1)[0].split(":", 1)[0])
                break
    for package in packages:
        copyright_file = Path("/usr/share/doc") / package / "copyright"
        if copyright_file.is_file():
            shutil.copy2(copyright_file, licenses / f"{package}.txt")
    shutil.copytree("/usr/share/common-licenses", licenses / "common-licenses")
    for rust_licenses in Path("/usr/local/rustup/toolchains").glob("*/share/doc/rust/licenses"):
        shutil.copytree(rust_licenses, licenses / "rust", dirs_exist_ok=True)
    for license_file in Path("/build/cargo/registry/src").glob("*/*/LICENSE*"):
        if license_file.is_file():
            crate_dir = licenses / "rust-crates" / license_file.parent.name
            crate_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(license_file, crate_dir / license_file.name)
    runtime_license = Path("/opt/appimage-runtime.LICENSE")
    if runtime_license.is_file():
        shutil.copy2(runtime_license, licenses / "appimage-runtime.txt")
    print(f"AppDir: {app_dir}; {len(seen)} shared libraries included")


if __name__ == "__main__":
    bundle(Path(sys.argv[1]), Path(sys.argv[2]))
