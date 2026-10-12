"""Pruebas del inicio sin red, sudo ni dispositivos: python3 scripts/test_startup.py."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parent.parent


class StartupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="soundshare inicio ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "pc").mkdir()
        shutil.copy2(REPO / "soundshare", self.root / "soundshare")
        shutil.copy2(REPO / "scripts/setup.sh", self.root / "scripts/setup.sh")
        self.tools = self.root / "tools"
        self.tools.mkdir()
        # PATH aislado: ningún instalador real puede ejecutarse.
        for name in ("bash", "dirname", "readlink", "uname", "grep", "mkdir", "cat"):
            (self.tools / name).symlink_to(shutil.which(name))
        for name in ("cc", "clang", "pw-cli", "wireplumber"):
            self.tool(name, "exit 0")
        self.tool("ldd", "exit 0")
        self.tool("pkg-config", "exit 0")
        self.tool("adb", "exit 0")
        self.tool("dnf", "echo dnf >> \"$TEST_LOG\"; exit 90")
        self.tool("sudo", "echo sudo >> \"$TEST_LOG\"; exit 90")
        self.tool("curl", "echo curl >> \"$TEST_LOG\"; exit 90")
        self.tool("cargo", """
echo build >> "$TEST_LOG"
[[ "$PWD" == "$TEST_ROOT/pc" ]] || exit 91
[[ "$*" == 'build --release --locked' ]] || exit 92
mkdir -p target/release
cat > target/release/soundshare <<'EOF'
#!/usr/bin/env bash
printf 'arg=%s\\n' "$@"
EOF
/bin/chmod +x target/release/soundshare
""")
        self.log = self.root / "calls.log"
        self.env = {
            "PATH": str(self.tools),
            "HOME": str(self.root),
            "TEST_LOG": str(self.log),
            "TEST_ROOT": str(self.root),
        }

    def tool(self, name, body):
        path = self.tools / name
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body + "\n")
        path.chmod(0o755)

    def run_launcher(self, *args):
        return subprocess.run(
            [str(self.root / "soundshare"), *args], cwd="/", env=self.env,
            capture_output=True, text=True, timeout=10,
        )

    def bundled_binary(self):
        (self.root / "bin").mkdir()
        binary = self.root / "bin/soundshare"
        binary.write_text('#!/usr/bin/env bash\nprintf "arg=%s\\n" "$@"\n')
        binary.chmod(0o755)

    def test_source_builds_once_and_forwards_arguments(self):
        for _ in range(2):
            result = self.run_launcher("phone2pc", "-o", "salida con espacios")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("arg=phone2pc\narg=-o\narg=salida con espacios", result.stdout)
        self.assertEqual(self.log.read_text(), "build\n")

    def test_bundle_does_not_require_compilers_or_sdk(self):
        self.bundled_binary()
        for name in ("cargo", "clang", "cc", "pkg-config"):
            (self.tools / name).unlink()
        result = self.run_launcher("pc2phone", "-s", "PHONE")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("arg=pc2phone\narg=-s\narg=PHONE", result.stdout)
        self.assertFalse(self.log.exists())

    def test_help_without_binary_does_not_install_anything(self):
        result = self.run_launcher("--help")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Uso:", result.stdout)
        self.assertFalse(self.log.exists())

    def test_bundle_accepts_adb_from_existing_sdk(self):
        self.bundled_binary()
        (self.tools / "adb").unlink()
        sdk = self.root / "SDK con espacios"
        (sdk / "platform-tools").mkdir(parents=True)
        (sdk / "platform-tools/adb").symlink_to("/bin/true")
        self.env["ANDROID_SDK_ROOT"] = str(sdk)
        result = self.run_launcher("pc2phone")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.log.exists())

    def test_bundle_help_without_adb_does_not_install(self):
        self.bundled_binary()
        (self.tools / "adb").unlink()
        result = self.run_launcher("--help")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.log.exists())

    def test_missing_adb_attempts_runtime_install_without_building(self):
        self.bundled_binary()
        (self.tools / "adb").unlink()
        result = self.run_launcher()
        self.assertEqual(result.returncode, 90)
        self.assertIn("android-tools", result.stdout)
        self.assertNotIn("build", self.log.read_text())
        self.assertNotIn("curl", self.log.read_text())

    def test_failed_source_build_does_not_launch(self):
        self.tool("cargo", "exit 42")
        result = self.run_launcher("pc2phone")
        self.assertEqual(result.returncode, 42)
        self.assertNotIn("arg=", result.stdout)

    def test_setup_rejects_unknown_flags_before_installing(self):
        result = subprocess.run(
            [str(self.root / "scripts/setup.sh"), "--unknown"], env=self.env,
            capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(result.returncode, 1)
        self.assertFalse(self.log.exists())


if __name__ == "__main__":
    unittest.main()
