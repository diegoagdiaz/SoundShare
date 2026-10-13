//! Lo poco que necesitamos de adb: ver el teléfono, abrir el túnel y lanzar el capturador.

use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

pub const PACKAGE: &str = "com.soundshare";
const CAPTURE_CLASS: &str = "com.soundshare.capture.CaptureMain";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Device {
    pub serial: String,
    /// "device", "unauthorized", "offline"...
    pub state: String,
    pub model: String,
}

#[derive(Clone)]
pub struct Adb {
    bin: PathBuf,
}

impl Adb {
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
            let err = String::from_utf8_lossy(&err);
            return Err(io::Error::other(format!("adb {}: {}", args.join(" "), err.trim())));
        }
        Ok(String::from_utf8_lossy(&out).into_owned())
    }

    pub fn devices(&self) -> io::Result<Vec<Device>> {
        let out = self.run(None, &["devices", "-l"])?;
        Ok(out
            .lines()
            .skip_while(|l| !l.starts_with("List of devices"))
            .skip(1)
            .filter_map(|l| {
                let mut parts = l.split_whitespace();
                let serial = parts.next()?.to_string();
                let state = parts.next()?.to_string();
                let model = parts
                    .find_map(|p| p.strip_prefix("model:"))
                    .unwrap_or("Android")
                    .replace('_', " ");
                Some(Device { serial, state, model })
            })
            .collect())
    }

    /// Hace que 127.0.0.1:port del teléfono llegue a 127.0.0.1:port de la PC.
    pub fn reverse(&self, serial: &str, port: u16) -> io::Result<()> {
        let spec = format!("tcp:{port}");
        self.run(Some(serial), &["reverse", &spec, &spec]).map(|_| ())
    }

    pub fn shell(&self, serial: &str, command: &str) -> io::Result<String> {
        self.run(Some(serial), &["shell", command])
    }

    /// Ruta del APK instalado (la usa app_process para cargar el capturador).
    pub fn package_path(&self, serial: &str) -> io::Result<Option<String>> {
        let out = self.shell(serial, &format!("pm path {PACKAGE}"))?;
        Ok(out.lines().find_map(|l| l.trim().strip_prefix("package:").map(str::to_string)))
    }

    pub fn install(&self, serial: &str, apk: &Path) -> io::Result<()> {
        let apk = apk.to_string_lossy();
        self.run(Some(serial), &["install", "-r", &apk]).map(|_| ())
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
