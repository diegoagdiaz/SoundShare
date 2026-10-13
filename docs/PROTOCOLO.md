# Protocolo SoundShare, versión 1

Esta especificación describe `pc/src/protocol.rs`, `hub.rs`, `engine.rs` y las implementaciones Android de `Protocol`, `LinkService`, `Player` y `CaptureMain`.

## Transporte y convenciones

La PC escucha TCP en loopback. El puerto predeterminado es **47100**. El teléfono siempre inicia las conexiones hacia su propio `127.0.0.1:47100`; `adb reverse tcp:47100 tcp:47100` las lleva por USB hasta la PC. No hay conexiones iniciadas por la PC hacia el teléfono. Cada rol tiene su propio socket.

La PC acepta otro puerto con `-p` y lo pasa al capturador y al túnel, pero `LinkService` y `Player` usan la constante 47100. No hay descubrimiento ni negociación de puerto en la app actual.

Los offsets son en bytes, desde cero al comienzo de cada bloque. Todos los enteros multibyte y muestras son **little-endian**. Un frame contiene una muestra por canal; estéreo se intercala izquierda, derecha. TCP es un flujo: una lectura puede contener un bloque parcial, varios bloques o un frame partido. Hay que juntar los bytes necesarios y conservar los sobrantes; los límites de `write` no son delimitadores.

No hay compresión, cifrado propio, autenticación propia, timestamps de audio ni cabeceras por bloque PCM. La autorización de depuración USB pertenece a adb. El formato se fija una vez por conexión de audio; cambiarlo requiere otro socket.

## Hello binario: 8 bytes

El teléfono lo envía primero en **todas** las conexiones. La PC no devuelve un hello binario.

| Offset | Tamaño | Tipo | Valor |
| --- | --- | --- | --- |
| 0 | 4 | Bytes ASCII | `SNDS` = `53 4e 44 53` hexadecimal |
| 4 | 1 | `u8` | Versión: `1` |
| 5 | 1 | `u8` | Rol: `1` CONTROL, `2` PLAYER, `3` CAPTURE |
| 6 | 2 | Bytes reservados | Emitir `00 00` |

La PC aplica un timeout de lectura de 5 s durante el hello. Rechaza magic, versión o rol desconocidos; intenta enviar `ERROR <texto>\n` y cierra la conexión, incluso si el rol solicitado era binario. Después de aceptar el hello elimina ese timeout en CONTROL y PLAYER; en CAPTURE lo conserva hasta leer también el bloque de formato.

## Bloque de formato: 12 bytes

Solo aparece en PLAYER y CAPTURE, enviado por el productor de audio.

| Offset | Tamaño | Tipo | Contenido |
| --- | --- | --- | --- |
| 0 | 4 | `u32` LE | Frecuencia de muestreo en Hz |
| 4 | 1 | `u8` | Cantidad de canales |
| 5 | 1 | `u8` | `1` = S16LE; `2` = F32LE |
| 6 | 6 | Bytes reservados | Emitir todos en cero |

S16LE representa enteros con signo de 16 bits; F32LE representa float IEEE 754 de 32 bits. Un frame ocupa `canales × bytes por muestra`.

| Rol | Emisor del formato y PCM | Formato actual | Bytes/frame | Bytes/s nominales |
| --- | --- | --- | --- | --- |
| PLAYER | PC | 48000 Hz, 2 canales, F32LE | 8 | 384000 |
| CAPTURE | Capturador Android | 48000 Hz, 2 canales, S16LE | 4 | 192000 |

Los bloques actuales son, en hexadecimal:

```text
PLAYER:  80 bb 00 00 02 02 00 00 00 00 00 00
CAPTURE: 80 bb 00 00 02 01 00 00 00 00 00 00
```

El lector Rust acepta frecuencias de 8000 a 384000 Hz, 1–2 canales y ambos tipos de muestra. El lector Kotlin acepta 1–8 canales en la cabecera, pero `Player` solo configura salida mono o estéreo; ese rango del parser no constituye soporte multicanal. Los productores actuales siempre usan los formatos de la tabla, sin negociación.

## CONTROL: líneas UTF-8

Después del hello, ambas direcciones intercambian líneas terminadas en `\n` (LF), de hasta 4096 bytes incluyendo LF; si se excede el límite se cierra el canal. El primer espacio separa el comando de su argumento. No hay bloque de formato en este canal.

| Dirección | Línea | Significado |
| --- | --- | --- |
| Teléfono → PC | `HELLO <fabricante> <modelo>\|<sdk>` | Identificación; el separador real es `|`, sin barra invertida |
| Teléfono → PC | `MODE off` | Pedir apagado |
| Teléfono → PC | `MODE pc2phone` | Pedir PC → Móvil |
| Teléfono → PC | `MODE phone2pc` | Pedir Móvil → PC |
| PC → teléfono | `STATE <modo>` | Modo decidido por la PC: `off`, `pc2phone` o `phone2pc` |
| PC → teléfono | `INFO <texto>` | Estadísticas para mostrar; texto libre |
| PC → teléfono | `ERROR <texto>` | Error para mostrar; texto libre |

La PC transmite `STATE` apenas incorpora un CONTROL, sin esperar el `HELLO` de texto, y lo difunde a todas las apps conectadas. `HELLO` identifica al dispositivo; no cambia el modo. `MODE` es un pedido: la app inicia o detiene PLAYER al recibir `STATE`. Si falla un cambio, la PC informa `ERROR` y `STATE off`. El arranque adb y la creación de streams son asíncronos: también pueden producir ese error después del primer `STATE` del modo pedido. Un error de PipeWire se asocia al stream que lo originó para no apagar una sesión posterior.

`INFO` se emite aproximadamente una vez por segundo con audio activo y su stream disponible, incluso si se ocultaron las estadísticas de la terminal. No se emite en `off`. Los comandos de texto desconocidos se ignoran; un modo desconocido solo deja un mensaje en la terminal de la PC. No hay IDs de pedido ni ACK separado.

```text
App Android                         PC / Hub
    |---- TCP (por adb reverse) -------->|
    |---- hello binario CONTROL -------->|
    |<--- STATE <modo actual> -----------|  también a las otras apps
    |---- HELLO fabricante modelo|sdk -->|  puede cruzarse con STATE
    |---- MODE pc2phone ---------------->|
    |                                   |-- inicia sink / decide modo
    |<--- STATE pc2phone ----------------|
    |<--- INFO <texto> ------------------|  ~1 s mientras hay audio
    |---- MODE off --------------------->|
    |<--- STATE off ---------------------|
```

Cerrar el último CONTROL durante PC → Móvil apaga el modo y restaura la salida de la PC. En Móvil → PC, cerrar CONTROL por sí solo no implica detener el capturador de shell. La acción **Salir** de la notificación envía `MODE off` y espera hasta 500 ms por `STATE off` antes de cerrar el servicio. Desconectar el dispositivo USB apaga el modo. Durante el apagado de la PC se rechazan nuevos pedidos y conexiones; el motor no admite inicios de streams después de `Quit`.

## PLAYER: PC → Móvil

Solo se acepta cuando la PC está en `pc2phone` y tiene un sink. Un nuevo PLAYER reemplaza al anterior. La PC envía formato y luego PCM sin cabeceras hasta cerrar el socket. En paralelo, el teléfono envía bloques de estado de 16 bytes; el primero no necesita esperar a que se complete un bloque PCM específico.

| Offset | Tamaño | Tipo | Estado del reproductor |
| --- | --- | --- | --- |
| 0 | 4 | `i32` LE | Frames pendientes: AudioTrack + bytes disponibles en el socket y frame parcial local, expresados como frames completos |
| 4 | 4 | `i32` LE | Frames objetivo de la cola del teléfono |
| 8 | 4 | `i32` LE | Contador de underruns de AudioTrack en esta sesión |
| 12 | 4 | `i32` LE | Latencia de salida estimada en frames; `-1` si no hay timestamp válido |

La última medida se calcula como `framesWritten - presented`, extrapolando `AudioTimestamp` hasta el instante actual. No incluye por sí sola la cola pendiente en el socket ni el cuántum de PipeWire. Los bloques de estado son consecutivos, sin magic ni longitud. Se envían cada ~10 ms **cuando el bucle receptor avanza**; no existe un heartbeat independiente si el audio deja de llegar.

```text
App / Player                        PC / Hub + PipeWire
    |<--- STATE pc2phone (CONTROL) ------|
    |---- nuevo TCP -------------------->|
    |---- hello binario PLAYER --------->|
    |<--- formato: 48000 / 2 / F32LE ----|
    |<=== PCM intercalado continuo =====|
    |---- estado de 16 bytes ----------->|  ~10 ms
    |<=== PCM ... =======================|  ambos flujos en paralelo
    |<--- cierre al apagar/reemplazar ---|
```

El sink virtual pide 256 frames (5,3 ms a 48 kHz), usa `node.always-process` y entrega los datos a un ring; otro hilo los manda por TCP. Android solicita AudioTrack de baja latencia y conserva el sufijo de cada escritura parcial hasta entregarlo completo. Su objetivo cubre el peor hueco reciente del túnel más un burst, con mínimo de dos bursts, una ventana de 30 s y límite de media capacidad de la pista. Sube enseguida, baja un burst cada 20 s y agrega un piso ante cortes que decae un burst cada 30 s. Ignora huecos/cortes iniciales para ese aprendizaje durante 700 ms tras iniciar la reproducción. El objetivo final se persiste en SharedPreferences por frecuencia de muestreo; un teléfono nuevo arranca con 30 ms.

El buffer de escritura se ajusta a objetivo + dos bursts, limitado por la capacidad de la pista. En Android 12+ se fija además el umbral de inicio en el objetivo, limitado por el tamaño efectivo devuelto por AudioTrack, para evitar esperar también el margen de escritura. En Android 8–11 se conserva el umbral del sistema. Los descensos del objetivo y del piso respetan sus límites incluso cuando el valor aprendido no es múltiplo de un burst.

Si la cola supera `objetivo + max(objetivo, 50 ms)`, programa el descarte de `cola - objetivo` frames entrantes. No vacía de inmediato AudioTrack: omite las siguientes muestras recibidas hasta consumir ese exceso.

## CAPTURE: Móvil → PC

La PC lanza por adb un proceso con uid de shell:

```text
CLASSPATH=<ruta del apk instalado> app_process / com.soundshare.capture.CaptureMain --port 47100
```

El capturador usa `ShellIdentity` para registrar una Application mínima atribuida a `com.android.shell` (con `AttributionSource` explícito desde API 31), abre TCP y envía hello, formato y PCM. La PC lee el formato y solo acepta la sesión en modo `phone2pc`; un nuevo CAPTURE reemplaza al anterior. No devuelve datos en una sesión CAPTURE válida. El capturador vigila el cierre del socket para detener AudioRecord. Las consultas adb tienen un timeout de 10 s y se hacen fuera del lock del Hub; el lanzamiento se revalida antes de crear el proceso.

```text
Capturador Android                   PC / Hub + PipeWire
    |                                |-- MODE phone2pc (app o terminal)
    |<--- lanzamiento por adb shell -|
    |---- TCP (por adb reverse) ------>|
    |---- hello binario CAPTURE ------>|
    |---- formato: 48000 / 2 / S16LE ->|
    |==== PCM intercalado continuo ===>|-- ring → stream de reproducción
    |<--- cierre del socket ----------|  al apagar/reemplazar
    |-- detiene y libera AudioRecord  |
```

REMOTE_SUBMIX captura la mezcla de medios y desvía su salida del parlante. Si Android exige una actividad visible para el shell, incluso si lanza una excepción al construir AudioRecord, el capturador abre `com.android.shell/.HeapDumpActivity` un instante y luego intenta cerrar sus tareas. Usa `am stack` en Android 11; si no encuentra tareas o falla, abre `com.soundshare/.MainActivity` para taparla, con `KEYCODE_HOME` como último recurso. Sus comandos leen la salida en paralelo y tienen un timeout de 5 s. La compatibilidad de captura en Android 12+ todavía necesita prueba real. Intenta elevar el volumen de medios del dispositivo virtual activo al máximo mediante `cmd media_session volume`; no transporta comandos de volumen por este protocolo.

La PC reproduce con el stream `SoundShare (Móvil)`. `-o/--output SALIDA` o `SOUNDSHARE_OUTPUT` eligen su `node.name`; sin ambos usa la salida por defecto, y una salida explícita no tiene fallback. `SOUNDSHARE_DUMP=archivo` vuelca el PCM recibido antes del resampler, sin cabecera ni modificación del protocolo.

El capturador espera un frame completo con `READ_BLOCKING` y drena los frames ya disponibles con `READ_NON_BLOCKING`, hasta 1024 frames por escritura TCP. No espera a completar ese máximo ni retiene el resto parcial de un bloque del submix hasta su siguiente entrega. Si la lectura no bloqueante falla después de leer el primer frame, envía lo leído y reporta el error en la siguiente iteración.

El objetivo promedio inicial del ring es 26 ms. Arranca cuando reúne objetivo + medio «serrucho» de los bloques recibidos: la amplitud se estima inicialmente en 21 ms, se mide en ventanas de 1 s, se suaviza y se acota a 5–60 ms. El arranque descarta el exceso hasta ese nivel y prepara el filtro del lazo en el objetivo con `RateLoop::prime()`. Cada underrun rellena con silencio, aumenta el objetivo 4 ms (máximo 120 ms) y espera volver a llenar. `SourceTarget` baja 4 ms hacia el mínimo de 26 ms tras 60 s de ciclos completos con una cola restante de al menos 4 ms más otro ciclo. Cuenta frames reproducidos según la frecuencia del formato; no cuenta el llenado. Los cortes, la falta de margen y los descartes por acumulación reinician el período estable. La recuperación reduce solo el objetivo: el PI drena el exceso gradualmente, sin saltar muestras. Si el ring supera objetivo + serrucho + 100 ms, descarta hasta el punto de arranque, vuelve a preparar el filtro y reinicia la espera de recuperación.

## Compatibilidad

- Versión actual: **1**. La PC exige igualdad exacta; no negocia versiones ni capacidades. Para un cambio incompatible, incrementá la versión y actualizá ambos extremos.
- Los emisores escriben los bytes reservados en cero. Los lectores actuales los ignoran; no los uses para extensiones sin definir su compatibilidad.
- No interpretes un rol, formato o versión desconocido como uno conocido. El hello inválido se rechaza; un formato inválido cierra la sesión de audio correspondiente.
- Conservá tamaños, endianness, orden de campos y delimitadores en una implementación de versión 1. Los campos de texto `INFO` y `ERROR` son para mostrar, no una interfaz estructurada de métricas.
- No hay marcador de fin PCM: EOF/cierre termina el flujo. El cambio de modo cierra los canales de audio anteriores; CONTROL puede seguir abierto.
- La aceptación de un formato por el parser no significa que la app lo pueda reproducir ni que un productor lo emita.

## Sincronización de relojes

Los cristales de la PC y del teléfono difieren aunque ambos declaren 48000 Hz. Sin corrección, una cola se vacía o crece continuamente. El objetivo es absorber jitter con una cola pequeña y corregir solo la deriva lenta del reloj.

`RateLoop` es un PI críticamente amortiguado con ancho de banda nominal de 0,01 Hz y desviación máxima ±1000 ppm. Suaviza el nivel con dos etapas en cascada de 1,5 s cada una (`LOOP_SMOOTHING_S = 3.0`) y limita el integrador. Nivel y objetivo se expresan en segundos; `dt` es el tiempo entre mediciones, limitado al rango 0,0001–0,5 s.

```text
k = dt / (1,5 + dt)
etapa_a += (nivel - etapa_a) * k
nivel_filtrado += (etapa_a - nivel_filtrado) * k
error = nivel_filtrado - objetivo
integral = limitar(integral + error * dt)
wn = 2π * 0,01
dev = limitar(2 * wn * error + wn² * integral, -0,001, +0,001)
rate_match = 1 / (1 + dev)
```

Sin preparación previa, las dos etapas se inicializan con la primera medición. `prime(objetivo)` las inicializa en el promedio esperado y limpia el integrador para no tomar un pico del serrucho como nivel inicial. `dev > 0` significa que sobra audio; `dev < 0`, que falta. Su aplicación depende del lado del stream:

| Sentido | Nivel medido | Si sobra audio (`dev > 0`) |
| --- | --- | --- |
| PC → Móvil | `(cola reportada por Android + frames del ring de la PC) / 48000`; objetivo reportado por Android | `rate_match < 1`: el resampler del stream de captura entrega menos frames por ciclo al túnel |
| Móvil → PC | Frames disponibles en el ring / frecuencia del formato; objetivo local de 26–120 ms | `rate_match < 1`: el resampler del stream de reproducción consume más frames del ring |

Si falta audio, la corrección se invierte. En PC → Móvil, un hilo lee el estado y publica `dev` mediante un valor atómico; el callback de tiempo real de PipeWire aplica la corrección. En Móvil → PC, el callback calcula y aplica el lazo usando la cantidad de frames solicitados para `dt`.

Se usa el área **`SPA_IO_RateMatch`**, obtenida en `io_changed` tras verificar puntero no nulo y tamaño suficiente: el callback escribe `rate` y activa `SPA_IO_RATE_MATCH_FLAG_ACTIVE`. Así el resampler adaptativo de PipeWire, con `resample.quality=10`, ajusta el ritmo en el ciclo de audio sin hacer remuestreo en el teléfono ni bloquear el hilo de tiempo real para operaciones de red. Si esa área todavía no está disponible, no se escribe la corrección. No cambia el formato declarado de 48 kHz ni se agrega un campo de reloj al protocolo.

`SOUNDSHARE_FORCE_RATE=1.01` reemplaza el factor aplicado por un valor fijo para diagnóstico. Los comentarios del motor registran que `1,01` produjo ~48480 frames/s del lado de captura y ~47520 frames/s del lado de reproducción; esos sentidos explican el factor inverso usado por el lazo. `SOUNDSHARE_DEBUG=1` agrega cuántum y máximos de ciclo/envío a las estadísticas de PC → Móvil.

## Mediciones reales

Datos aportados por el autor en un moto g play (2021), Android 11; no son garantías para otros equipos:

- Móvil → PC, tono de 440 Hz a −12 dBFS: PCM recibido del teléfono bit a bit, THD+N −84,5 dB (piso teórico de 16 bits: −85,8 dB). Tras el resampler adaptativo de la PC: THD+N −82,4 dB, nivel exacto y L=R. Afinación estable ±30 ppm, con variación medida de 0,014 % pico a pico; lo audible empieza ~0,05 %.
- PC → Móvil: 0 cortes en 90 s, latencia total ~55–60 ms y cola del teléfono ~25–30 ms, necesaria por el jitter del túnel adb con picos de ~20 ms.
- Móvil → PC: buffer de la PC ~26–30 ms y 0–1 cortes por minuto.
