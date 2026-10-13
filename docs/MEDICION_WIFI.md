# Verificación WiFi de SoundShare 0.4.0

Pruebas locales del 13 de octubre de 2026 con Linux/PipeWire y un Motorola moto g play (2021), Android 11, conectado por WiFi de 2,4 GHz. La instancia de PC usó `ADB=/usr/bin/false`: los canales de audio y CONTROL se conectaron directamente por WiFi. USB se usó para instalar la APK y consultar registros.

## Funcionamiento

- El teléfono descubre la PC emparejada, autentica CONTROL y conecta PLAYER/CAPTURE.
- `soundshare pc2phone` inicia el modo al conectar CONTROL WiFi, sin un dispositivo adb disponible.
- PC → Móvil anuncia 48000 Hz, estéreo, S16LE. Se envió un tono suave de 440 Hz desde PipeWire al sink `soundshare_to_phone`; Android mantiene la pista y devuelve estados de cola.
- Móvil → PC solicita el consentimiento de Android y captura el audio de Chrome. Se reprodujo un WAV de 440 Hz y se registró el monitor de la salida de la PC a 48000 Hz, estéreo. El registro contiene el tono recibido.
- La instalación de la APK detiene el servicio anterior y la app vuelve a autenticar al abrirse. Los tests cubren además el orden `CAPTURE app`/`STATE` al reemplazar CONTROL.

## Latencia y cortes

Las cifras son estimaciones de telemetría, no mediciones acústicas. Se usó el perfil Estable; para comparar S16 con distintos bloqueos se reiniciaron sólo los objetivos aprendidos de WiFi a los valores iniciales. Los datos de emparejamiento y los objetivos USB se conservaron.

Con F32 y DSCP EF, una prueba de 60 s dio una mediana de 317 ms, máximo de 490 ms y 9 underruns. Cerrar la vista previa del dispositivo no eliminó el problema. El socket mostró retransmisiones y una cola de envío de 93 KB: el envío desde PipeWire completaba los ciclos en unos 3 ms, pero el TCP acumulaba audio pendiente.

La versión final usa S16 (1,536 Mbit/s) y prioridad normal. Con el bloqueo HIGH_PERF del Moto, una prueba de 60 s dio una mediana de 360 ms, máximo de 410 ms y 9 underruns durante el aprendizaje; no hubo nuevos underruns en los últimos 30 s. El objetivo llegó al máximo de 250 ms. Reducir el caudal y cambiar la prioridad no eliminó el retardo de esta red.

Esta verificación confirma el transporte, pero **PC → Móvil todavía necesita mejorar la latencia y los cortes en este equipo/red**. No permite prometer continuidad ni latencia baja en otras redes. Falta comparar en 5 GHz con otro teléfono y evaluar un transporte con plazos de entrega para evitar esperar retransmisiones de audio antiguo.

## Compilación y Bluetooth

Pasaron 56 tests release de Rust, clippy con `-D warnings`, 68 tests Android, `assembleDebug` y `lintDebug` sin errores. El paquete local incluye la APK 0.4.0/code 7, el ejecutable release, los documentos y `scripts/bluetooth.sh`.

La PC anuncia Audio Sink A2DP. En la primera prueba WiFi todavía no había emparejamiento Bluetooth; PC → Móvil por Bluetooth sigue pendiente.

### Comprobación Bluetooth posterior

El Moto se emparejó con `fedora`. Con el modo nativo de SoundShare detenido, Chrome reprodujo el mismo tono nominal de 440 Hz. BlueZ cambió `MediaTransport1.State` de `idle` a `active`, con perfil Audio Sink y códec SBC; PipeWire abrió `bluez_input.30_83_D2_DE_1C_40.2` como `Stream/Output/Audio` en ejecución, sin nodos de audio SoundShare.

El monitor de los parlantes registró el tono: una ventana de un segundo mostró una componente dominante próxima a 439 Hz y amplitud 0,00992, con RMS 0,00706. No se determinó la causa de la diferencia respecto del tono nominal ni se midió latencia acústica. Al terminar el tono, el transporte volvió a `idle` y conservó el emparejamiento y la conexión.

En la APK 0.4.2/code 9, la interfaz mostró **Bluetooth conectado · fedora** en reposo y **Audio por Bluetooth · fedora** durante la reproducción. También se probó cambiar desde USB con Móvil → PC activo: desaparecieron el proceso `CaptureMain`, el nodo `soundshare_from_phone` y el socket CONTROL, mientras se conservó la conexión A2DP. Cerrar y abrir la app conservó la selección Bluetooth. Pasaron 73 tests Android, `assembleDebug` y `lintDebug` sin errores, además de los 56 tests release y clippy de Rust.
