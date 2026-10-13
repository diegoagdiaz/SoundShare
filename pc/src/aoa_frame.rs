//! Envoltura AOA v1. Cada transferencia termina en un byte de padding: tamaño impar,
//! nunca múltiplo del paquete bulk (64/512/1024), sin necesitar un ZLP separado.
use std::io::{self, Read};

pub const HELLO: u8 = 1;
pub const FORMAT: u8 = 2;
pub const PCM: u8 = 3;
pub const STATUS: u8 = 4;
pub const END: u8 = 5;
pub const MAX_PCM: usize = 8192;

#[derive(Debug, PartialEq)]
pub struct Frame {
    pub kind: u8,
    pub session: u32,
    pub payload: Vec<u8>,
}

fn valid(kind: u8, len: usize) -> bool {
    match kind {
        HELLO => len == 8,
        FORMAT => len == 12,
        PCM => len > 0 && len <= MAX_PCM && len.is_multiple_of(8),
        STATUS => len == 16,
        END => len == 0,
        _ => false,
    }
}
fn invalid() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, "bloque AOA inválido")
}

pub fn encode(kind: u8, session: u32, payload: &[u8]) -> io::Result<Vec<u8>> {
    if session == 0 || !valid(kind, payload.len()) {
        return Err(invalid());
    }
    let mut b = Vec::with_capacity(13 + payload.len());
    b.extend_from_slice(b"SSAO");
    b.extend_from_slice(&[1, kind]);
    b.extend_from_slice(&(payload.len() as u16).to_le_bytes());
    b.extend_from_slice(&session.to_le_bytes());
    b.extend_from_slice(payload);
    b.push(0);
    Ok(b)
}

pub fn read(input: &mut impl Read) -> io::Result<Frame> {
    let mut h = [0; 12];
    input.read_exact(&mut h)?;
    let len = u16::from_le_bytes([h[6], h[7]]) as usize;
    let session = u32::from_le_bytes(h[8..12].try_into().unwrap());
    if &h[..4] != b"SSAO" || h[4] != 1 || session == 0 || !valid(h[5], len) {
        return Err(invalid());
    }
    let mut payload = vec![0; len];
    input.read_exact(&mut payload)?;
    let mut pad = [0];
    input.read_exact(&mut pad)?;
    if pad != [0] {
        return Err(invalid());
    }
    Ok(Frame {
        kind: h[5],
        session,
        payload,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roundtrip_and_short_packets() {
        for (kind, size) in [(HELLO, 8), (FORMAT, 12), (STATUS, 16), (END, 0)]
            .into_iter()
            .chain((8..=MAX_PCM).step_by(8).map(|n| (PCM, n)))
        {
            let data = vec![42; size];
            let encoded = encode(kind, 19, &data).unwrap();
            for packet in [64, 512, 1024] {
                assert_ne!(encoded.len() % packet, 0);
            }
            assert_eq!(
                read(&mut encoded.as_slice()).unwrap(),
                Frame {
                    kind,
                    session: 19,
                    payload: data
                }
            );
        }
    }
    #[test]
    fn cross_language_vector() {
        assert_eq!(
            encode(END, 0x12345678, &[]).unwrap(),
            [83, 83, 65, 79, 1, 5, 0, 0, 0x78, 0x56, 0x34, 0x12, 0]
        );
    }
    #[test]
    fn fragmented_and_coalesced() {
        struct Slow<'a>(&'a [u8]);
        impl Read for Slow<'_> {
            fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
                let n = b.len().min(1);
                self.0.read(&mut b[..n])
            }
        }
        let mut bytes = encode(FORMAT, 1, &[0; 12]).unwrap();
        bytes.extend(encode(PCM, 1, &[7; 2048]).unwrap());
        let mut slow = Slow(&bytes);
        assert_eq!(read(&mut slow).unwrap().kind, FORMAT);
        assert_eq!(read(&mut slow).unwrap().payload.len(), 2048);
    }
    #[test]
    fn rejects_invalid_and_truncated() {
        assert!(encode(PCM, 1, &[0; 512 + 1]).is_err());
        assert!(encode(HELLO, 0, &[0; 8]).is_err());
        for offset in [0, 4, 5, 6, 20] {
            let mut b = encode(HELLO, 1, &[0; 8]).unwrap();
            b[offset] = 255;
            assert!(read(&mut b.as_slice()).is_err());
        }
        let b = encode(PCM, 1, &[0; 2048]).unwrap();
        for len in 0..b.len() {
            assert!(read(&mut &b[..len]).is_err());
        }
    }
}
