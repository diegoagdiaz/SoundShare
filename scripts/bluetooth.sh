#!/usr/bin/env bash
set -euo pipefail

# A2DP: el teléfono ofrece Audio Source; la PC recibe como Audio Sink.
# ConnectProfile recibe el UUID del servicio remoto, no el de la PC.
audio_source='0000110a-0000-1000-8000-00805f9b34fb'
audio_sink='0000110b-0000-1000-8000-00805f9b34fb'
bus_options=(--system --no-pager --auto-start=no --allow-interactive-authorization=no --timeout=20)

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Uso: scripts/bluetooth.sh [status | list | connect MAC | disconnect MAC]
  status          Muestra los adaptadores Bluetooth y el estado de PipeWire.
  list            Lista dispositivos emparejados y sus transportes de audio.
  connect MAC     Conecta el perfil de audio Móvil → PC de ese teléfono.
  disconnect MAC Desconecta solo ese perfil de audio; conserva el emparejamiento.
  -h, --help      Muestra esta ayuda. Sin argumentos: status.

Antes del primer uso, emparejá el teléfono desde la configuración Bluetooth
de Linux y Android, confirmando el código en ambos. Luego ejecutá list para
obtener la MAC y connect MAC. En Android, habilitá «Audio multimedia» para la
PC y elegila como salida de audio; reproducí música en el teléfono.
Un enlace Bluetooth conectado no confirma reproducción de audio: status y
list muestran el transporte como idle (sin transmisión), pending (aún no
adquirido) o active (transmisión adquirida). Revisá también la salida de la PC.

Requiere Linux con BlueZ, busctl, PipeWire, WirePlumber y soporte Bluetooth
de PipeWire. Usa la salida que el sistema asigne al teléfono: normalmente
los parlantes predeterminados. Cambiala desde la configuración de sonido.
Si configuraste bluez5.media-source-role=input, el teléfono será una entrada
y deberás dirigirla a una salida desde tu gestor de audio.

Bluetooth A2DP cubre Móvil → PC. Para PC → Móvil, usá WiFi o USB en SoundShare.
No necesita la app Android de SoundShare ni Depuración USB para A2DP.
No escanea, empareja, modifica servicios ni configura dispositivos.
EOF
}

pairing_help() {
    printf 'Emparejá primero el teléfono desde la configuración Bluetooth de Linux y Android; confirmá el mismo código en ambos. Después usá scripts/bluetooth.sh list.\n' >&2
}

property() {
    busctl "${bus_options[@]}" get-property org.bluez "$1" "$2" "$3"
}

yes_no() {
    [[ "$1" == 'b true' ]] && printf 'sí' || printf 'no'
}

has_receive_transport() {
    local device="$1" connected path owner uuid
    connected="$(property "$device" org.bluez.Device1 Connected 2>/dev/null)" || return 1
    [[ "$connected" == 'b true' ]] || return 1
    while IFS= read -r path; do
        [[ "$path" == "$device/"* ]] || continue
        owner="$(property "$path" org.bluez.MediaTransport1 Device 2>/dev/null)" || continue
        [[ "$owner" == "o \"$device\"" ]] || continue
        uuid="$(property "$path" org.bluez.MediaTransport1 UUID 2>/dev/null)" || continue
        [[ "$uuid" == "s \"$audio_sink\"" ]] && return 0
    done <<< "$object_paths"
    return 1
}

show_transports() {
    local device="$1" path owner state uuid codec profile codec_name found=false
    # El transporte puede estar en /sepX/fdX: no suponemos una ruta /fdX fija.
    # Device confirma que pertenece al teléfono y adaptador que mostramos.
    while IFS= read -r path; do
        [[ "$path" == "$device/"* ]] || continue
        owner="$(property "$path" org.bluez.MediaTransport1 Device 2>/dev/null)" || continue
        [[ "$owner" == "o \"$device\"" ]] || continue
        state="$(property "$path" org.bluez.MediaTransport1 State 2>/dev/null)" || state=''
        uuid="$(property "$path" org.bluez.MediaTransport1 UUID 2>/dev/null)" || uuid=''
        codec="$(property "$path" org.bluez.MediaTransport1 Codec 2>/dev/null)" || codec=''
        case "$uuid" in
            "s \"$audio_sink\"") profile='A2DP Audio Sink (PC recibe)' ;;
            "s \"$audio_source\"") profile='A2DP Audio Source (PC envía)' ;;
            '') profile='no disponible' ;;
            *) profile="${uuid#s }" ;;
        esac
        codec_name="${codec#y }"
        if [[ "$uuid" == "s \"$audio_sink\"" || "$uuid" == "s \"$audio_source\"" ]]; then
            case "$codec" in
                'y 0') codec_name='SBC (0)' ;;
                'y 1') codec_name='MPEG-1/2 Audio (1)' ;;
                'y 2') codec_name='AAC (2)' ;;
                'y 255') codec_name='específico del fabricante (255)' ;;
            esac
        fi
        case "$state" in
            's "idle"') state='idle (sin transmisión de audio)' ;;
            's "pending"') state='pending (transmisión todavía no adquirida)' ;;
            's "active"') state='active (transmisión adquirida; revisá la salida de la PC)' ;;
            '') state='no disponible' ;;
            *) state="${state#s }" ;;
        esac
        printf '    Transporte de audio: %s; perfil: %s; códec: %s\n' \
            "$state" "$profile" "${codec_name:-no disponible}"
        found=true
    done <<< "$object_paths"
    if ! $found; then
        printf '    Audio: sin transporte multimedia observable. El enlace Bluetooth no confirma reproducción.\n'
    fi
}

list_devices() {
    local path paired alias connected uuids address found=false
    printf 'Dispositivos emparejados (Audio Source indica compatibilidad Móvil → PC):\n'
    while IFS= read -r path; do
        [[ "$path" =~ ^/org/bluez/hci[0-9]+/dev_([[:xdigit:]_]+)$ ]] || continue
        paired="$(property "$path" org.bluez.Device1 Paired)" || continue
        [[ "$paired" == 'b true' ]] || continue
        alias="$(property "$path" org.bluez.Device1 Alias)" || continue
        connected="$(property "$path" org.bluez.Device1 Connected)" || continue
        uuids="$(property "$path" org.bluez.Device1 UUIDs)" || uuids=''
        address="${path##*/dev_}"
        address="${address//_/:}"
        printf '  %s  %s [%s] — enlace Bluetooth: %s; Audio Source: %s\n' \
            "$address" "${alias#s }" "${path%/dev_*}" "$(yes_no "$connected")" \
            "$(if [[ "$uuids" == *"$audio_source"* ]]; then printf 'sí'; else printf 'no'; fi)"
        show_transports "$path"
        found=true
    done <<< "$object_paths"
    if ! $found; then
        printf '  No hay dispositivos emparejados.\n'
        pairing_help
    fi
}

show_status() {
    local path alias powered uuids found=false
    printf 'Adaptadores Bluetooth:\n'
    while IFS= read -r path; do
        [[ "$path" =~ ^/org/bluez/hci[0-9]+$ ]] || continue
        alias="$(property "$path" org.bluez.Adapter1 Alias)" || continue
        powered="$(property "$path" org.bluez.Adapter1 Powered)" || continue
        uuids="$(property "$path" org.bluez.Adapter1 UUIDs)" || uuids=''
        printf '  %s %s — encendido: %s; Audio Sink: %s\n' \
            "$path" "${alias#s }" "$(yes_no "$powered")" \
            "$(if [[ "$uuids" == *"$audio_sink"* ]]; then printf 'sí'; else printf 'no'; fi)"
        found=true
    done <<< "$object_paths"
    $found || printf '  No hay adaptadores disponibles.\n'
    printf '\n'
    list_devices
    printf '\nAudio de la PC:\n'
    if command -v wpctl >/dev/null 2>&1; then
        wpctl status -n || printf 'No pude consultar PipeWire. Revisá el audio de tu sesión de escritorio.\n' >&2
    else
        printf 'wpctl no está disponible; revisá PipeWire y WirePlumber desde la configuración de sonido.\n'
    fi
}

command_name="${1:-status}"
case "$command_name" in
    -h|--help) [[ $# == 1 ]] || fail 'La ayuda no acepta argumentos adicionales.'; usage; exit 0 ;;
    status|list) [[ $# -le 1 ]] || fail 'status y list no aceptan argumentos adicionales.' ;;
    connect|disconnect)
        [[ $# == 2 ]] || fail "Uso: scripts/bluetooth.sh $command_name MAC"
        [[ "$2" =~ ^([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}$ ]] || fail 'La MAC debe tener el formato AA:BB:CC:DD:EE:FF.'
        address="${2^^}"
        ;;
    *) fail "Comando desconocido: $command_name. Usá --help para ver la ayuda." ;;
esac

[[ "$(uname -s)" == Linux ]] || fail 'Este ayudante Bluetooth requiere Linux.'
command -v busctl >/dev/null 2>&1 || fail 'Falta busctl (systemd). Instalalo desde el gestor de paquetes de tu distribución.'
object_paths="$(busctl "${bus_options[@]}" --list tree org.bluez 2>/dev/null)" \
    || fail 'No pude consultar BlueZ. Revisá que Bluetooth esté disponible en la configuración de Linux.'

case "$command_name" in
    status) show_status; exit 0 ;;
    list) list_devices; exit 0 ;;
esac

# Buscamos en todos los adaptadores y elegimos únicamente un registro emparejado.
device_path=''
while IFS= read -r path; do
    [[ "$path" =~ ^/org/bluez/hci[0-9]+/dev_[[:xdigit:]_]+$ ]] || continue
    [[ "${path##*/dev_}" == "${address//:/_}" ]] || continue
    paired="$(property "$path" org.bluez.Device1 Paired)" || continue
    [[ "$paired" == 'b true' ]] || continue
    [[ -z "$device_path" ]] || fail 'La MAC está emparejada en varios adaptadores. Conectá el audio desde la configuración Bluetooth de Linux para elegir el adaptador.'
    device_path="$path"
done <<< "$object_paths"
if [[ -z "$device_path" ]]; then
    pairing_help
    fail "$address no está emparejado con esta PC."
fi

if [[ "$command_name" == connect ]]; then
    uuids="$(property "$device_path" org.bluez.Device1 UUIDs)" || fail 'No pude consultar los perfiles del teléfono.'
    [[ "$uuids" == *"$audio_source"* ]] || fail 'Este dispositivo no anuncia Audio Source (A2DP). Elegí un teléfono compatible y habilitá Audio multimedia para la PC en Android.'
    adapter_path="${device_path%/dev_*}"
    powered="$(property "$adapter_path" org.bluez.Adapter1 Powered)" || fail 'No pude consultar el adaptador Bluetooth.'
    [[ "$powered" == 'b true' ]] || fail 'El adaptador está apagado. Activá Bluetooth desde la configuración de Linux.'
    uuids="$(property "$adapter_path" org.bluez.Adapter1 UUIDs)" || fail 'No pude consultar los perfiles de la PC.'
    [[ "$uuids" == *"$audio_sink"* ]] || fail 'La PC no anuncia Audio Sink. Revisá que PipeWire tenga soporte Bluetooth y WirePlumber esté activo en tu sesión.'
    if has_receive_transport "$device_path"; then
        printf 'El perfil A2DP de recepción de %s ya está abierto.\n' "$address"
    else
        printf 'Conectando el audio Móvil → PC de %s…\n' "$address"
        if reply="$(busctl "${bus_options[@]}" call org.bluez "$device_path" org.bluez.Device1 ConnectProfile s "$audio_source" 2>&1)"; then
            printf 'BlueZ aceptó la conexión del perfil A2DP.\n'
        elif [[ "${reply,,}" == *alreadyconnected* || "${reply,,}" == *'already connected'* ]] \
                && object_paths="$(busctl "${bus_options[@]}" --list tree org.bluez 2>/dev/null)" \
                && has_receive_transport "$device_path"; then
            # El perfil pudo abrirse entre la consulta inicial y ConnectProfile.
            # AlreadyConnected solo es éxito si observamos el transporte receptor.
            printf 'El perfil A2DP de recepción de %s ya está abierto.\n' "$address"
        else
            fail "No pude conectar A2DP: $reply. Revisá Bluetooth y Audio multimedia en el teléfono."
        fi
    fi
    printf 'Estado observado ahora:\n'
    if object_paths="$(busctl "${bus_options[@]}" --list tree org.bluez 2>/dev/null)"; then
        connected="$(property "$device_path" org.bluez.Device1 Connected 2>/dev/null)" || connected=''
        if [[ -n "$connected" ]]; then
            printf '  Enlace Bluetooth: %s (no confirma reproducción).\n' "$(yes_no "$connected")"
        else
            printf '  No pude consultar el enlace Bluetooth.\n'
        fi
        show_transports "$device_path"
    else
        printf '  No pude consultar los transportes de audio. Usá status para volver a comprobarlos.\n'
    fi
    printf 'Elegí esta PC como salida multimedia en Android y reproducí audio; después usá status para comprobar el transporte.\nLa salida de la PC la administra WirePlumber; ajustala desde la configuración de sonido.\n'
else
    printf 'Desconectando el perfil de audio Móvil → PC de %s…\n' "$address"
    if ! reply="$(busctl "${bus_options[@]}" call org.bluez "$device_path" org.bluez.Device1 DisconnectProfile s "$audio_source" 2>&1)"; then
        fail "No pude desconectar A2DP: $reply"
    fi
    printf 'Perfil A2DP desconectado; el emparejamiento se conserva.\n'
fi
