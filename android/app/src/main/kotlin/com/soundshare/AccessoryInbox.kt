package com.soundshare

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bytes de una sola sesión: cierre inmediato y cola acotada sin bloquear el lector USB. */
class AccessoryInbox(capacity: Int = 64) : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>(capacity)
    private val pending = AtomicInteger()
    @Volatile private var ended = false
    @Volatile var peerEnded = false
        private set
    val isEnded get() = ended
    private var bytes = ByteArray(0)
    private var offset = 0

    @Synchronized fun receive(payload: ByteArray) {
        if (ended) return
        pending.addAndGet(payload.size)
        if (!queue.offer(payload)) { pending.addAndGet(-payload.size); finish() }
    }
    @Synchronized fun finish(peer: Boolean = false): Boolean {
        peerEnded = peerEnded || peer
        if (ended) return false
        ended = true
        queue.clear()
        pending.set(0)
        bytes = ByteArray(0)
        offset = 0
        queue.offer(ByteArray(0))
        return true
    }
    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b) == -1) -1 else b[0].toInt() and 255
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val next = synchronized(this) {
            if (ended) return -1
            offset == bytes.size
        }
        // No tomar el monitor mientras se espera: finish() debe despertar al lector.
        val payload = if (next) queue.poll(3, TimeUnit.SECONDS) else null
        synchronized(this) {
            if (ended) return -1
            if (next) {
                bytes = payload ?: throw IOException("sin PCM/formato AOA durante 3 s")
                offset = 0
            }
            if (bytes.isEmpty()) return -1
            val n = minOf(len, bytes.size-offset)
            System.arraycopy(bytes, offset, b, off, n)
            offset += n
            pending.addAndGet(-n)
            return n
        }
    }
    override fun available() = if (ended) 0 else pending.get().coerceAtLeast(0)
}
