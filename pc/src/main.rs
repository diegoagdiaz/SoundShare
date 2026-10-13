//! SoundShare para Linux: comparte audio con un teléfono Android por cable USB o WiFi.
//!
//!   soundshare                 espera al teléfono; el modo se elige desde la app o acá
//!   soundshare pc2phone        arranca directamente en PC → Móvil
//!   soundshare phone2pc        arranca directamente en Móvil → PC
//!   soundshare install [apk]   instala la app en el teléfono
//!   soundshare adb …           ejecuta el adb incluido (p. ej. `adb pair` para depuración inalámbrica)
//!   soundshare bluetooth …     conecta el audio Bluetooth del teléfono a la PC

mod adb;
mod aoa;
mod aoa_frame;
mod player_transport;
mod control;
mod engine;
mod hub;
mod protocol;
mod ring;
mod source_target;
mod wifi;

use std::io::BufRead;
use std::net::{TcpListener, UdpSocket};
use std::path::PathBuf;
use std::process::exit;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use adb::Adb;
use engine::Engine;
use hub::Hub;
use protocol::{Mode, PORT};
use wifi::Wifi;

const REQUIRED_APP_VERSION_CODE: u32 = 9;
const REQUIRED_APP_VERSION_NAME: &str = "0.4.2";

struct Args {
    serial: Option<String>,
    output: Option<String>,
    port: u16,
    start_mode: Option<Mode>,
    install: Option<Option<PathBuf>>,
    wifi: bool,
}

fn usage() -> ! {
    println!(
        "SoundShare {} — audio entre la PC y Android por cable USB o WiFi

Uso:
  soundshare [pc2phone|phone2pc] [-s SERIAL] [-p PUERTO] [-o SALIDA] [--no-wifi]
  soundshare install [ruta.apk] [-s SERIAL]
  soundshare adb pair IP:PUERTO CÓDIGO   (depuración inalámbrica, Android 11+)
  soundshare bluetooth [status|list|connect MAC|disconnect MAC]

Al conectar, instala o actualiza la app a la versión requerida si encuentra la APK.
Activá y autorizá la depuración USB en el teléfono.
SOUNDSHARE_NO_AUTO_INSTALL=1 desactiva la instalación automática.

WiFi: conectá el teléfono una vez por USB para emparejarlo; después la app encuentra
esta PC en la misma red sin cable. Usa los puertos TCP PUERTO+1 y UDP 47101.

Bluetooth Móvil → PC: emparejá desde Linux y Android; luego usá
«soundshare bluetooth list» y «soundshare bluetooth connect MAC».

  -o SALIDA  dónde suena el audio del teléfono (node.name de PipeWire,
             ver «pactl list short sinks»); por defecto, la salida actual
  --no-wifi  sólo USB: no abre puertos de red (también SOUNDSHARE_WIFI=0)

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
        wifi: std::env::var("SOUNDSHARE_WIFI").as_deref() != Ok("0"),
    };
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "-h" | "--help" => usage(),
            "-s" | "--serial" => args.serial = it.next(),
            "-o" | "--output" => args.output = Some(it.next().unwrap_or_else(|| usage())),
            "-p" | "--port" => args.port = it.next().and_then(|p| p.parse().ok()).unwrap_or_else(|| usage()),
            "--no-wifi" => args.wifi = false,
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
    select_device(adb.devices().map_err(|e| e.to_string())?, serial)
}

fn select_device(devices: Vec<adb::Device>, serial: Option<&str>) -> Result<Option<adb::Device>, String> {
    if let Some(serial) = serial {
        return Ok(devices.into_iter().find(|d| d.serial == serial));
    }
    // El cable gana: el mismo teléfono puede estar a la vez por depuración inalámbrica.
    let cable = devices.iter().any(|d| d.usb);
    let devices: Vec<_> = devices.into_iter().filter(|d| d.usb || !cable).collect();
    if devices.len() > 1 {
        let serials = devices.iter().map(|d| d.serial.as_str()).collect::<Vec<_>>().join(", ");
        return Err(format!("Hay varios dispositivos ({serials}). Elegí uno con -s SERIAL o desconectá los demás."));
    }
    Ok(devices.into_iter().next())
}

/// Busca la APK del paquete distribuido o la compilada junto al proyecto.
fn default_apk() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let candidates = ["SoundShare.apk", "android/app/build/outputs/apk/release/app-release.apk", "android/app/build/outputs/apk/debug/app-debug.apk"];
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

/// Comprueba la versión una sola vez por cable; devuelve si podemos abrir la app.
fn prepare_app(adb: &Adb, serial: &str) -> std::io::Result<bool> {
    let installed = adb.installed_version_code(serial)?;
    if installed == Some(REQUIRED_APP_VERSION_CODE) {
        return Ok(true);
    }
    let disabled = std::env::var("SOUNDSHARE_NO_AUTO_INSTALL").as_deref() == Ok("1");
    let apk = if disabled { None } else { default_apk() };
    let Some(apk) = apk else {
        if let Some(version) = installed {
            println!("⚠ La app instalada tiene versionCode={version}; esta PC necesita {REQUIRED_APP_VERSION_CODE} ({REQUIRED_APP_VERSION_NAME}). Instalá la APK correspondiente y reconectá el cable (también podés usar «soundshare install»).");
        } else {
            println!("⚠ Instalá SoundShare.apk en el teléfono y reconectá el cable (también podés usar «soundshare install»).");
        }
        if disabled {
            println!("  Instalación automática desactivada (SOUNDSHARE_NO_AUTO_INSTALL=1).");
        }
        return Ok(installed.is_some());
    };
    println!("  Instalando SoundShare {REQUIRED_APP_VERSION_NAME} en el teléfono…");
    if let Err(e) = adb.install(serial, &apk) {
        eprintln!("⚠ No pude instalar SoundShare: {e}");
        eprintln!("  Autorizá la instalación por USB y usá «soundshare install»; si la firma es distinta, ejecutá «adb uninstall com.soundshare» (borra los datos) y reconectá el cable.");
        return Ok(installed.is_some());
    }
    match adb.installed_version_code(serial) {
        Ok(Some(REQUIRED_APP_VERSION_CODE)) => println!("✓ App actualizada"),
        Ok(_) => eprintln!("⚠ La APK encontrada ({}) no es la versión {REQUIRED_APP_VERSION_CODE}; recompilá con ./scripts/build.sh", apk.display()),
        Err(e) => eprintln!("⚠ Instalé la APK, pero no pude comprobar su versión: {e}. Revisá el teléfono y reconectá el cable."),
    }
    Ok(true)
}

/// Vigila el cable: cuando aparece el teléfono abre el túnel, cuando se va apaga todo.
fn watch_devices(hub: Arc<Hub>, adb: Adb, serial: Option<String>, port: u16) {
    let mut current: Option<adb::Device> = None;
    let mut warned = String::new();
    let mut attempted: Option<String> = None;
    let mut refresh_reverse = false;
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

        if ready.is_none() && attempted.as_ref().is_some_and(|s| aoa::present(s)) {
            // Un reinicio de adb no es una desconexión de cable. Rehacer reverse al volver.
            refresh_reverse = true;
            thread::sleep(Duration::from_secs(1));
            continue;
        }
        if ready.is_none() && current.is_none() && attempted.is_some() {
            attempted = None;
            hub.set_aoa(None);
        }

        if ready.as_ref().map(|d| &d.serial) != current.as_ref().map(|d| &d.serial) || refresh_reverse {
            match &ready {
                Some(d) => {
                    // Una sola conmutación por conexión de cable, antes de reverse/CONTROL/CAPTURE.
                    let mut checked = None;
                    if attempted.as_deref() != Some(&d.serial) {
                        attempted = Some(d.serial.clone());
                        // Instalar antes de AOA: sin una app que atienda el accesorio, Android muestra la URI.
                        checked = Some(prepare_app(&adb, &d.serial));
                        // Sin cable no hay accesorio USB que negociar.
                        if d.usb && std::env::var("SOUNDSHARE_PLAYER_TRANSPORT").as_deref() != Ok("adb") {
                            match aoa::prepare(&d.serial) {
                                Ok(link) => { hub.set_aoa(Some(link.clone())); link.run(Arc::downgrade(&hub)); }
                                Err(e) => eprintln!("⚠ AOA no disponible: {e}. PLAYER por adb."),
                            }
                            // START reinicia adb. Esperar aquí evita confundirlo con cable desconectado.
                            let until = std::time::Instant::now() + Duration::from_secs(15);
                            while !pick_device(&adb, Some(&d.serial)).ok().flatten().is_some_and(|v| v.state == "device") {
                                if std::time::Instant::now() >= until { break; }
                                thread::sleep(Duration::from_millis(200));
                            }
                        }
                    }
                    match adb.reverse(&d.serial, port) {
                        Ok(()) => {
                            let via = if d.usb { "" } else { " por depuración inalámbrica" };
                            println!("● Teléfono conectado{via}: {} ({})", d.model, d.serial);
                            current = ready.clone();
                            refresh_reverse = false;
                            warned.clear();
                            hub.device_changed(ready.clone());
                            // Rehacer reverse tras AOA no habilita otro intento de instalación.
                            let app_ready = checked.unwrap_or_else(|| adb.package_path(&d.serial).map(|p| p.is_some()));
                            match app_ready {
                                Ok(true) => {
                                    println!("  Abriendo SoundShare: elegí el sentido del audio en el teléfono (o acá: 1 / 2).");
                                    if let Err(e) = adb.start_app(&d.serial) {
                                        eprintln!("⚠ No pude abrir la app: {e}. Abrí SoundShare en el teléfono.");
                                    }
                                    hub.start_requested_mode();
                                }
                                Ok(false) => {},
                                Err(e) => eprintln!("⚠ No pude comprobar la app: {e}. Revisá el teléfono y reconectá el cable."),
                            }
                        }
                        Err(e) => eprintln!("✗ No pude abrir el túnel USB: {e}"),
                    }
                },
                None => {
                    println!("○ Teléfono desconectado");
                    current = None;
                    attempted = None;
                            hub.set_aoa(None);
                    hub.device_changed(None);
                }
            }
        }
        thread::sleep(Duration::from_secs(1));
    }
}

/// Abre los puertos de red; si no puede, SoundShare sigue sólo por USB.
fn open_wifi(port: u16) -> Option<(Arc<Wifi>, TcpListener, UdpSocket)> {
    let Some(port) = port.checked_add(1) else {
        eprintln!("⚠ WiFi desactivado: no hay puerto después de {port}");
        return None;
    };
    let wifi = Wifi::open(port).map_err(|e| eprintln!("⚠ WiFi desactivado: {e}")).ok()?;
    match wifi.bind() {
        Ok((tcp, udp)) => Some((Arc::new(wifi), tcp, udp)),
        Err(e) => {
            eprintln!("⚠ WiFi desactivado: no pude escuchar en TCP {port} / UDP {}: {e}", wifi::DISCOVERY_PORT);
            None
        }
    }
}

/// Cada conexión de la red prueba conocer el secreto antes de llegar al Hub.
fn serve_wifi(hub: Arc<Hub>, wifi: Arc<Wifi>, listener: TcpListener) {
    for mut conn in listener.incoming().flatten() {
        let deadline = std::time::Instant::now() + wifi::HANDSHAKE_TIMEOUT;
        let Some(pending) = wifi.admit() else { continue };
        let (hub, wifi) = (hub.clone(), wifi.clone());
        thread::spawn(move || {
            let peer = conn.peer_addr().map_or_else(|_| "?".into(), |a| a.ip().to_string());
            match wifi.handshake(&mut conn, pending, deadline) {
                Ok(role) => hub.handle_wifi(conn, role),
                Err(e) => eprintln!("⚠ Conexión WiFi rechazada desde {peer}: {e}"),
            }
        });
    }
}

fn main() {
    let mut raw = std::env::args().skip(1);
    let command = raw.next();
    if command.as_deref() == Some("bluetooth") {
        let helper = std::env::current_exe().ok().and_then(|exe| {
            exe.ancestors().skip(1).map(|dir| dir.join("scripts/bluetooth.sh"))
                .find(|path| path.is_file())
        }).unwrap_or_else(|| die("Falta scripts/bluetooth.sh junto al paquete de SoundShare."));
        let status = std::process::Command::new("bash").arg(helper).args(raw).status()
            .unwrap_or_else(|e| die(&format!("No pude abrir el ayudante Bluetooth: {e}")));
        exit(status.code().unwrap_or(1));
    }
    if command.as_deref() == Some("adb") {
        let adb = Adb::find().unwrap_or_else(|| die("No encontré adb."));
        let args: Vec<String> = raw.collect();
        exit(adb.passthrough(&args).map_or(1, |s| s.code().unwrap_or(1)));
    }
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
    let network = if args.wifi { open_wifi(args.port) } else { None };
    let (engine, errors) = Engine::start().unwrap_or_else(|e| die(&format!("No pude conectarme a PipeWire: {e}")));
    let hub = Arc::new(Hub::new(adb.clone(), engine, args.port, args.output.clone(), network.as_ref().map(|(w, _, _)| w.clone()), args.start_mode));
    {
        let hub = hub.clone();
        thread::spawn(move || hub.engine_errors(errors));
    }

    let via = if network.is_some() { "USB o WiFi" } else { "USB" };
    println!("SoundShare {} · esperando al teléfono por {via}… (escribí «h» para ver los comandos)", env!("CARGO_PKG_VERSION"));
    if let Some((wifi, tcp, udp)) = network {
        let paired = match wifi.paired() {
            0 => "ningún teléfono emparejado todavía".to_string(),
            1 => "1 teléfono emparejado".to_string(),
            n => format!("{n} teléfonos emparejados"),
        };
        println!("  WiFi: puerto {} · {paired}. Para emparejar uno, conectalo una vez por USB.", wifi.port);
        {
            let wifi = wifi.clone();
            thread::spawn(move || wifi.serve_discovery(udp));
        }
        let hub = hub.clone();
        thread::spawn(move || serve_wifi(hub, wifi, tcp));
    }

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
        let (serial, port) = (args.serial.clone(), args.port);
        thread::spawn(move || watch_devices(hub, adb, serial, port));
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn required_app_version_matches_android() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../android/app/build.gradle.kts");
        let gradle = std::fs::read_to_string(path).expect("No pude leer la versión de Android");
        let version = |key: &str| {
            gradle.lines().find_map(|line| {
                let (name, value) = line.trim().split_once('=')?;
                (name.trim() == key).then(|| value.trim())
            }).unwrap_or_else(|| panic!("Falta {key} en build.gradle.kts"))
        };
        assert_eq!(REQUIRED_APP_VERSION_CODE, version("versionCode").parse::<u32>().unwrap());
        assert_eq!(REQUIRED_APP_VERSION_NAME, version("versionName").trim_matches('"'));
    }

    fn device(serial: &str, state: &str) -> adb::Device {
        adb::Device { serial: serial.into(), state: state.into(), model: "Android".into(), usb: true }
    }

    fn wireless(serial: &str) -> adb::Device {
        adb::Device { usb: false, ..device(serial, "device") }
    }

    #[test]
    fn cable_wins_over_wireless_debugging() {
        let both = vec![wireless("192.168.0.20:37099"), device("phone", "device")];
        assert_eq!(select_device(both.clone(), None).unwrap(), Some(both[1].clone()));
        assert_eq!(select_device(both.clone(), Some("192.168.0.20:37099")).unwrap(), Some(both[0].clone()));
        let only = vec![wireless("adb-phone._adb-tls-connect._tcp")];
        assert_eq!(select_device(only.clone(), None).unwrap(), Some(only[0].clone()));
        assert!(select_device(vec![wireless("a:1"), wireless("b:2")], None).unwrap_err().contains("-s SERIAL"));
    }

    #[test]
    fn selects_only_device_including_pending_usb_authorization() {
        assert!(select_device(vec![], None).unwrap().is_none());
        let phone = device("phone", "unauthorized");
        assert_eq!(select_device(vec![phone.clone()], None).unwrap(), Some(phone));
    }

    #[test]
    fn multiple_devices_require_explicit_selection() {
        let phones = vec![device("first", "unauthorized"), device("second", "device")];
        let error = select_device(phones.clone(), None).unwrap_err();
        assert!(error.contains("-s SERIAL"));
        assert_eq!(select_device(phones.clone(), Some("second")).unwrap(), Some(phones[1].clone()));
        assert!(select_device(phones, Some("missing")).unwrap().is_none());
    }
}
