//! Lazo de control que engancha dos relojes de audio.
//!
//! El cristal de la PC y el del teléfono nunca van exactamente a la misma velocidad
//! (difieren en decenas de partes por millón). Sin corrección, el buffer intermedio
//! se vacía (cortes) o crece sin límite (latencia). Este lazo mide el nivel del buffer
//! y devuelve una corrección de velocidad diminuta que el resampler adaptativo de PipeWire
//! aplica: la diferencia de afinación resultante es inaudible.

pub struct RateLoop {
    kp: f64,
    ki: f64,
    tau: f64,
    max_dev: f64,
    /// Dos etapas de filtro pasabajos en cascada (rechazan mejor el serrucho).
    stage: Option<(f64, f64)>,
    integral: f64,
}

impl RateLoop {
    /// `bandwidth_hz`: qué tan rápido corrige (más alto = más rápido, más nervioso).
    /// `max_ppm`: corrección máxima permitida, en partes por millón.
    /// `smoothing_s`: constante de tiempo del filtro de la medición.
    ///
    /// La medición del buffer es un "serrucho" (los datos llegan en bloques); si ese ruido
    /// pasa a la corrección, el resampler modula la afinación y se oye como distorsión.
    /// Por eso el filtro es lento y el lazo todavía más: la deriva real es casi constante.
    pub fn new(bandwidth_hz: f64, max_ppm: f64, smoothing_s: f64) -> RateLoop {
        // Sistema de segundo orden críticamente amortiguado: e'' + kp e' + ki e = 0.
        let wn = 2.0 * std::f64::consts::PI * bandwidth_hz;
        RateLoop {
            kp: 2.0 * wn,
            ki: wn * wn,
            tau: smoothing_s / 2.0,
            max_dev: max_ppm * 1e-6,
            stage: None,
            integral: 0.0,
        }
    }

    /// Arranca con el filtro ya asentado en `level` (el promedio esperado), en vez de
    /// tomar la primera medición cruda, que puede caer en un pico del serrucho.
    pub fn prime(&mut self, level: f64) {
        self.stage = Some((level, level));
        self.integral = 0.0;
    }

    /// Nivel suavizado del buffer (segundos), si ya hubo mediciones.
    pub fn level(&self) -> Option<f64> {
        self.stage.map(|(_, b)| b)
    }

    /// `level` y `target` en segundos de audio, `dt` en segundos desde la medición anterior.
    ///
    /// Devuelve la desviación de velocidad para el lado que *vacía* el buffer:
    /// positiva = consumir más rápido (hay de más), negativa = más lento (falta).
    pub fn update(&mut self, level: f64, target: f64, dt: f64) -> f64 {
        let dt = dt.clamp(1e-4, 0.5);
        let k = dt / (self.tau + dt);
        let (a, b) = match self.stage {
            None => (level, level),
            Some((a, b)) => {
                let a = a + (level - a) * k;
                (a, b + (a - b) * k)
            }
        };
        self.stage = Some((a, b));
        let filtered = b;

        let error = filtered - target;
        self.integral += error * dt;
        // Anti-windup: el integrador solo puede pedir hasta la corrección máxima.
        let lim = self.max_dev / self.ki;
        self.integral = self.integral.clamp(-lim, lim);

        (self.kp * error + self.ki * self.integral).clamp(-self.max_dev, self.max_dev)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Simula un buffer que se llena a un ritmo y se vacía a otro con 80 ppm de diferencia.
    #[test]
    fn locks_onto_clock_drift() {
        let drift = 80e-6;
        let target = 0.020;
        let dt = 0.010;
        let mut l = RateLoop::new(0.01, 1000.0, 3.0);
        let mut level: f64 = 0.030;
        for _ in 0..(600.0 / dt) as usize {
            let dev = l.update(level, target, dt);
            // entra (1 + drift), sale (1 + dev)
            level += ((1.0 + drift) - (1.0 + dev)) * dt;
        }
        assert!((level - target).abs() < 0.0005, "nivel final {level}");
        let dev = l.update(level, target, dt);
        assert!((dev - drift).abs() < 5e-6, "corrección final {dev}");
    }
}

#[cfg(test)]
mod ripple_tests {
    use super::*;

    /// Un serrucho de ±10 ms a 47 Hz (bloques de 21 ms), muestreado cada 5,3 ms (con aliasing
    /// a 0,5 Hz, peor que el caso real) apenas debe mover la corrección: 25 ppm de pico a pico
    /// son 0,0025 % de "wow", muy por debajo de lo audible (~0,05 %).
    #[test]
    fn ignores_block_ripple() {
        let dt = 256.0 / 48_000.0;
        let mut l = RateLoop::new(0.01, 1000.0, 3.0);
        let (mut lo, mut hi) = (f64::MAX, f64::MIN);
        for i in 0..(120.0 / dt) as usize {
            let t = i as f64 * dt;
            let saw = ((t * 47.0).fract() - 0.5) * 0.020;
            let dev = l.update(0.030 + saw, 0.030, dt);
            if t > 60.0 {
                lo = lo.min(dev);
                hi = hi.max(dev);
            }
        }
        assert!((hi - lo) < 25e-6, "la corrección oscila {:.2} ppm", (hi - lo) * 1e6);
    }
}
