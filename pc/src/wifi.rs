//! WiFi nativo: emparejamiento por USB, descubrimiento por UDP y autenticación de cada conexión
//! (ver docs/INALAMBRICO.md).
//!
//! El cable autentica al teléfono una vez (adb ya lo autorizó) y le entrega un secreto. Por la red,
//! cada conexión TCP prueba conocerlo con HMAC-SHA256 antes del hello de siempre, en los dos
//! sentidos: un equipo de la red no puede pedir modos ni recibir o inyectar audio. No se cifra.

use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt};
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use hmac::{Hmac, Mac};
use sha2::Sha256;

use crate::protocol::{self, Role};

/// El teléfono siempre pregunta en este puerto UDP; la respuesta trae el puerto TCP real.
pub const DISCOVERY_PORT: u16 = 47101;
pub const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const MAGIC: &[u8; 4] = b"SSLN";
const VERSION: u8 = 1;
const ACCEPTED: u8 = 0;
const UNKNOWN_PHONE: u8 = 1;
const BAD_VERSION: u8 = 2;
/// Conexiones que todavía no probaron conocer el secreto.
const MAX_PENDING: usize = 8;
const MAX_DATAGRAM: usize = 512;

type Id = [u8; 16];
type Secret = [u8; 32];

#[derive(Clone)]
struct Phone {
    secret: Secret,
    name: String,
}

pub struct Wifi {
    /// Puerto TCP de la red; el de loopback es el anterior.
    pub port: u16,
    pc_id: Id,
    name: String,
    path: PathBuf,
    phones: Mutex<HashMap<Id, Phone>>,
    pending: AtomicUsize,
}

/// Cuenta una conexión sin autenticar mientras vive.
pub struct Pending(Arc<Wifi>);
impl Drop for Pending {
    fn drop(&mut self) {
        self.0.pending.fetch_sub(1, Ordering::AcqRel);
    }
}

impl Wifi {
    /// Lee la identidad de esta PC y los teléfonos emparejados; crea la identidad si falta.
    pub fn open(port: u16) -> io::Result<Wifi> {
        let path = config_dir().ok_or_else(|| io::Error::other("no sé dónde guardar la configuración (falta HOME)"))?
            .join("soundshare/wifi");
        Self::open_path(port, path)
    }

    fn open_path(port: u16, path: PathBuf) -> io::Result<Wifi> {
        let text = match read_private_config(&path) {
            Ok(t) => t,
            Err(e) if e.kind() == io::ErrorKind::NotFound => String::new(),
            Err(e) => return Err(e),
        };
        let (pc_id, phones) = parse_config(&text);
        let wifi = Wifi {
            port,
            pc_id: match pc_id { Some(id) => id, None => random()? },
            name: hostname(),
            path,
            phones: Mutex::new(phones),
            pending: AtomicUsize::new(0),
        };
        if pc_id.is_none() {
            wifi.save(&wifi.phones.lock().unwrap())?;
        }
        Ok(wifi)
    }

    pub fn bind(&self) -> io::Result<(TcpListener, UdpSocket)> {
        Ok((TcpListener::bind(("0.0.0.0", self.port))?, UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT))?))
    }

    pub fn paired(&self) -> usize {
        self.phones.lock().unwrap().len()
    }

    fn save(&self, phones: &HashMap<Id, Phone>) -> io::Result<()> {
        let dir = self.path.parent().expect("la ruta tiene carpeta");
        fs::DirBuilder::new().recursive(true).mode(0o700).create(dir)?;
        let mut text = format!("# SoundShare: identidad WiFi de esta PC y teléfonos emparejados. Es privado.\npc {}\n", hex(&self.pc_id));
        for (id, phone) in phones {
            text += &format!("phone {} {} {}\n", hex(id), hex(&phone.secret), phone.name);
        }
        // create_new evita reutilizar un archivo con permisos heredados o seguir un enlace.
        let tmp = dir.join(format!(".wifi-{}.tmp", hex(&random::<16>()?)));
        let mut f = OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp)?;
        let result = (|| {
            f.set_permissions(fs::Permissions::from_mode(0o600))?;
            f.write_all(text.as_bytes())?;
            f.sync_all()?;
            fs::rename(&tmp, &self.path)
        })();
        if result.is_err() { let _ = fs::remove_file(&tmp); }
        result
    }

    /// `LAN_PAIR` por el CONTROL de loopback: un secreto nuevo por pedido, guardado antes de responder.
    pub fn pair(&self, phone_id: &str, name: &str) -> io::Result<String> {
        let id: Id = unhex(phone_id).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "ID de teléfono inválido"))?;
        let secret: Secret = random()?;
        // El nombre va al final de una línea del archivo: sin saltos.
        let name = name.replace(['\n', '\r'], " ");
        let mut phones = self.phones.lock().unwrap();
        let old = phones.insert(id, Phone { secret, name });
        if let Err(e) = self.save(&phones) {
            match old { Some(p) => phones.insert(id, p), None => phones.remove(&id) };
            return Err(e);
        }
        Ok(format!("LAN_PAIRED {} {} {} {}", hex(&self.pc_id), self.port, hex(&secret), self.name))
    }

    /// Sólo los teléfonos emparejados reciben respuesta: los demás no se enteran de que estamos.
    fn discovery_reply(&self, request: &[u8]) -> Option<String> {
        let text = std::str::from_utf8(request).ok()?;
        let mut parts = text.split(' ');
        if parts.next()? != "SOUNDSHARE?" || parts.next()? != "1" { return None; }
        let id: Id = unhex(parts.next()?)?;
        if parts.next().is_some() || !self.phones.lock().unwrap().contains_key(&id) { return None; }
        Some(format!("SOUNDSHARE 1 {} {} {}", hex(&self.pc_id), self.port, self.name))
    }

    pub fn serve_discovery(self: Arc<Self>, sock: UdpSocket) {
        let mut buf = [0u8; MAX_DATAGRAM];
        loop {
            let Ok((n, from)) = sock.recv_from(&mut buf) else { continue };
            if let Some(reply) = self.discovery_reply(&buf[..n]) {
                let _ = sock.send_to(&reply.as_bytes()[..reply.len().min(MAX_DATAGRAM)], from);
            }
        }
    }

    /// Reserva un lugar para una conexión sin autenticar; None si ya hay demasiadas.
    pub fn admit(self: &Arc<Self>) -> Option<Pending> {
        let n = self.pending.fetch_add(1, Ordering::AcqRel);
        let pending = Pending(self.clone());
        (n < MAX_PENDING).then_some(pending)
    }

    /// Un único plazo desde accept cubre el preámbulo, la prueba y el hello del rol.
    /// El lugar reservado sigue ocupado hasta terminar también el hello.
    pub fn handshake(self: &Arc<Self>, s: &mut TcpStream, pending: Pending, deadline: Instant) -> io::Result<Role> {
        debug_assert!(Arc::ptr_eq(self, &pending.0));
        let mut s = DeadlineStream { socket: s, deadline };
        self.authenticate(&mut s)?;
        let role = protocol::read_hello(&mut s)?;
        s.remaining()?;
        Ok(role)
    }

    /// Preámbulo de autenticación del lado de la PC. Devuelve el nombre del teléfono.
    pub fn authenticate(&self, s: &mut (impl Read + Write)) -> io::Result<String> {
        let mut hello = [0u8; 40];
        s.read_exact(&mut hello)?;
        if &hello[..4] != MAGIC {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "no es un cliente de SoundShare"));
        }
        if hello[4] != VERSION {
            reject(s, BAD_VERSION)?;
            return Err(io::Error::new(io::ErrorKind::InvalidData, format!("preámbulo versión {}; actualizá la app", hello[4])));
        }
        let phone_id: Id = hello[8..24].try_into().unwrap();
        let phone_nonce: [u8; 16] = hello[24..40].try_into().unwrap();
        let Some(phone) = self.phones.lock().unwrap().get(&phone_id).cloned() else {
            reject(s, UNKNOWN_PHONE)?;
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "teléfono no emparejado (conectalo una vez por USB)"));
        };
        let pc_nonce: [u8; 16] = random()?;
        let mut reply = [0u8; 56];
        reply[..4].copy_from_slice(MAGIC);
        reply[4] = VERSION;
        reply[5] = ACCEPTED;
        reply[8..24].copy_from_slice(&pc_nonce);
        reply[24..].copy_from_slice(&pc_proof(&phone.secret, &phone_id, &phone_nonce, &pc_nonce));
        s.write_all(&reply)?;
        let mut proof = [0u8; 32];
        s.read_exact(&mut proof)?;
        phone_mac(&phone.secret, &phone_id, &phone_nonce, &pc_nonce).verify_slice(&proof)
            .map_err(|_| io::Error::new(io::ErrorKind::PermissionDenied, format!("{} no probó conocer el secreto", phone.name)))?;
        Ok(phone.name)
    }
}

/// Los timeouts del socket limitan cada read/write; recalcular el tiempo restante impide
/// que mandar un byte por vez renueve el plazo de read_exact/write_all.
struct DeadlineStream<'a> {
    socket: &'a mut TcpStream,
    deadline: Instant,
}

impl DeadlineStream<'_> {
    fn remaining(&self) -> io::Result<Duration> {
        self.deadline.checked_duration_since(Instant::now())
            .filter(|d| !d.is_zero())
            .ok_or_else(|| io::Error::new(io::ErrorKind::TimedOut, "tiempo agotado autenticando la conexión WiFi"))
    }
}

fn handshake_error(e: io::Error) -> io::Error {
    if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) {
        io::Error::new(io::ErrorKind::TimedOut, "tiempo agotado autenticando la conexión WiFi")
    } else { e }
}

impl Read for DeadlineStream<'_> {
    fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
        self.socket.set_read_timeout(Some(self.remaining()?))?;
        self.socket.read(b).map_err(handshake_error)
    }
}

impl Write for DeadlineStream<'_> {
    fn write(&mut self, b: &[u8]) -> io::Result<usize> {
        self.socket.set_write_timeout(Some(self.remaining()?))?;
        self.socket.write(b).map_err(handshake_error)
    }
    fn flush(&mut self) -> io::Result<()> { Ok(()) }
}

fn read_private_config(path: &std::path::Path) -> io::Result<String> {
    let mut f = OpenOptions::new().read(true).custom_flags(libc::O_NOFOLLOW).open(path)?;
    if !f.metadata()?.is_file() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "la configuración WiFi no es un archivo"));
    }
    f.set_permissions(fs::Permissions::from_mode(0o600))?;
    let mut text = String::new();
    f.read_to_string(&mut text)?;
    Ok(text)
}

fn reject(s: &mut impl Write, status: u8) -> io::Result<()> {
    let mut reply = [0u8; 56];
    reply[..4].copy_from_slice(MAGIC);
    reply[4] = VERSION;
    reply[5] = status;
    s.write_all(&reply)
}

fn mac(secret: &Secret, label: &[u8], parts: [&[u8]; 3]) -> Hmac<Sha256> {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(secret).expect("HMAC acepta claves de cualquier largo");
    mac.update(label);
    for p in parts { mac.update(p); }
    mac
}

fn pc_proof(secret: &Secret, phone_id: &Id, phone_nonce: &[u8; 16], pc_nonce: &[u8; 16]) -> [u8; 32] {
    mac(secret, b"SSLN-PC", [phone_id, phone_nonce, pc_nonce]).finalize().into_bytes().into()
}

fn phone_mac(secret: &Secret, phone_id: &Id, phone_nonce: &[u8; 16], pc_nonce: &[u8; 16]) -> Hmac<Sha256> {
    mac(secret, b"SSLN-PHONE", [phone_id, pc_nonce, phone_nonce])
}

/// Sockets WiFi: sin Nagle, con TOS configurable y con un límite para notar a un
/// teléfono que se fue de la red sin cerrar (si no, una escritura o lectura esperaría minutos).
pub fn tune(s: &TcpStream) {
    let tos = match std::env::var("SOUNDSHARE_WIFI_TOS") {
        Ok(value) => value.parse::<u8>().unwrap_or_else(|_| {
            eprintln!("⚠ SOUNDSHARE_WIFI_TOS inválido: usá un entero de 0 a 255; uso 0.");
            0
        }),
        Err(_) => 0,
    };
    tune_tos(s, tos);
}

fn tune_tos(s: &TcpStream, tos: u8) {
    let _ = s.set_nodelay(true);
    let fd = s.as_raw_fd();
    let set = |level, name, value: libc::c_int| unsafe {
        libc::setsockopt(fd, level, name, (&value as *const libc::c_int).cast(), size_of::<libc::c_int>() as libc::socklen_t);
    };
    set(libc::IPPROTO_IP, libc::IP_TOS, tos.into());
    set(libc::SOL_SOCKET, libc::SO_KEEPALIVE, 1);
    set(libc::IPPROTO_TCP, libc::TCP_KEEPIDLE, 3);
    set(libc::IPPROTO_TCP, libc::TCP_KEEPINTVL, 1);
    set(libc::IPPROTO_TCP, libc::TCP_KEEPCNT, 3);
    set(libc::IPPROTO_TCP, libc::TCP_USER_TIMEOUT, 6000);
}

fn parse_config(text: &str) -> (Option<Id>, HashMap<Id, Phone>) {
    let mut pc = None;
    let mut phones = HashMap::new();
    for line in text.lines() {
        let mut parts = line.splitn(4, ' ');
        match (parts.next(), parts.next(), parts.next()) {
            (Some("pc"), Some(id), None) => pc = unhex(id).or(pc),
            (Some("phone"), Some(id), Some(secret)) => {
                if let (Some(id), Some(secret)) = (unhex(id), unhex(secret)) {
                    phones.insert(id, Phone { secret, name: parts.next().unwrap_or("Android").to_string() });
                }
            }
            _ => {}
        }
    }
    (pc, phones)
}

fn config_dir() -> Option<PathBuf> {
    std::env::var_os("XDG_CONFIG_HOME").map(PathBuf::from).filter(|p| p.is_absolute())
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))
}

fn hostname() -> String {
    let mut buf = [0u8; 256];
    let ok = unsafe { libc::gethostname(buf.as_mut_ptr().cast(), buf.len()) } == 0;
    let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    let name = String::from_utf8_lossy(&buf[..end]).trim().to_string();
    if ok && !name.is_empty() { name } else { "PC".into() }
}

fn random<const N: usize>() -> io::Result<[u8; N]> {
    let mut b = [0u8; N];
    let mut filled = 0;
    while filled < N {
        let n = unsafe { libc::getrandom(b[filled..].as_mut_ptr().cast(), N - filled, 0) };
        if n < 0 {
            let e = io::Error::last_os_error();
            if e.kind() == io::ErrorKind::Interrupted { continue; }
            return Err(e);
        }
        filled += n as usize;
    }
    Ok(b)
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Sólo hexadecimal minúscula del largo exacto, como en la especificación.
fn unhex<const N: usize>(s: &str) -> Option<[u8; N]> {
    if s.len() != 2 * N || !s.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)) {
        return None;
    }
    let mut out = [0u8; N];
    for (i, o) in out.iter_mut().enumerate() {
        *o = u8::from_str_radix(&s[2 * i..2 * i + 2], 16).ok()?;
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;

    fn seq<const N: usize>(start: u8) -> [u8; N] {
        std::array::from_fn(|i| start + i as u8)
    }

    fn wifi(dir: &std::path::Path) -> Wifi {
        Wifi {
            port: 47101,
            pc_id: seq(0x50),
            name: "fedora".into(),
            path: dir.join("soundshare/wifi"),
            phones: Mutex::new(HashMap::new()),
            pending: AtomicUsize::new(0),
        }
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("soundshare-wifi-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn proofs_match_spec_vector() {
        let (secret, id, phone_nonce, pc_nonce) = (seq(0x00), seq(0xa0), seq(0xb0), seq(0xc0));
        assert_eq!(hex(&pc_proof(&secret, &id, &phone_nonce, &pc_nonce)),
                   "559986fb2eef2a73102d6ddfa2ce35b0deaea6200fb9c475933a47924352ecd2");
        assert_eq!(hex(&phone_mac(&secret, &id, &phone_nonce, &pc_nonce).finalize().into_bytes()),
                   "4c313817e3833ac17d867be912dfc370524369cab770fbe41d212a63a0c7122c");
    }

    #[test]
    fn hex_is_strict_lowercase_with_exact_length() {
        assert_eq!(unhex::<2>("0aff"), Some([0x0a, 0xff]));
        for bad in ["0AFF", "0af", "0aff0", "0g00", "+aff", ""] {
            assert_eq!(unhex::<2>(bad), None, "{bad}");
        }
        assert_eq!(hex(&[0x00, 0x0a, 0xff]), "000aff");
    }

    #[test]
    fn pairing_persists_privately_and_rotates_secret() {
        let dir = temp_dir("pair");
        let w = wifi(&dir);
        let id = hex(&seq::<16>(0xa0));
        let first = w.pair(&id, "moto g play").unwrap();
        let parts: Vec<_> = first.splitn(5, ' ').collect();
        assert_eq!(parts[..3], ["LAN_PAIRED", &hex(&seq::<16>(0x50)), "47101"]);
        assert_eq!(parts[4], "fedora");
        let second = w.pair(&id, "moto g play").unwrap();
        assert_ne!(first, second, "cada emparejamiento genera otro secreto");

        let text = fs::read_to_string(&w.path).unwrap();
        let (pc, phones) = parse_config(&text);
        assert_eq!(pc, Some(seq(0x50)));
        assert_eq!(phones.len(), 1);
        let phone = &phones[&seq::<16>(0xa0)];
        assert_eq!(phone.name, "moto g play");
        assert_eq!(hex(&phone.secret), second.split(' ').nth(3).unwrap());
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(fs::metadata(&w.path).unwrap().permissions().mode() & 0o777, 0o600);
        assert!(w.pair("A0", "x").is_err());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn pairing_does_not_reuse_a_public_temporary_file() {
        let dir = temp_dir("public-tmp");
        let w = wifi(&dir);
        fs::create_dir_all(w.path.parent().unwrap()).unwrap();
        let legacy_tmp = w.path.with_extension("tmp");
        fs::write(&legacy_tmp, "existing file").unwrap();
        fs::set_permissions(&legacy_tmp, fs::Permissions::from_mode(0o644)).unwrap();
        w.pair(&hex(&seq::<16>(0xa0)), "moto").unwrap();
        assert_eq!(fs::metadata(&w.path).unwrap().permissions().mode() & 0o777, 0o600);
        assert_eq!(fs::read_to_string(&legacy_tmp).unwrap(), "existing file");
        assert_eq!(fs::read_dir(w.path.parent().unwrap()).unwrap().count(), 2);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn loading_repairs_permissions_without_changing_identity_or_pairings() {
        let dir = temp_dir("public-config");
        let w = wifi(&dir);
        w.pair(&hex(&seq::<16>(0xa0)), "moto").unwrap();
        let before = fs::read_to_string(&w.path).unwrap();
        fs::set_permissions(&w.path, fs::Permissions::from_mode(0o644)).unwrap();
        let loaded = Wifi::open_path(w.port, w.path.clone()).unwrap();
        assert_eq!(loaded.pc_id, w.pc_id);
        assert_eq!(loaded.paired(), 1);
        assert_eq!(fs::read_to_string(&w.path).unwrap(), before);
        assert_eq!(fs::metadata(&w.path).unwrap().permissions().mode() & 0o777, 0o600);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn loading_does_not_follow_a_config_symlink() {
        let dir = temp_dir("config-symlink");
        let w = wifi(&dir);
        fs::create_dir_all(w.path.parent().unwrap()).unwrap();
        let target = dir.join("target");
        fs::write(&target, "unrelated file").unwrap();
        fs::set_permissions(&target, fs::Permissions::from_mode(0o644)).unwrap();
        std::os::unix::fs::symlink(&target, &w.path).unwrap();
        assert!(Wifi::open_path(w.port, w.path.clone()).is_err());
        assert_eq!(fs::metadata(&target).unwrap().permissions().mode() & 0o777, 0o644);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn discovery_answers_only_paired_phones() {
        let dir = temp_dir("discovery");
        let w = wifi(&dir);
        let id = hex(&seq::<16>(0xa0));
        let request = format!("SOUNDSHARE? 1 {id}");
        assert_eq!(w.discovery_reply(request.as_bytes()), None);
        w.pair(&id, "moto").unwrap();
        assert_eq!(w.discovery_reply(request.as_bytes()).unwrap(),
                   format!("SOUNDSHARE 1 {} 47101 fedora", hex(&seq::<16>(0x50))));
        for bad in [format!("SOUNDSHARE? 2 {id}"), format!("SOUNDSHARE? 1 {id} extra"), format!("SOUNDSHARE 1 {id}"), "SOUNDSHARE? 1".into()] {
            assert_eq!(w.discovery_reply(bad.as_bytes()), None, "{bad}");
        }
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn config_skips_malformed_lines() {
        let text = format!("# comentario\npc {}\nphone zz {}\nphone {} {} Moto G\npc nada\n",
            hex(&seq::<16>(1)), hex(&seq::<32>(0)), hex(&seq::<16>(2)), hex(&seq::<32>(3)));
        let (pc, phones) = parse_config(&text);
        assert_eq!(pc, Some(seq(1)));
        assert_eq!(phones.len(), 1);
        assert_eq!(phones[&seq::<16>(2)].name, "Moto G");
        assert_eq!(phones[&seq::<16>(2)].secret, seq(3));
    }

    #[test]
    fn pending_connections_are_limited() {
        let dir = temp_dir("pending");
        let w = Arc::new(wifi(&dir));
        let held: Vec<_> = (0..MAX_PENDING).map(|_| w.admit().expect("hay lugar")).collect();
        assert!(w.admit().is_none());
        drop(held);
        assert!(w.admit().is_some());
    }

    #[test]
    fn wifi_tos_is_applied_per_socket_for_best_effort_and_ef() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let _client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let socket = listener.accept().unwrap().0;
        for tos in [0, 184] {
            tune_tos(&socket, tos);
            let mut actual: libc::c_int = -1;
            let mut len = size_of::<libc::c_int>() as libc::socklen_t;
            let result = unsafe { libc::getsockopt(socket.as_raw_fd(), libc::IPPROTO_IP, libc::IP_TOS,
                (&mut actual as *mut libc::c_int).cast(), &mut len) };
            assert_eq!(result, 0);
            assert_eq!(actual, tos as libc::c_int);
        }
    }

    /// Cliente mínimo como el del teléfono: verifica a la PC antes de probar conocer el secreto.
    fn client(addr: std::net::SocketAddr, secret: Secret, id: Id, tamper: bool) -> io::Result<u8> {
        let mut s = TcpStream::connect(addr)?;
        client_auth(&mut s, secret, id, tamper)
    }

    fn client_auth(s: &mut TcpStream, secret: Secret, id: Id, tamper: bool) -> io::Result<u8> {
        let phone_nonce: [u8; 16] = random()?;
        let mut hello = [0u8; 40];
        hello[..4].copy_from_slice(MAGIC);
        hello[4] = VERSION;
        hello[8..24].copy_from_slice(&id);
        hello[24..].copy_from_slice(&phone_nonce);
        s.write_all(&hello)?;
        let mut reply = [0u8; 56];
        s.read_exact(&mut reply)?;
        if reply[5] != ACCEPTED { return Ok(reply[5]); }
        let pc_nonce: [u8; 16] = reply[8..24].try_into().unwrap();
        assert_eq!(reply[24..], pc_proof(&secret, &id, &phone_nonce, &pc_nonce));
        let mut proof: [u8; 32] = phone_mac(&secret, &id, &phone_nonce, &pc_nonce).finalize().into_bytes().into();
        if tamper { proof[0] ^= 1; }
        s.write_all(&proof)?;
        Ok(ACCEPTED)
    }

    fn paired_wifi(name: &str) -> (PathBuf, Arc<Wifi>, Id, Secret) {
        let dir = temp_dir(name);
        let w = Arc::new(wifi(&dir));
        let id = seq::<16>(0xa0);
        let line = w.pair(&hex(&id), "moto").unwrap();
        let secret = unhex(line.split(' ').nth(3).unwrap()).unwrap();
        (dir, w, id, secret)
    }

    #[test]
    fn admission_slot_is_held_until_the_role_hello_finishes() {
        let (dir, w, id, secret) = paired_wifi("pending-hello");
        let held: Vec<_> = (0..MAX_PENDING - 1).map(|_| w.admit().unwrap()).collect();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let server = {
            let w = w.clone();
            thread::spawn(move || {
                let (mut s, _) = listener.accept().unwrap();
                let deadline = Instant::now() + HANDSHAKE_TIMEOUT;
                w.handshake(&mut s, w.admit().unwrap(), deadline)
            })
        };
        let mut s = TcpStream::connect(addr).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        assert_eq!(client_auth(&mut s, secret, id, false).unwrap(), ACCEPTED);
        assert!(w.admit().is_none(), "la prueba HMAC no libera el lugar antes del hello");
        s.write_all(b"SNDS\x01\x01\0\0").unwrap();
        assert_eq!(server.join().unwrap().unwrap(), Role::Control);
        assert!(w.admit().is_some());
        drop(held);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn slow_preamble_cannot_extend_the_accept_deadline() {
        let (dir, w, _, _) = paired_wifi("slow-preamble");
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let client = thread::spawn(move || {
            let mut s = TcpStream::connect(addr).unwrap();
            for byte in b"SSLN\x01\0\0\0".iter().chain([0u8; 32].iter()) {
                if s.write_all(&[*byte]).is_err() { break; }
                thread::sleep(Duration::from_millis(30));
            }
        });
        let (mut s, _) = listener.accept().unwrap();
        let start = Instant::now();
        let e = w.handshake(&mut s, w.admit().unwrap(), start + Duration::from_millis(150)).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::TimedOut);
        assert!(start.elapsed() < Duration::from_secs(1));
        drop(s);
        client.join().unwrap();
        assert_eq!(w.pending.load(Ordering::Acquire), 0);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn slow_role_hello_uses_the_time_left_after_authentication() {
        let (dir, w, id, secret) = paired_wifi("slow-hello");
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let client = thread::spawn(move || {
            let mut s = TcpStream::connect(addr).unwrap();
            s.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            thread::sleep(Duration::from_millis(150));
            assert_eq!(client_auth(&mut s, secret, id, false).unwrap(), ACCEPTED);
            for &byte in b"SNDS\x01\x01\0\0" {
                if s.write_all(&[byte]).is_err() { break; }
                thread::sleep(Duration::from_millis(50));
            }
        });
        let (mut s, _) = listener.accept().unwrap();
        let start = Instant::now();
        let e = w.handshake(&mut s, w.admit().unwrap(), start + Duration::from_millis(400)).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::TimedOut);
        assert!(start.elapsed() < Duration::from_secs(1));
        drop(s);
        client.join().unwrap();
        assert_eq!(w.pending.load(Ordering::Acquire), 0);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn handshake_accepts_paired_and_rejects_others() {
        let dir = temp_dir("handshake");
        let w = Arc::new(wifi(&dir));
        let id = seq::<16>(0xa0);
        let line = w.pair(&hex(&id), "moto").unwrap();
        let secret: Secret = unhex(line.split(' ').nth(3).unwrap()).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let server = {
            let w = w.clone();
            thread::spawn(move || {
                listener.incoming().take(3).map(|c| w.authenticate(&mut c.unwrap()).map_err(|e| e.kind())).collect::<Vec<_>>()
            })
        };
        assert_eq!(client(addr, secret, id, false).unwrap(), ACCEPTED);
        assert_eq!(client(addr, secret, seq(0x10), false).unwrap(), UNKNOWN_PHONE);
        assert_eq!(client(addr, secret, id, true).unwrap(), ACCEPTED);
        let results = server.join().unwrap();
        assert_eq!(results[0], Ok("moto".to_string()));
        assert_eq!(results[1], Err(io::ErrorKind::PermissionDenied));
        assert_eq!(results[2], Err(io::ErrorKind::PermissionDenied));
        let _ = fs::remove_dir_all(&dir);
    }
}
