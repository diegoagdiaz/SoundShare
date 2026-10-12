//! Buffer circular de bytes sin locks para exactamente un productor y un consumidor.
//!
//! Lo usa el hilo de tiempo real de PipeWire, que nunca puede bloquearse: ni push ni pop
//! toman locks ni reservan memoria.

use std::cell::UnsafeCell;
use std::sync::atomic::{AtomicUsize, Ordering};

pub struct Ring {
    buf: Box<[UnsafeCell<u8>]>,
    mask: usize,
    /// Total escrito (solo lo avanza el productor).
    head: AtomicUsize,
    /// Total leído (solo lo avanza el consumidor).
    tail: AtomicUsize,
}

// Seguro mientras se respete un único productor y un único consumidor: cada lado
// solo toca la región que el otro ya le cedió mediante head/tail.
unsafe impl Sync for Ring {}
unsafe impl Send for Ring {}

impl Ring {
    pub fn new(min_capacity: usize) -> Ring {
        let cap = min_capacity.next_power_of_two();
        Ring {
            buf: (0..cap).map(|_| UnsafeCell::new(0)).collect(),
            mask: cap - 1,
            head: AtomicUsize::new(0),
            tail: AtomicUsize::new(0),
        }
    }

    pub fn capacity(&self) -> usize {
        self.mask + 1
    }

    /// Bytes disponibles para leer. Un observador ajeno a los dos extremos puede leer
    /// contadores de instantes distintos; en ese caso devuelve 0, nunca más que la capacidad.
    pub fn len(&self) -> usize {
        let n = self.head.load(Ordering::Acquire).wrapping_sub(self.tail.load(Ordering::Acquire));
        if n <= self.capacity() { n } else { 0 }
    }

    fn ptr(&self) -> *mut u8 {
        // UnsafeCell es transparente: no materializamos una referencia mutable al buffer.
        self.buf.as_ptr().cast::<u8>().cast_mut()
    }

    /// Productor: copia todos los frames completos que entren y devuelve los bytes escritos.
    pub fn push_frames(&self, data: &[u8], frame_bytes: usize) -> usize {
        let head = self.head.load(Ordering::Relaxed);
        let tail = self.tail.load(Ordering::Acquire);
        let free = self.capacity() - head.wrapping_sub(tail);
        let n = data.len().min(free) / frame_bytes * frame_bytes;
        if n == 0 {
            return 0;
        }
        let start = head & self.mask;
        let first = n.min(self.capacity() - start);
        unsafe {
            std::ptr::copy_nonoverlapping(data.as_ptr(), self.ptr().add(start), first);
            std::ptr::copy_nonoverlapping(data.as_ptr().add(first), self.ptr(), n - first);
        }
        self.head.store(head.wrapping_add(n), Ordering::Release);
        n
    }

    /// Consumidor: copia hasta `out.len()` bytes y devuelve cuántos leyó.
    pub fn pop(&self, out: &mut [u8]) -> usize {
        let tail = self.tail.load(Ordering::Relaxed);
        let head = self.head.load(Ordering::Acquire);
        let n = out.len().min(head.wrapping_sub(tail));
        if n == 0 {
            return 0;
        }
        let start = tail & self.mask;
        let first = n.min(self.capacity() - start);
        unsafe {
            std::ptr::copy_nonoverlapping(self.ptr().add(start), out.as_mut_ptr(), first);
            std::ptr::copy_nonoverlapping(self.ptr(), out.as_mut_ptr().add(first), n - first);
        }
        self.tail.store(tail.wrapping_add(n), Ordering::Release);
        n
    }

    /// Consumidor: descarta hasta `n` bytes.
    pub fn skip(&self, n: usize) -> usize {
        let tail = self.tail.load(Ordering::Relaxed);
        let n = n.min(self.head.load(Ordering::Acquire).wrapping_sub(tail));
        self.tail.store(tail.wrapping_add(n), Ordering::Release);
        n
    }

    /// Consumidor: vacía el buffer.
    pub fn clear(&self) {
        self.tail.store(self.head.load(Ordering::Acquire), Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn wraps_and_keeps_order() {
        let r = Ring::new(16);
        let mut out = [0u8; 16];
        for round in 0..10u8 {
            let data: Vec<u8> = (0..12).map(|i| round.wrapping_mul(31).wrapping_add(i)).collect();
            assert_eq!(r.push_frames(&data, 4), 12);
            assert_eq!(r.pop(&mut out[..12]), 12);
            assert_eq!(&out[..12], &data[..]);
        }
    }

    #[test]
    fn only_whole_frames() {
        let r = Ring::new(16);
        assert_eq!(r.push_frames(&[1; 10], 4), 8);
        assert_eq!(r.push_frames(&[2; 12], 4), 8);
        assert_eq!(r.len(), 16);
        assert_eq!(r.push_frames(&[3; 4], 4), 0);
        assert_eq!(r.skip(4), 4);
        r.clear();
        assert_eq!(r.len(), 0);
    }

    #[test]
    fn observer_with_inconsistent_counters() {
        let r = Ring::new(16);
        // Un observador leyó head antes de que ambos extremos avanzaran.
        r.head.store(12, Ordering::Relaxed);
        r.tail.store(16, Ordering::Relaxed);
        assert_eq!(r.len(), 0);
        // El desborde de los contadores sigue representando una cola válida.
        r.head.store(4, Ordering::Relaxed);
        r.tail.store(usize::MAX - 3, Ordering::Relaxed);
        assert_eq!(r.len(), 8);
    }

    #[test]
    fn threads() {
        let r = Arc::new(Ring::new(1024));
        let p = r.clone();
        let total = 1_000_000usize;
        let observer_ring = r.clone();
        let done = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let observer_done = done.clone();
        let observer = std::thread::spawn(move || {
            while !observer_done.load(Ordering::Relaxed) {
                assert!(observer_ring.len() <= observer_ring.capacity());
            }
        });
        let t = std::thread::spawn(move || {
            let mut i = 0usize;
            while i < total {
                let chunk: Vec<u8> = (i..(i + 64).min(total)).map(|v| v as u8).collect();
                let mut off = 0;
                while off < chunk.len() {
                    off += p.push_frames(&chunk[off..], 1);
                }
                i += chunk.len();
            }
        });
        let mut got = 0usize;
        let mut buf = [0u8; 100];
        while got < total {
            let n = r.pop(&mut buf);
            for b in &buf[..n] {
                assert_eq!(*b, got as u8);
                got += 1;
            }
        }
        t.join().unwrap();
        done.store(true, Ordering::Relaxed);
        observer.join().unwrap();
    }
}
