//! El Hub conserva el mismo PLAYER y lazo de reloj con sockets (adb o WiFi) o sesiones del accesorio.
use crate::aoa::Session;
use crate::protocol::{Format, Sample};
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
    Wifi(TcpStream),
}
impl PlayerTransport {
    pub fn is_current(&self) -> bool {
        match self {
            Self::Adb(_) | Self::Wifi(_) => true, // El Hub valida el ID y cierra el socket reemplazado.
            Self::Aoa(s) => s.is_current(),
        }
    }
    pub fn try_clone(&self) -> io::Result<Self> {
        Ok(match self {
            Self::Adb(s) => Self::Adb(s.try_clone()?),
            Self::Aoa(s) => Self::Aoa(s.clone()),
            Self::Wifi(s) => Self::Wifi(s.try_clone()?),
        })
    }
    pub fn close(&self) {
        match self {
            Self::Adb(s) | Self::Wifi(s) => {
                let _ = s.shutdown(Shutdown::Both);
            }
            Self::Aoa(s) => s.close(),
        }
    }
    pub fn format(&mut self, b: &[u8]) -> io::Result<()> {
        match self {
            Self::Adb(s) | Self::Wifi(s) => s.write_all(b),
            Self::Aoa(s) => s.send(crate::aoa_frame::FORMAT, b),
        }
    }
    /// El ring sigue en float; WiFi usa S16 en el cable para reducir el caudal a la mitad.
    pub fn pcm_format(&self, source: Format) -> Format {
        if matches!(self, Self::Wifi(_)) { Format { sample: Sample::S16LE, ..source } } else { source }
    }
    pub fn write_pcm(&mut self, source: &[u8], converted: &mut Vec<u8>) -> io::Result<()> {
        match self {
            Self::Wifi(socket) => {
                f32le_to_s16le(source, converted)?;
                socket.write_all(converted)
            }
            _ => self.write_all(source),
        }
    }
    pub fn label(&self) -> &'static str {
        match self {
            Self::Adb(_) => "adb",
            Self::Aoa(_) => "AOA",
            Self::Wifi(_) => "WiFi",
        }
    }
}

/// Conversión en el hilo emisor, sin tocar PipeWire ni reservar una vez calentado el buffer.
fn f32le_to_s16le(source: &[u8], converted: &mut Vec<u8>) -> io::Result<()> {
    if !source.len().is_multiple_of(4) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "PCM float incompleto"));
    }
    converted.resize(source.len() / 2, 0);
    for (input, output) in source.as_chunks::<4>().0.iter().zip(converted.as_chunks_mut::<2>().0.iter_mut()) {
        let sample = f32::from_le_bytes(*input);
        // El cast saturado recorta +1 a 32767 y convierte NaN a silencio.
        let pcm = (sample.clamp(-1.0, 1.0) * 32768.0).round() as i16;
        output.copy_from_slice(&pcm.to_le_bytes());
    }
    Ok(())
}
impl Read for PlayerTransport {
    fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
        match self {
            Self::Adb(s) | Self::Wifi(s) => s.read(b),
            Self::Aoa(s) => s.read(b),
        }
    }
}
impl Write for PlayerTransport {
    fn write(&mut self, b: &[u8]) -> io::Result<usize> {
        match self {
            Self::Adb(s) | Self::Wifi(s) => s.write(b),
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

    fn float_bytes(samples: &[f32]) -> Vec<u8> {
        samples.iter().flat_map(|v| v.to_le_bytes()).collect()
    }

    #[test]
    fn wifi_pcm_conversion_clips_rounds_and_encodes_little_endian() {
        let samples = [f32::NEG_INFINITY, -2.0, -1.0, -0.5, -1.0 / 65536.0,
            0.0, 1.0 / 65536.0, 0.5, 1.0, 2.0, f32::INFINITY, f32::NAN];
        let expected: [i16; 12] = [-32768, -32768, -32768, -16384, -1, 0, 1, 16384, 32767, 32767, 32767, 0];
        let mut converted = Vec::new();
        f32le_to_s16le(&float_bytes(&samples), &mut converted).unwrap();
        assert_eq!(converted, expected.iter().flat_map(|v| v.to_le_bytes()).collect::<Vec<_>>());
        assert_eq!(&converted[6..10], &[0x00, 0xc0, 0xff, 0xff]);
        assert!(f32le_to_s16le(&[0; 3], &mut converted).is_err());
    }

    #[test]
    fn only_wifi_changes_the_pcm_format_and_wire_payload() {
        for wifi in [false, true] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let mut reader = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
            reader.set_read_timeout(Some(std::time::Duration::from_secs(2))).unwrap();
            let writer = listener.accept().unwrap().0;
            let mut transport = if wifi { PlayerTransport::Wifi(writer) } else { PlayerTransport::Adb(writer) };
            let format = transport.pcm_format(crate::engine::SINK_FORMAT);
            assert_eq!(format.rate, 48000);
            assert_eq!(format.channels, 2);
            assert_eq!(format.sample, if wifi { Sample::S16LE } else { Sample::F32LE });
            transport.format(&format.encode()).unwrap();
            assert_eq!(Format::read(&mut reader).unwrap().sample, format.sample);
            let source = float_bytes(&[-0.5, 0.5]);
            let mut converted = Vec::new();
            transport.write_pcm(&source, &mut converted).unwrap();
            let expected = if wifi { vec![0x00, 0xc0, 0x00, 0x40] } else { source };
            let mut actual = vec![0; expected.len()];
            reader.read_exact(&mut actual).unwrap();
            assert_eq!(actual, expected);
            // El segundo bloque reutiliza el buffer y conserva el orden estéreo.
            transport.write_pcm(&float_bytes(&[0.25, -0.25]), &mut converted).unwrap();
            if wifi {
                reader.read_exact(&mut actual).unwrap();
                assert_eq!(actual, [0x00, 0x20, 0x00, 0xe0]);
            }
        }
    }

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
