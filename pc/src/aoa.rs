//! AOA + adb: sólo reclamamos la interfaz del accesorio; adb conserva la suya.
use crate::{aoa_frame as frame, hub::Hub, player_transport::PlayerTransport, protocol};
use rusb::{DeviceHandle, Direction, GlobalContext, TransferType};
use std::collections::VecDeque;
use std::io::{self, Read};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, Weak};
use std::thread;
use std::time::{Duration, Instant};

const CONTROL_TIMEOUT: Duration = Duration::from_secs(1);
fn usb_error(e: rusb::Error) -> io::Error {
    io::Error::other(format!("USB AOA: {e}"))
}

/// Selecciona por serie USB exacta, incluso tras la reenumeración. Nunca toca otros teléfonos.
fn selected(serial: &str) -> io::Result<DeviceHandle<GlobalContext>> {
    let mut error = None;
    for device in rusb::devices().map_err(usb_error)?.iter() {
        let Ok(desc) = device.device_descriptor() else {
            continue;
        };
        if desc.serial_number_string_index().is_none() {
            continue;
        }
        match device.open() {
            Ok(handle) => {
                if handle
                    .read_serial_number_string_ascii(&desc)
                    .ok()
                    .as_deref()
                    == Some(serial)
                {
                    return Ok(handle);
                }
            }
            Err(e) => error = Some(e),
        }
    }
    Err(error
        .map(usb_error)
        .unwrap_or_else(|| io::Error::other("no encontré la serie adb en USB")))
}

/// La presencia física no depende del estado temporal de adb ni de permisos libusb.
pub fn present(serial: &str) -> bool {
    std::fs::read_dir("/sys/bus/usb/devices")
        .ok()
        .is_some_and(|entries| {
            entries.flatten().any(|e| {
                std::fs::read_to_string(e.path().join("serial")).is_ok_and(|s| s.trim() == serial)
            })
        })
}

pub fn prepare(serial: &str) -> io::Result<Arc<Link>> {
    let mut handle = selected(serial)?;
    let desc = handle.device().device_descriptor().map_err(usb_error)?;
    if (desc.vendor_id(), desc.product_id()) != (0x18d1, 0x2d01) {
        if desc.vendor_id() == 0x18d1 && desc.product_id() == 0x2d00 {
            return Err(io::Error::other(
                "AOA sin adb (2d00); activá depuración USB y reconectá el cable",
            ));
        }
        let mut version = [0; 2];
        if handle
            .read_control(0xc0, 51, 0, 0, &mut version, CONTROL_TIMEOUT)
            .map_err(usb_error)?
            != 2
            || u16::from_le_bytes(version) == 0
        {
            return Err(io::Error::other("el teléfono no soporta AOA"));
        }
        for (index, value) in [
            "SoundShare",
            "SoundShare",
            "Audio PC a Android",
            "1",
            "https://github.com/diegoagdiaz/SoundShare",
            serial,
        ]
        .iter()
        .enumerate()
        {
            let bytes = format!("{value}\0").into_bytes();
            let n = handle
                .write_control(0x40, 52, 0, index as u16, &bytes, CONTROL_TIMEOUT)
                .map_err(usb_error)?;
            if n != bytes.len() {
                return Err(io::Error::other("cadena AOA incompleta"));
            }
        }
        println!("  AOA: conmutando USB; adb se reconecta antes de iniciar los canales…");
        handle
            .write_control(0x40, 53, 0, 0, &[], CONTROL_TIMEOUT)
            .map_err(usb_error)?;
        drop(handle);
        let deadline = Instant::now() + Duration::from_secs(10);
        handle = loop {
            thread::sleep(Duration::from_millis(200));
            if let Ok(h) = selected(serial) {
                let d = h.device().device_descriptor().map_err(usb_error)?;
                if (d.vendor_id(), d.product_id()) == (0x18d1, 0x2d01) {
                    break h;
                }
                if (d.vendor_id(), d.product_id()) == (0x18d1, 0x2d00) {
                    return Err(io::Error::other(
                        "el teléfono conmutó a AOA sin adb; reconectá con depuración USB",
                    ));
                }
            }
            if Instant::now() >= deadline {
                return Err(io::Error::other(
                    "no apareció 18d1:2d01 accesible; revisá udev (fallback adb)",
                ));
            }
        };
    }
    let config = handle
        .device()
        .active_config_descriptor()
        .map_err(usb_error)?;
    // La interfaz AOA es ff/ff/00; adb es ff/42/01 y nunca se reclama ni se separa.
    for interface in config.interfaces() {
        for alt in interface.descriptors() {
            if (alt.class_code(), alt.sub_class_code(), alt.protocol_code()) != (0xff, 0xff, 0) {
                continue;
            }
            let mut input = None;
            let mut output = None;
            for ep in alt
                .endpoint_descriptors()
                .filter(|e| e.transfer_type() == TransferType::Bulk)
            {
                match ep.direction() {
                    Direction::In => input = Some(ep.address()),
                    Direction::Out => output = Some(ep.address()),
                }
            }
            if let (Some(input), Some(output)) = (input, output) {
                handle
                    .claim_interface(alt.interface_number())
                    .map_err(usb_error)?;
                // SET_INTERFACE(0) vuelve a activar f_accessory y puede perder el request
                // pendiente del fd Android ya abierto al reiniciar el hub. El alt 0 activo
                // no necesita reinicialización; sólo seleccionamos otro si existe.
                if alt.setting_number() != 0 {
                    handle
                        .set_alternate_setting(alt.interface_number(), alt.setting_number())
                        .map_err(usb_error)?;
                }
                println!(
                    "  AOA accesible sin root: interfaz {}, bulk IN {input:#x}, OUT {output:#x}",
                    alt.interface_number()
                );
                return Ok(Arc::new(Link {
                    handle,
                    input,
                    output,
                    alive: AtomicBool::new(true),
                    writer: Mutex::new(()),
                    current: Mutex::new(None),
                }));
            }
        }
    }
    Err(io::Error::other(
        "no encontré los endpoints del accesorio + adb",
    ))
}

pub struct Link {
    handle: DeviceHandle<GlobalContext>,
    input: u8,
    output: u8,
    alive: AtomicBool,
    writer: Mutex<()>,
    current: Mutex<Option<(u32, Arc<Inbox>)>>,
}
struct Inbox {
    bytes: Mutex<(VecDeque<u8>, bool)>,
    wake: Condvar,
}
impl Inbox {
    fn finish(&self) {
        self.bytes.lock().unwrap().1 = true;
        self.wake.notify_all();
    }
}
impl Link {
    pub fn alive(&self) -> bool {
        self.alive.load(Ordering::Acquire)
    }
    pub fn stop(&self) {
        self.alive.store(false, Ordering::Release);
        if let Some((_, q)) = self.current.lock().unwrap().take() {
            q.finish();
        }
    }
    fn send(&self, kind: u8, id: u32, b: &[u8]) -> io::Result<()> {
        let bytes = frame::encode(kind, id, b)?;
        let _guard = self.writer.lock().unwrap();
        if !self.alive() {
            return Err(io::Error::other("accesorio desconectado"));
        }
        // Una escritura bulk completa por bloque; la cola no se agrega al hilo RT.
        match self
            .handle
            .write_bulk(self.output, &bytes, Duration::from_millis(500))
        {
            Ok(n) if n == bytes.len() => Ok(()),
            other => {
                self.stop();
                Err(io::Error::other(format!(
                    "escritura AOA incompleta: {other:?}"
                )))
            }
        }
    }
    pub fn run(self: Arc<Self>, hub: Weak<Hub>) {
        thread::spawn(move || {
            let mut reader = UsbReader {
                link: self.clone(),
                buf: vec![0; 16384],
                pos: 0,
                len: 0,
            };
            while self.alive() {
                let f = match frame::read(&mut reader) {
                    Ok(f) => f,
                    Err(e) => {
                        if self.alive() {
                            eprintln!("⚠ AOA: {e}; PLAYER puede volver a adb");
                        }
                        break;
                    }
                };
                match f.kind {
                    frame::HELLO => {
                        if protocol::read_hello(&mut f.payload.as_slice()).ok()
                            != Some(protocol::Role::Player)
                        {
                            break;
                        }
                        let q = Arc::new(Inbox {
                            bytes: Mutex::new((VecDeque::new(), false)),
                            wake: Condvar::new(),
                        });
                        if let Some((_, old)) =
                            self.current.lock().unwrap().replace((f.session, q.clone()))
                        {
                            old.finish();
                        }
                        let session = Session {
                            link: self.clone(),
                            id: f.session,
                            inbox: q,
                        };
                        if let Some(hub) = hub.upgrade() {
                            thread::spawn(move || hub.handle_player(PlayerTransport::Aoa(session)));
                        } else {
                            break;
                        }
                    }
                    frame::STATUS | frame::END => {
                        let current = self.current.lock().unwrap();
                        if let Some((id, q)) = current.as_ref().filter(|(id, _)| *id == f.session) {
                            if f.kind == frame::END {
                                q.finish();
                            } else {
                                let mut state = q.bytes.lock().unwrap();
                                if state.0.len() > 4096 {
                                    state.1 = true;
                                } else {
                                    state.0.extend(f.payload);
                                }
                                q.wake.notify_all();
                            }
                            let _ = id;
                        }
                    }
                    _ => break,
                }
            }
            self.stop();
        });
    }
}
struct UsbReader {
    link: Arc<Link>,
    buf: Vec<u8>,
    pos: usize,
    len: usize,
}
impl Read for UsbReader {
    fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
        if b.is_empty() {
            return Ok(0);
        }
        while self.pos == self.len {
            if !self.link.alive() {
                return Ok(0);
            }
            match self.link.handle.read_bulk(
                self.link.input,
                &mut self.buf,
                Duration::from_millis(200),
            ) {
                Ok(0) => continue,
                Ok(n) => {
                    self.pos = 0;
                    self.len = n;
                }
                Err(rusb::Error::Timeout) => continue,
                Err(e) => return Err(usb_error(e)),
            }
        }
        let n = b.len().min(self.len - self.pos);
        b[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
        self.pos += n;
        Ok(n)
    }
}
#[derive(Clone)]
pub struct Session {
    link: Arc<Link>,
    id: u32,
    inbox: Arc<Inbox>,
}
impl Session {
    pub fn send(&self, kind: u8, b: &[u8]) -> io::Result<()> {
        if kind != frame::END && self.inbox.bytes.lock().unwrap().1 {
            return Err(io::Error::other("sesión AOA terminada"));
        }
        self.link.send(kind, self.id, b)
    }
    pub fn close(&self) {
        self.inbox.finish();
        let _ = self.send(frame::END, &[]);
    }
}
impl Read for Session {
    fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
        if b.is_empty() {
            return Ok(0);
        }
        let mut state = self.inbox.bytes.lock().unwrap();
        while state.0.is_empty() && !state.1 {
            let (next, t) = self
                .inbox
                .wake
                .wait_timeout(state, Duration::from_secs(3))
                .unwrap();
            state = next;
            if t.timed_out() {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "sin estado PLAYER AOA",
                ));
            }
        }
        let n = b.len().min(state.0.len());
        for dest in &mut b[..n] {
            *dest = state.0.pop_front().unwrap();
        }
        Ok(n)
    }
}
