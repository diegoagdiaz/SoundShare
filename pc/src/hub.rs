//! Estado central: qué modo está activo y qué conexiones del teléfono lo atienden.
//!
//! La PC es la única que decide el modo. El teléfono (o la terminal) lo pide y la PC
//! avisa el resultado a todas las apps conectadas con `STATE`.

use std::io::{BufReader, ErrorKind, Read, Write};
use std::net::{Shutdown, TcpStream};
use std::process::Child;
use std::sync::atomic::{AtomicI32, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::thread;
use std::time::{Duration, Instant};

use crate::adb::{Adb, Device};
use crate::player_transport::{self, PlayerTransport, TransportChoice, TransportReason};
use crate::control::RateLoop;
use crate::engine::{self, Cmd, Engine, SINK_FORMAT, SinkShared, SourceShared};
use crate::protocol::{self, Format, Mode, PlayerStatus, Role};

pub struct Hub {
    adb: Adb,
    engine: Engine,
    port: u16,
    /// Salida de PipeWire para Móvil → PC (None = la de por defecto).
    output: Option<String>,
    st: Mutex<State>,
    aoa_open: Mutex<()>,
    next_id: AtomicU64,
    /// Último estado del reproductor del teléfono (para estadísticas).
    phone: PhoneStats,
    /// Diagnóstico: mayor intervalo entre envíos al teléfono (µs).
    max_send_us: AtomicU32,
    debug: bool,
}

#[derive(Default)]
struct PhoneStats {
    queued: AtomicI32,
    target: AtomicI32,
    underruns: AtomicI32,
    output_latency: AtomicI32,
}

#[derive(Default)]
struct State {
    shutting_down: bool,
    mode: Mode,
    device: Option<Device>,
    controls: Vec<(u64, TcpStream)>,
    sink: Option<Arc<SinkShared>>,
    player: Option<(u64, PlayerTransport)>,
    source: Option<Arc<SourceShared>>,
    capture: Option<TcpStream>,
    capture_child: Option<(u64, Child)>,
    capture_launch: Option<u64>,
    show_stats: bool,
    aoa: Option<Arc<crate::aoa::Link>>,
    /// Edad del último PCM leído en Android, antes de enviarlo por USB.
    capture_latency_ms: Option<(f64, Instant)>,
}

impl Hub {
    pub fn new(adb: Adb, engine: Engine, port: u16, output: Option<String>) -> Hub {
        Hub {
            adb,
            engine,
            port,
            output,
            st: Mutex::new(State { show_stats: true, ..State::default() }),
            aoa_open: Mutex::new(()),
            next_id: AtomicU64::new(1),
            phone: PhoneStats::default(),
            max_send_us: AtomicU32::new(0),
            debug: std::env::var_os("SOUNDSHARE_DEBUG").is_some(),
        }
    }

    pub fn mode(&self) -> Mode {
        self.st.lock().unwrap().mode
    }

    pub fn toggle_stats(&self) -> bool {
        let mut st = self.st.lock().unwrap();
        st.show_stats = !st.show_stats;
        st.show_stats
    }

    fn id(&self) -> u64 {
        self.next_id.fetch_add(1, Ordering::Relaxed)
    }

    // ------------------------------------------------------------ modos

    pub fn set_mode(self: &Arc<Self>, mode: Mode) {
        let mut st = self.st.lock().unwrap();
        self.set_mode_locked(&mut st, mode);
    }

    fn set_mode_locked(self: &Arc<Self>, st: &mut State, mode: Mode) {
        if st.shutting_down { return; }
        if st.mode == mode {
            self.broadcast(st, &format!("STATE {}", mode.wire()));
            return;
        }
        self.teardown(st);

        let result = match mode {
            Mode::Off => Ok(()),
            Mode::PcToPhone => self.start_pc_to_phone(st),
            Mode::PhoneToPc => self.start_phone_to_pc(st),
        };
        match result {
            Ok(()) => {
                st.mode = mode;
                println!("▶ Modo: {}", mode.label());
            }
            Err(e) => {
                eprintln!("✗ {e}");
                self.teardown(st);
                self.broadcast(st, &format!("ERROR {e}"));
            }
        }
        let line = format!("STATE {}", st.mode.wire());
        self.broadcast(st, &line);
    }

    fn teardown(&self, st: &mut State) {
        match st.mode {
            Mode::Off => {}
            Mode::PcToPhone => {
                self.engine.send(Cmd::StopSink);
                st.sink = None;
                if let Some((_, s)) = st.player.take() {
                    s.close();
                }
            }
            Mode::PhoneToPc => {
                if let Some((_, mut child)) = st.capture_child.take() {
                    let _ = child.kill();
                    thread::spawn(move || { let _ = child.wait(); });
                }
                if let Some(s) = st.capture.take() {
                    let _ = s.shutdown(Shutdown::Both);
                }
                self.engine.send(Cmd::StopSource);
                st.source = None;
            }
        }
        st.mode = Mode::Off;
        st.capture_launch = None;
        st.capture_latency_ms = None;
    }

    fn start_pc_to_phone(&self, st: &mut State) -> Result<(), String> {
        let device = st.device.clone().ok_or("No hay ningún teléfono conectado")?;
        let shared = SinkShared::new();
        st.sink = Some(shared.clone());
        self.engine.send(Cmd::StartSink(shared));
        // El reproductor vive en la app: si no está abierta, la abrimos.
        if st.controls.is_empty() {
            println!("  Abriendo la app en el teléfono…");
            let adb = self.adb.clone();
            thread::spawn(move || { let _ = adb.start_app(&device.serial); });
        }
        Ok(())
    }

    fn start_phone_to_pc(self: &Arc<Self>, st: &mut State) -> Result<(), String> {
        let device = st.device.clone().ok_or("No hay ningún teléfono conectado")?;
        let id = self.id();
        st.capture_launch = Some(id);
        // Los comandos adb pueden tardar: no retenemos el estado mientras arrancan.
        let hub = self.clone();
        thread::spawn(move || hub.launch_capture(id, device));
        Ok(())
    }

    fn launch_capture(self: Arc<Self>, id: u64, device: Device) {
        let apk = (|| -> Result<String, String> {
            self.adb
                .package_path(&device.serial)
                .map_err(|e| e.to_string())?
                .ok_or_else(|| "La app SoundShare no está instalada en el teléfono (usá «soundshare install»)".to_string())
        })();
        let mut st = self.st.lock().unwrap();
        if st.shutting_down || st.mode != Mode::PhoneToPc || st.capture_launch != Some(id) {
            return;
        }
        // Solo el spawn local queda bajo el lock: una consulta vieja no lanza otra captura.
        let result = apk.and_then(|apk| self.adb.spawn_capture(&device.serial, &apk, self.port)
            .map_err(|e| format!("No pude lanzar el capturador: {e}")));
        match result {
            Ok(mut child) => {
                let out = child.stdout.take();
                st.capture_child = Some((id, child));
                if let Some(out) = out {
                    let hub = self.clone();
                    thread::spawn(move || hub.watch_capture(id, out));
                }
            }
            Err(e) => {
                eprintln!("✗ {e}");
                self.teardown(&mut st);
                self.broadcast(&mut st, &format!("ERROR {e}"));
                self.broadcast(&mut st, "STATE off");
            }
        }
    }

    /// Muestra los mensajes del capturador y apaga el modo si termina solo.
    fn watch_capture(self: Arc<Self>, id: u64, out: impl Read) {
        let mut last = String::new();
        let mut reader = BufReader::new(out);
        while let Ok(Some(line)) = protocol::read_line(&mut reader) {
            let line = line.trim().to_string();
            if let Some(value) = line.strip_prefix("[captura] LATENCY ") {
                if let Ok(ms) = value.parse::<f64>() {
                    let mut st = self.st.lock().unwrap();
                    if st.capture_child.as_ref().is_some_and(|(cid, _)| *cid == id) {
                        st.capture_latency_ms = (ms.is_finite() && (0.0..=5000.0).contains(&ms))
                            .then_some((ms, Instant::now()));
                    }
                }
                continue;
            }
            if line.is_empty() {
                continue;
            }
            println!("  teléfono: {}", line.trim_start_matches("[captura] "));
            last = line;
        }
        let mut st = self.st.lock().unwrap();
        if st.capture_child.as_ref().is_some_and(|(cid, _)| *cid == id) {
            let msg = if last.contains("error") {
                last.trim_start_matches("[captura] ").to_string()
            } else {
                "el capturador del teléfono se cerró".to_string()
            };
            eprintln!("✗ {msg}");
            self.teardown(&mut st);
            self.broadcast(&mut st, &format!("ERROR {msg}"));
            self.broadcast(&mut st, "STATE off");
        }
    }

    // ------------------------------------------------------------ teléfono

    pub fn set_aoa(&self, link: Option<Arc<crate::aoa::Link>>) {
        let mut st = self.st.lock().unwrap();
        if let Some(old) = st.aoa.take() { old.stop(); }
        st.aoa = link;
    }

    /// Si la app cerró su lado del accesorio (se reinició o se reinstaló), el cable sigue en modo
    /// AOA: reabrimos la interfaz sin conmutar para que PLAYER no se quede en adb.
    fn reopen_aoa(self: &Arc<Self>, explicit: bool) -> TransportReason {
        let _opening = self.aoa_open.lock().unwrap();
        let serial = {
            let mut st = self.st.lock().unwrap();
            if st.shutting_down { return TransportReason::Disconnected; }
            if st.aoa.as_ref().is_some_and(|a| a.alive()) { return TransportReason::Unavailable; }
            let Some(serial) = st.device.as_ref().map(|d| d.serial.clone()) else { return TransportReason::Disconnected };
            if st.aoa.is_none() && !explicit { return TransportReason::Unavailable; }
            // stop libera el handle aunque el lector o sesiones conserven Arc<Link>.
            if let Some(old) = st.aoa.take() { old.stop(); }
            serial
        };
        match crate::aoa::reopen(&serial) {
            Ok(link) => {
                let mut st = self.st.lock().unwrap();
                if st.shutting_down || st.device.as_ref().map(|d| &d.serial) != Some(&serial) {
                    link.stop();
                    return TransportReason::Disconnected;
                }
                println!("  AOA: accesorio reabierto sin conmutar USB.");
                st.aoa = Some(link.clone());
                drop(st);
                link.run(Arc::downgrade(self));
                TransportReason::Unavailable // Sólo se usa si el enlace deja de estar vivo.
            }
            Err(e) => {
                eprintln!("⚠ No pude reabrir AOA: {e}. PLAYER por adb.");
                if e.kind() == ErrorKind::Unsupported { TransportReason::Reconnect }
                else { TransportReason::Unavailable }
            }
        }
    }

    fn request_transport(self: &Arc<Self>, id: u64, choice: TransportChoice) {
        let reason = if choice == TransportChoice::Aoa { self.reopen_aoa(true) }
            else { TransportReason::Unavailable };
        let mut st = self.st.lock().unwrap();
        let line = player_transport::advertisement(choice, st.aoa.as_ref().is_some_and(|a| a.alive()), reason);
        // Responder al solicitante; no cambiar modo/sink ni cerrar el accesorio al elegir adb.
        if let Some((_, writer)) = st.controls.iter_mut().find(|(cid, _)| *cid == id) {
            let _ = writer.write_all(format!("{line}\n").as_bytes());
        }
    }

    pub fn device_changed(self: &Arc<Self>, device: Option<Device>) {
        let mut st = self.st.lock().unwrap();
        if st.shutting_down { return; }
        let gone = st.device.is_some() && st.device.as_ref().map(|d| &d.serial) != device.as_ref().map(|d| &d.serial);
        st.device = device;
        if gone {
            self.set_mode_locked(&mut st, Mode::Off);
        }
    }

    pub fn handle(self: &Arc<Self>, mut sock: TcpStream) {
        if self.st.lock().unwrap().shutting_down { return; }
        let _ = sock.set_nodelay(true);
        let _ = sock.set_read_timeout(Some(Duration::from_secs(5)));
        let role = match protocol::read_hello(&mut sock) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("✗ Conexión rechazada: {e}");
                let _ = sock.write_all(format!("ERROR {e}\n").as_bytes());
                return;
            }
        };
        if role != Role::Capture {
            let _ = sock.set_read_timeout(None);
        }
        match role {
            Role::Control => self.handle_control(sock),
            Role::Player => self.handle_player(PlayerTransport::Adb(sock)),
            Role::Capture => self.handle_capture(sock),
        }
    }

    fn handle_control(self: &Arc<Self>, sock: TcpStream) {
        let id = self.id();
        let Ok(writer) = sock.try_clone() else { return };
        let _ = writer.set_write_timeout(Some(Duration::from_secs(1)));
        let reason = self.reopen_aoa(false);
        {
            let mut st = self.st.lock().unwrap();
            if st.shutting_down { return; }
            st.controls.push((id, writer));
            let line = player_transport::advertisement(TransportChoice::Aoa,
                st.aoa.as_ref().is_some_and(|a| a.alive()), reason);
            // El anuncio inicial va sólo a esta app, para no pisar el selector de otro CONTROL.
            if let Some((_, writer)) = st.controls.iter_mut().find(|(cid, _)| *cid == id) {
                let _ = writer.write_all(format!("{line}\n").as_bytes());
            }
            let line = format!("STATE {}", st.mode.wire());
            self.broadcast(&mut st, &line);
        }
        let mut name = String::from("la app");
        let mut reader = BufReader::new(sock);
        while let Ok(Some(line)) = protocol::read_line(&mut reader) {
            let (cmd, arg) = line.split_once(' ').unwrap_or((line.as_str(), ""));
            match cmd {
                "HELLO" => {
                    let (model, sdk) = arg.split_once('|').unwrap_or((arg, "?"));
                    name = model.to_string();
                    println!("● App conectada: {model} (Android API {sdk})");
                }
                "MODE" => match Mode::parse(arg) {
                    Some(m) => self.set_mode(m),
                    None => eprintln!("✗ Modo desconocido desde el teléfono: {arg}"),
                },
                "TRANSPORT" => {
                    if let Some(choice) = TransportChoice::parse(arg) { self.request_transport(id, choice); }
                }
                "PING" => {
                    if let Ok(token) = arg.parse::<u64>() {
                        let mut st = self.st.lock().unwrap();
                        if let Some((_, writer)) = st.controls.iter_mut().find(|(cid, _)| *cid == id) {
                            let _ = writer.write_all(format!("PONG {token}\n").as_bytes());
                        }
                    }
                }
                _ => {}
            }
        }
        println!("○ Se desconectó {name}");
        let mut st = self.st.lock().unwrap();
        st.controls.retain(|(cid, _)| *cid != id);
        // Sin la app no hay quien reproduzca: devolvemos el audio a la PC.
        if st.controls.is_empty() && st.mode == Mode::PcToPhone {
            self.set_mode_locked(&mut st, Mode::Off);
        }
    }

    /// PC → Móvil: vacía el ring del sink hacia el teléfono y escucha su estado.
    pub fn handle_player(self: &Arc<Self>, mut sock: PlayerTransport) {
        let id = self.id();
        let shared = {
            let mut st = self.st.lock().unwrap();
            let Some(shared) = st.sink.clone().filter(|_| !st.shutting_down && st.mode == Mode::PcToPhone && sock.is_current()) else { sock.close(); return };
            let Ok(clone) = sock.try_clone() else { sock.close(); return };
            if let Some((_, old)) = st.player.take() {
                old.close();
            }
            st.player = Some((id, clone));
            shared
        };
        let _consumer = shared.consumer.lock().unwrap();
        if !self.player_is_current(id) || !sock.is_current() {
            sock.close();
            self.finish_player(id);
            return;
        }
        shared.ring.clear();
        if sock.format(&SINK_FORMAT.encode()).is_err() {
            sock.close();
            self.finish_player(id);
            return;
        }
        println!("  Audio de la PC → teléfono por {}: {} Hz, float 32 bits, estéreo", sock.label(), SINK_FORMAT.rate);

        let status = sock.try_clone().ok().map(|rs| {
            let hub = self.clone();
            let shared = shared.clone();
            thread::spawn(move || hub.player_status_loop(id, rs, shared))
        });

        let buffer_size = match &sock { PlayerTransport::Adb(_) => 32 * 1024, PlayerTransport::Aoa(_) => crate::aoa_frame::MAX_PCM };
        let mut buf = vec![0u8; buffer_size];
        let mut last_send = engine::now_ns();
        'send: loop {
            shared.wake.wait(100);
            loop {
                if !self.player_is_current(id) || !sock.is_current() {
                    break 'send;
                }
                let n = shared.ring.pop(&mut buf);
                if n == 0 {
                    break;
                }
                if sock.write_all(&buf[..n]).is_err() {
                    break 'send;
                }
                let now = engine::now_ns();
                self.max_send_us.fetch_max(((now - last_send) / 1000) as u32, Ordering::Relaxed);
                last_send = now;
            }
            if !self.player_is_current(id) {
                break;
            }
        }
        sock.close();
        if let Some(t) = status {
            let _ = t.join();
        }
        self.finish_player(id);
    }

    fn player_is_current(&self, id: u64) -> bool {
        self.st.lock().unwrap().player.as_ref().is_some_and(|(pid, _)| *pid == id)
    }

    fn finish_player(&self, id: u64) {
        let mut st = self.st.lock().unwrap();
        if st.player.as_ref().is_some_and(|(pid, _)| *pid == id) {
            st.player = None;
        }
    }

    /// El teléfono informa su cola cada ~10 ms; el lazo la lleva al objetivo ajustando nuestro reloj.
    fn player_status_loop(&self, id: u64, mut sock: PlayerTransport, shared: Arc<SinkShared>) {
        let mut control = RateLoop::new(engine::LOOP_BANDWIDTH_HZ, engine::LOOP_MAX_PPM, engine::LOOP_SMOOTHING_S);
        let rate = SINK_FORMAT.rate as f64;
        let frame = SINK_FORMAT.frame_bytes();
        let mut b = [0u8; protocol::STATUS_BYTES];
        let mut last = Instant::now();
        loop {
            match sock.read_exact(&mut b) {
                Ok(()) => {}
                Err(e) if e.kind() == ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
            // read_exact puede devolver un estado viejo después de que otra sesión tomó el sink.
            let st = self.st.lock().unwrap();
            if !st.player.as_ref().is_some_and(|(pid, _)| *pid == id) || !sock.is_current() {
                break;
            }
            let s = PlayerStatus::decode(&b);
            let now = Instant::now();
            let dt = now.duration_since(last).as_secs_f64();
            last = now;
            let backlog = (shared.ring.len() / frame) as f64;
            let level = (s.queued as f64 + backlog) / rate;
            let dev = control.update(level, s.target as f64 / rate, dt);
            shared.rate_dev.store(dev as f32);
            self.phone.queued.store(s.queued, Ordering::Relaxed);
            self.phone.target.store(s.target, Ordering::Relaxed);
            self.phone.underruns.store(s.underruns, Ordering::Relaxed);
            self.phone.output_latency.store(s.output_latency, Ordering::Relaxed);
        }
        sock.close();
        let st = self.st.lock().unwrap();
        if st.player.as_ref().is_some_and(|(pid, _)| *pid == id) {
            shared.rate_dev.store(0.0);
        }
    }

    /// Móvil → PC: lo que manda el capturador va al ring que lee PipeWire.
    fn handle_capture(self: &Arc<Self>, mut sock: TcpStream) {
        let fmt = match Format::read(&mut sock) {
            Ok(f) => f,
            Err(e) => {
                eprintln!("✗ Formato del capturador inválido: {e}");
                return;
            }
        };
        let _ = sock.set_read_timeout(None);
        let shared = SourceShared::new(fmt, self.output.clone());
        {
            let mut st = self.st.lock().unwrap();
            if st.shutting_down || st.mode != Mode::PhoneToPc {
                return;
            }
            if let Some(old) = st.capture.take() {
                let _ = old.shutdown(Shutdown::Both);
            }
            let Ok(clone) = sock.try_clone() else { return };
            st.capture = Some(clone);
            st.source = Some(shared.clone());
            self.engine.send(Cmd::StartSource(shared.clone()));
        }
        println!(
            "  Audio del teléfono → PC: {} Hz, {} bits, {} canales",
            fmt.rate,
            fmt.sample.bytes() * 8,
            fmt.channels
        );

        let frame = fmt.frame_bytes();
        let mut buf = vec![0u8; 32 * 1024];
        let mut fill = 0;
        // Diagnóstico: SOUNDSHARE_DUMP=archivo guarda el PCM tal como llega del teléfono.
        let mut dump = std::env::var_os("SOUNDSHARE_DUMP").and_then(|p| std::fs::File::create(p).ok());
        loop {
            let n = match sock.read(&mut buf[fill..]) {
                Ok(0) => break,
                Ok(n) => n,
                Err(e) if e.kind() == ErrorKind::Interrupted => continue,
                Err(_) => break,
            };
            if let Some(f) = dump.as_mut() {
                let _ = f.write_all(&buf[fill..fill + n]);
            }
            fill += n;
            let whole = fill - fill % frame;
            if shared.ring.push_frames(&buf[..whole], frame) < whole {
                shared.overruns.fetch_add(1, Ordering::Relaxed);
            }
            buf.copy_within(whole..fill, 0);
            fill -= whole;
        }

        let mut st = self.st.lock().unwrap();
        if st.source.as_ref().is_some_and(|s| Arc::ptr_eq(s, &shared)) {
            self.engine.send(Cmd::StopSource);
            st.source = None;
            st.capture = None;
        }
    }

    fn broadcast(&self, st: &mut State, line: &str) {
        let msg = format!("{line}\n");
        st.controls.retain_mut(|(_, s)| s.write_all(msg.as_bytes()).is_ok());
    }

    /// Un error tardío solo puede apagar el stream que lo originó, nunca una sesión nueva.
    pub fn engine_errors(self: Arc<Self>, errors: mpsc::Receiver<engine::Failure>) {
        for failure in errors {
            let mut st = self.st.lock().unwrap();
            let (current, message) = match failure {
                engine::Failure::Sink(s, e) => (st.sink.as_ref().is_some_and(|v| Arc::ptr_eq(v, &s)), e),
                engine::Failure::Source(s, e) => (st.source.as_ref().is_some_and(|v| Arc::ptr_eq(v, &s)), e),
            };
            if current && !st.shutting_down {
                eprintln!("✗ {message}");
                self.teardown(&mut st);
                self.broadcast(&mut st, &format!("ERROR {message}"));
                self.broadcast(&mut st, "STATE off");
            }
        }
    }

    // ------------------------------------------------------------ estadísticas

    /// Una línea de estado por segundo en la terminal y en la app.
    pub fn stats_loop(self: Arc<Self>) {
        let mut last_frames = 0u64;
        let mut last = Instant::now();
        loop {
            thread::sleep(Duration::from_secs(1));
            let mut st = self.st.lock().unwrap();
            let now = Instant::now();
            let secs = now.duration_since(last).as_secs_f64();
            last = now;
            let (line, frames) = match st.mode {
                Mode::PcToPhone => match &st.sink {
                    Some(s) => (self.pc_to_phone_stats(s), s.frames.load(Ordering::Relaxed)),
                    None => continue,
                },
                Mode::PhoneToPc => match &st.source {
                    Some(s) => (self.phone_to_pc_stats(s), s.frames.load(Ordering::Relaxed)),
                    None => continue,
                },
                Mode::Off => continue,
            };
            let fps = frames.saturating_sub(last_frames) as f64 / secs;
            last_frames = frames;
            if st.show_stats {
                println!("  {line} · {fps:.0} frames/s");
            }
            self.broadcast(&mut st, &format!("INFO PC: {line}"));
            let latency = match st.mode {
                Mode::PcToPhone => st.sink.as_ref().map(|s| {
                    let rate = SINK_FORMAT.rate as f64;
                    let queued = s.ring.len() as f64 / SINK_FORMAT.frame_bytes() as f64 / rate * 1000.0;
                    let pipeline = s.pipeline_latency.load() as f64 * 1000.0;
                    let device = if pipeline >= 0.0 {
                        pipeline.max(s.quantum.load(Ordering::Relaxed) as f64 / rate * 1000.0)
                    } else { -1.0 };
                    format!("LATENCY pc2phone {queued:.3} {device:.3} -1")
                }),
                Mode::PhoneToPc => st.source.as_ref().map(|s| {
                    let capture = st.capture_latency_ms
                        .filter(|(_, received)| received.elapsed() < Duration::from_secs(3))
                        .map_or(-1.0, |(ms, _)| ms);
                    let pipeline = s.pipeline_latency.load() as f64;
                    format!("LATENCY phone2pc {:.3} {:.3} {capture:.3}",
                        s.level.load() as f64 * 1000.0,
                        if pipeline >= 0.0 { pipeline * 1000.0 } else { -1.0 })
                }),
                Mode::Off => None,
            };
            if let Some(latency) = latency { self.broadcast(&mut st, &latency); }
        }
    }

    fn pc_to_phone_stats(&self, s: &SinkShared) -> String {
        let ms = |frames: i32| frames as f64 * 1000.0 / SINK_FORMAT.rate as f64;
        let quantum = s.quantum.load(Ordering::Relaxed) as i32;
        let queued = self.phone.queued.load(Ordering::Relaxed);
        let out = self.phone.output_latency.load(Ordering::Relaxed);
        // cuántum de PipeWire + lo que queda hasta el parlante del teléfono
        let total = if out >= 0 { format!("≈{:.0} ms", ms(quantum) + ms(out.max(queued))) } else { "?".into() };
        let mut line = format!(
            "latencia {total} · cola tel. {:.1}/{:.1} ms · reloj {:+.0} ppm · cortes {}",
            ms(queued),
            ms(self.phone.target.load(Ordering::Relaxed)),
            s.rate_dev.load() as f64 * 1e6,
            self.phone.underruns.load(Ordering::Relaxed),
        );
        if self.debug {
            line += &format!(
                " · cuántum {quantum} · ciclo máx {:.1} ms · envío máx {:.1} ms",
                s.max_cycle_us.swap(0, Ordering::Relaxed) as f64 / 1000.0,
                self.max_send_us.swap(0, Ordering::Relaxed) as f64 / 1000.0,
            );
        }
        line
    }

    fn phone_to_pc_stats(&self, s: &SourceShared) -> String {
        format!(
            "buffer PC {:.1}/{:.1} ms · reloj {:+.0} ppm · cortes {}",
            s.level.load() as f64 * 1000.0,
            s.target.load() as f64 * 1000.0,
            s.rate_dev.load() as f64 * 1e6,
            s.underruns.load(Ordering::Relaxed),
        )
    }

    /// Salida ordenada: devuelve el audio a la PC y restaura la salida por defecto.
    pub fn shutdown(self: &Arc<Self>) {
        {
            let mut st = self.st.lock().unwrap();
            if st.shutting_down { return; }
            st.shutting_down = true;
            self.teardown(&mut st);
            self.broadcast(&mut st, "STATE off");
            for (_, sock) in st.controls.drain(..) {
                let _ = sock.shutdown(Shutdown::Both);
            }
        }
        self.engine.shutdown();
    }
}
