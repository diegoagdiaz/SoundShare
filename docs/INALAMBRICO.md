# SoundShare inalámbrico: WiFi y Bluetooth

Diseño y protocolo de la conexión sin cable. Complementa a [PROTOCOLO.md](PROTOCOLO.md): todo lo que no se menciona acá (hello, formato, CONTROL, estado de 16 bytes, PCM) es idéntico.

## Resumen

| Camino | Necesita | PC → Móvil | Móvil → PC | Estado |
| --- | --- | --- | --- | --- |
| USB (adb + AOA) | Depuración USB, cable | AOA / adb | Capturador shell (todo el audio, el teléfono se calla) | Existente |
| adb inalámbrico | Depuración inalámbrica (Android 11+) | adb por WiFi | Capturador shell | Fase 1 |
| WiFi nativo | Emparejar una vez por USB, misma red | TCP directo | Captura de la app (MediaProjection, Android 10+) | Fase 1 |
| Bluetooth | Emparejar por Bluetooth | — (ver abajo) | A2DP: la PC es un parlante | Ayudante `scripts/bluetooth.sh` |

La conexión por USB no cambia: `127.0.0.1:47100` por `adb reverse`, sin autenticación propia (la da adb).

## Fase 1a: adb inalámbrico

Un teléfono emparejado con **Depuración inalámbrica** aparece en `adb devices` con una serie de red (`192.168.0.20:37099` o `adb-<serie>-<id>._adb-tls-connect._tcp`). `adb reverse`, `adb shell` y el capturador funcionan igual que por cable; AOA no existe sin USB, así que PLAYER usa adb.

Selección de dispositivo en la PC sin `-s`:

- Si hay exactamente un dispositivo USB (`usb:` en `adb devices -l`), se usa ese y se ignoran los de red: es el mismo teléfono conectado de las dos formas o el cable es la elección explícita.
- Si no hay USB y hay exactamente uno de red, se usa ese.
- Si no, se pide `-s SERIE` como hasta ahora.

AOA sólo se negocia con dispositivos USB. Para un dispositivo de red no se busca la serie USB ni se espera la reenumeración.

## Fase 1b: WiFi nativo

### Puertos

Con el puerto base `P` (47100 por defecto, `-p`):

| Puerto | Escucha | Uso |
| --- | --- | --- |
| TCP 127.0.0.1:`P` | Loopback | USB por `adb reverse`, sin cambios, **sin autenticación** |
| TCP 0.0.0.0:`P+1` | Todas las interfaces | WiFi nativo; **toda conexión empieza con el preámbulo de autenticación** |
| UDP 0.0.0.0:47101 | Todas las interfaces | Descubrimiento, siempre en 47101 |

El teléfono siempre consulta el descubrimiento en UDP **47101**. La respuesta trae el puerto TCP real. `soundshare --no-wifi` o `SOUNDSHARE_WIFI=0` no abren los puertos de red.

### Identidades y emparejamiento

- **PC:** `pc_id` de 16 bytes aleatorios. Se guarda con los teléfonos emparejados en `$XDG_CONFIG_HOME/soundshare/wifi` (por defecto `~/.config/soundshare/wifi`), permisos 0600. El nombre mostrado es el hostname.
- **Teléfono:** `phone_id` de 16 bytes aleatorios, generado una vez y guardado en SharedPreferences `lan`. El teléfono guarda cada PC emparejada por `pc_id`: secreto, nombre, puerto y última dirección que respondió.
- En texto, los IDs van en hexadecimal minúscula (32 caracteres); el secreto, 32 bytes en hexadecimal minúscula (64 caracteres).

El emparejamiento ocurre **sólo por el CONTROL de loopback** (USB o adb inalámbrico), porque adb ya autenticó al teléfono:

| Dirección | Línea | Significado |
| --- | --- | --- |
| Teléfono → PC | `LAN_PAIR <phone_id>` | Después de `HELLO`, en cada CONTROL de loopback |
| PC → teléfono | `LAN_PAIRED <pc_id> <puerto_tcp> <secreto> <nombre>` | Nombre = resto de la línea, puede tener espacios |

La PC genera un secreto nuevo en cada `LAN_PAIR` (reemplaza al anterior de ese `phone_id`) y lo guarda antes de responder. Si los puertos de red están desactivados, no responde. Un `LAN_PAIR` recibido por una conexión WiFi se ignora. Los clientes y PCs anteriores ignoran estas líneas.

### Descubrimiento (UDP)

El teléfono envía un datagrama a `255.255.255.255:47101` y a la dirección de broadcast de la red WiFi:

```text
SOUNDSHARE? 1 <phone_id>
```

La PC responde **sólo si `phone_id` está emparejado**, por unicast a la dirección y puerto de origen:

```text
SOUNDSHARE 1 <pc_id> <puerto_tcp> <nombre>
```

ASCII/UTF-8 sin salto de línea, como máximo 512 bytes. Se ignoran datagramas con otro formato o versión. El teléfono reintenta cada 300 ms hasta 1,5 s y elige la PC emparejada que responda (si responden varias, la usada más recientemente). Antes de descubrir, prueba la última dirección conocida de esa PC directamente por TCP.

### Preámbulo de autenticación (TCP `P+1`)

Ocurre una vez por conexión TCP, antes del hello de 8 bytes. Todos los campos son bytes crudos.

1. Teléfono → PC, 40 bytes:

   | Offset | Tamaño | Campo |
   | --- | --- | --- |
   | 0 | 4 | ASCII `SSLN` (`53 53 4c 4e`) |
   | 4 | 1 | Versión: `1` |
   | 5 | 3 | Reservado, cero |
   | 8 | 16 | `phone_id` |
   | 24 | 16 | `phone_nonce` aleatorio |

2. PC → teléfono, 56 bytes:

   | Offset | Tamaño | Campo |
   | --- | --- | --- |
   | 0 | 4 | ASCII `SSLN` |
   | 4 | 1 | Versión: `1` |
   | 5 | 1 | Estado: `0` aceptado, `1` teléfono no emparejado, `2` versión no soportada |
   | 6 | 2 | Reservado, cero |
   | 8 | 16 | `pc_nonce` aleatorio (ceros si estado ≠ 0) |
   | 24 | 32 | `pc_proof` (ceros si estado ≠ 0) |

   Con estado ≠ 0 la PC cierra después de enviar los 56 bytes.

3. El teléfono verifica `pc_proof` en tiempo constante. Si no coincide, cierra sin enviar nada más: no manda audio ni pedidos a una PC que no conoce el secreto. Si coincide, envía `phone_proof` (32 bytes).

4. La PC verifica `phone_proof` en tiempo constante y cierra si no coincide. Si coincide, sigue el protocolo de siempre en el mismo socket: hello de 8 bytes y el rol (CONTROL, PLAYER o CAPTURE).

```text
pc_proof    = HMAC-SHA256(secreto, "SSLN-PC"    || phone_id || phone_nonce || pc_nonce)
phone_proof = HMAC-SHA256(secreto, "SSLN-PHONE" || phone_id || pc_nonce    || phone_nonce)
```

Las etiquetas son ASCII sin terminador. La PC aplica un timeout de 5 s desde que acepta hasta terminar el hello y admite como máximo 8 conexiones sin autenticar a la vez.

**Vector de prueba** (las dos implementaciones lo verifican en sus tests):

```text
secreto      = 000102…1f   (bytes 0x00..0x1f)
phone_id     = a0a1…af     (bytes 0xa0..0xaf)
phone_nonce  = b0b1…bf     (bytes 0xb0..0xbf)
pc_nonce     = c0c1…cf     (bytes 0xc0..0xcf)
pc_proof     = 559986fb2eef2a73102d6ddfa2ce35b0deaea6200fb9c475933a47924352ecd2
phone_proof  = 4c313817e3833ac17d867be912dfc370524369cab770fbe41d212a63a0c7122c
```

**Alcance de la seguridad:** autentica a los dos extremos y evita que otro equipo de la red cambie el modo, reciba el audio de la PC o le inyecte audio. **No cifra:** quien capture el tráfico de la red puede escuchar el PCM. Un proceso local de la PC puede pedir un secreto por loopback (como ya puede usar la conexión USB).

### CONTROL por WiFi

Mismas líneas que por USB, con estas diferencias:

- La PC **no** envía `PLAYER_TRANSPORT` ni intenta reabrir AOA para un CONTROL WiFi. El teléfono no envía `TRANSPORT` ni `LAN_PAIR` por WiFi.
- `PING`/`PONG` mide el RTT de la red WiFi, que es el mismo camino de PLAYER: la estimación usa RTT/2 como etapa de transporte.
- **Latido:** por WiFi el teléfono envía `PING` cada segundo también con el modo en `off`. La PC da por perdido un CONTROL WiFi tras 6 s sin recibir líneas, y el teléfono tras 5 s sin recibir nada (el `PONG` alcanza). Así un teléfono que sale de la red no deja una sesión colgada. Los sockets WiFi de la PC usan además keepalive TCP y `TCP_USER_TIMEOUT` de 6 s, para que PLAYER y CAPTURE también noten la caída.
- Con un CONTROL WiFi, la PC permite `MODE pc2phone` aunque no haya un dispositivo adb. Sin adb no abre la app: ya está conectada.

Nuevas líneas para Móvil → PC:

| Dirección | Línea | Significado |
| --- | --- | --- |
| PC → teléfono | `CAPTURE app` | Al entrar en `phone2pc` sin dispositivo adb: la app debe capturar y abrir CAPTURE por WiFi. Va a los CONTROL WiFi, antes de `STATE phone2pc` |
| Teléfono → PC | `CAPTURE_FAILED <motivo>` | La app no puede capturar (sin consentimiento, sin permiso, Android < 10). La PC lo muestra, difunde `ERROR <motivo>` y pasa a `STATE off` |
| Teléfono → PC | `CAPTURE_LATENCY <ms>` | Igual que `[captura] LATENCY` del capturador shell, cada ~250 ms, decimal con punto |

Si hay dispositivo adb, `phone2pc` usa el capturador shell como siempre (también por adb inalámbrico) y no se envía `CAPTURE app`.

### PLAYER por WiFi

El teléfono abre otra conexión TCP a la misma dirección y puerto que su CONTROL WiFi, con su propio preámbulo, y envía el hello PLAYER. Usa el mismo bloque de formato, PCM sin cabeceras y estado de 16 bytes cada ~10 ms que adb. Por WiFi la PC anuncia **48000 Hz, estéreo, S16LE** y convierte el sink float a enteros de 16 bits en el hilo de envío. El flujo usa 1,536 Mbit/s, la mitad que F32LE, para reducir la carga de la red. La PC lo etiqueta **WiFi**.

Ajustes para la red:

- Ambos extremos usan `TCP_NODELAY` y la prioridad de red normal. Para comparar otra clasificación en el extremo PC: `SOUNDSHARE_WIFI_TOS=184 ./soundshare` aplica DSCP EF (`0xB8`); el predeterminado es `0`. La respuesta del punto de acceso a DSCP depende de su configuración y no se presupone que EF seleccione una cola de voz.
- El teléfono mantiene un bloqueo WiFi de alto rendimiento hasta Android 13 y de baja latencia en Android 14+. Se libera al desconectar CONTROL. Android 14 convierte el bloqueo de alto rendimiento en uno de baja latencia; mantener ambos no agrega un fallback. Ver [WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_HIGH_PERF). El ahorro de energía del WiFi puede agregar huecos de 100 ms o más.
- La pista reserva 500 ms de capacidad (objetivo máximo 250 ms) en vez de 200 ms.
- Los objetivos aprendidos se guardan aparte: `target_ms_wifi_48000` y `target_ms_min_wifi_48000`. Un teléfono nuevo arranca con 60 ms en estable y 30 ms en mínima.
- Las políticas de cola (Estable/Mínima), el arranque en silencio y el lazo de reloj de la PC son los mismos.

### CAPTURE por WiFi (Móvil → PC desde la app)

Sin adb no hay capturador shell. La app captura con `AudioPlaybackCaptureConfiguration` (Android 10+), que exige:

- Permiso `RECORD_AUDIO` y consentimiento de MediaProjection (diálogo del sistema).
- Un servicio en primer plano de tipo `mediaProjection` iniciado **antes** de `getMediaProjection()`.

Se capturan los usos `MEDIA`, `GAME` y `UNKNOWN`, en 48000 Hz, estéreo, S16LE, igual que el capturador shell, y se envían por una conexión CAPTURE WiFi con preámbulo. La app conserva la MediaProjection mientras el servicio vive y Android no la revoque, así el consentimiento no se repite en cada cambio de modo. Si la app no está visible al recibir `CAPTURE app`, muestra una notificación que abre el diálogo. Cuando Android detiene la proyección, la captura se detiene y hay que autorizarla otra vez al reiniciar Móvil → PC.

Diferencias con el capturador shell, a documentar para el usuario:

- **El teléfono sigue sonando**: la captura duplica el audio, no lo desvía.
- No se capturan apps que lo prohíben (`allowAudioPlaybackCapture=false`, contenido protegido, llamadas).
- Android muestra el indicador de captura de pantalla mientras dura la proyección.
- Android 15 QPR1+ detiene MediaProjection al bloquear la pantalla. Para continuar hay que desbloquear, elegir Móvil → PC y autorizar de nuevo. Ver [MediaProjection: parada automática](https://developer.android.com/media/grow/media-projection#status_bar_chip_and_auto_stop).

### Selección del enlace en el teléfono

Preferencia `link` en SharedPreferences `player`: `auto` (predeterminado), `usb` o `wifi`.

- `auto`: intenta `127.0.0.1:47100`. Si se rechaza o no hay respuesta de la PC y hay alguna PC emparejada, intenta WiFi. Se queda en el enlace que funcionó hasta que se corta.
- `usb`: sólo loopback, como hasta ahora.
- `wifi`: sólo WiFi nativo.

Cambiar la preferencia cierra CONTROL y reconecta. La interfaz muestra el enlace actual (USB / WiFi · nombre de la PC) y, sin PC emparejada, explica que hay que conectar el cable una vez.

## Bluetooth

### Móvil → PC: la PC como parlante Bluetooth (A2DP)

PipeWire/WirePlumber pueden publicar el rol `a2dp_sink`: emparejado el teléfono con la PC, Android la ve como un parlante y le manda el audio multimedia, sin depuración USB ni MediaProjection. El ayudante incluido consulta BlueZ y conecta sólo ese perfil de un teléfono ya emparejado:

```bash
./soundshare bluetooth status
./soundshare bluetooth list
./soundshare bluetooth connect AA:BB:CC:DD:EE:FF
./soundshare bluetooth disconnect AA:BB:CC:DD:EE:FF
```

Emparejá primero desde la configuración Bluetooth de Linux y Android, confirmando el mismo código en ambos. Usá `list` para obtener la MAC del teléfono, ejecutá `connect` y elegí la PC como salida multimedia en Android. No hace falta abrir la app Android de SoundShare. Detené el modo WiFi/USB antes de reproducir por Bluetooth para evitar dos caminos de audio a la vez.

Desde SoundShare 0.4.2, Android muestra una tarjeta **Bluetooth · Móvil → PC** debajo de **Conexión**, incluso sin conexión USB/WiFi. **Usar Bluetooth** guarda la selección y detiene el audio y CONTROL nativos. Si había CONTROL, primero entrega `MODE off` y espera hasta 500 ms por `STATE off`, para detener también la captura shell que podría sobrevivir al cierre del socket. Un cambio posterior de elección invalida el cierre pendiente de esa generación.

Mientras Bluetooth está seleccionado, no se intenta conectar por adb ni WiFi y no se inicia PLAYER/CAPTURE ni AOA. La interfaz muestra **Bluetooth seleccionado**, el nombre del destino conectado y **Audio por Bluetooth** cuando A2DP informa reproducción. Consulta `BluetoothA2dp.getConnectedDevices()` e `isA2dpPlaying()` mediante un proxy público que libera al cambiar de enlace o cerrar el servicio. Android 12+ requiere permiso `BLUETOOTH_CONNECT`; no se necesita permiso de escaneo ni ubicación. Sin permiso o sin perfil de audio conectado, se muestra esa situación y no se afirma una conexión.

**Ajustes Bluetooth** abre la selección del sistema. PC → Móvil queda deshabilitado en esta ruta; Móvil → PC usa el audio de las apps del teléfono, administrado por Android. Elegir Automática, USB o WiFi reanuda el transporte nativo. Ver [la API pública A2DP](https://developer.android.com/reference/android/bluetooth/BluetoothA2dp) y [permisos Bluetooth](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

El comando `soundshare bluetooth` ejecuta el ayudante incluido; `scripts/bluetooth.sh` sigue disponible directamente. El launcher del repositorio y el AppImage lo abren sin iniciar el servidor de audio ni necesitar adb.

El ayudante usa `Device1.ConnectProfile` con el UUID remoto de Audio Source `0000110a-0000-1000-8000-00805f9b34fb`. No escanea ni configura el emparejamiento. La salida la administra WirePlumber: con `bluez5.media-source-role=playback` (predeterminado), el audio va a los parlantes; cambiá la salida desde el gestor de sonido. Una configuración `input` requiere encaminar esa entrada manualmente. El argumento `-o` de SoundShare no interviene en este camino.

La latencia y el reloj los maneja PipeWire y dependen del teléfono, del códec y de la red Bluetooth. Se verificó reproducción con el Moto emparejado: al reproducir un tono, BlueZ pasó el transporte A2DP/SBC a `active`, PipeWire abrió `bluez_input.*` y el tono llegó al monitor de los parlantes sin streams de audio SoundShare. No se midió la latencia acústica de Bluetooth.

`status` y `list` distinguen el enlace Bluetooth de los transportes `MediaTransport1` y muestran su estado (`idle`, `pending`, `active`), perfil y códec. Un enlace conectado o un transporte adquirido no garantizan por sí solos que el volumen/salida elegidos sean audibles; el estado se informa sin simular reproducción. Ver [la API multimedia de BlueZ](https://bluez.readthedocs.io/en/latest/media-api/).

Referencias: [API de dispositivos BlueZ](https://bluez.readthedocs.io/en/latest/device-api/), [propiedades Bluetooth de PipeWire](https://docs.pipewire.org/page_man_pipewire-props_7.html), [configuración Bluetooth de WirePlumber](https://pipewire.pages.freedesktop.org/wireplumber/daemon/configuration/bluetooth.html).

### PC → Móvil por Bluetooth

Esta adaptación implementa Bluetooth sólo para Móvil → PC. Para PC → Móvil queda por desarrollar un transporte propio y comprobar su caudal en ambos dispositivos. PCM estéreo a 48 kHz consume 3,07 Mbit/s en float o 1,54 Mbit/s en S16, sin contar el protocolo; todavía no se midió un socket Bluetooth propio. Se evaluaría compresión, como Opus, si el enlace no ofrece caudal suficiente.
