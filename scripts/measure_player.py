#!/usr/bin/env python3
"""Mide PLAYER sin enviar jamás audio a una salida física de la PC.

Usa PCM continuo de ruido muy tenue (-60 dBFS pico), exclusivamente en soundshare_to_phone.
No acepta diálogos USB por el usuario. Conserva hub/logcat y un resumen JSON reproducible.
"""
import argparse
import array
import json
import os
from pathlib import Path
import random
import re
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


def summarize(logcat, hub):
    rows = []
    initial_targets = re.findall(r"pista:.*?objetivo (\d+)", logcat)
    sessions = []
    current = None
    for line in logcat.splitlines():
        if re.search(r"transporte=\w+, (?:perfil=\w+, )?pista:", line):
            current = []
            sessions.append(current)
        match = re.search(r"diag: transporte=(\w+), hueco máx (\d+) µs, cola mín (-?\d+), objetivo (\d+), cortes (\d+)", line)
        if match and current is not None:
            transport, *values = match.groups()
            row = (transport, *map(int, values))
            rows.append(row)
            current.append(row)
    if not rows:
        return {"error": "No hay diag:; revisar permiso/estado y log.tag.SoundShare.Player"}
    gaps = sorted(row[1] / 1000 for row in rows[5:])  # Separar los 5 s del arranque.
    stable = rows[5:] or rows
    latencies = [float(v) for v in re.findall(r"latencia ≈([\d.]+) ms", hub)]
    percentile = lambda p: gaps[min(len(gaps)-1, int((len(gaps)-1)*p))] if gaps else None
    return {"transportes": sorted(set(row[0] for row in rows)), "segundos_diag": len(rows),
            "hueco_max_por_segundo_mediana_ms": percentile(.5), "hueco_max_por_segundo_p95_ms": percentile(.95),
            "peor_hueco_ms": max(gaps) if gaps else None, "cola_min_frames": min(row[2] for row in stable),
            "objetivo_inicial_frames": int(initial_targets[0]) if initial_targets else None,
            "objetivo_min_frames": min(row[3] for row in stable),
            "objetivo_max_frames": max(row[3] for row in stable),
            "objetivo_final_frames": rows[-1][3], "cortes_iniciales": rows[0][4],
            "cortes_finales": rows[-1][4], "cortes_durante_medicion": sum(s[-1][4] for s in sessions if s),
            "sesiones_player": len(sessions),
            "latencia_hub_mediana_ms": sorted(latencies)[len(latencies)//2] if latencies else None,
            "latencia_hub_final_ms": latencies[-1] if latencies else None}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--transport", choices=["adb", "aoa"], required=True)
    ap.add_argument("--seconds", type=int, default=310)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = ["adb", "-s", args.serial]
    subprocess.run(adb+["shell", "setprop log.tag.SoundShare.Player DEBUG"], check=True)
    # Un nivel bajo del parlante; no se modifica ningún dispositivo de salida de la laptop.
    subprocess.run(adb+["shell", "cmd media_session volume --stream 3 --set 1"], check=True)
    subprocess.run(adb+["shell", "am force-stop com.soundshare"], check=True)
    processes = []
    stopped = threading.Event()
    previous_sink = subprocess.check_output(["pactl","get-default-sink"],text=True).strip()
    guard = subprocess.check_output(["pactl","load-module","module-null-sink","sink_name=soundshare_test_guard"],text=True).strip()
    # Si desaparece el sink del hub, una migración automática sigue siendo silenciosa.
    subprocess.run(["pactl","set-default-sink","soundshare_test_guard"],check=True)
    try:
        with (args.output/"hub.log").open("w") as hublog, (args.output/"logcat.log").open("w") as catlog:
            env = dict(os.environ, SOUNDSHARE_DEBUG="1", SOUNDSHARE_PLAYER_TRANSPORT=args.transport)
            hub = subprocess.Popen([str(ROOT/"pc/target/release/soundshare"),"-s",args.serial], stdin=subprocess.PIPE, stdout=hublog, stderr=subprocess.STDOUT, env=env)
            processes.append(hub)
            until=time.monotonic()+60
            while "● App conectada:" not in (args.output/"hub.log").read_text():
                if hub.poll() is not None: raise RuntimeError("El hub terminó antes de iniciar")
                if time.monotonic()>until: raise RuntimeError("CONTROL no conectó; revisar diálogo/permisos USB")
                time.sleep(.5)
            # START reinicia adb y corta logcat: abrir el registro después de la conmutación.
            logcat = subprocess.Popen(adb+["logcat", "-v", "epoch", "-T", "1", "SoundShare.Player:D", "SoundShare.AOA:D", "SoundShare.Link:I", "*:S"], stdout=catlog, stderr=subprocess.STDOUT)
            processes.append(logcat)
            time.sleep(.5)
            hub.stdin.write(b"1\n");hub.stdin.flush()
            until=time.monotonic()+15
            while True:
                sinks=subprocess.check_output(["pactl","list","short","sinks"], text=True)
                if any(line.split()[1]=="soundshare_to_phone" for line in sinks.splitlines()): break
                if time.monotonic()>until: raise RuntimeError("No apareció el sink de prueba")
                time.sleep(.2)
            player=subprocess.Popen(["pacat","--playback","--raw","--format=float32le","--rate=48000","--channels=2","--device=soundshare_to_phone"], stdin=subprocess.PIPE)
            processes.append(player)
            random.seed(1)
            pcm=array.array('f',(random.uniform(-.001,.001) for _ in range(4800*2))).tobytes()
            def feed():
                try:
                    while not stopped.is_set(): player.stdin.write(pcm); player.stdin.flush()
                except (BrokenPipeError, ValueError): pass
            worker=threading.Thread(target=feed, daemon=True); worker.start()
            started=time.monotonic()
            while time.monotonic()-started<args.seconds:
                if hub.poll() is not None or player.poll() is not None: raise RuntimeError("Se interrumpió el flujo de prueba")
                time.sleep(1)
            stopped.set()
            player.terminate();player.wait(timeout=5)
            hub.stdin.write(b"q\n");hub.stdin.flush();hub.wait(timeout=10)
            logcat.terminate();logcat.wait(timeout=5)
    finally:
        stopped.set()
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired: process.kill();process.wait()
        for process in processes:
            if process.stdin is not None:
                try: process.stdin.close()
                except BrokenPipeError: pass
        subprocess.run(["pactl","set-default-sink",previous_sink],check=False)
        subprocess.run(["pactl","unload-module",guard],check=False)
    result=summarize((args.output/"logcat.log").read_text(),(args.output/"hub.log").read_text())
    result.update(serial=args.serial,requested_transport=args.transport,seconds=args.seconds)
    (args.output/"summary.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__ == "__main__": main()
