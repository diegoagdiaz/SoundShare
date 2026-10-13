package com.soundshare

/** El estado de audio procede de A2DP, nunca de una conexión Bluetooth genérica o del emparejamiento. */
data class BluetoothStatus(val phase: Phase, val destination: String = "") {
    enum class Phase { PERMISSION_REQUIRED, UNAVAILABLE, DISABLED, CHECKING, DISCONNECTED, CONNECTED, PLAYING }
    val connected get() = phase == Phase.CONNECTED || phase == Phase.PLAYING
    val playing get() = phase == Phase.PLAYING

    data class Destination(val name: String, val playing: Boolean)
    companion object {
        fun fromA2dp(devices: List<Destination>): BluetoothStatus {
            if (devices.isEmpty()) return BluetoothStatus(Phase.DISCONNECTED)
            val playing = devices.filter { it.playing }
            val destinations = playing.ifEmpty { devices }.map { it.name }.distinct().joinToString(", ")
            return BluetoothStatus(if (playing.isEmpty()) Phase.CONNECTED else Phase.PLAYING, destinations)
        }
    }
}
