package com.soundshare

/** Valores CONTROL y decisión pura; la preferencia nunca se reemplaza por el fallback de la PC. */
enum class TransportChoice(val wire: String) {
    AOA("aoa"), ADB("adb");
    companion object { fun parse(value: String) = entries.firstOrNull { it.wire == value } }
}

enum class TransportReason(val wire: String) {
    SELECTED("selected"), RECONNECT("reconnect"), UNAVAILABLE("unavailable"), DISCONNECTED("disconnected");
    companion object { fun parse(value: String) = entries.firstOrNull { it.wire == value } }
}

data class TransportCandidate(val choice: TransportChoice, val reason: TransportReason? = null) {
    companion object {
        fun parse(value: String): TransportCandidate? {
            val parts = value.split(' ')
            if (parts.size !in 1..2) return null
            val choice = TransportChoice.parse(parts[0]) ?: return null
            val reason = if (parts.size == 2) TransportReason.parse(parts[1]) ?: return null else null
            if (choice == TransportChoice.AOA && reason != null) return null
            return TransportCandidate(choice, reason)
        }
    }
}

enum class AccessoryState { UNAVAILABLE, PERMISSION_REQUIRED, PERMISSION_DENIED, READY, FAILED, DISCONNECTED }

object TransportSelection {
    enum class Hint { WAITING, IDLE, CONNECTING, ACTIVE, SELECTED_ADB, PERMISSION_REQUIRED, PERMISSION_DENIED, UNAVAILABLE, FAILED, DISCONNECTED, RECONNECT }

    fun useAoa(preference: TransportChoice, candidate: TransportCandidate) =
        preference == TransportChoice.AOA && candidate.choice == TransportChoice.AOA

    fun hint(preference: TransportChoice, candidate: TransportCandidate, accessory: AccessoryState,
             connected: Boolean, playing: Boolean, actual: String?): Hint {
        if (!connected) return Hint.WAITING
        if (preference == TransportChoice.ADB) return Hint.SELECTED_ADB
        if (actual == "aoa" && playing) return Hint.ACTIVE
        if (candidate.choice == TransportChoice.ADB) return when (candidate.reason) {
            TransportReason.RECONNECT -> Hint.RECONNECT
            TransportReason.DISCONNECTED -> Hint.DISCONNECTED
            else -> Hint.UNAVAILABLE
        }
        return when (accessory) {
            AccessoryState.PERMISSION_REQUIRED -> Hint.PERMISSION_REQUIRED
            AccessoryState.PERMISSION_DENIED -> Hint.PERMISSION_DENIED
            AccessoryState.FAILED -> Hint.FAILED
            AccessoryState.UNAVAILABLE -> Hint.UNAVAILABLE
            AccessoryState.DISCONNECTED -> Hint.DISCONNECTED
            AccessoryState.READY -> if (playing) Hint.CONNECTING else Hint.IDLE
        }
    }
}
