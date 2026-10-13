//! El Hub conserva el mismo PLAYER y lazo de reloj con sockets o sesiones del accesorio.
use crate::aoa::Session;
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpStream};

pub enum PlayerTransport {
    Adb(TcpStream),
    Aoa(Session),
}
impl PlayerTransport {
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
