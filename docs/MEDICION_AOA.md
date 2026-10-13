# Implementación y verificación de PLAYER por AOA

Prueba del 13 de octubre de 2026 en moto g play (2021), Android 11/API 30, serie `ZY22DVN9D2`. El USB original `22b8:2e81` conmutó a `18d1:2d01`. CONTROL y CAPTURE conservan adb; sólo PLAYER cambia de transporte.

## Archivos cambiados

| Archivo | Cambio |
| --- | --- |
| `pc/Cargo.toml`, `pc/Cargo.lock` | `rusb 0.9.4`, `libusb1-sys` vendored y lock de dependencias. |
| `pc/src/aoa.rs` | Handshake AOA por serie, espera de reenumeración, selección de interfaz/endpoints sin reiniciar alt 0, lector persistente, cola de estados y sesiones con cierre acotado. |
| `pc/src/aoa_frame.rs` | Framing AOA v1, validación estricta y tests de bloques partidos/juntos, truncamiento, vector común con Kotlin y paquetes cortos. |
| `pc/src/player_transport.rs` | Entrada/salida PLAYER intercambiable entre socket adb y sesión AOA. |
| `pc/src/main.rs` | Un intento de conmutación por cable antes de reverse/app/captura; espera adb y rehace reverse tras reinicios temporales. Variable para forzar adb. |
| `pc/src/hub.rs` | Usa el transporte PLAYER común, anuncia el candidato por CONTROL y conserva el mismo RateLoop/resampler. END al detener/reemplazar. |
| `android/.../AccessoryFrame.kt` | Codec de la misma envoltura, sin dependencias Android. |
| `android/.../AccessoryInbox.kt` | Flujo lógico con payload pendiente, cola acotada y cierre que despierta al consumidor. |
| `android/.../AccessoryTransport.kt` | UsbManager, permiso normal del sistema, fd persistente, FileInputStream/FileOutputStream, lecturas físicas de 16 KB y hilos daemon. |
| `android/.../Player.kt` | Conserva AudioTrack/QueueTarget; recibe desde ambas fuentes y separa el objetivo persistido. Identifica el transporte en diag. |
| `android/.../LinkService.kt` | Candidato AOA por CONTROL, apertura/permiso y reinicio del PLAYER al concederlo. No usa RTT de adb como estimación USB de AOA. |
| `android/.../MainActivity.kt` | Atiende el intent de accesorio también cuando la actividad ya estaba abierta. |
| `android/app/src/main/AndroidManifest.xml`, `res/xml/accessory_filter.xml` | Feature USB opcional, filtro SoundShare/SoundShare/1 e intent USB_ACCESSORY_ATTACHED. |
| `android/.../AccessoryFrameTest.kt`, `AccessoryInboxTest.kt` | Tests de framing, lecturas grandes del fd, cola parcial, fin y overflow. |
| `packaging/appimage/bundle.py` | Incluye también el COPYING de libusb vendored dentro del AppImage. |
| `scripts/measure_player.py` | Medición reproducible con ruido muy tenue, volumen 1, sink exclusivo, null sink de respaldo y logs/resumen JSON. Abre logcat después de reenumerar. |
| `README.md`, `docs/PROTOCOLO.md` | Uso, permisos, fallback, framing, paquete corto, cierre y límites de la estimación de latencia. |

Los paths abreviados Android corresponden a `android/app/src/main/kotlin/com/soundshare/` y `android/app/src/test/kotlin/com/soundshare/` para los tests.

## Conmutación y fallback verificados

La PC usó GET_PROTOCOL 51, seis SEND_STRING 52 y START 53 sin reclamar la interfaz de adb. Abrió la interfaz 0 del accesorio, IN `0x81`, OUT `0x01`. Al reabrir el hub no se vuelve a seleccionar el alt 0 ya activo: ese SET_INTERFACE reinicializaba el accesorio abierto y se observó PCM antes del formato. Se corrigió conservando el alt 0, y después se verificaron seis sesiones AOA (tres por cada uno de dos arranques del hub) con la app y el mismo descriptor vivos, sin fallback ni cuelgues. El transporte se anuncia antes de STATE; si Android no tiene permiso, el mismo Player usa el socket anterior. Tras conceder permiso inicia una sesión AOA. La reenumeración cortó adb/logcat; adb volvió con la misma serie y el Hub repuso reverse/CONTROL.

El teléfono mostró el diálogo «Abrir siempre SoundShare cuando se conecte Audio PC a Android». **Lo aceptó personalmente el usuario**; no se automatizó ni se eludió. Antes de conceder permiso se observó el intento de PLAYER por adb; esa tentativa no se cuenta como una medición AOA válida. La regla existente `/etc/udev/rules.d/51-android.rules` ya contempla 18d1:2d01 y uaccess: `/dev/bus/usb/003/012` tuvo ACL `user:diegodiaz:rw-`. La apertura libusb y claim_interface funcionaron sin root. No se modificaron permisos ni reglas udev.

Se eligió cabecera de 12 bytes más **un byte de padding**, con payload par. El bloque normal de 256 frames ocupa 2061 bytes: termina en paquete corto aunque el payload original de 2048 bytes sea múltiplo de 512. Android pide siempre 16384 bytes al fd y el parser conserva sobrantes. END y un ID por sesión evitan depender del cierre del fd para detener PLAYER; no se espera al lector físico al salir.

## Método y resultados

Cada corrida formal reproduce 310 s de PCM continuo con ruido a pico de −60 dBFS y volumen de medios del teléfono en 1. El generador usa exclusivamente `soundshare_to_phone`; un null sink queda como destino de respaldo si desaparece el sink del Hub. El teléfono conserva la pista rápida de AudioTrack, burst de 240 frames y capacidad de 9600 frames. No se reproduce sonido por los parlantes de la laptop.

`diag:` registra el máximo de hueco y mínimo de cola de cada segundo. El hueco descuenta el tiempo de escritura propio en AudioTrack. Se excluyen los primeros cinco segundos para resumir hueco y cola; los contadores de underruns se conservan desde el arranque. «Típico» es la mediana de máximos por segundo; se informa también p95 y máximo absoluto. Cola y objetivo se convierten a ms con 48 frames/ms. La latencia es la **aproximación del hub**, no una medición acústica; la app usa 0 ms para la etapa USB AOA: PING/PONG sigue en CONTROL/adb y no mide el accesorio. Ese 0 es una aproximación, no una medición.

Las corridas formales se hicieron sin compilaciones concurrentes. AOA usa el lector con prioridad de audio. Cada una mantuvo una sola sesión PLAYER durante 310 s y produjo 308 ventanas `diag:`.

| Medición | adb | AOA |
| --- | ---: | ---: |
| Duración PCM continuo | 310 s | 310 s |
| Hueco típico (mediana de máximos por segundo) | 13,998 ms | 7,392 ms |
| Hueco p95 (máximos por segundo) | 19,685 ms | 9,818 ms |
| Peor hueco, excluido arranque | 32,241 ms | 12,163 ms |
| Cola mínima observada | 469 frames / 9,77 ms | 332 frames / 6,92 ms |
| Objetivo inicial aprendido | 1920 frames / 40 ms | 960 frames / 20 ms |
| Objetivo mínimo–máximo durante la corrida | 25–45 ms | 15–20 ms |
| Objetivo final | 1680 frames / 35 ms | 720 frames / 15 ms |
| Underruns de AudioTrack | 4 | 0 |
| Latencia estimada del hub, mediana / final | 65 / 72 ms | 49 / 44 ms |

AOA redujo el máximo típico y el peor caso observados y terminó en el objetivo de 15 ms sin cortes en esta corrida. El objetivo osciló entre 15 y 20 ms: **no fue una cola fija de 15 ms durante cinco minutos**. No se probó fijar 10 ms. La latencia estimada quedó por debajo de 60–70 ms; hace falta una medición acústica para afirmar ese valor de extremo a extremo.

Se conservan `MIN_BURSTS = 2` (10 ms en este teléfono) e `INITIAL_TARGET_MS = 30` para teléfonos sin aprendizaje previo. El máximo de 12,163 ms justifica temporalmente 20 ms al sumar el burst de 5 ms y redondear. El aprendizaje AOA, independiente del de adb, dejó 15 ms para el próximo inicio; bajar el mínimo no reduciría el objetivo que piden estos huecos. Se priorizó el lector USB porque entrega PCM al Player urgente: dejarlo con prioridad normal agrega una espera del scheduler.

La corrida AOA anterior, con lector normal y arranque en 30 ms, dio en 310 s: típico 8,205 ms, p95 12,905 ms, peor 20,609 ms, 2 underruns, objetivo final 20 ms y latencia del hub mediana/final 54/49 ms. Es una sola corrida por variante, no una garantía estadística para otros teléfonos o cargas.

Los resultados y el método quedan resumidos en este documento autocontenido. Se retiraron del repositorio los logs, CSV y JSON crudos; la tabla excluye las primeras cinco ventanas de hueco/cola. El script permite generar evidencia nueva localmente.

La primera corrida adb, durante compilaciones de Rust/Android en la PC, duró 310 s (307 diag): mediana 14,331 ms, p95 22,847 ms, peor hueco 35,897 ms, cola mínima 256 frames (5,33 ms), objetivo final 1680 frames (35 ms), 5 underruns, latencia del hub mediana 71 ms y final 65 ms. Se conserva como medición con carga; no se presenta como comparación aislada del transporte.

## Compilación y pruebas

Se usaron las cabeceras ya extraídas en `/tmp/pwdev/root`, sin sudo:

```bash
PKG_CONFIG_PATH=/tmp/pwdev/root/usr/lib64/pkgconfig \
PKG_CONFIG_SYSROOT_DIR=/tmp/pwdev/root cargo test --manifest-path pc/Cargo.toml
PKG_CONFIG_PATH=/tmp/pwdev/root/usr/lib64/pkgconfig \
PKG_CONFIG_SYSROOT_DIR=/tmp/pwdev/root cargo build --manifest-path pc/Cargo.toml
JAVA_HOME=$HOME/.gradle/jdks/jdk-21.0.12+8 \
android/gradlew -p android testDebugUnitTest assembleDebug
```

La verificación inicial registró 22 tests Rust y 25 tests unitarios Android, con assembleDebug exitoso. Inicio: 8 tests; AppImage: 4 tests existentes. Clippy con `--all-targets -- -D warnings` sin advertencias. `ldd` del ejecutable release sólo requiere PipeWire, libgcc y libc: libusb queda enlazada dentro del binario. CAPTURE por adb en el USB 2d01: 105472 frames PCM recibidos, inicio y salida correctos, dos eventos auxiliares fuera de la pantalla principal y recursos liberados. Se agregó el COPYING de libusb al empaquetado; no se reconstruyó el contenedor/AppImage completo.

## Revisión de generaciones y selector (sin dispositivo)

Sobre la base 207d656 se aplicó y descartó el stash de la ronda 2. Cada PLAYER tiene cancelación irreversible y recursos propios; detenerlo pausa/vacía su pista antes del arranque siguiente. Se conservan los sufijos de escrituras parciales con escrituras AudioTrack no bloqueantes protegidas por la generación. Lecturas, END, limpieza y publicaciones tardías no pueden tocar el arranque nuevo. En Rust los estados se validan por ID bajo el lock del Hub antes de ajustar el reloj.

`Link::stop()` retira el handle USB bajo un lock exclusivo después de las transferencias pendientes (timeouts de 200/500 ms); el destructor de `DeviceHandle` libera las interfaces reclamadas. No depende de que terminen todos los `Arc<Link>` de lectores o sesiones. La reapertura está serializada y valida que el dispositivo siga vigente antes de instalar el enlace nuevo.

La app muestra el transporte real de PLAYER y conserva una preferencia AOA/adb. `TRANSPORT aoa` puede reabrir el accesorio ya en 18d1:2d01, sin conmutar; `TRANSPORT adb` conserva el modo físico. La respuesta `PLAYER_TRANSPORT` incluye un motivo opcional para fallback. El detalle del selector, permisos y la decisión de no reenumerar desde CONTROL están en [PROTOCOLO.md](PROTOCOLO.md).

Verificación de esta revisión: **27 tests Rust y 40 tests Android, todos exitosos**, clippy `--all-targets -- -D warnings`, `cargo build` debug y `assembleDebug` exitosos. Los tests puros cubren generaciones canceladas, cierre de cola, decisión de transporte, anuncios antiguos/nuevos, argumentos inválidos, líneas CONTROL partidas, permiso automático único y reintento explícito. Se usaron los comandos de arriba; clippy con las mismas variables de pkg-config y `--manifest-path pc/Cargo.toml`. Gradle informa deprecaciones existentes, sin fallos.

No se usó el teléfono, no se ejecutó el Hub ni se reprodujo audio en esta revisión. Tampoco se reconstruyó `pc/target/release/soundshare`: el nuevo ejecutable está en `pc/target/debug/soundshare` y el APK en `android/app/build/outputs/apk/debug/app-debug.apk`. Los resultados acústicos/USB de la tabla pertenecen a las corridas anteriores, no al selector ni a las generaciones nuevas.

## Latencia: perfiles, arranque y cuántum (v0.3.2)

Mediciones del 13 de octubre de 2026 en el moto g play (2021), Android 11, con `scripts/measure_player.py` por AOA. El objetivo aprendido se fijó en 20 ms antes de cada corrida. El teléfono tiene burst de 240 frames y su mezclador rápido informa 23 ms de latencia propia. La latencia es la aproximación del hub, no una medición acústica.

| Variante | Duración | Latencia del hub, mediana / final | Objetivo | Cortes |
| --- | ---: | ---: | ---: | ---: |
| v0.3.1, cuántum 256 | 120 s | 63 / 54 ms | 20–40 ms | 2 |
| v0.3.1 con cuántum 128 | 120 s | 56 / 45 ms | 20–35 ms | 2 |
| v0.3.2, estable | 120 s | 46 / 41 ms | 15–25 ms | 2 (uno con la pista llena: de la salida) |
| v0.3.2, estable | 300 s | 47 / 46 ms | 15–30 ms | 2 (un hueco de 13,7 ms con el objetivo en 15 ms) |
| v0.3.2, mínima con cola fija de 7,7 ms (descartada) | 120 s | 35 / 34 ms | 7,7 ms | 180 |
| v0.3.2, mínima | 120 s | 38 / 40 ms | 9–13 ms | 23 |
| v0.3.2, mínima | 300 s | 39 / 42 ms | 11–17 ms | 47 |

Hallazgos:

- **Bache de 50 ms al arrancar.** Las siete corridas previas a la corrección tuvieron un hueco de 51–55 ms en el primer segundo; las cinco posteriores, de 4–7 ms. No venía del USB: la PC siguió enviando cada ≤13 ms. Cronometrando el bucle del reproductor, el bache estaba en la primera llamada a `QueueTarget.tick()`: `IntArray.max()` carga `ArraysKt` de kotlin-stdlib, una clase con miles de métodos. Producía cortes al arrancar y dejaba 30–50 ms de cola de más durante un minuto. Se reemplazó por un bucle.
- **Acumulación al arrancar.** Mientras se creaba la pista y despertaba la salida se juntaban hasta 6336 frames (132 ms), descartados con un salto ya sonando. Ahora la pista arranca con silencio y el audio real entra recién con la salida en marcha.
- **Cortes de la salida.** A los 6–9 s de arrancar aparecían dos cortes con 20–27 ms en la pista y huecos de datos de menos de 10 ms, que subían el objetivo a 35–40 ms durante minutos. Un corte con al menos un burst en la pista ya no agranda la cola.
- **De dónde sale el jitter.** Con cuántum 128, la PC procesa cada 3,0 ms como máximo, pero el máximo por segundo entre envíos USB fue de 4,7–4,9 ms en la mediana. La escritura USB sincrónica llegó a tardar 2–9 ms, porque espera que el teléfono vuelva a pedir datos al driver del accesorio. El lector USB del teléfono vio casi los mismos huecos que el reproductor (mediana 5,6 ms, p95 7,3 ms). Pasar el hilo de envío de la PC a `SCHED_RR` con rtkit no los redujo (mediana 4,2 ms, p95 8,2 ms), así que no se incorporó.
- **Perfil mínimo.** Una cola fija de medio burst más un bloque de la PC (más medio burst) cortaba 1,5 veces por segundo en este teléfono. La versión adaptativa de 10 s queda en 9–17 ms y unos 9 cortes por minuto: gana ~8 ms frente al estable en el moto. En teléfonos con menos jitter USB la diferencia debería ser mayor; falta medirlo en el A21s.
- **Costo del cuántum 128.** En la PC, el hub pasó de 7,5 % a 11 % de un núcleo y PipeWire de 1,2 % a 1,9 %.

## Límites y verificación humana

No se mide latencia acústica, propagación USB AOA ni se modifica la latencia del HAL/FastMixer. El feedback de estado conserva la política existente: ~10 ms cuando avanza el receptor, sin heartbeat separado. El objetivo no es fijo: sube ante huecos/cortes y baja lentamente; no se promete 10–15 ms en todos los teléfonos.

El fallback se observó al faltar permiso y ante el formato inválido durante la prueba de reapertura anterior a la corrección. Queda comprobar desconexión/reconexión física del cable, rechazar personalmente el permiso de accesorio y verificar el fallback audible por adb, y Linux sin ACL para 18d1:2d01. También conviene probar Android 12+ con sus cambios de permisos y umbral de AudioTrack y el AppImage final en una PC sin libusb-devel. El script deja el volumen bajo; los datos son diagnóstico de transporte y buffers, no una evaluación subjetiva de calidad con música.
