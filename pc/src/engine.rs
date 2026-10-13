//! Motor de audio: todo lo que toca PipeWire vive en un hilo propio con su main loop.
//!
//! - PC → Móvil: un sink virtual ("SoundShare → Móvil") que pasa a ser la salida por defecto,
//!   como cuando se enchufan auriculares. Lo que suena ahí va a un buffer y de ahí al USB.
//! - Móvil → PC: un stream de reproducción normal que suena por la salida por defecto.
//!
//! En los dos casos el ajuste fino de reloj lo hace el resampler adaptativo de PipeWire:
//! en cada ciclo de tiempo real le escribimos la corrección en `SPA_IO_RateMatch`.

use std::cell::RefCell;
use std::io::Cursor;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::rc::Rc;
use std::sync::atomic::{AtomicPtr, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, mpsc};

use pipewire as pw;
use pw::properties::properties;
use pw::spa;
use pw::spa::sys as spa_sys;
use pw::stream::{StreamFlags, StreamListener, StreamRc};

use crate::control::RateLoop;
use crate::protocol::{Format, Sample};
use crate::ring::Ring;
use crate::source_target::SourceTarget;

pub const SINK_NAME: &str = "soundshare_to_phone";
const SOURCE_NAME: &str = "soundshare_from_phone";
const CONFIGURED_SINK: &str = "default.configured.audio.sink";

/// Lo que mandamos al teléfono: el formato interno de PipeWire, sin pérdidas.
pub const SINK_FORMAT: Format = Format { rate: 48_000, channels: 2, sample: Sample::F32LE };

/// Cuántum pedido al grafo: 256 frames = 5,3 ms a 48 kHz.
const QUANTUM: u32 = 256;
/// Calidad del resampler adaptativo (0-14). Solo actúa para las correcciones de reloj.
const RESAMPLE_QUALITY: &str = "10";

/// Hasta medirla, suponemos el bloque típico de Android (~21 ms de pico a pico).
const SOURCE_RIPPLE_GUESS_S: f64 = 0.021;

/// Lazo de reloj: lento y suave a propósito (ver control.rs). ±1000 ppm son 1,7 cents: inaudible.
pub const LOOP_BANDWIDTH_HZ: f64 = 0.01;
pub const LOOP_MAX_PPM: f64 = 1000.0;
pub const LOOP_SMOOTHING_S: f64 = 3.0;

/// f32 atómico para pasar valores al hilo de tiempo real.
#[derive(Default)]
pub struct AtomicF32(AtomicU32);

impl AtomicF32 {
    pub fn load(&self) -> f32 {
        f32::from_bits(self.0.load(Ordering::Relaxed))
    }
    pub fn store(&self, v: f32) {
        self.0.store(v.to_bits(), Ordering::Relaxed)
    }
}

/// Despertador entre el hilo de tiempo real y el de red (write en un eventfd no bloquea).
pub struct EventFd(OwnedFd);

impl EventFd {
    fn new() -> EventFd {
        let fd = unsafe { libc::eventfd(0, libc::EFD_NONBLOCK | libc::EFD_CLOEXEC) };
        assert!(fd >= 0, "eventfd: {}", std::io::Error::last_os_error());
        EventFd(unsafe { OwnedFd::from_raw_fd(fd) })
    }

    pub fn notify(&self) {
        let one: u64 = 1;
        unsafe { libc::write(self.0.as_raw_fd(), (&one as *const u64).cast(), 8) };
    }

    /// Espera una notificación hasta `timeout_ms`.
    pub fn wait(&self, timeout_ms: i32) {
        let mut pfd = libc::pollfd { fd: self.0.as_raw_fd(), events: libc::POLLIN, revents: 0 };
        if unsafe { libc::poll(&mut pfd, 1, timeout_ms) } > 0 {
            let mut v: u64 = 0;
            unsafe { libc::read(self.0.as_raw_fd(), (&mut v as *mut u64).cast(), 8) };
        }
    }
}

/// PC → Móvil, compartido entre el hilo de PipeWire y el de red.
pub struct SinkShared {
    pub ring: Ring,
    pub wake: EventFd,
    /// Lo toma el hilo que vacía el ring hacia el socket (un único consumidor).
    pub consumer: Mutex<()>,
    /// Corrección de velocidad que pide el lazo (positiva = el teléfono tiene de más).
    pub rate_dev: AtomicF32,
    pub frames: AtomicU64,
    pub quantum: AtomicU32,
    pub overruns: AtomicU32,
    /// Diagnóstico: mayor intervalo entre ciclos de PipeWire desde la última lectura (µs).
    pub max_cycle_us: AtomicU32,
    last_cycle_ns: AtomicU64,
}

/// Reloj monótono en ns (apto para el hilo de tiempo real: no reserva ni bloquea).
pub fn now_ns() -> u64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) };
    ts.tv_sec as u64 * 1_000_000_000 + ts.tv_nsec as u64
}

impl SinkShared {
    pub fn new() -> Arc<SinkShared> {
        Arc::new(SinkShared {
            // 1 s de colchón: solo se usa entero si el USB se traba.
            ring: Ring::new(SINK_FORMAT.rate as usize * SINK_FORMAT.frame_bytes()),
            wake: EventFd::new(),
            consumer: Mutex::new(()),
            rate_dev: AtomicF32::default(),
            frames: AtomicU64::new(0),
            quantum: AtomicU32::new(0),
            overruns: AtomicU32::new(0),
            max_cycle_us: AtomicU32::new(0),
            last_cycle_ns: AtomicU64::new(0),
        })
    }
}

/// Móvil → PC, compartido entre el hilo de red y el de PipeWire.
pub struct SourceShared {
    pub format: Format,
    /// Salida de PipeWire donde sonar (node.name); None = la salida por defecto.
    pub output: Option<String>,
    pub ring: Ring,
    /// Nivel suavizado del buffer y objetivo, en segundos.
    pub level: AtomicF32,
    pub target: AtomicF32,
    pub rate_dev: AtomicF32,
    pub frames: AtomicU64,
    pub quantum: AtomicU32,
    pub underruns: AtomicU32,
    pub overruns: AtomicU32,
}

impl SourceShared {
    pub fn new(format: Format, output: Option<String>) -> Arc<SourceShared> {
        Arc::new(SourceShared {
            format,
            output,
            ring: Ring::new(format.rate as usize * format.frame_bytes()),
            level: AtomicF32::default(),
            target: AtomicF32::default(),
            rate_dev: AtomicF32::default(),
            frames: AtomicU64::new(0),
            quantum: AtomicU32::new(0),
            underruns: AtomicU32::new(0),
            overruns: AtomicU32::new(0),
        })
    }
}

pub enum Cmd {
    StartSink(Arc<SinkShared>),
    StopSink,
    StartSource(Arc<SourceShared>),
    StopSource,
    Quit,
}

pub enum Failure {
    Sink(Arc<SinkShared>, String),
    Source(Arc<SourceShared>, String),
}

pub struct Engine {
    tx: pw::channel::Sender<Cmd>,
    thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

impl Engine {
    pub fn start() -> Result<(Engine, mpsc::Receiver<Failure>), String> {
        let (tx, rx) = pw::channel::channel::<Cmd>();
        let (ready_tx, ready_rx) = mpsc::channel::<Result<(), String>>();
        let (errors_tx, errors_rx) = mpsc::channel();
        let thread = std::thread::Builder::new()
            .name("pipewire".into())
            .spawn(move || {
                if let Err(e) = run(rx, &ready_tx, errors_tx) {
                    let _ = ready_tx.send(Err(e.to_string()));
                }
            })
            .map_err(|e| e.to_string())?;
        ready_rx.recv().map_err(|_| "el hilo de PipeWire terminó".to_string())??;
        Ok((Engine { tx, thread: Mutex::new(Some(thread)) }, errors_rx))
    }

    pub fn send(&self, cmd: Cmd) {
        let _ = self.tx.send(cmd);
    }

    /// Restaura la salida por defecto, cierra los streams y espera al hilo.
    pub fn shutdown(&self) {
        self.send(Cmd::Quit);
        if let Some(t) = self.thread.lock().unwrap().take() {
            let _ = t.join();
        }
    }
}

/// Stream + listener, cerrados en el orden correcto.
struct StreamHandle<D> {
    stream: StreamRc,
    listener: Option<StreamListener<D>>,
}

impl<D> Drop for StreamHandle<D> {
    fn drop(&mut self) {
        // Primero se detiene el procesamiento en tiempo real y después se suelta el listener.
        let _ = self.stream.disconnect();
        self.listener.take();
    }
}

#[derive(Default)]
struct Defaults {
    metadata: Option<pw::metadata::Metadata>,
    listener: Option<pw::metadata::MetadataListener>,
    configured_sink: Option<String>,
}

#[derive(Default)]
struct State {
    sink: Option<StreamHandle<SinkData>>,
    source: Option<StreamHandle<SourceData>>,
    /// Salida por defecto anterior (`Some(None)` = no había ninguna configurada).
    saved_default: Option<Option<String>>,
    /// Conservado entre StopSink y StartSink mientras llega el eco del metadato.
    last_original: Option<Option<String>>,
    quitting: u32,
}

fn run(rx: pw::channel::Receiver<Cmd>, ready: &mpsc::Sender<Result<(), String>>, errors: mpsc::Sender<Failure>) -> Result<(), pw::Error> {
    pw::init();
    let mainloop = pw::main_loop::MainLoopRc::new(None)?;
    let context = pw::context::ContextRc::new(&mainloop, None)?;
    let core = context.connect_rc(None)?;
    let registry = core.get_registry_rc()?;

    // Seguimos el objeto de metadatos "default" para leer y cambiar la salida por defecto.
    let defaults = Rc::new(RefCell::new(Defaults::default()));
    let _registry_listener = registry
        .add_listener_local()
        .global({
            let defaults = defaults.clone();
            let registry = registry.downgrade();
            move |obj| {
                if obj.type_ != pw::types::ObjectType::Metadata
                    || obj.props.and_then(|p| p.get("metadata.name")) != Some("default")
                {
                    return;
                }
                let Some(registry) = registry.upgrade() else { return };
                let Ok(meta) = registry.bind::<pw::metadata::Metadata, _>(obj) else { return };
                let weak = Rc::downgrade(&defaults);
                let listener = meta
                    .add_listener_local()
                    .property(move |subject, key, _type, value| {
                        if subject == 0
                            && key == Some(CONFIGURED_SINK)
                            && let Some(d) = weak.upgrade()
                            && let Ok(mut d) = d.try_borrow_mut()
                        {
                            d.configured_sink = value.map(str::to_string);
                        }
                        0
                    })
                    .register();
                if let Ok(mut d) = defaults.try_borrow_mut() {
                    d.metadata = Some(meta);
                    d.listener = Some(listener);
                }
            }
        })
        .register();

    let state = Rc::new(RefCell::new(State::default()));

    // Al salir damos un par de vueltas al loop para que el cambio de salida por defecto
    // llegue al servidor antes de cortar la conexión.
    let quit_ticker = mainloop.loop_().add_timer({
        let state = state.clone();
        let mainloop = mainloop.downgrade();
        move |_| {
            let mut st = state.borrow_mut();
            if st.quitting > 0 {
                st.quitting += 1;
                if st.quitting > 3
                    && let Some(ml) = mainloop.upgrade()
                {
                    ml.quit();
                }
            }
        }
    });
    let tick = std::time::Duration::from_millis(50);
    quit_ticker.update_timer(Some(tick), Some(tick));

    let _commands = rx.attach(mainloop.loop_(), {
        let state = state.clone();
        let defaults = defaults.clone();
        let core = core.clone();
        move |cmd| {
            let mut st = state.borrow_mut();
            match cmd {
                Cmd::StartSink(shared) => {
                    if st.quitting != 0 { return; }
                    st.sink = None;
                    match create_sink(&core, shared.clone(), &errors) {
                        Ok(handle) => {
                            st.sink = Some(handle);
                            make_sink_default(&defaults, &mut st);
                        }
                        Err(e) => {
                            restore_default(&defaults, &mut st);
                            let _ = errors.send(Failure::Sink(shared, format!("No pude crear la salida virtual: {e}")));
                        }
                    }
                }
                Cmd::StopSink => {
                    if st.sink.take().is_some() {
                        restore_default(&defaults, &mut st);
                    }
                }
                Cmd::StartSource(shared) => {
                    if st.quitting != 0 { return; }
                    st.source = None;
                    match create_source(&core, shared.clone(), &errors) {
                        Ok(handle) => st.source = Some(handle),
                        Err(e) => {
                            let _ = errors.send(Failure::Source(shared, format!("No pude crear el stream de reproducción: {e}")));
                        }
                    }
                }
                Cmd::StopSource => st.source = None,
                Cmd::Quit => {
                    if st.sink.take().is_some() {
                        restore_default(&defaults, &mut st);
                    }
                    st.source = None;
                    st.quitting = 1;
                }
            }
        }
    });

    let _ = ready.send(Ok(()));
    mainloop.run();
    Ok(())
}

fn make_sink_default(defaults: &Rc<RefCell<Defaults>>, st: &mut State) {
    let d = defaults.borrow();
    let Some(meta) = &d.metadata else {
        eprintln!("⚠ No encontré los metadatos de PipeWire: elegí «SoundShare → Móvil» como salida a mano.");
        return;
    };
    if st.saved_default.is_none() {
        // Si el eco de StopSink aún es nuestro valor, reutilizamos el último original.
        // Al iniciar el proceso sin un original conocido, limpiamos el sink de una salida interrumpida.
        let original = if d.configured_sink.as_ref().is_some_and(|v| v.contains(SINK_NAME)) {
            st.last_original.clone().unwrap_or(None)
        } else {
            d.configured_sink.clone()
        };
        st.last_original = Some(original.clone());
        st.saved_default = Some(original);
    }
    let value = format!("{{ \"name\": \"{SINK_NAME}\" }}");
    meta.set_property(0, CONFIGURED_SINK, Some("Spa:String:JSON"), Some(&value));
}

fn restore_default(defaults: &Rc<RefCell<Defaults>>, st: &mut State) {
    let Some(previous) = st.saved_default.take() else { return };
    let d = defaults.borrow();
    let Some(meta) = &d.metadata else { return };
    match previous {
        Some(v) => meta.set_property(0, CONFIGURED_SINK, Some("Spa:String:JSON"), Some(&v)),
        None => meta.set_property(0, CONFIGURED_SINK, None, None),
    }
}

fn format_pod(fmt: Format) -> Vec<u8> {
    let mut info = spa::param::audio::AudioInfoRaw::new();
    info.set_format(match fmt.sample {
        Sample::S16LE => spa::param::audio::AudioFormat::S16LE,
        Sample::F32LE => spa::param::audio::AudioFormat::F32LE,
    });
    info.set_rate(fmt.rate);
    info.set_channels(fmt.channels);
    let mut position = [0u32; spa_sys::SPA_AUDIO_MAX_CHANNELS as usize];
    if fmt.channels == 2 {
        position[0] = spa_sys::SPA_AUDIO_CHANNEL_FL;
        position[1] = spa_sys::SPA_AUDIO_CHANNEL_FR;
    } else {
        position[0] = spa_sys::SPA_AUDIO_CHANNEL_MONO;
    }
    info.set_position(position);
    let obj = spa::pod::Object {
        type_: spa::utils::SpaTypes::ObjectParamFormat.as_raw(),
        id: spa::param::ParamType::EnumFormat.as_raw(),
        properties: info.into(),
    };
    spa::pod::serialize::PodSerializer::serialize(Cursor::new(Vec::new()), &spa::pod::Value::Object(obj))
        .expect("serializar formato")
        .0
        .into_inner()
}

/// Escribe la corrección de velocidad para el próximo ciclo del resampler.
fn apply_rate(rate_match: &AtomicPtr<spa_sys::spa_io_rate_match>, rate: f64) {
    let rm = rate_match.load(Ordering::Acquire);
    if !rm.is_null() {
        unsafe {
            (*rm).rate = rate;
            (*rm).flags |= spa_sys::SPA_IO_RATE_MATCH_FLAG_ACTIVE;
        }
    }
}

/// Para medir el sentido de la corrección: SOUNDSHARE_FORCE_RATE=1.01
fn forced_rate() -> Option<f64> {
    std::env::var("SOUNDSHARE_FORCE_RATE").ok().and_then(|v| v.parse().ok())
}

// ---------------------------------------------------------------- PC → Móvil

struct SinkData {
    shared: Arc<SinkShared>,
    rate_match: AtomicPtr<spa_sys::spa_io_rate_match>,
    forced_rate: Option<f64>,
}

fn create_sink(core: &pw::core::CoreRc, shared: Arc<SinkShared>, errors: &mpsc::Sender<Failure>) -> Result<StreamHandle<SinkData>, pw::Error> {
    let props = properties! {
        *pw::keys::MEDIA_TYPE => "Audio",
        *pw::keys::MEDIA_CLASS => "Audio/Sink",
        *pw::keys::NODE_NAME => SINK_NAME,
        *pw::keys::NODE_DESCRIPTION => "SoundShare → Móvil",
        *pw::keys::APP_NAME => "SoundShare",
        *pw::keys::NODE_LATENCY => format!("{QUANTUM}/{}", SINK_FORMAT.rate),
        // Siempre en marcha, aunque no suene nada: así la latencia no cambia nunca
        // y el teléfono no entra y sale de reposo (sin "clics").
        *pw::keys::NODE_ALWAYS_PROCESS => "true",
        *pw::keys::NODE_PAUSE_ON_IDLE => "false",
        "node.suspend-on-idle" => "false",
        "resample.quality" => RESAMPLE_QUALITY,
        "device.icon-name" => "phone",
    };
    let stream = StreamRc::new(core.clone(), "soundshare-to-phone", props)?;
    let failed = shared.clone();
    let errors = errors.clone();
    let data = SinkData { shared, rate_match: AtomicPtr::default(), forced_rate: forced_rate() };
    let listener = stream
        .add_local_listener_with_user_data(data)
        .state_changed(move |_, _, _, new| {
            if let pw::stream::StreamState::Error(e) = new {
                let _ = errors.send(Failure::Sink(failed.clone(), format!("Error en la salida virtual: {e}")));
            }
        })
        .io_changed(|_, d, id, area, size| {
            if id == spa_sys::SPA_IO_RateMatch {
                let ptr = if !area.is_null() && size as usize >= std::mem::size_of::<spa_sys::spa_io_rate_match>() {
                    area.cast()
                } else { std::ptr::null_mut() };
                d.rate_match.store(ptr, Ordering::Release);
            }
        })
        .process(sink_process)
        .register()?;

    let pod = format_pod(SINK_FORMAT);
    let mut params = [spa::pod::Pod::from_bytes(&pod).expect("pod de formato")];
    stream.connect(
        spa::utils::Direction::Input,
        None,
        StreamFlags::AUTOCONNECT | StreamFlags::MAP_BUFFERS | StreamFlags::RT_PROCESS,
        &mut params,
    )?;
    Ok(StreamHandle { stream, listener: Some(listener) })
}

/// Hilo de tiempo real: copia lo que sonó en el sink al ring y despierta al hilo de red.
fn sink_process(stream: &pw::stream::Stream, d: &mut SinkData) {
    let frame = SINK_FORMAT.frame_bytes();
    let now = now_ns();
    let prev = d.shared.last_cycle_ns.swap(now, Ordering::Relaxed);
    if prev != 0 {
        d.shared.max_cycle_us.fetch_max(((now - prev) / 1000) as u32, Ordering::Relaxed);
    }
    if let Some(mut buffer) = stream.dequeue_buffer()
        && let Some(data) = buffer.datas_mut().first_mut()
    {
        let (offset, size) = (data.chunk().offset() as usize, data.chunk().size() as usize);
        if let Some(bytes) = data.data() {
            let end = (offset + size).min(bytes.len());
            let pcm = &bytes[offset.min(end)..end];
            let whole = pcm.len() - pcm.len() % frame;
            if d.shared.ring.push_frames(pcm, frame) < whole {
                d.shared.overruns.fetch_add(1, Ordering::Relaxed);
            }
            d.shared.frames.fetch_add((whole / frame) as u64, Ordering::Relaxed);
            d.shared.quantum.store((whole / frame) as u32, Ordering::Relaxed);
            d.shared.wake.notify();
        }
    }
    // Del lado de captura, rate > 1 hace que el resampler nos entregue más frames por ciclo
    // (medido: 1,01 → 48 480 frames/s). Si el teléfono acumula de más, pedimos menos.
    let rate = d.forced_rate.unwrap_or(1.0 / (1.0 + d.shared.rate_dev.load() as f64));
    apply_rate(&d.rate_match, rate);
}

// ---------------------------------------------------------------- Móvil → PC

struct SourceData {
    shared: Arc<SourceShared>,
    rate_match: AtomicPtr<spa_sys::spa_io_rate_match>,
    forced_rate: Option<f64>,
    control: RateLoop,
    started: bool,
    /// Nivel promedio buscado, con recuperación lenta después de los cortes.
    target: SourceTarget,
    /// Amplitud del "serrucho" del buffer (s): el teléfono entrega en bloques.
    ripple: f64,
    win_min: usize,
    win_max: usize,
    win_frames: usize,
}

fn create_source(
    core: &pw::core::CoreRc,
    shared: Arc<SourceShared>,
    errors: &mpsc::Sender<Failure>,
) -> Result<StreamHandle<SourceData>, pw::Error> {
    let fmt = shared.format;
    let mut props = properties! {
        *pw::keys::MEDIA_TYPE => "Audio",
        *pw::keys::MEDIA_CATEGORY => "Playback",
        *pw::keys::MEDIA_ROLE => "Music",
        *pw::keys::NODE_NAME => SOURCE_NAME,
        *pw::keys::NODE_DESCRIPTION => "SoundShare (Móvil)",
        *pw::keys::MEDIA_NAME => "Audio del teléfono",
        *pw::keys::APP_NAME => "SoundShare",
        *pw::keys::NODE_LATENCY => format!("{QUANTUM}/{}", fmt.rate),
        "resample.quality" => RESAMPLE_QUALITY,
        "application.icon-name" => "phone",
    };
    if let Some(output) = &shared.output {
        // Elegida a mano: suena solo ahí (si no existe, no cae a la salida por defecto).
        props.insert(*pw::keys::TARGET_OBJECT, output.as_str());
        props.insert("node.dont-fallback", "true");
    }
    let stream = StreamRc::new(core.clone(), "soundshare-from-phone", props)?;
    let failed = shared.clone();
    let errors = errors.clone();
    let data = SourceData {
        shared,
        rate_match: AtomicPtr::default(),
        forced_rate: forced_rate(),
        control: RateLoop::new(LOOP_BANDWIDTH_HZ, LOOP_MAX_PPM, LOOP_SMOOTHING_S),
        started: false,
        target: SourceTarget::new(fmt.rate),
        ripple: SOURCE_RIPPLE_GUESS_S,
        win_min: usize::MAX,
        win_max: 0,
        win_frames: 0,
    };
    let listener = stream
        .add_local_listener_with_user_data(data)
        .state_changed(move |_, _, _, new| {
            if let pw::stream::StreamState::Error(e) = new {
                let _ = errors.send(Failure::Source(failed.clone(), format!("Error en el stream de reproducción: {e}")));
            }
        })
        .io_changed(|_, d, id, area, size| {
            if id == spa_sys::SPA_IO_RateMatch {
                let ptr = if !area.is_null() && size as usize >= std::mem::size_of::<spa_sys::spa_io_rate_match>() {
                    area.cast()
                } else { std::ptr::null_mut() };
                d.rate_match.store(ptr, Ordering::Release);
            }
        })
        .process(source_process)
        .register()?;

    let pod = format_pod(fmt);
    let mut params = [spa::pod::Pod::from_bytes(&pod).expect("pod de formato")];
    stream.connect(
        spa::utils::Direction::Output,
        None,
        StreamFlags::AUTOCONNECT | StreamFlags::MAP_BUFFERS | StreamFlags::RT_PROCESS,
        &mut params,
    )?;
    Ok(StreamHandle { stream, listener: Some(listener) })
}

/// Hilo de tiempo real: entrega a PipeWire exactamente lo que pide el resampler.
fn source_process(stream: &pw::stream::Stream, d: &mut SourceData) {
    let Some(mut buffer) = stream.dequeue_buffer() else { return };
    let requested = buffer.requested() as usize;
    let Some(data) = buffer.datas_mut().first_mut() else { return };

    let fmt = d.shared.format;
    let frame = fmt.frame_bytes();
    let rate = fmt.rate as f64;
    let ring = &d.shared.ring;
    let mut dev = 0.0;

    let Some(bytes) = data.data() else { return };
    let max = bytes.len() / frame;
    let n = if requested > 0 { requested.min(max) } else { (QUANTUM as usize).min(max) };
    let out = &mut bytes[..n * frame];

    let mut avail = ring.len() / frame;

    // Medimos el serrucho (máximo - mínimo del buffer) en ventanas de 1 s.
    if d.started {
        d.win_min = d.win_min.min(avail);
        d.win_max = d.win_max.max(avail);
        d.win_frames += n;
        if d.win_frames >= fmt.rate as usize {
            let measured = (d.win_max - d.win_min) as f64 / rate;
            d.ripple = (0.5 * d.ripple + 0.5 * measured).clamp(0.005, 0.060);
            (d.win_min, d.win_max, d.win_frames) = (usize::MAX, 0, 0);
        }
    }
    // Llenando justo después de que llegó un bloque, el promedio queda media amplitud abajo:
    // arrancamos ahí para que el promedio caiga en el objetivo y el lazo no tenga nada que corregir.
    let target = d.target.seconds();
    let start_level = ((target + d.ripple / 2.0) * rate) as usize;

    // Si tras un tirón del USB llegó mucho de golpe, saltamos al punto de arranque: mejor un
    // salto corto que quedarse con latencia de más.
    if avail as f64 > (target + d.ripple + 0.100) * rate {
        ring.skip((avail - start_level) * frame);
        avail = start_level;
        d.control.prime(target);
        d.target.reset_stability();
    }
    if !d.started && avail >= start_level {
        // Lo que se juntó mientras arrancaba el stream sobra (el lazo es lento y tardaría en drenarlo).
        ring.skip((avail - start_level) * frame);
        avail = start_level;
        d.started = true;
        d.control.prime(target);
    }
    let mut got = 0;
    if d.started {
        dev = d.control.update(avail as f64 / rate, target, n as f64 / rate);
        got = ring.pop(out) / frame;
        if got < n {
            // Corte: rellenamos con silencio, agrandamos el colchón y volvemos a llenarlo.
            // La ventana del serrucho se descarta: incluye la caída y no representa nada.
            d.started = false;
            (d.win_min, d.win_max, d.win_frames) = (usize::MAX, 0, 0);
            d.target.on_underrun();
            d.shared.underruns.fetch_add(1, Ordering::Relaxed);
        } else {
            // Solo baja después de un minuto completo con margen; el lazo drena
            // el exceso lentamente y no agregamos descartes al recuperar latencia.
            d.target.on_playback(n, avail.saturating_sub(n));
        }
    }
    out[got * frame..].fill(0);

    let chunk = data.chunk_mut();
    *chunk.offset_mut() = 0;
    *chunk.stride_mut() = frame as i32;
    *chunk.size_mut() = (n * frame) as u32;

    d.shared.frames.fetch_add(n as u64, Ordering::Relaxed);
    d.shared.quantum.store(n as u32, Ordering::Relaxed);
    d.shared.level.store(d.control.level().unwrap_or(avail as f64 / rate) as f32);
    d.shared.target.store(d.target.seconds() as f32);
    d.shared.rate_dev.store(dev as f32);
    // Del lado de reproducción, rate > 1 hace que el resampler consuma menos frames nuestros
    // (medido: 1,01 → 47 520 frames/s). Si sobra audio en el buffer, pedimos que consuma más.
    let rate_corr = d.forced_rate.unwrap_or(1.0 / (1.0 + dev));
    apply_rate(&d.rate_match, rate_corr);
}
