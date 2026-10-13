# SoundShare

Audio entre una PC Linux con PipeWire y un teléfono Android por cable USB, como un cable auxiliar en cualquiera de los dos sentidos. Transporta PCM sin compresión y ajusta el reloj de la PC para evitar que la cola crezca o se vacíe por la diferencia entre los relojes de audio.

Solo hay un sentido activo a la vez:

```text
PC → Móvil
Apps PC → sink «SoundShare → Móvil» → PipeWire → USB/AOA → AudioTrack → salida del teléfono
                                              F32LE, 48 kHz, estéreo

Móvil → PC
Medios Android → REMOTE_SUBMIX → USB/adb → PipeWire → salida de la PC
                                  S16LE, 48 kHz, estéreo
```

## Inicio rápido

Descargá el [APK para Android](https://github.com/diegoagdiaz/SoundShare/releases/latest/download/SoundShare.apk) y el [AppImage para Linux x86_64](https://github.com/diegoagdiaz/SoundShare/releases/latest/download/SoundShare-x86_64.AppImage). También está disponible el paquete `.tar.gz` para **Fedora 44 x86_64** en [GitHub Releases](https://github.com/diegoagdiaz/SoundShare/releases/latest).

En el teléfono, instalá **SoundShare.apk** y, la primera vez, activá **Depuración USB** en las opciones de desarrollador. Al conectar el cable, desbloqueá el teléfono y aceptá la autorización de la PC; podés marcar «Permitir siempre desde esta computadora».

En la carpeta donde descargaste el AppImage, ejecutá:

```bash
chmod +x SoundShare-x86_64.AppImage   # Una vez
./SoundShare-x86_64.AppImage         # Para usarlo
```

El AppImage incluye **adb, el cliente de PipeWire y sus bibliotecas y plugins**. No compila ni instala dependencias del sistema, ni pide `sudo`. Configura la conexión USB y abre la app en el teléfono; elegí **PC → Móvil** o **Móvil → PC** desde la app. Para salir, `q` + Enter o `Ctrl+C`. Se ejecuta en una terminal; SoundShare todavía no tiene una interfaz gráfica para la PC.

Requiere **Linux x86_64 con glibc ≥ 2.36**, PipeWire y un gestor de sesión como WirePlumber activos en tu sesión, y permiso de acceso al teléfono por USB. El cliente incluido se conecta al servidor de audio de la PC. El AppImage no instala ni reemplaza ese servidor. En equipos donde FUSE no está disponible, ejecutá `./SoundShare-x86_64.AppImage --appimage-extract-and-run`; no necesitás instalar libfuse2 porque el runtime incluido es estático.

El APK también está incluido en el AppImage: si preferís instalarlo por USB desde la PC, conectá y autorizá el teléfono y ejecutá `./SoundShare-x86_64.AppImage install`.

**Alternativa con el paquete `.tar.gz`:** descomprimí `SoundShare-<distribución>-<arquitectura>.tar.gz` y ejecutá desde la carpeta **SoundShare**:

```bash
./soundshare
```

Eso prepara las dependencias faltantes, configura la conexión USB y abre la app en el teléfono. No necesitás Rust, Java ni Android SDK para usar el paquete. El ejecutable del `.tar.gz` requiere bibliotecas compatibles con la distribución y arquitectura indicadas en el nombre del archivo.

Si preferís preparar la PC antes de empezar, son dos comandos:

```bash
./scripts/setup.sh    # Una vez
./soundshare         # Cada vez que lo quieras usar
```

La instalación automática de dependencias admite Fedora y Debian/Ubuntu y puede pedir tu contraseña de `sudo`. En otras distribuciones, instalá `adb`, PipeWire y WirePlumber con tu gestor de paquetes. PipeWire y WirePlumber deben estar activos en la sesión del usuario; el script no cambia ni reinicia la configuración de audio.

**Desde este repositorio** también podés ejecutar `./soundshare`: prepara las herramientas y compila únicamente la PC en la primera ejecución. En ese caso necesitás cabeceras de PipeWire ≥ 1.2 y Rust ≥ 1.88; si falta Rust, el script instala la versión estable con el instalador oficial. La APK se instala por separado en el teléfono, sin compilar Android en la PC de uso.

```bash
git clone https://github.com/diegoagdiaz/SoundShare.git
cd SoundShare
./soundshare
```

No hace falta ejecutar `build.sh` ni `setup.sh` antes. En Fedora y Debian/Ubuntu, el inicio instala las dependencias faltantes; puede pedir `sudo`. Descargá el APK desde la release e instalalo en el teléfono. La primera compilación requiere conexión a Internet; las siguientes ejecuciones usan el ejecutable ya compilado.

Para arrancar directamente en un sentido:

```bash
./soundshare pc2phone
./soundshare phone2pc
```

La depuración USB sigue siendo necesaria: CONTROL y CAPTURE usan adb, incluso cuando PLAYER va por AOA. Instalar el APK no puede activar ni autorizar ese permiso por vos.

## Conexión

PC → Móvil prefiere **Android Open Accessory (AOA)** para PCM y el estado de cola de 10 ms. La PC busca la serie USB que coincide con la serie de adb, negocia AOA y espera **18d1:2d01 (accessory + adb)** antes de abrir el resto. El teléfono se reenumera una sola vez por conexión del cable; luego SoundShare rehace `adb reverse` y abre la app. No reclama la interfaz de adb. Una caída temporal de adb con el cable presente rehace el túnel cuando vuelve y permite reconectar CONTROL.

En Android, aceptá el diálogo **Abrir SoundShare cuando se conecte este accesorio** o el permiso USB de SoundShare. Si falta permiso en Linux, se rechaza el diálogo, no hay soporte AOA o falla la apertura/transferencia, PLAYER vuelve al socket adb automáticamente. Un fallo del descriptor inhibe AOA hasta reconectar el accesorio; detener y volver a iniciar audio conserva el descriptor y abre otra sesión lógica. El diálogo puede quedar pendiente mientras funciona el fallback.

CONTROL y CAPTURE siguen en `127.0.0.1:47100` por `adb reverse tcp:47100 tcp:47100`. CAPTURE se lanza con `adb shell` y conserva la identidad shell necesaria en Android 11. Para comparar o forzar el transporte anterior: `SOUNDSHARE_PLAYER_TRANSPORT=adb ./soundshare pc2phone`. Sin esa variable se intenta AOA; `aoa` también intenta AOA con fallback.

AOA usa libusb compilada dentro del ejecutable (`rusb` con `vendored`): no necesita libusb-devel y no agrega una biblioteca dinámica al AppImage. La regla Android existente debe cubrir **18d1:2d01**. Si no lo cubre, el administrador puede agregar una regla udev (por ejemplo `/etc/udev/rules.d/70-soundshare.rules`) y reconectar el cable:

```udev
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", ATTR{idProduct}=="2d01", MODE="0660", TAG+="uaccess"
```

SoundShare se ejecuta en la sesión de usuario, sin root. También necesita acceso al USB original para negociar AOA. No cambia reglas ni permisos del sistema; si no puede abrir el accesorio, informa el motivo y usa adb. AOA reemplaza MTP mientras está conectado; para volver a transferencia de archivos, desconectá y reconectá el cable.

## Requisitos para desarrollar y compilar

- Linux con PipeWire ≥ 1.0 y WirePlumber. El crate actual usa `pipewire-rs 0.10.1` con la feature `v1_2_0`; la compatibilidad de compilación con cabeceras anteriores a 1.2 no está verificada.
- Rust ≥ 1.88, `pkg-config`, cabeceras de PipeWire ≥ 1.2 y clang/libclang para bindgen. En Fedora: `sudo dnf install pipewire-devel clang pkgconf-pkg-config`.
- `adb` de Android SDK Platform-Tools.
- Para la APK: JDK 17 o superior **con `javac`**, Android SDK con plataforma 36 (`compileSdk = 36`). El proyecto usa AGP 9.4.1 con Kotlin integrado y wrapper Gradle 9.8.0, sin AndroidX.
- Teléfono: Android 8+ para PC → Móvil (`minSdk = 26`). Móvil → PC necesita Android 11+; se verificó la captura en Android 11 y Android 16 (LineageOS 23.2, Samsung SM-A217M). Usa APIs internas cuya compatibilidad depende del sistema.
- Cable USB que permita datos y depuración USB autorizada en el teléfono.

## Compilar y generar el paquete

Desde la raíz:

```bash
./scripts/build.sh
```

Compila la PC en release y la APK debug. Si falta `JAVA_HOME`, busca un JDK con `javac` ≥ 17 en `~/.gradle/jdks/*` y `/usr/lib/jvm/*`. Si falta `android/local.properties`, lo crea usando un SDK existente en `ANDROID_HOME` o `~/Android/Sdk`. No instala dependencias del sistema; Cargo y el wrapper de Gradle pueden descargar sus dependencias al compilar.

```bash
./scripts/build.sh --pc          # Solo la PC
./scripts/build.sh --android     # Solo la APK
./scripts/build.sh --install     # Ambas partes y luego instala en el teléfono
./scripts/build.sh --bundle      # Ambas partes y genera el paquete para distribuir
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
- Con `--bundle`: `dist/SoundShare.apk` y `dist/SoundShare-<distribución>-<arquitectura>.tar.gz`, con APK, ejecutable, comando de inicio e instalador de dependencias, más `dist/SHA256SUMS` para verificar las descargas. El APK usa la firma debug del entorno de compilación, igual que la app debug existente.

Para empaquetar artefactos existentes sin volver a compilarlos: `./scripts/package.sh`. También podés combinar `--bundle` con `--pc` o `--android` si el artefacto omitido ya existe. El paquete incluye una única APK, que podés instalar directamente desde el administrador de archivos del teléfono.

Para generar el **AppImage**, con una APK debug ya compilada y Podman o Docker disponibles:

```bash
./scripts/appimage.sh
```

El script compila la PC y un cliente PipeWire 1.2.8 en un contenedor Debian 12 para mantener una base de glibc 2.36. Empaqueta adb, las bibliotecas y los plugins que se cargan dinámicamente, el APK y las licencias. Las fuentes de PipeWire, appimagetool y el runtime se descargan con checksums verificados. No instala herramientas en el sistema anfitrión. El resultado es `dist/SoundShare-x86_64.AppImage`; `dist/SHA256SUMS` incluye todos los paquetes presentes. Los archivos y la caché de compilación quedan en `dist/appimage-build/`.

Para verificar el capturador en un teléfono conectado: `python3 scripts/test_capture.py --serial SERIAL`. Usa la APK debug ya compilada sin instalarla, cuenta PCM en memoria durante tres segundos y elimina el APK temporal y su túnel al terminar. Durante la prueba desvía el audio de medios del teléfono, sin cambiar su volumen ni guardar la grabación. Agregá `--check-no-popup` para comprobar en el registro de actividades que el arranque no abrió el auxiliar en la pantalla principal, que la actividad visible se conservó y que la pantalla auxiliar se liberó.

Conectá y autorizá el teléfono, y ejecutá:

```bash
pc/target/release/soundshare install
# O elegí la APK explícitamente:
pc/target/release/soundshare install android/app/build/outputs/apk/debug/app-debug.apk
```

`install` busca `SoundShare.apk` y las APK release y debug subiendo desde la ubicación del ejecutable y elige la más reciente por fecha de modificación. También acepta una ruta explícita.

## Usar

En la PC:

```bash
./soundshare            # Espera al teléfono y abre la app al conectar
./soundshare pc2phone   # Arranca en PC → Móvil
./soundshare phone2pc   # Arranca en Móvil → PC
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

La PC abre **SoundShare** al detectar el teléfono autorizado; si el sistema impide abrirla, podés abrirla manualmente. Tocá **PC → Móvil** o **Móvil → PC**. Tocar la tarjeta activa o **Detener** apaga el modo. La app muestra estadísticas y mantiene un servicio en primer plano con notificación y acción **Salir**. Esa acción pide apagar ambos sentidos y espera hasta 500 ms la confirmación de la PC antes de cerrar. La PC decide el modo y lo informa a la app. En PC → Móvil abre la app si no hay un canal de control conectado; si se desconecta la última app, apaga ese modo automáticamente. Al reconectar el cable, vuelve a abrir la app; el modo se elige otra vez.

Durante la transmisión aparece **Latencia estimada del enlace**, separada del objetivo de cola. Suma las colas observadas, los tiempos de captura/salida que informa Android/PipeWire y la mitad del tiempo de ida y vuelta del canal USB. Esa última parte supone transporte simétrico: el canal de control y el de audio pueden sufrir retrasos diferentes. El indicador no mide la latencia acústica ni los buffers internos de la app que genera el sonido. Si falta un timestamp o una medición reciente, dice **Estimación parcial** y nombra la parte faltante; no inventa un tiempo fijo para completarla. Requiere la APK y el ejecutable de PC actualizados.

En Móvil → PC, la PC lanza el capturador con permisos de shell (uid 2000), sin root. Android desvía la salida de medios al `REMOTE_SUBMIX` y el parlante del teléfono se calla. El capturador intenta poner al máximo el volumen de ese dispositivo virtual, conservando el del parlante; regulá la escucha desde la PC. Suena como un stream de reproducción `SoundShare (Móvil)` por la salida de la PC.

Opciones y diagnóstico:

```bash
./soundshare -s SERIAL  # También acepta ANDROID_SERIAL; obligatorio si hay varios dispositivos
./soundshare phone2pc -o NOMBRE_SALIDA  # También --output o SOUNDSHARE_OUTPUT
SOUNDSHARE_DEBUG=1 ./soundshare
SOUNDSHARE_DUMP=recibido.pcm ./soundshare phone2pc
SOUNDSHARE_FORCE_RATE=1.01 ./soundshare  # Solo para medir la corrección
```

`-o/--output SALIDA` elige el `node.name` de PipeWire para Móvil → PC (consultá `pactl list short sinks`). Tiene prioridad sobre `SOUNDSHARE_OUTPUT`; sin ambos se usa la salida actual. Si la salida elegida no existe, el stream no cae a otra salida. `SOUNDSHARE_DUMP` guarda PCM crudo S16LE, 48 kHz estéreo, tal como llega del teléfono, antes del resampler; puede agregar carga de disco.

La PC acepta `-p PUERTO`, pero la app usa el puerto fijo 47100: dejá el predeterminado para usar ambos sentidos con la app actual. El capturador sí recibe el puerto elegido. Busca `adb` en `ADB`, `PATH`, `ANDROID_HOME/platform-tools`, `ANDROID_SDK_ROOT/platform-tools` y `~/Android/Sdk/platform-tools`. Sus comandos tienen un timeout de 10 s y el arranque del capturador no bloquea los cambios de modo ni la salida.

## Calidad y latencia

PC → Móvil transporta float de 32 bits a 48 kHz estéreo. Móvil → PC transporta PCM de 16 bits a 48 kHz estéreo, el formato de la mezcla capturada. No hay codec con pérdidas. La corrección de reloj usa el resampler adaptativo de PipeWire con `resample.quality=10`; por eso la salida completa no se promete idéntica bit a bit tras el ajuste de reloj.

El sink pide un cuántum de 256 frames (5,3 ms) y sigue procesando aunque no haya audio. Android solicita AudioTrack de baja latencia; obtener una pista rápida depende del teléfono. En Android 12+, fija el [umbral de inicio de AudioTrack](https://developer.android.com/reference/android/media/AudioTrack#setStartThresholdInFrames(int)) en el objetivo de cola, separado de los dos bursts de margen para escribir. La cola recuerda el peor hueco del túnel USB en una ventana de 30 s: sube enseguida ante un hueco mayor y baja hasta un burst cada 20 s, sin pasar por debajo del nivel seguro. Los cortes elevan un piso que decae cada 30 s. El objetivo aprendido se guarda en SharedPreferences para la próxima sesión, separado para adb y AOA. Se conserva el mínimo de dos bursts; AOA se adapta con la misma política de huecos y cortes. Ante una acumulación grande se descarta el exceso para recuperar latencia.

Móvil → PC espera un solo frame de AudioRecord y luego drena hasta 1024 frames disponibles sin bloquear. Así un resto parcial del bloque de REMOTE_SUBMIX se envía enseguida, sin esperar a completar un pedido fijo de 240 frames. Esto no elimina el período de entrega del submix ni el jitter de adb.

Verificación de ese cambio en el moto g play (2021), Android 11: en pruebas de captura de 12 s por variante, las iteraciones de lectura/envío estables bajaron de 200–201/s a 47/s al enviar cada bloque disponible completo. Ambas variantes entregaron PCM a 48 kHz estéreo y cerraron correctamente. Es una comprobación de la entrega de captura; no mide la latencia total de extremo a extremo ni la fidelidad con un tono.

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

Móvil → PC tiene un objetivo promedio inicial de 26 ms; arranca al reunir ese objetivo más medio «serrucho» de los bloques recibidos (estimado inicialmente en 21 ms y luego medido). Cada corte agrega 4 ms, hasta 120 ms. Tras 60 s continuos de reproducción con al menos 4 ms más un ciclo de margen después de consumir audio, baja 4 ms hacia el mínimo de 26 ms. Un corte, margen insuficiente o descarte por acumulación reinicia esa espera; el lazo drena el exceso gradualmente, sin agregar descartes durante la recuperación. Ambos sentidos usan un lazo PI de 0,01 Hz limitado a ±1000 ppm, con dos etapas de filtro y suavizado total de 3 s. El filtro se prepara en el objetivo al arrancar Móvil → PC. Los valores de latencia mostrados son estimaciones; puede aparecer `?` si Android no entrega un timestamp de salida. Ver [la especificación del protocolo](docs/PROTOCOLO.md).

Para medir PLAYER con el teléfono conectado, la APK instalada y ningún hub activo:

```bash
python3 scripts/measure_player.py --serial SERIAL --transport adb --seconds 310 --output /tmp/medicion-adb
python3 scripts/measure_player.py --serial SERIAL --transport aoa --seconds 310 --output /tmp/medicion-aoa
```

La prueba fija el volumen de medios del teléfono en 1, activa `diag:`, usa ruido muy tenue y envía exclusivamente al sink `soundshare_to_phone`; no reproduce por la salida física de la PC. Guarda `hub.log`, `logcat.log` y `summary.json`. **No acepta diálogos USB**: aceptalos personalmente. Revisá `transportes` en el resumen: una solicitud AOA puede medir adb si hubo fallback. El hueco típico se resume como mediana y p95 del máximo de cada segundo; excluye los primeros cinco segundos para huecos/cola. La latencia del hub es aproximada y no incluye una medición acústica. Ver [el informe AOA](docs/MEDICION_AOA.md).

## Solución de problemas

- **Teléfono `unauthorized`:** desbloquealo y aceptá el diálogo de depuración USB.
- **Puerto en uso:** revisá si ya hay otro SoundShare abierto y cerralo. Cambiar `-p` no cambia el puerto de la app actual.
- **App no instalada:** instalá `SoundShare.apk` desde el teléfono y reconectá el cable, o ejecutá `./soundshare install SoundShare.apk` desde la carpeta del paquete.
- **Varios dispositivos conectados:** elegí el teléfono con `./soundshare -s SERIAL` o desconectá los demás.
- **Binario incompatible:** usá un paquete para tu distribución y arquitectura, o ejecutá `./soundshare` desde el código fuente para compilar en tu PC.
- **El AppImage no monta con FUSE:** ejecutá `./SoundShare-x86_64.AppImage --appimage-extract-and-run`.
- **Sin permisos USB:** el AppImage incluye adb, pero no puede modificar las reglas USB del sistema sin privilegios. Configurá las reglas udev de Android de tu distribución y volvé a conectar el teléfono; no ejecutes SoundShare con `sudo` porque necesita tu sesión de audio.
- **Ventana de volcado al iniciar Móvil → PC:** las APK anteriores mostraban brevemente una actividad de shell en Android 11. Ahora, si el sistema necesita ese recurso para iniciar la captura, se abre en una pantalla virtual privada fuera de la pantalla del teléfono; al terminar el arranque, se libera esa pantalla junto con su actividad. No se abren ni cierran tareas de la pantalla principal. Android 12+ inicia la captura directamente. La corrección de Android 14+ que identifica el dispositivo real al crear `AudioRecord` se conserva; las APK anteriores a esa corrección podían dejar una ventana de volcado abierta en Android 16. Actualizá la APK para usar estas correcciones.
- **PC → Móvil sin sonido:** revisá que la salida por defecto sea **SoundShare → Móvil**, que la aplicación de la PC use esa salida y que el volumen del teléfono permita escuchar. Si faltan los metadatos de PipeWire, seleccioná el sink a mano.
- **Falta PipeWire al compilar:** instalá `pipewire-devel` y clang/libclang; el script corta si `pkg-config` no encuentra `libpipewire-0.3`.
- **Gradle no encuentra Java o el SDK:** apuntá `JAVA_HOME` a un JDK con `javac` ≥ 17 y configurá `ANDROID_HOME` o `android/local.properties` con la ruta del SDK y plataforma 36.

## Limitaciones y próximas ideas

Actualmente necesita Linux/PipeWire, cable USB y depuración USB. No ofrece audio simultáneo en ambos sentidos, interfaz gráfica para la PC ni transporte Wi-Fi. Los formatos emitidos son fijos a 48 kHz estéreo. La latencia tiene el piso del hardware y el jitter del transporte elegido; el buffer puede crecer con los cortes y los descartes pueden producir un salto audible. La captura usa APIs internas; se verificó en Android 11 y Android 16, sin garantizar todas las versiones ni las modificaciones de cada fabricante.

Ideas para una **v2**, todavía no implementadas:

- PC → Móvil sin depuración USB: AOA ya lleva PLAYER, pero CONTROL todavía usa adb.
- Interfaz gráfica para la PC.
- Transporte opcional por Wi-Fi.
- Más formatos: 24 bits y 44,1/96 kHz.
