package com.soundshare

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Envoltura AOA v1: cabecera de 12 bytes, payload par y padding cero → paquete corto. */
object AccessoryFrame {
    const val HELLO = 1
    const val FORMAT = 2
    const val PCM = 3
    const val STATUS = 4
    const val END = 5
    const val MAX_PCM = 8192
    data class Frame(val kind: Int, val session: Int, val payload: ByteArray)
    private fun valid(kind: Int, size: Int) = when (kind) {
        HELLO -> size == 8
        FORMAT -> size == 12
        PCM -> size in 8..MAX_PCM && size % 8 == 0
        STATUS -> size == 16
        END -> size == 0
        else -> false
    }
    fun encode(kind: Int, session: Int, payload: ByteArray): ByteArray {
        if (session == 0 || !valid(kind, payload.size)) throw IOException("bloque AOA inválido")
        return ByteBuffer.allocate(13 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(83, 83, 65, 79)).put(1).put(kind.toByte())
            .putShort(payload.size.toShort()).putInt(session).put(payload).put(0).array()
    }
    fun read(input: InputStream): Frame {
        val data = DataInputStream(input)
        val header = ByteArray(12)
        data.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        val version = b.get().toInt()
        val kind = b.get().toInt()
        val size = b.short.toInt() and 0xffff
        val session = b.int
        if (!magic.contentEquals(byteArrayOf(83,83,65,79)) || version != 1 || session == 0 || !valid(kind,size))
            throw IOException("cabecera AOA inválida")
        val payload = ByteArray(size).also { data.readFully(it) }
        if (data.readUnsignedByte() != 0) throw IOException("padding AOA inválido")
        return Frame(kind,session,payload)
    }
}
