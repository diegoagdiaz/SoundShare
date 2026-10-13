//! Lo poco que necesitamos de adb: ver el teléfono, abrir el túnel y lanzar el capturador.

use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

pub const PACKAGE: &str = "com.soundshare";
const CAPTURE_CLASS: &str = "com.soundshare.capture.CaptureMain";

fn parse_version_code(output: &str) -> Option<u32> {
    output.split_whitespace().find_map(|part| part.strip_prefix("versionCode=")?.parse().ok())
}

fn parse_devices(out: &str) -> Vec<Device> {
    out.lines()
        .skip_while(|l| !l.starts_with("List of devices"))
        .skip(1)
        .filter_map(|l| {
            let mut parts = l.split_whitespace();
            let serial = parts.next()?.to_string();
            let state = parts.next()?.to_string();
            let fields: Vec<&str> = parts.collect();
            let model = fields.iter()
                .find_map(|p| p.strip_prefix("model:"))
                .unwrap_or("Android")
                .replace('_', " ");
            let usb = fields.iter().any(|p| p.starts_with("usb:"));
            Some(Device { serial, state, model, usb })
        })
        .collect()
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Device {
    pub serial: String,
    /// "device", "unauthorized", "offline"...
    pub state: String,
    pub model: String,
    /// Por cable; si no, por depuración inalámbrica (`IP:puerto` o `adb-…._adb-tls-connect._tcp`).
    pub usb: bool,
}

#[derive(Clone)]
pub struct Adb {
    bin: PathBuf,
}

impl Adb {
    #[cfg(test)]
    pub(crate) fn without_device() -> Adb {
        Adb { bin: "/nonexistent-soundshare-test-adb".into() }
    }

    /// Busca adb en $ADB, en el PATH o en el SDK de Android.
    pub fn find() -> Option<Adb> {
        let mut candidates: Vec<PathBuf> = Vec::new();
        if let Some(p) = std::env::var_os("ADB") {
            candidates.push(p.into());
        }
        if let Some(path) = std::env::var_os("PATH") {
            candidates.extend(std::env::split_paths(&path).map(|d| d.join("adb")));
        }
        for var in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
            if let Some(sdk) = std::env::var_os(var) {
                candidates.push(Path::new(&sdk).join("platform-tools/adb"));
            }
        }
        if let Some(home) = std::env::var_os("HOME") {
            candidates.push(Path::new(&home).join("Android/Sdk/platform-tools/adb"));
        }
        candidates.into_iter().find(|p| p.is_file()).map(|bin| Adb { bin })
    }

    fn cmd(&self, serial: Option<&str>) -> Command {
        let mut c = Command::new(&self.bin);
        if let Some(s) = serial {
            c.args(["-s", s]);
        }
        c.stdin(Stdio::null());
        c
    }

    fn run(&self, serial: Option<&str>, args: &[&str]) -> io::Result<String> {
        self.run_timeout(serial, args, Duration::from_secs(10))
    }

    fn run_timeout(&self, serial: Option<&str>, args: &[&str], timeout: Duration) -> io::Result<String> {
        let deadline = Instant::now() + timeout;
        let mut child = self.cmd(serial).args(args).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn()?;
        // Drenamos ambas salidas en paralelo: esperar primero puede llenar una tubería.
        let read = |mut pipe: Box<dyn Read + Send>| {
            let (tx, rx) = mpsc::channel();
            thread::spawn(move || {
                let mut bytes = Vec::new();
                let _ = tx.send(pipe.read_to_end(&mut bytes).map(|_| bytes));
            });
            rx
        };
        let stdout = read(Box::new(child.stdout.take().unwrap()));
        let stderr = read(Box::new(child.stderr.take().unwrap()));
        let status = loop {
            if let Some(status) = child.try_wait()? {
                break status;
            }
            if Instant::now() >= deadline {
                let _ = child.kill();
                thread::spawn(move || { let _ = child.wait(); });
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    format!("adb {}: tiempo agotado ({:.1} s)", args.join(" "), timeout.as_secs_f64()),
                ));
            }
            thread::sleep(Duration::from_millis(20));
        };
        let receive = |rx: mpsc::Receiver<io::Result<Vec<u8>>>| {
            rx.recv_timeout(deadline.saturating_duration_since(Instant::now()))
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "adb: tiempo agotado leyendo la salida"))?
        };
        let out = receive(stdout)?;
        let err = receive(stderr)?;
        if !status.success() {
            let detail = format!("{}\n{}", String::from_utf8_lossy(&out), String::from_utf8_lossy(&err));
            return Err(io::Error::other(format!("adb {}: {}", args.join(" "), detail.trim())));
        }
        Ok(String::from_utf8_lossy(&out).into_owned())
    }

    pub fn devices(&self) -> io::Result<Vec<Device>> {
        Ok(parse_devices(&self.run(None, &["devices", "-l"])?))
    }

    /// Ejecuta adb con estos argumentos y su misma terminal (para `adb pair`/`adb connect`).
    pub fn passthrough(&self, args: &[String]) -> io::Result<std::process::ExitStatus> {
        Command::new(&self.bin).args(args).status()
    }

    /// Hace que 127.0.0.1:port del teléfono llegue a 127.0.0.1:port de la PC.
    pub fn reverse(&self, serial: &str, port: u16) -> io::Result<()> {
        let spec = format!("tcp:{port}");
        match self.run(Some(serial), &["reverse", &spec, &spec]) {
            Ok(_) => Ok(()),
            Err(e) if e.to_string().contains("Address already in use") => {
                // Un listener viejo puede sobrevivir al cambio de transporte USB/AOA.
                // Retirar sólo nuestro puerto y reintentar una vez, nunca todos los túneles.
                let _ = self.run(Some(serial), &["reverse", "--remove", &spec]);
                self.run(Some(serial), &["reverse", &spec, &spec]).map(|_| ())
            }
            Err(e) => Err(e),
        }
    }

    pub fn shell(&self, serial: &str, command: &str) -> io::Result<String> {
        self.run(Some(serial), &["shell", command])
    }

    /// Ruta del APK instalado (la usa app_process para cargar el capturador).
    pub fn package_path(&self, serial: &str) -> io::Result<Option<String>> {
        let out = self.shell(serial, &format!("pm path {PACKAGE}"))?;
        Ok(out.lines().find_map(|l| l.trim().strip_prefix("package:").map(str::to_string)))
    }

    /// dumpsys funciona también en Android 8 (minSdk 26).
    pub fn installed_version_code(&self, serial: &str) -> io::Result<Option<u32>> {
        let out = self.shell(serial, &format!("dumpsys package {PACKAGE}"))?;
        Ok(parse_version_code(&out))
    }

    pub fn install(&self, serial: &str, apk: &Path) -> io::Result<()> {
        let apk = apk.to_string_lossy();
        self.run(Some(serial), &["install", "-r", "-d", &apk]).map(|_| ())
    }

    pub fn start_app(&self, serial: &str) -> io::Result<()> {
        self.shell(serial, &format!("am start -n {PACKAGE}/.MainActivity")).map(|_| ())
    }

    /// Lanza el capturador con permisos de shell. Su salida estándar son mensajes de estado.
    pub fn spawn_capture(&self, serial: &str, apk_path: &str, port: u16) -> io::Result<Child> {
        let remote = format!("CLASSPATH={apk_path} app_process / {CAPTURE_CLASS} --port {port} --latency");
        self.cmd(Some(serial))
            .args(["shell", &remote])
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(unix)]
    #[test]
    fn reverse_recovers_only_occupied_port() {
        use std::os::unix::fs::PermissionsExt;
        let dir = std::env::temp_dir().join(format!("soundshare-reverse-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let bin = dir.join("adb");
        std::fs::write(&bin, r#"#!/bin/sh
cd "$(dirname "$0")" || exit 1
printf '%s\n' "$*" >> calls
if [ "$4" = '--remove' ]; then touch removed; exit 0; fi
if [ ! -f removed ]; then
    echo 'adb: error: cannot bind listener: Address already in use' >&2
    exit 1
fi
"#).unwrap();
        std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o700)).unwrap();
        let adb = Adb { bin };
        adb.reverse("test-phone", 47100).unwrap();
        assert_eq!(std::fs::read_to_string(dir.join("calls")).unwrap(),
            "-s test-phone reverse tcp:47100 tcp:47100\n-s test-phone reverse --remove tcp:47100\n-s test-phone reverse tcp:47100 tcp:47100\n");
        std::fs::write(dir.join("calls"), "").unwrap();
        std::fs::write(&adb.bin, r#"#!/bin/sh
cd "$(dirname "$0")" || exit 1
printf '%s\n' "$*" >> calls
echo 'adb: device unauthorized' >&2
exit 1
"#).unwrap();
        assert!(adb.reverse("test-phone", 47100).unwrap_err().to_string().contains("unauthorized"));
        assert_eq!(std::fs::read_to_string(dir.join("calls")).unwrap(),
            "-s test-phone reverse tcp:47100 tcp:47100\n");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn installed_version_with_sdk_fields() {
        let out = "Packages:\n  Package [com.soundshare] (abc):\n    versionCode=4 minSdk=26 targetSdk=34\n    versionName=0.3.0\n";
        assert_eq!(parse_version_code(out), Some(4));
    }

    #[test]
    fn missing_package_has_no_version() {
        for out in ["", "\n  ", "Unable to find package: com.soundshare"] {
            assert_eq!(parse_version_code(out), None);
        }
    }

    #[test]
    fn first_version_wins_with_multiple_entries() {
        let out = "  versionCode=4 minSdk=26 targetSdk=34\nHidden system packages:\n  versionCode=2 minSdk=26 targetSdk=34";
        assert_eq!(parse_version_code(out), Some(4));
    }

    #[test]
    fn tells_usb_from_wireless_devices() {
        let out = "* daemon started successfully\nList of devices attached\n\
            ZY22DVN9D2             device usb:3-2 product:guamna_a model:moto_g_play__2021_ device:guamna transport_id:38\n\
            192.168.0.20:37099     device product:a21sxx model:SM_A217M device:a21s transport_id:4\n\
            adb-RF8N72VT02E-Ab12Cd._adb-tls-connect._tcp unauthorized transport_id:5\n\n";
        let devices = parse_devices(out);
        assert_eq!(devices.len(), 3);
        assert_eq!((devices[0].model.as_str(), devices[0].usb), ("moto g play  2021 ", true));
        assert_eq!((devices[1].serial.as_str(), devices[1].model.as_str(), devices[1].usb), ("192.168.0.20:37099", "SM A217M", false));
        assert_eq!((devices[2].state.as_str(), devices[2].usb), ("unauthorized", false));
    }

    /// Simula un comando colgado sin depender de un dispositivo ni del servidor adb.
    #[test]
    fn command_timeout() {
        let adb = Adb { bin: "/bin/sleep".into() };
        let start = Instant::now();
        let e = adb.run_timeout(None, &["30"], Duration::from_millis(60)).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::TimedOut);
        assert!(start.elapsed() < Duration::from_secs(2));
    }
}
