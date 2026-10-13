# SoundShare inalámbrico: WiFi y Bluetooth

Diseño y protocolo de la conexión sin cable. Complementa a [PROTOCOLO.md](PROTOCOLO.md): todo lo que no se menciona acá (hello, formato, CONTROL, estado de 16 bytes, PCM) es idéntico.

## Resumen

| Camino | Necesita | PC → Móvil | Móvil → PC | Estado |
| --- | --- | --- | --- | --- |
| USB (adb + AOA) | Depuración USB, cable | AOA / adb | Capturador shell (todo el audio, el teléfono se calla) | Existente |
| adb inalámbrico | Depuración inalámbrica (Android 11+) | adb por WiFi | Capturador shell | Fase 1 |
| WiFi nativo | Emparejar una vez por USB, misma red | TCP directo | Captura de la app (MediaProjection, Android 10+) | Fase 1 |
| Bluetooth | Emparejar por Bluetooth | — (ver abajo) | A2DP: la PC es un parlante | Fase 2 |

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
| UDP 0.0.0.0:`P+1` | Todas las interfaces | Descubrimiento |

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
- Con un CONTROL WiFi, la PC permite `MODE pc2phone` aunque no haya un dispositivo adb. Sin adb no abre la app: ya está conectada.

Nuevas líneas para Móvil → PC:

| Dirección | Línea | Significado |
| --- | --- | --- |
| PC → teléfono | `CAPTURE app` | Al entrar en `phone2pc` sin dispositivo adb: la app debe capturar y abrir CAPTURE por WiFi. Va a los CONTROL WiFi, antes de `STATE phone2pc` |
| Teléfono → PC | `CAPTURE_FAILED <motivo>` | La app no puede capturar (sin consentimiento, sin permiso, Android < 10). La PC lo muestra, difunde `ERROR <motivo>` y pasa a `STATE off` |
| Teléfono → PC | `CAPTURE_LATENCY <ms>` | Igual que `[captura] LATENCY` del capturador shell, cada ~250 ms, decimal con punto |

Si hay dispositivo adb, `phone2pc` usa el capturador shell como siempre (también por adb inalámbrico) y no se envía `CAPTURE app`.

### PLAYER por WiFi

El teléfono abre otra conexión TCP a la misma dirección y puerto que su CONTROL WiFi, con su propio preámbulo, y envía el hello PLAYER. El resto es igual que PLAYER por adb: formato, PCM sin cabeceras y estado de 16 bytes cada ~10 ms. La PC lo etiqueta **WiFi**.

Ajustes para la red:

- Ambos extremos usan `TCP_NODELAY` y marcan los sockets WiFi con DSCP EF (`IP_TOS` / `trafficClass` = `0xB8`) para que los puntos de acceso con WMM usen la cola de voz.
- El teléfono mantiene un `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY` en Android 10+, `WIFI_MODE_FULL_HIGH_PERF` antes) mientras su CONTROL va por WiFi: el ahorro de energía del WiFi agrega huecos de 100 ms o más.
- La pista reserva 500 ms de capacidad (objetivo máximo 250 ms) en vez de 200 ms.
- Los objetivos aprendidos se guardan aparte: `target_ms_wifi_48000` y `target_ms_min_wifi_48000`. Un teléfono nuevo arranca con 60 ms en estable y 30 ms en mínima.
- Las políticas de cola (Estable/Mínima), el arranque en silencio y el lazo de reloj de la PC son los mismos.

### CAPTURE por WiFi (Móvil → PC desde la app)

Sin adb no hay capturador shell. La app captura con `AudioPlaybackCaptureConfiguration` (Android 10+), que exige:

- Permiso `RECORD_AUDIO` y consentimiento de MediaProjection (diálogo del sistema).
- Un servicio en primer plano de tipo `mediaProjection` iniciado **antes** de `getMediaProjection()`.

Se capturan los usos `MEDIA`, `GAME` y `UNKNOWN`, en 48000 Hz, estéreo, S16LE, igual que el capturador shell, y se envían por una conexión CAPTURE WiFi con preámbulo. La app conserva la MediaProjection mientras el servicio vive, así el consentimiento se pide una vez por sesión de la app, no en cada cambio de modo. Si la app no está visible al recibir `CAPTURE app`, muestra una notificación que abre el diálogo.

Diferencias con el capturador shell, a documentar para el usuario:

- **El teléfono sigue sonando**: la captura duplica el audio, no lo desvía.
- No se capturan apps que lo prohíben (`allowAudioPlaybackCapture=false`, contenido protegido, llamadas).
- Android muestra el indicador de captura de pantalla mientras dura la proyección.

### Selección del enlace en el teléfono

Preferencia `link` en SharedPreferences `player`: `auto` (predeterminado), `usb` o `wifi`.

- `auto`: intenta `127.0.0.1:47100`. Si se rechaza o no hay respuesta de la PC y hay alguna PC emparejada, intenta WiFi. Se queda en el enlace que funcionó hasta que se corta.
- `usb`: sólo loopback, como hasta ahora.
- `wifi`: sólo WiFi nativo.

Cambiar la preferencia cierra CONTROL y reconecta. La interfaz muestra el enlace actual (USB / WiFi · nombre de la PC) y, sin PC emparejada, explica que hay que conectar el cable una vez.

## Fase 2: Bluetooth

### Móvil → PC: la PC como parlante Bluetooth (A2DP)

PipeWire/WirePlumber ya publican el rol `a2dp_sink`: emparejado el teléfono con la PC, Android la ve como un parlante y le manda **todo** el audio, sin depuración USB ni MediaProjection. Lo que agrega SoundShare:

- Detectar el adaptador (BlueZ por D-Bus) y, a pedido, hacerlo visible y emparejable un tiempo limitado.
- Iniciar la conexión A2DP desde la PC hacia el teléfono emparejado (`Device1.ConnectProfile` con el UUID de Audio Source `0000110a-…`), porque una app Android no puede conectarse a un perfil A2DP sin APIs de sistema.
- Dirigir el nodo `bluez_input.*` a la salida elegida con `-o`.

Latencia esperable: 100–200 ms con SBC/AAC, según teléfono y códec. No hay lazo de reloj propio: lo maneja PipeWire.

### PC → Móvil por Bluetooth

Android no ofrece el rol A2DP sink en teléfonos, así que no hay camino estándar. La alternativa es un socket propio (RFCOMM o L2CAP CoC), pero su caudal real (~1–2 Mbit/s compartidos) no alcanza para el PCM float actual (3,07 Mbit/s) y queda justo para S16 (1,54 Mbit/s). Requiere comprimir (Opus) en la PC y decodificar en Android. Se evalúa después de la Fase 1.
