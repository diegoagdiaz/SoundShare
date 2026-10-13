# Protocolo SoundShare, versión 1

Esta especificación describe `pc/src/protocol.rs`, `hub.rs`, `engine.rs` y las implementaciones Android de `Protocol`, `LinkService`, `Player` y `CaptureMain`.

## Transporte y convenciones

La PC escucha TCP en loopback. El puerto predeterminado es **47100**. El teléfono siempre inicia las conexiones hacia su propio `127.0.0.1:47100`; `adb reverse tcp:47100 tcp:47100` las lleva por USB hasta la PC. No hay conexiones TCP iniciadas por la PC hacia el teléfono. La PC negocia el accesorio USB antes de configurar adb reverse. CONTROL y CAPTURE tienen su propio socket. PLAYER usa AOA cuando está disponible, con fallback al socket original.

La PC acepta otro puerto con `-p` y lo pasa al capturador y al túnel, pero `LinkService` y `Player` usan la constante 47100. No hay descubrimiento ni negociación de puerto en la app actual.

Los offsets son en bytes, desde cero al comienzo de cada bloque. Todos los enteros multibyte y muestras son **little-endian**. Un frame contiene una muestra por canal; estéreo se intercala izquierda, derecha. TCP es un flujo: una lectura puede contener un bloque parcial, varios bloques o un frame partido. Hay que juntar los bytes necesarios y conservar los sobrantes; los límites de `write` no son delimitadores.

No hay compresión, cifrado propio, autenticación propia ni timestamps de audio. TCP conserva PCM sin cabeceras por bloque; AOA agrega una envoltura de transporte descrita abajo. La autorización de depuración USB pertenece a adb; el descriptor AOA requiere además el permiso de UsbManager. El formato se fija una vez por conexión de audio; cambiarlo requiere otro socket.

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
| Teléfono → PC | `TRANSPORT aoa` / `TRANSPORT adb` | Preferencia PLAYER; respuesta al solicitante, sin cambiar el modo |
| PC → teléfono | `PLAYER_TRANSPORT aoa` / `PLAYER_TRANSPORT adb [motivo]` | Candidato PLAYER y motivo opcional del fallback |
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

### Telemetría opcional de latencia

El protocolo 1 conserva el hello, formato, PCM y estado PLAYER originales. Agrega líneas opcionales de CONTROL; los clientes anteriores las ignoran:

- `PING <token>`: Android envía su tiempo monótono en ns justo al escribir la línea, hasta una vez por segundo mientras hay audio.
- `PONG <token>`: la PC responde exclusivamente al cliente que mandó ese token. Android acepta solo la respuesta pendiente y estima USB como la mitad del round trip; no compara relojes de dispositivos diferentes.
- `LATENCY <modo> <cola_pc_ms> <pipewire_ms> <captura_android_ms>`: la PC informa valores decimales con punto cada segundo. `-1` significa desconocido. `pipewire_ms` incluye el tiempo hasta el dispositivo/grafo y el buffer del resampler; en PC → Móvil se usa al menos el cuántum observado. El tiempo de captura Android solo corresponde a Móvil → PC.

La PC lanza el capturador con `--latency`. Cada 250 ms este informa por stdout `[captura] LATENCY <ms>`, calculado con `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` y la posición del punto medio del bloque leído. No mezcla esta telemetría con el PCM. El Hub acepta esos datos solo del proceso de captura vigente y vence la medición tras 3 s.

La app muestra una estimación por etapas. En PC → Móvil suma cola/grafo PC + tiempo de AudioTrack hasta salida + frames pendientes en el socket + USB aproximado. AudioTrack ya incluye su propia cola, que no se suma dos veces. Si no hay timestamp de salida, usa la cola como parte conocida y marca la salida desconocida. En Móvil → PC suma captura Android + promedio del ring PC + resampler/grafo/salida PC + USB aproximado. Los datos CONTROL/PONG vencen en 3 s; los del reproductor en 1,5 s. Cambiar de modo o desconectarse limpia la telemetría de la sesión anterior. No se deriva ninguna cifra del texto `INFO`.

Esta estimación no es una medición acústica de extremo a extremo ni incluye los buffers de la aplicación original. La simetría del USB y los timestamps/reportes de los dispositivos tienen límites. Las etapas sin datos se identifican explícitamente como una estimación parcial, sin completar con constantes supuestas.

## PLAYER: PC → Móvil

Solo se acepta cuando la PC está en `pc2phone` y tiene un sink. Un nuevo PLAYER reemplaza al anterior. La PC envía formato y luego PCM; en TCP no hay cabeceras de PCM y el cierre termina la sesión. En AOA cada mensaje va en la envoltura de transporte y END termina la sesión lógica. En paralelo, el teléfono envía bloques de estado de 16 bytes; el primero no necesita esperar a que se complete un bloque PCM específico.

| Offset | Tamaño | Tipo | Estado del reproductor |
| --- | --- | --- | --- |
| 0 | 4 | `i32` LE | Frames pendientes: AudioTrack + bytes disponibles en el transporte y frame parcial local, expresados como frames completos |
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

El sink virtual pide 256 frames (5,3 ms a 48 kHz), usa `node.always-process` y entrega los datos a un ring; otro hilo los manda por el transporte elegido. Android solicita AudioTrack de baja latencia y conserva el sufijo de cada escritura parcial hasta entregarlo completo. Su objetivo cubre el peor hueco reciente del túnel más un burst, con mínimo de dos bursts, una ventana de 30 s y límite de media capacidad de la pista. Sube enseguida, baja un burst cada 20 s y agrega un piso ante cortes que decae un burst cada 30 s. Ignora huecos/cortes iniciales para ese aprendizaje durante 700 ms tras iniciar la reproducción. El objetivo final se persiste en SharedPreferences por frecuencia de muestreo y transporte (adb conserva su clave anterior); un teléfono nuevo arranca con 30 ms.

El buffer de escritura se ajusta a objetivo + dos bursts, limitado por la capacidad de la pista. En Android 12+ se fija además el umbral de inicio en el objetivo, limitado por el tamaño efectivo devuelto por AudioTrack, para evitar esperar también el margen de escritura. En Android 8–11 se conserva el umbral del sistema. Los descensos del objetivo y del piso respetan sus límites incluso cuando el valor aprendido no es múltiplo de un burst.

Si la cola supera `objetivo + max(objetivo, 50 ms)`, programa el descarte de `cola - objetivo` frames entrantes. No vacía de inmediato AudioTrack: omite las siguientes muestras recibidas hasta consumir ese exceso.

### Transporte AOA v1 (solo PLAYER)

El handshake físico sigue [AOA](https://source.android.com/docs/core/interaction/accessories/aoa): GET_PROTOCOL 51 (IN vendor, LE16), SEND_STRING 52 (OUT vendor, índice 0–5, UTF-8 con NUL) y START 53. Cadenas: manufacturer `SoundShare`, model `SoundShare`, description `Audio PC a Android`, version `1`, URI del repositorio y serial de adb. No se habilita AOA Audio: PCM continúa en nuestro PLAYER. Tras START se espera la misma serie USB en **18d1:2d01**, se reclama exclusivamente la interfaz ff/ff/00 del accesorio y se descubren sus endpoints bulk; la interfaz ff/42/01 de adb queda libre. No se repite SET_INTERFACE para el alt 0 ya activo: al reiniciar el hub con el fd Android abierto se debe conservar la lectura pendiente. Si sólo aparece 2d00, se rechaza como transporte: hace falta adb para CONTROL y CAPTURE.

El Hub informa `PLAYER_TRANSPORT aoa` o `PLAYER_TRANSPORT adb [motivo]` al nuevo CONTROL **antes de STATE**. Es un candidato: Android usa adb si no puede abrir AOA o si falta permiso. La app guarda en SharedPreferences una preferencia `aoa` (predeterminada) o `adb`, y al conectar y al tocar el selector manda `TRANSPORT aoa` o `TRANSPORT adb`. La respuesta va sólo al solicitante; no altera el modo, el sink ni CAPTURE. Un argumento de transporte desconocido se ignora.

`TRANSPORT adb` responde `PLAYER_TRANSPORT adb selected`: el teléfono reinicia únicamente PLAYER y abre su socket aunque el accesorio siga vivo. `TRANSPORT aoa` intenta reabrir la interfaz si el Link murió o falta; antes libera el handle anterior, incluso si el lector o sesiones conservan referencias. Sólo reabre USB ya en **18d1:2d01**, sin volver a seleccionar alt 0. Si está disponible responde `PLAYER_TRANSPORT aoa`; si falla responde `PLAYER_TRANSPORT adb unavailable`, `adb disconnected` (sin dispositivo vigente) o `adb reconnect` (el USB no está en modo accesorio). La app usa el candidato junto con su preferencia; una respuesta de fallback no cambia la preferencia guardada.

La selección AOA prepara/pide permiso Android. Hay un pedido automático por conexión del cable; un toque explícito sobre AOA puede volver a pedirlo tras un rechazo y reintentar un descriptor fallido. Al concederlo se reinicia sólo PLAYER. El transporte mostrado proviene de `Player.Stats.transportName`, no del candidato del Hub, y el motivo de fallback combina CONTROL y el estado local USB. Detener/reiniciar o cambiar de transporte cancela irreversiblemente la generación anterior, pausa su pista y cierra únicamente sus recursos; un lector tardío no puede revivir ni publicar sobre el arranque nuevo.

**CONTROL nunca hace START/conmutación completa**: reenumerar corta adb, puede apagar PC → Móvil al perder CONTROL y puede interrumpir CAPTURE. Para `reconnect`, la app indica «Reconectá el cable y arrancá la PC con AOA habilitado». Si se arrancó con `SOUNDSHARE_PLAYER_TRANSPORT=adb`, primero hay que reiniciar el Hub sin esa variable (o con `aoa`) y reconectar; la conmutación se hace en el flujo inicial previo a reverse/CONTROL. Elegir adb en la app no cambia el modo físico del USB.

Clientes anteriores ignoran `TRANSPORT`/`PLAYER_TRANSPORT` desconocidos y siguen por adb; la app acepta anuncios antiguos sin motivo. No se cambia la versión del protocolo interno 1 ni se agregan campos en sus bytes reservados.

Cada mensaje AOA en ambas direcciones contiene:

| Offset | Tamaño | Campo |
| --- | --- | --- |
| 0 | 4 | ASCII `SSAO` (`53 53 41 4f`) |
| 4 | 1 | Versión de envoltura: `1` |
| 5 | 1 | Tipo: 1 HELLO, 2 FORMAT, 3 PCM, 4 STATUS, 5 END |
| 6 | 2 | Longitud del payload, u16 LE |
| 8 | 4 | ID de sesión, u32 LE distinto de cero |
| 12 | longitud | Payload original |
| 12 + longitud | 1 | Padding obligatorio cero |

HELLO lleva los mismos 8 bytes `SNDS`/1/PLAYER, de Android a PC. FORMAT lleva los mismos 12 bytes, de PC a Android. PCM lleva 8–8192 bytes (frames completos F32LE estéreo), de PC a Android. STATUS lleva los mismos 16 bytes, de Android a PC, cada ~10 ms cuando avanza el receptor. END no lleva payload y puede enviarse en ambos sentidos. Las longitudes de esos tipos son estrictas; se rechazan magic/versión/tipo/longitud/padding inválidos y EOF parcial. Los lectores reconstruyen mensajes partidos y varios mensajes juntos; no usan los límites de una lectura como framing.

**Paquetes cortos:** todos los payloads son pares, por lo que el tamaño completo `12 + payload + 1` es impar. La PC manda un mensaje completo en una transferencia bulk OUT; nunca termina en un múltiplo del wMaxPacketSize bulk USB (64/512/1024). Para 256 frames son **2061 bytes**. Se eligió cabecera más padding, sin ZLP, para que `f_accessory` entregue cada bloque sin esperar llenar sus requests de 16 KB. Una escritura parcial de libusb invalida el enlace: no se reintenta el sufijo de un bloque con delimitación ambigua.

Android siempre lee el fd con un buffer de **16384 bytes**, aunque el parser pida uno o doce. Un hilo daemon con prioridad de audio mantiene ese lector durante toda la conexión; separa los payloads en una cola acotada de la sesión activa. `available()` cuenta sólo payload pendiente, sin cabeceras ni padding. La PC tiene una lectura bulk IN persistente con timeout de 200 ms y una cola acotada de estados. Se ignoran mensajes de una sesión anterior.

Detener, cambiar de modo o reemplazar PLAYER envía END y despierta el lector lógico. La próxima sesión envía un nuevo HELLO con otro ID; conserva el accesorio abierto. Cerrar el servicio/desconectar termina las colas y cierra el descriptor en un hilo daemon, sin join del lector físico: algunas versiones no desbloquean `read()` al cerrar el fd. Las escrituras de END de Android también son daemon. La PC limita escrituras a 500 ms y el estado a 3 s; Android limita la espera lógica de formato/PCM a 3 s. Fallos de apertura, framing o transferencia inhiben el accesorio hasta reconectar o tocar explícitamente AOA y permiten reintentar PLAYER por adb. Rechazar el permiso no repite el diálogo en cada intento.

El resampler y RateLoop no cambian: el estado AOA pasa por el mismo lazo del Hub. Se conservan dos bursts mínimos y una política adaptativa común. AOA tiene su propio objetivo persistido, de modo que no hereda el jitter de adb.

El PING/PONG de CONTROL **sigue midiendo adb**. La app usa **0 ms aproximados** para esa etapa USB cuando PLAYER usa AOA; no presenta la mitad del RTT de adb como una medición del accesorio. Ese cero no mide propagación física ni constituye una medición acústica. El hub conserva su aproximación cuántum + máximo de cola/salida Android; no mide propagación USB ni latencia acústica.

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

REMOTE_SUBMIX captura la mezcla de medios y desvía su salida del parlante. Android 11 puede exigir que el UID del shell tenga una actividad visible para iniciar AudioRecord. Si falla el intento directo, el capturador crea una pantalla virtual privada con superficie local, sin espejo de la pantalla física, y lanza `com.android.shell/.HeapDumpActivity` exclusivamente allí, con una tarea nueva. Una vez iniciado AudioRecord, libera esa pantalla, su actividad y la superficie; el mismo cierre se ejecuta si falla el arranque. No usa `am stack remove`, no cierra tareas ajenas ni abre ventanas en la pantalla principal como alternativa. Sus comandos leen la salida en paralelo y tienen un timeout de 5 s. Si el sistema no permite el arranque en pantalla secundaria, reporta el error sin recurrir a una ventana física. Android 12+ conserva el arranque directo; `ShellIdentity` mantiene la atribución de API 31+ y el dispositivo real de API 34+, verificado previamente en Android 16. Intenta elevar el volumen de medios del dispositivo virtual activo al máximo mediante `cmd media_session volume`; no transporta comandos de volumen por este protocolo.

La PC reproduce con el stream `SoundShare (Móvil)`. `-o/--output SALIDA` o `SOUNDSHARE_OUTPUT` eligen su `node.name`; sin ambos usa la salida por defecto, y una salida explícita no tiene fallback. `SOUNDSHARE_DUMP=archivo` vuelca el PCM recibido antes del resampler, sin cabecera ni modificación del protocolo.

El capturador espera un frame completo con `READ_BLOCKING` y drena los frames ya disponibles con `READ_NON_BLOCKING`, hasta 1024 frames por escritura TCP. No espera a completar ese máximo ni retiene el resto parcial de un bloque del submix hasta su siguiente entrega. Si la lectura no bloqueante falla después de leer el primer frame, envía lo leído y reporta el error en la siguiente iteración.

El objetivo promedio inicial del ring es 26 ms. Arranca cuando reúne objetivo + medio «serrucho» de los bloques recibidos: la amplitud se estima inicialmente en 21 ms, se mide en ventanas de 1 s, se suaviza y se acota a 5–60 ms. El arranque descarta el exceso hasta ese nivel y prepara el filtro del lazo en el objetivo con `RateLoop::prime()`. Cada underrun rellena con silencio, aumenta el objetivo 4 ms (máximo 120 ms) y espera volver a llenar. `SourceTarget` baja 4 ms hacia el mínimo de 26 ms tras 60 s de ciclos completos con una cola restante de al menos 4 ms más otro ciclo. Cuenta frames reproducidos según la frecuencia del formato; no cuenta el llenado. Los cortes, la falta de margen y los descartes por acumulación reinician el período estable. La recuperación reduce solo el objetivo: el PI drena el exceso gradualmente, sin saltar muestras. Si el ring supera objetivo + serrucho + 100 ms, descarta hasta el punto de arranque, vuelve a preparar el filtro y reinicia la espera de recuperación.

## Compatibilidad

- Versión actual: **1**. La PC exige igualdad exacta; no negocia versiones ni capacidades. Para un cambio incompatible, incrementá la versión y actualizá ambos extremos.
- Los emisores escriben los bytes reservados en cero. Los lectores actuales los ignoran; no los uses para extensiones sin definir su compatibilidad.
- No interpretes un rol, formato o versión desconocido como uno conocido. El hello inválido se rechaza; un formato inválido cierra la sesión de audio correspondiente.
- Conservá tamaños, endianness, orden de campos y delimitadores en una implementación de versión 1. Los campos de texto `INFO` y `ERROR` son para mostrar, no una interfaz estructurada de métricas.
- En TCP no hay marcador de fin PCM: EOF/cierre termina el flujo. AOA usa END en la envoltura. El cambio de modo cierra los canales de audio anteriores; CONTROL puede seguir abierto.
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
