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

    fun receive(payload: ByteArray) {
        if (ended) return
        pending.addAndGet(payload.size)
        if (!queue.offer(payload)) { pending.addAndGet(-payload.size); finish() }
    }
    fun finish(peer: Boolean = false) {
        peerEnded = peerEnded || peer
        ended = true
        queue.offer(ByteArray(0))
    }
    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b) == -1) -1 else b[0].toInt() and 255
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (ended) return -1
        if (offset == bytes.size) {
            bytes = queue.poll(3, TimeUnit.SECONDS) ?: throw IOException("sin PCM/formato AOA durante 3 s")
            offset = 0
        }
        if (ended || bytes.isEmpty()) return -1
        val n = minOf(len, bytes.size-offset)
        System.arraycopy(bytes, offset, b, off, n)
        offset += n
        pending.addAndGet(-n)
        return n
    }
    override fun available() = if (ended) 0 else pending.get().coerceAtLeast(0)
}
