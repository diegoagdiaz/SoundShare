package com.soundshare

import java.io.Closeable

/** Cancelación irreversible por hilo; el cierre nunca busca recursos de otro arranque. */
internal class PlayerGeneration {
    @Volatile var active = true
        private set
    private var resource: Closeable? = null
    private var playback: Closeable? = null

    @Synchronized fun <T> ifActive(action: () -> T): T? = if (active) action() else null

    fun attach(value: Closeable): Boolean {
        synchronized(this) {
            if (active) { resource = value; return true }
        }
        runCatching { value.close() }
        return false
    }

    @Synchronized fun detach(value: Closeable) {
        if (resource === value) resource = null
    }

    @Synchronized fun attachPlayback(value: Closeable): Boolean {
        if (active) { playback = value; return true }
        runCatching { value.close() }
        return false
    }

    @Synchronized fun detachPlayback(value: Closeable) {
        if (playback === value) playback = null
    }

    fun cancel() {
        val owned = synchronized(this) {
            active = false
            // Las escrituras no bloqueantes y play() usan este mismo monitor: al volver de cancel,
            // ninguna pista vieja puede sonar mientras arranca la nueva.
            runCatching { playback?.close() }
            playback = null
            resource.also { resource = null }
        }
        runCatching { owned?.close() }
    }
}
