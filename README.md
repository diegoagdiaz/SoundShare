# SoundShare

Audio entre una PC Linux con PipeWire y un teléfono Android por cable USB, como un cable auxiliar en cualquiera de los dos sentidos. Transporta PCM sin compresión y ajusta el reloj de la PC para evitar que la cola crezca o se vacíe por la diferencia entre los relojes de audio.

Solo hay un sentido activo a la vez:

```text
PC → Móvil
Apps PC → sink «SoundShare → Móvil» → PipeWire → USB/adb → AudioTrack → salida del teléfono
                                              F32LE, 48 kHz, estéreo

Móvil → PC
Medios Android → REMOTE_SUBMIX → USB/adb → PipeWire → salida de la PC
                                  S16LE, 48 kHz, estéreo
```

La PC escucha en `127.0.0.1:47100`. Todas las conexiones las abre el teléfono hacia su propio `127.0.0.1:47100`; el túnel `adb reverse tcp:47100 tcp:47100` las lleva por USB a la PC. SoundShare configura ese túnel automáticamente. Requiere depuración USB, incluso en Móvil → PC.

## Requisitos

- Linux con PipeWire ≥ 1.0 y WirePlumber. El crate actual usa `pipewire-rs 0.10.1` con la feature `v1_2_0`; la compatibilidad de compilación con cabeceras anteriores a 1.2 no está verificada.
- Rust estable actualizado, `pkg-config`, cabeceras de PipeWire y clang/libclang para bindgen. En Fedora: `sudo dnf install pipewire-devel clang pkgconf-pkg-config`.
- `adb` de Android SDK Platform-Tools.
- Para la APK: JDK 17 o superior **con `javac`**, Android SDK con plataforma 36 (`compileSdk = 36`). El proyecto usa AGP 9.4.1 con Kotlin integrado y wrapper Gradle 9.8.0, sin AndroidX.
- Teléfono: Android 8+ para PC → Móvil (`minSdk = 26`). Móvil → PC necesita Android 11+ y fue probado solo en Android 11; usa APIs internas cuya compatibilidad depende del sistema.
- Cable USB que permita datos y depuración USB autorizada en el teléfono.

## Compilar e instalar

Desde la raíz:

```bash
./scripts/build.sh
```

Compila la PC en release y la APK debug. Si falta `JAVA_HOME`, busca un JDK con `javac` ≥ 17 en `~/.gradle/jdks/*` y `/usr/lib/jvm/*`. Si falta `android/local.properties`, lo crea usando un SDK existente en `ANDROID_HOME` o `~/Android/Sdk`. No instala dependencias del sistema; Cargo y el wrapper de Gradle pueden descargar sus dependencias al compilar.

```bash
./scripts/build.sh --pc          # Solo la PC
./scripts/build.sh --android     # Solo la APK
./scripts/build.sh --install     # Ambas partes y luego instala en el teléfono
```

`--pc` y `--android` son excluyentes. Se puede agregar `--install` a cualquiera: necesitás el ejecutable de la PC y una APK ya compilados para la parte que omitís. La instalación usa el dispositivo seleccionado por `ANDROID_SERIAL`, si está definido.

También podés compilar por separado:

```bash
(cd pc && cargo build --release)
# JAVA_HOME debe apuntar a un JDK compatible con javac:
(cd android && ./gradlew assembleDebug)
```

Resultados:

- Ejecutable: `pc/target/release/soundshare`.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

Conectá y autorizá el teléfono, y ejecutá:

```bash
pc/target/release/soundshare install
# O elegí la APK explícitamente:
pc/target/release/soundshare install android/app/build/outputs/apk/debug/app-debug.apk
```

`install` busca las APK release y debug subiendo desde la ubicación del ejecutable y elige la más reciente por fecha de modificación. También acepta una ruta explícita.

## Usar

En la PC:

```bash
pc/target/release/soundshare            # Espera al teléfono
pc/target/release/soundshare pc2phone   # Arranca en PC → Móvil
pc/target/release/soundshare phone2pc   # Arranca en Móvil → PC
```

Mientras corre, escribí un comando y presioná Enter:

| Comando | Acción |
| --- | --- |
| `1` | PC → Móvil |
| `2` | Móvil → PC |
| `0` | Apagar el audio compartido |
| `s` | Mostrar u ocultar estadísticas en la terminal |
| `q` | Salir |
| `h` | Mostrar los comandos |

`Ctrl+C` también sale en orden y restaura la salida configurada anterior de PipeWire. Al activar PC → Móvil, crea el sink `SoundShare → Móvil` (`soundshare_to_phone`) y lo pone como salida por defecto mediante `default.configured.audio.sink`. Si el valor anterior ya apuntaba a SoundShare, lo limpia al apagar para no restaurar un sink de una ejecución interrumpida.

En Android, abrí **SoundShare** y tocá **PC → Móvil** o **Móvil → PC**. Tocar la tarjeta activa o **Detener** apaga el modo. La app muestra estadísticas y mantiene un servicio en primer plano con notificación y acción **Salir**. Esa acción pide apagar ambos sentidos y espera hasta 500 ms la confirmación de la PC antes de cerrar. La PC decide el modo y lo informa a la app. En PC → Móvil abre la app si no hay un canal de control conectado; si se desconecta la última app, apaga ese modo automáticamente.

En Móvil → PC, la PC lanza el capturador con permisos de shell (uid 2000), sin root. Android desvía la salida de medios al `REMOTE_SUBMIX` y el parlante del teléfono se calla. El capturador intenta poner al máximo el volumen de ese dispositivo virtual, conservando el del parlante; regulá la escucha desde la PC. Suena como un stream de reproducción `SoundShare (Móvil)` por la salida de la PC.

Opciones y diagnóstico:

```bash
pc/target/release/soundshare -s SERIAL  # También acepta ANDROID_SERIAL
pc/target/release/soundshare phone2pc -o NOMBRE_SALIDA  # También --output o SOUNDSHARE_OUTPUT
SOUNDSHARE_DEBUG=1 pc/target/release/soundshare
SOUNDSHARE_DUMP=recibido.pcm pc/target/release/soundshare phone2pc
SOUNDSHARE_FORCE_RATE=1.01 pc/target/release/soundshare  # Solo para medir la corrección
```

`-o/--output SALIDA` elige el `node.name` de PipeWire para Móvil → PC (consultá `pactl list short sinks`). Tiene prioridad sobre `SOUNDSHARE_OUTPUT`; sin ambos se usa la salida actual. Si la salida elegida no existe, el stream no cae a otra salida. `SOUNDSHARE_DUMP` guarda PCM crudo S16LE, 48 kHz estéreo, tal como llega del teléfono, antes del resampler; puede agregar carga de disco.

La PC acepta `-p PUERTO`, pero la app usa el puerto fijo 47100: dejá el predeterminado para usar ambos sentidos con la app actual. El capturador sí recibe el puerto elegido. Busca `adb` en `ADB`, `PATH`, `ANDROID_HOME/platform-tools`, `ANDROID_SDK_ROOT/platform-tools` y `~/Android/Sdk/platform-tools`. Sus comandos tienen un timeout de 10 s y el arranque del capturador no bloquea los cambios de modo ni la salida.

## Calidad y latencia

PC → Móvil transporta float de 32 bits a 48 kHz estéreo. Móvil → PC transporta PCM de 16 bits a 48 kHz estéreo, el formato de la mezcla capturada. No hay codec con pérdidas. La corrección de reloj usa el resampler adaptativo de PipeWire con `resample.quality=10`; por eso la salida completa no se promete idéntica bit a bit tras el ajuste de reloj.

El sink pide un cuántum de 256 frames (5,3 ms) y sigue procesando aunque no haya audio. Android solicita AudioTrack de baja latencia; obtener una pista rápida depende del teléfono. La cola recuerda el peor hueco del túnel USB en una ventana de 30 s: sube enseguida ante un hueco mayor y baja de a un burst cada 20 s. Los cortes elevan un piso que decae cada 30 s. El objetivo aprendido se guarda en SharedPreferences para la próxima sesión. Ante una acumulación grande se descarta el exceso para recuperar latencia.

Mediciones aportadas por el autor en un **moto g play (2021), Android 11**; no son garantías para otros equipos ni pruebas ejecutadas por este script:

| Medición | Resultado observado |
| --- | --- |
| Ciclos y envíos de PipeWire en la PC | Cada 5,4–5,6 ms |
| Huecos de llegada del túnel adb en el teléfono | Típicos de 8–11 ms; picos de ~20 ms cada algunos segundos, también con un receptor sin audio |
| Cola estable PC → Móvil | ~25–30 ms, limitada por el jitter del túnel adb (picos de ~20 ms) |
| Latencia total PC → Móvil | ~55–60 ms |
| Cortes PC → Móvil | 0 en 90 s |
| Buffer PC en Móvil → PC | ~26–30 ms; 0–1 cortes por minuto |
| Entrega de REMOTE_SUBMIX | Bloques de ~21 ms: piso de Móvil → PC, no latencia total |
| Arranque del parlante del teléfono | ~100–150 ms; se descarta la acumulación excesiva |
| Fidelidad de la captura de submix con volumen máximo | Tono de amplitud 8000 → pico 8000, RMS 5656,4 |
| Móvil → PC, tono de 440 Hz a −12 dBFS recibido del teléfono | Bit a bit; THD+N −84,5 dB (piso teórico de 16 bits: −85,8 dB) |
| Móvil → PC tras el resampler adaptativo | THD+N −82,4 dB, nivel exacto, L=R |
| Afinación Móvil → PC | Estable ±30 ppm; variación medida de 0,014 % pico a pico (lo audible empieza ~0,05 %) |

Móvil → PC tiene un objetivo promedio inicial de 26 ms; arranca al reunir ese objetivo más medio «serrucho» de los bloques recibidos (estimado inicialmente en 21 ms y luego medido). Cada corte agrega 4 ms, hasta 120 ms. Ambos sentidos usan un lazo PI de 0,01 Hz limitado a ±1000 ppm, con dos etapas de filtro y suavizado total de 3 s. El filtro se prepara en el objetivo al arrancar Móvil → PC. Los valores de latencia mostrados son estimaciones; puede aparecer `?` si Android no entrega un timestamp de salida. Ver [la especificación del protocolo](docs/PROTOCOLO.md).

## Solución de problemas

- **Teléfono `unauthorized`:** desbloquealo y aceptá el diálogo de depuración USB.
- **Puerto en uso:** revisá si ya hay otro SoundShare abierto y cerralo. Cambiar `-p` no cambia el puerto de la app actual.
- **App no instalada:** compilá la APK y ejecutá `soundshare install` con su ruta si hace falta.
- **Parpadeo al iniciar Móvil → PC:** cuando el sistema exige una actividad visible, el capturador abre un instante `com.android.shell/.HeapDumpActivity`. Intenta cerrar sus tareas con `am stack` en Android 11; si no está disponible, abre SoundShare para taparla, y usa Inicio como último recurso. Android 12+ tiene atribución explícita al shell, pero sigue pendiente probar la captura en un equipo de esa versión.
- **PC → Móvil sin sonido:** revisá que la salida por defecto sea **SoundShare → Móvil**, que la aplicación de la PC use esa salida y que el volumen del teléfono permita escuchar. Si faltan los metadatos de PipeWire, seleccioná el sink a mano.
- **Falta PipeWire al compilar:** instalá `pipewire-devel` y clang/libclang; el script corta si `pkg-config` no encuentra `libpipewire-0.3`.
- **Gradle no encuentra Java o el SDK:** apuntá `JAVA_HOME` a un JDK con `javac` ≥ 17 y configurá `ANDROID_HOME` o `android/local.properties` con la ruta del SDK y plataforma 36.

## Limitaciones y próximas ideas

Actualmente necesita Linux/PipeWire, cable USB y depuración USB. No ofrece audio simultáneo en ambos sentidos, interfaz gráfica para la PC ni transporte Wi-Fi. Los formatos emitidos son fijos a 48 kHz estéreo. La latencia tiene el piso del hardware y del túnel adb; el buffer puede crecer con los cortes y los descartes pueden producir un salto audible. La captura usa APIs internas y solo está probada en Android 11.

Ideas para una **v2**, todavía no implementadas:

- Transporte AOA (Android Open Accessory) directo por USB, sin adbd: explorar menos jitter y PC → Móvil sin depuración USB.
- Interfaz gráfica para la PC.
- Transporte opcional por Wi-Fi.
- Más formatos: 24 bits y 44,1/96 kHz.
