"""Smoke tests of a built AppImage: python3 scripts/test_appimage.py [path].

Uses the current user's PipeWire for an idle connection, with a fake phone.
Doesn't switch the audio output, install an APK or contact a real USB device.
"""

import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest


REPO = Path(__file__).resolve().parent.parent
APPIMAGE = Path(sys.argv.pop(1)).resolve() if len(sys.argv) > 1 else REPO / "dist/SoundShare-x86_64.AppImage"


class AppImageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="soundshare appimage ")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        subprocess.run([str(APPIMAGE), "--appimage-extract"], cwd=cls.root,
                       stdout=subprocess.DEVNULL, check=True, timeout=30)
        cls.app_dir = cls.root / "squashfs-root"
        cls.log = cls.root / "adb.jsonl"
        cls.adb = cls.root / "fake adb"
        cls.adb.write_text("""#!/usr/bin/python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ['APPIMAGE_TEST_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\\n')
if args[:2] == ['-s', 'TEST_PHONE']:
    args = args[2:]
if args == ['devices', '-l']:
    print('List of devices attached\\nTEST_PHONE device model:AppImage_Test')
elif args == ['shell', 'pm path com.soundshare']:
    print('package:/data/app/com.soundshare/base.apk')
""")
        cls.adb.chmod(0o755)

    def env(self):
        return dict(os.environ, ADB=str(self.adb), ANDROID_SERIAL="TEST_PHONE",
                    APPIMAGE_TEST_LOG=str(self.log))

    def test_help_without_fuse(self):
        result = subprocess.run([str(APPIMAGE), "--appimage-extract-and-run", "--help"],
                                cwd="/", capture_output=True, text=True, timeout=30, check=True)
        self.assertIn("SoundShare 0.1.0", result.stdout)

    def test_bundled_adb_and_apk(self):
        env = dict(os.environ, LD_LIBRARY_PATH=str(self.app_dir / "usr/lib"))
        result = subprocess.run([str(self.app_dir / "usr/bin/adb"), "version"],
                                env=env, capture_output=True, text=True, check=True)
        self.assertIn("Android Debug Bridge", result.stdout)
        self.assertEqual((self.app_dir / "SoundShare.apk").read_bytes(),
                         (REPO / "dist/SoundShare.apk").read_bytes())
        self.assertTrue((self.app_dir / "usr/lib/spa-0.2/audioconvert/libspa-audioconvert.so").is_file())
        self.assertTrue((self.app_dir / "usr/lib/pipewire-0.3/libpipewire-module-protocol-native.so").is_file())
        self.assertFalse((self.app_dir / "usr/lib/libc.so.6").exists())

    def test_install_uses_embedded_apk(self):
        result = subprocess.run([str(APPIMAGE), "--appimage-extract-and-run", "install"],
                                env=self.env(), cwd="/", capture_output=True,
                                text=True, check=True, timeout=30)
        self.assertIn("✓ Listo", result.stdout)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertTrue(any(call[:4] == ["-s", "TEST_PHONE", "install", "-r"]
                            and call[4].endswith("/SoundShare.apk") for call in calls))

    def test_idle_connection_loads_bundled_pipewire(self):
        self.log.unlink(missing_ok=True)
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        # AppRun is also what the mounted AppImage executes; extraction gives
        # us its PID directly so /proc/maps can check which libraries it uses.
        process = subprocess.Popen([str(self.app_dir / "AppRun"), "-p", str(port)], env=self.env(), cwd="/",
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    self.fail(process.stdout.read())
                if self.log.exists() and "am start -n com.soundshare/.MainActivity" in self.log.read_text():
                    break
                time.sleep(0.05)
            else:
                self.fail("No abrió la app del teléfono simulado")
            maps = Path(f"/proc/{process.pid}/maps").read_text()
            self.assertIn(str(self.app_dir / "usr/lib/libpipewire-0.3.so.0"), maps)
            self.assertIn(str(self.app_dir / "usr/lib/pipewire-0.3/libpipewire-module-protocol-native.so"), maps)
            output, _ = process.communicate("q\n", timeout=5)
            self.assertEqual(process.returncode, 0, output)
            calls = [json.loads(line) for line in self.log.read_text().splitlines()]
            self.assertIn(["-s", "TEST_PHONE", "reverse", f"tcp:{port}", f"tcp:{port}"], calls)
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate()


if __name__ == "__main__":
    unittest.main()
