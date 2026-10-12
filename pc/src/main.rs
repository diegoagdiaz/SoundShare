//! SoundShare para Linux: comparte audio con un teléfono Android por cable USB.
//!
//!   soundshare                 espera al teléfono; el modo se elige desde la app o acá
//!   soundshare pc2phone        arranca directamente en PC → Móvil
//!   soundshare phone2pc        arranca directamente en Móvil → PC
//!   soundshare install [apk]   instala la app en el teléfono

mod adb;
mod control;
mod engine;
mod hub;
mod protocol;
mod ring;

use std::io::BufRead;
use std::net::TcpListener;
use std::path::PathBuf;
use std::process::exit;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use adb::Adb;
use engine::Engine;
use hub::Hub;
use protocol::{Mode, PORT};

struct Args {
    serial: Option<String>,
    output: Option<String>,
    port: u16,
    start_mode: Option<Mode>,
    install: Option<Option<PathBuf>>,
}

fn usage() -> ! {
    println!(
        "SoundShare {} — audio entre la PC y Android por cable USB

Uso:
  soundshare [pc2phone|phone2pc] [-s SERIAL] [-p PUERTO] [-o SALIDA]
  soundshare install [ruta.apk] [-s SERIAL]

  -o SALIDA  dónde suena el audio del teléfono (node.name de PipeWire,
             ver «pactl list short sinks»); por defecto, la salida actual

Mientras corre, escribí en la terminal:
  1  PC → Móvil      2  Móvil → PC      0  apagar
  s  mostrar/ocultar estadísticas       q  salir",
        env!("CARGO_PKG_VERSION")
    );
    exit(0)
}

fn parse_args() -> Args {
    let mut args = Args {
        serial: std::env::var("ANDROID_SERIAL").ok(),
        output: std::env::var("SOUNDSHARE_OUTPUT").ok(),
        port: PORT,
        start_mode: None,
        install: None,
    };
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "-h" | "--help" => usage(),
            "-s" | "--serial" => args.serial = it.next(),
            "-o" | "--output" => args.output = Some(it.next().unwrap_or_else(|| usage())),
            "-p" | "--port" => args.port = it.next().and_then(|p| p.parse().ok()).unwrap_or_else(|| usage()),
            "install" => args.install = Some(None),
            "pc2phone" | "pc" => args.start_mode = Some(Mode::PcToPhone),
            "phone2pc" | "movil" | "móvil" => args.start_mode = Some(Mode::PhoneToPc),
            other if args.install.is_some() => args.install = Some(Some(other.into())),
            other => {
                eprintln!("Argumento desconocido: {other}");
                usage()
            }
        }
    }
    args
}

fn die(msg: &str) -> ! {
    eprintln!("✗ {msg}");
    exit(1)
}

/// Bloquea SIGINT/SIGTERM en todos los hilos; uno solo los atiende para salir en orden.
fn block_signals() -> libc::sigset_t {
    unsafe {
        let mut set: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut set);
        libc::sigaddset(&mut set, libc::SIGINT);
        libc::sigaddset(&mut set, libc::SIGTERM);
        libc::sigaddset(&mut set, libc::SIGHUP);
        libc::pthread_sigmask(libc::SIG_BLOCK, &set, std::ptr::null_mut());
        set
    }
}

fn pick_device(adb: &Adb, serial: Option<&str>) -> Result<Option<adb::Device>, String> {
    let devices = adb.devices().map_err(|e| e.to_string())?;
    Ok(devices.into_iter().find(|d| serial.is_none_or(|s| s == d.serial)))
}

/// Busca la APK compilada junto al proyecto.
fn default_apk() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let candidates = ["android/app/build/outputs/apk/release/app-release.apk", "android/app/build/outputs/apk/debug/app-debug.apk"];
    exe.ancestors()
        .flat_map(|dir| candidates.iter().map(move |c| dir.join(c)))
        .filter_map(|p| {
            let meta = p.metadata().ok()?;
            if !meta.is_file() { return None; }
            Some((meta.modified().ok()?, p))
        })
        .max_by_key(|(modified, _)| *modified)
        .map(|(_, p)| p)
}

fn install(adb: &Adb, serial: Option<&str>, apk: Option<PathBuf>) -> ! {
    let apk = apk.or_else(default_apk).unwrap_or_else(|| die("No encontré la APK: pasá la ruta (soundshare install app.apk)"));
    let device = match pick_device(adb, serial) {
        Ok(Some(d)) if d.state == "device" => d,
        Ok(_) => die("No hay ningún teléfono listo (¿depuración USB activada y autorizada?)"),
        Err(e) => die(&e),
    };
    println!("Instalando {} en {}…", apk.display(), device.model);
    match adb.install(&device.serial, &apk) {
        Ok(()) => {
            println!("✓ Listo");
            exit(0)
        }
        Err(e) => die(&e.to_string()),
    }
}

/// Vigila el cable: cuando aparece el teléfono abre el túnel, cuando se va apaga todo.
fn watch_devices(hub: Arc<Hub>, adb: Adb, serial: Option<String>, port: u16, start_mode: Option<Mode>) {
    let mut current: Option<adb::Device> = None;
    let mut warned = String::new();
    let mut start_mode = start_mode;
    loop {
        let found = match pick_device(&adb, serial.as_deref()) {
            Ok(d) => d,
            Err(e) => {
                if warned != e {
                    eprintln!("✗ adb: {e}");
                    warned = e;
                }
                None
            }
        };
        let ready = found.clone().filter(|d| d.state == "device");
        if let Some(d) = &found
            && d.state != "device"
            && warned != d.state
        {
            match d.state.as_str() {
                "unauthorized" => println!("⚠ Aceptá la depuración USB en la pantalla del teléfono."),
                s => println!("⚠ El teléfono está en estado «{s}»."),
            }
            warned = d.state.clone();
        }

        if ready.as_ref().map(|d| &d.serial) != current.as_ref().map(|d| &d.serial) {
            match &ready {
                Some(d) => match adb.reverse(&d.serial, port) {
                    Ok(()) => {
                        println!("● Teléfono conectado: {} ({})", d.model, d.serial);
                        match adb.package_path(&d.serial) {
                            Ok(Some(_)) => println!("  Abrí SoundShare en el teléfono o elegí el modo acá (1 / 2)."),
                            _ => println!("⚠ La app no está instalada: ejecutá «soundshare install»."),
                        }
                        current = ready.clone();
                        warned.clear();
                        hub.device_changed(ready.clone());
                        if let Some(m) = start_mode.take() {
                            hub.set_mode(m);
                        }
                    }
                    Err(e) => eprintln!("✗ No pude abrir el túnel USB: {e}"),
                },
                None => {
                    println!("○ Teléfono desconectado");
                    current = None;
                    hub.device_changed(None);
                }
            }
        }
        thread::sleep(Duration::from_secs(1));
    }
}

fn main() {
    let args = parse_args();
    let sigset = block_signals();

    let adb = Adb::find().unwrap_or_else(|| {
        die("No encontré adb. Instalá platform-tools o indicá la ruta con ADB=/ruta/a/adb")
    });
    if let Some(apk) = args.install {
        install(&adb, args.serial.as_deref(), apk);
    }

    let listener = TcpListener::bind(("127.0.0.1", args.port))
        .unwrap_or_else(|e| die(&format!("No pude escuchar en el puerto {}: {e} (¿ya hay otro SoundShare abierto?)", args.port)));
    let (engine, errors) = Engine::start().unwrap_or_else(|e| die(&format!("No pude conectarme a PipeWire: {e}")));
    let hub = Arc::new(Hub::new(adb.clone(), engine, args.port, args.output.clone()));
    {
        let hub = hub.clone();
        thread::spawn(move || hub.engine_errors(errors));
    }

    println!("SoundShare {} · esperando al teléfono por USB… (escribí «h» para ver los comandos)", env!("CARGO_PKG_VERSION"));

    {
        let hub = hub.clone();
        thread::spawn(move || {
            let mut sig: libc::c_int = 0;
            unsafe { libc::sigwait(&sigset, &mut sig) };
            println!("\nSaliendo…");
            hub.shutdown();
            exit(0);
        });
    }
    {
        let hub = hub.clone();
        thread::spawn(move || {
            for conn in listener.incoming().flatten() {
                let hub = hub.clone();
                thread::spawn(move || hub.handle(conn));
            }
        });
    }
    {
        let hub = hub.clone();
        thread::spawn(move || hub.stats_loop());
    }
    {
        let hub = hub.clone();
        let (serial, port, mode) = (args.serial.clone(), args.port, args.start_mode);
        thread::spawn(move || watch_devices(hub, adb, serial, port, mode));
    }

    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        match line.trim() {
            "1" => hub.set_mode(Mode::PcToPhone),
            "2" => hub.set_mode(Mode::PhoneToPc),
            "0" => hub.set_mode(Mode::Off),
            "s" => println!("Estadísticas {}", if hub.toggle_stats() { "visibles" } else { "ocultas" }),
            "q" => break,
            "" => {}
            _ => println!("1 PC → Móvil · 2 Móvil → PC · 0 apagar · s estadísticas · q salir (modo actual: {})", hub.mode().label()),
        }
    }
    hub.shutdown();
}
