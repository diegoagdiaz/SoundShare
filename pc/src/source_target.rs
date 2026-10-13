//! Objetivo del ring Móvil → PC: crece ante cortes y recupera despacio el nivel inicial.
//!
//! El reloj de la recuperación son los frames reproducidos sin cortes. El tiempo de
//! llenado no cuenta, y una cola con poco margen reinicia la espera. No reserva memoria.

const MIN_S: f64 = 0.026;
const STEP_S: f64 = 0.004;
const MAX_S: f64 = 0.120;
const RECOVERY_S: u64 = 60;

pub struct SourceTarget {
    seconds: f64,
    stable_frames: u64,
    recovery_frames: u64,
    step_frames: usize,
}

impl SourceTarget {
    pub fn new(rate: u32) -> SourceTarget {
        SourceTarget {
            seconds: MIN_S,
            stable_frames: 0,
            recovery_frames: RECOVERY_S * rate as u64,
            step_frames: (STEP_S * rate as f64).ceil() as usize,
        }
    }

    pub fn seconds(&self) -> f64 {
        self.seconds
    }

    pub fn reset_stability(&mut self) {
        self.stable_frames = 0;
    }

    pub fn on_underrun(&mut self) {
        self.seconds = (self.seconds + STEP_S).min(MAX_S);
        self.reset_stability();
    }

    /// Solo ciclos completos de audio: `remaining` es la cola después de consumir
    /// `played` frames. Para bajar deben sobrar el paso de 4 ms y otro ciclo completo.
    /// El lazo de reloj drena ese paso gradualmente, sin descartar muestras.
    pub fn on_playback(&mut self, played: usize, remaining: usize) {
        if played == 0 || self.seconds <= MIN_S {
            return;
        }
        if remaining < self.step_frames.saturating_add(played) {
            self.reset_stability();
            return;
        }
        self.stable_frames = self.stable_frames.saturating_add(played as u64);
        if self.stable_frames >= self.recovery_frames {
            self.seconds = (self.seconds - STEP_S).max(MIN_S);
            self.reset_stability();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RATE: u32 = 48_000;
    const CYCLE: usize = 480;
    const HEADROOM: usize = 2_400;

    fn play(target: &mut SourceTarget, cycles: usize) {
        for _ in 0..cycles {
            target.on_playback(CYCLE, HEADROOM);
        }
    }

    fn assert_target(target: &SourceTarget, seconds: f64) {
        assert!((target.seconds() - seconds).abs() < 1e-12);
    }

    #[test]
    fn waits_for_a_full_minute_of_safe_playback() {
        let mut target = SourceTarget::new(RATE);
        target.on_underrun();
        play(&mut target, 5_999);
        assert_target(&target, 0.030);
        play(&mut target, 1);
        assert_target(&target, MIN_S);
    }

    #[test]
    fn low_headroom_requires_a_new_stable_window() {
        let mut target = SourceTarget::new(RATE);
        target.on_underrun();
        play(&mut target, 5_999);
        target.on_playback(CYCLE, 192 + CYCLE - 1);
        play(&mut target, 5_999);
        assert_target(&target, 0.030);
        target.on_playback(CYCLE, 192 + CYCLE);
        assert_target(&target, MIN_S);
    }

    #[test]
    fn repeated_underruns_restart_the_wait_and_keep_the_larger_target() {
        let mut target = SourceTarget::new(RATE);
        target.on_underrun();
        play(&mut target, 5_999);
        target.on_underrun();
        assert_target(&target, 0.034);
        play(&mut target, 5_999);
        assert_target(&target, 0.034);
        play(&mut target, 1);
        assert_target(&target, 0.030);
        play(&mut target, 5_999);
        assert_target(&target, 0.030);
        play(&mut target, 1);
        assert_target(&target, MIN_S);
    }

    #[test]
    fn backlog_catch_up_restarts_stability_without_increasing_the_target() {
        let mut target = SourceTarget::new(RATE);
        target.on_underrun();
        play(&mut target, 5_999);
        target.reset_stability();
        play(&mut target, 5_999);
        assert_target(&target, 0.030);
        play(&mut target, 1);
        assert_target(&target, MIN_S);
    }

    #[test]
    fn clamps_cuts_and_recovery_to_the_original_limits() {
        let mut target = SourceTarget::new(RATE);
        play(&mut target, 12_000);
        assert_target(&target, MIN_S);
        for _ in 0..100 {
            target.on_underrun();
        }
        assert_target(&target, MAX_S);
        for _ in 0..24 {
            play(&mut target, 6_000);
        }
        assert_target(&target, MIN_S);
        play(&mut target, 6_000);
        assert_target(&target, MIN_S);
    }

    #[test]
    fn recovery_uses_audio_duration_at_the_stream_sample_rate() {
        let mut target = SourceTarget::new(8_000);
        target.on_underrun();
        // 80 frames = 10 ms y el paso de 4 ms ocupa 32 frames a 8 kHz.
        for _ in 0..5_999 {
            target.on_playback(80, 112);
        }
        assert_target(&target, 0.030);
        target.on_playback(80, 112);
        assert_target(&target, MIN_S);
    }

    #[test]
    fn empty_callbacks_do_not_advance_recovery() {
        let mut target = SourceTarget::new(RATE);
        target.on_underrun();
        play(&mut target, 5_999);
        for _ in 0..100 {
            target.on_playback(0, HEADROOM);
        }
        assert_target(&target, 0.030);
    }
}
