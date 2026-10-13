//! El Hub conserva el mismo PLAYER y lazo de reloj con sockets o sesiones del accesorio.
use crate::aoa::Session;
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpStream};


/// Preferencia opcional por CONTROL; las sesiones PLAYER siguen decidiendo el transporte real.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TransportChoice { Aoa, Adb }
impl TransportChoice {
    pub fn parse(value: &str) -> Option<Self> {
        match value { "aoa" => Some(Self::Aoa), "adb" => Some(Self::Adb), _ => None }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TransportReason { Reconnect, Unavailable, Disconnected }
impl TransportReason {
    fn wire(self) -> &'static str {
        match self { Self::Reconnect => "reconnect", Self::Unavailable => "unavailable", Self::Disconnected => "disconnected" }
    }
}
/// No conmutar desde CONTROL: reenumerar cortaría adb y podría apagar el modo o CAPTURE.
pub fn advertisement(choice: TransportChoice, aoa_alive: bool, reason: TransportReason) -> String {
    match choice {
        TransportChoice::Adb => "PLAYER_TRANSPORT adb selected".into(),
        TransportChoice::Aoa if aoa_alive => "PLAYER_TRANSPORT aoa".into(),
        TransportChoice::Aoa => format!("PLAYER_TRANSPORT adb {}", reason.wire()),
    }
}

pub enum PlayerTransport {
    Adb(TcpStream),
    Aoa(Session),
}
impl PlayerTransport {
    pub fn is_current(&self) -> bool {
        match self {
            Self::Adb(_) => true, // El Hub valida el ID y cierra el socket reemplazado.
            Self::Aoa(s) => s.is_current(),
        }
    }
    pub fn try_clone(&self) -> io::Result<Self> {
        Ok(match self {
            Self::Adb(s) => Self::Adb(s.try_clone()?),
            Self::Aoa(s) => Self::Aoa(s.clone()),
        })
    }
    pub fn close(&self) {
        match self {
            Self::Adb(s) => {
                let _ = s.shutdown(Shutdown::Both);
            }
            Self::Aoa(s) => s.close(),
        }
    }
    pub fn format(&mut self, b: &[u8]) -> io::Result<()> {
        match self {
            Self::Adb(s) => s.write_all(b),
            Self::Aoa(s) => s.send(crate::aoa_frame::FORMAT, b),
        }
    }
    pub fn label(&self) -> &'static str {
        match self {
            Self::Adb(_) => "adb",
            Self::Aoa(_) => "AOA",
        }
    }
}
impl Read for PlayerTransport {
    fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
        match self {
            Self::Adb(s) => s.read(b),
            Self::Aoa(s) => s.read(b),
        }
    }
}
impl Write for PlayerTransport {
    fn write(&mut self, b: &[u8]) -> io::Result<usize> {
        match self {
            Self::Adb(s) => s.write(b),
            Self::Aoa(s) => {
                s.send(crate::aoa_frame::PCM, b)?;
                Ok(b.len())
            }
        }
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_only_known_transport_arguments() {
        assert_eq!(TransportChoice::parse("aoa"), Some(TransportChoice::Aoa));
        assert_eq!(TransportChoice::parse("adb"), Some(TransportChoice::Adb));
        for invalid in ["", "AOA", "wifi", " adb", "adb ", "aoa extra", "adb selected"] {
            assert_eq!(TransportChoice::parse(invalid), None, "{invalid}");
        }
    }

    #[test]
    fn forced_adb_wins_over_live_aoa_link() {
        assert_eq!(advertisement(TransportChoice::Adb, true, TransportReason::Unavailable),
                   "PLAYER_TRANSPORT adb selected");
        assert_eq!(advertisement(TransportChoice::Adb, false, TransportReason::Disconnected),
                   "PLAYER_TRANSPORT adb selected");
    }

    #[test]
    fn aoa_recovery_returns_candidate_without_old_fallback_reason() {
        assert_eq!(advertisement(TransportChoice::Aoa, false, TransportReason::Unavailable),
                   "PLAYER_TRANSPORT adb unavailable");
        assert_eq!(advertisement(TransportChoice::Aoa, true, TransportReason::Unavailable),
                   "PLAYER_TRANSPORT aoa");
    }

    #[test]
    fn missing_accessory_mode_or_device_explains_fallback() {
        assert_eq!(advertisement(TransportChoice::Aoa, false, TransportReason::Reconnect),
                   "PLAYER_TRANSPORT adb reconnect");
        assert_eq!(advertisement(TransportChoice::Aoa, false, TransportReason::Disconnected),
                   "PLAYER_TRANSPORT adb disconnected");
    }

    #[test]
    fn control_stream_preserves_transport_requests_and_following_mode() {
        let mut input = std::io::BufReader::with_capacity(3,
            &b"TRANSPORT adb\nTRANSPORT aoa\r\nTRANSPORT wifi\nMODE pc2phone\n"[..]);
        let choices: Vec<_> = (0..3).map(|_| {
            let line = crate::protocol::read_line(&mut input).unwrap().unwrap();
            let (cmd, arg) = line.split_once(' ').unwrap();
            assert_eq!(cmd, "TRANSPORT");
            TransportChoice::parse(arg)
        }).collect();
        assert_eq!(choices, vec![Some(TransportChoice::Adb), Some(TransportChoice::Aoa), None]);
        assert_eq!(crate::protocol::read_line(&mut input).unwrap().as_deref(), Some("MODE pc2phone"));
        assert_eq!(crate::protocol::read_line(&mut input).unwrap(), None);
    }
}
