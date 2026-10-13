package com.soundshare

/** Una petición automática por cable, sin comparar UsbAccessory ni leer su serie sin permiso. */
internal class AccessoryPermissionRequests {
    private var requested = false

    fun request(explicit: Boolean = false): Boolean {
        if (requested && !explicit) return false
        requested = true
        return true
    }

    fun disconnected() { requested = false }
}
