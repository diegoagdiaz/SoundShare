//! Protocolo entre la PC y el teléfono (ver docs/PROTOCOLO.md).
//!
//! El teléfono siempre abre las conexiones hacia 127.0.0.1:PORT de su lado; `adb reverse`
//! las trae por el USB hasta este servidor.

use std::io::{self, BufRead, Read};

pub const PORT: u16 = 47100;
pub const VERSION: u8 = 1;
const MAGIC: &[u8; 4] = b"SNDS";

pub const STATUS_BYTES: usize = 16;
pub const MAX_LINE_BYTES: usize = 4096;

/// Lee una línea sin reservar memoria ilimitada si el otro extremo no manda LF.
pub fn read_line(r: &mut impl BufRead) -> io::Result<Option<String>> {
    let mut b = Vec::new();
    let n = r.take((MAX_LINE_BYTES + 1) as u64).read_until(b'\n', &mut b)?;
    if n == 0 {
        return Ok(None);
    }
    if n > MAX_LINE_BYTES {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "línea de control demasiado larga"));
    }
    if b.last() == Some(&b'\n') { b.pop(); }
    if b.last() == Some(&b'\r') { b.pop(); }
    String::from_utf8(b).map(Some).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    /// Canal de control de la app (líneas de texto).
    Control,
    /// La app pide el audio de la PC para reproducirlo.
    Player,
    /// El capturador (shell) manda el audio del teléfono.
    Capture,
}

pub fn read_hello(r: &mut impl Read) -> io::Result<Role> {
    let mut b = [0u8; 8];
    r.read_exact(&mut b)?;
    if &b[..4] != MAGIC {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "no es un cliente de SoundShare"));
    }
    if b[4] != VERSION {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("versión de protocolo {} (la PC usa {VERSION}); actualizá la app", b[4]),
        ));
    }
    match b[5] {
        1 => Ok(Role::Control),
        2 => Ok(Role::Player),
        3 => Ok(Role::Capture),
        r => Err(io::Error::new(io::ErrorKind::InvalidData, format!("rol desconocido {r}"))),
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Sample {
    S16LE,
    F32LE,
}

impl Sample {
    pub fn bytes(self) -> usize {
        match self {
            Sample::S16LE => 2,
            Sample::F32LE => 4,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Format {
    pub rate: u32,
    pub channels: u32,
    pub sample: Sample,
}

impl Format {
    pub fn frame_bytes(&self) -> usize {
        self.channels as usize * self.sample.bytes()
    }

    pub fn encode(&self) -> [u8; 12] {
        let mut b = [0u8; 12];
        b[..4].copy_from_slice(&self.rate.to_le_bytes());
        b[4] = self.channels as u8;
        b[5] = match self.sample {
            Sample::S16LE => 1,
            Sample::F32LE => 2,
        };
        b
    }

    pub fn read(r: &mut impl Read) -> io::Result<Format> {
        let mut b = [0u8; 12];
        r.read_exact(&mut b)?;
        let rate = u32::from_le_bytes(b[..4].try_into().unwrap());
        let channels = b[4] as u32;
        let sample = match b[5] {
            1 => Sample::S16LE,
            2 => Sample::F32LE,
            s => return Err(io::Error::new(io::ErrorKind::InvalidData, format!("muestra desconocida {s}"))),
        };
        if !(8000..=384_000).contains(&rate) || !(1..=2).contains(&channels) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("formato no soportado: {rate} Hz, {channels} canales"),
            ));
        }
        Ok(Format { rate, channels, sample })
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Mode {
    #[default]
    Off,
    PcToPhone,
    PhoneToPc,
}

impl Mode {
    pub fn wire(self) -> &'static str {
        match self {
            Mode::Off => "off",
            Mode::PcToPhone => "pc2phone",
            Mode::PhoneToPc => "phone2pc",
        }
    }

    pub fn parse(s: &str) -> Option<Mode> {
        match s.trim() {
            "off" => Some(Mode::Off),
            "pc2phone" => Some(Mode::PcToPhone),
            "phone2pc" => Some(Mode::PhoneToPc),
            _ => None,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Mode::Off => "apagado",
            Mode::PcToPhone => "PC → Móvil",
            Mode::PhoneToPc => "Móvil → PC",
        }
    }
}

/// Estado del reproductor del teléfono, cada ~10 ms.
#[derive(Clone, Copy, Debug, Default)]
pub struct PlayerStatus {
    /// Frames pendientes en el teléfono (AudioTrack + socket).
    pub queued: i32,
    /// Cola que el teléfono quiere mantener.
    pub target: i32,
    pub underruns: i32,
    /// Desde que el teléfono recibe un frame hasta que suena (-1 si no lo sabe).
    pub output_latency: i32,
}

impl PlayerStatus {
    pub fn decode(b: &[u8; STATUS_BYTES]) -> PlayerStatus {
        let i = |o: usize| i32::from_le_bytes(b[o..o + 4].try_into().unwrap());
        PlayerStatus { queued: i(0), target: i(4), underruns: i(8), output_latency: i(12) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufReader, Cursor};

    #[test]
    fn bounded_lines_keep_next_command() {
        let mut data = vec![b'x'; MAX_LINE_BYTES - 1];
        data.extend_from_slice(b"\nMODE off\r\n");
        let mut r = BufReader::with_capacity(7, Cursor::new(data));
        assert_eq!(read_line(&mut r).unwrap().unwrap().len(), MAX_LINE_BYTES - 1);
        assert_eq!(read_line(&mut r).unwrap().as_deref(), Some("MODE off"));
        assert_eq!(read_line(&mut r).unwrap(), None);
    }

    #[test]
    fn rejects_long_line_without_lf() {
        let mut r = Cursor::new(vec![b'x'; MAX_LINE_BYTES * 2]);
        assert_eq!(read_line(&mut r).unwrap_err().kind(), io::ErrorKind::InvalidData);
        assert_eq!(r.position(), (MAX_LINE_BYTES + 1) as u64);
    }
}
