"""Verify the shell capturer on a real Android device without installing the APK.

python3 scripts/test_capture.py --serial SERIAL
Temporarily redirects media audio for three seconds. Counts PCM in memory;
doesn't save audio or change the media volume. Requires a built debug APK.
"""

import argparse
import os
from pathlib import Path
import socket
import struct
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, default=Path(__file__).resolve().parent.parent /
                        "android/app/build/outputs/apk/debug/app-debug.apk")
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error("Primero compilá la APK con ./scripts/build.sh --android")
    adb = [os.environ.get("ADB", "adb"), "-s", args.serial]
    sdk = int(subprocess.check_output(adb + ["shell", "getprop", "ro.build.version.sdk"], text=True).strip())
    if sdk < 30:
        parser.error("La captura necesita Android 11 o posterior")
    remote = f"/data/local/tmp/soundshare-probe-{uuid.uuid4().hex}.apk"
    process = None
    connection = None
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(8)
        port = listener.getsockname()[1]
        try:
            subprocess.run(adb + ["push", str(args.apk.resolve()), remote], check=True, capture_output=True)
            subprocess.run(adb + ["reverse", f"tcp:{port}", f"tcp:{port}"], check=True, capture_output=True)
            command = f"CLASSPATH={remote} app_process / com.soundshare.capture.CaptureMain --port {port} --keep-volume --debug"
            process = subprocess.Popen(adb + ["shell", command], stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True)
            connection, _ = listener.accept()
            connection.settimeout(5)

            def read_exact(size):
                data = b""
                while len(data) < size:
                    chunk = connection.recv(size - len(data))
                    if not chunk:
                        raise RuntimeError("El capturador cerró antes de enviar PCM")
                    data += chunk
                return data

            assert read_exact(8) == b"SNDS\x01\x03\x00\x00"
            fmt = read_exact(12)
            assert struct.unpack("<I", fmt[:4])[0] == 48000 and fmt[4:6] == bytes([2, 1]), fmt
            total = 0
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                chunk = connection.recv(32768)
                if not chunk:
                    break
                total += len(chunk)
            connection.close()
            connection = None
            output, _ = process.communicate(timeout=8)
            print(output, end="")
            assert process.returncode == 0, process.returncode
            assert "capturando 48000 Hz" in output and "error:" not in output, output
            if sdk >= 31:
                assert "actividad visible" not in output, output
            assert total >= 48000 * 4 and total % 4 == 0, total
            print(f"API {sdk}: {total // 4} frames PCM recibidos; inicio y salida correctos.")
        finally:
            if connection is not None:
                connection.close()
            if process is not None:
                if process.poll() is None:
                    process.kill()
                output, _ = process.communicate()
                if process.returncode:
                    print(output, end="")
            subprocess.run(adb + ["reverse", "--remove", f"tcp:{port}"], capture_output=True)
            subprocess.run(adb + ["shell", "rm", "-f", remote], capture_output=True)


if __name__ == "__main__":
    main()
