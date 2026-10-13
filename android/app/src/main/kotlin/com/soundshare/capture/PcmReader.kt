package com.soundshare.capture

/** Espera un frame y drena lo disponible sin esperar a completar otro bloque. */
internal class PcmReader(
    private val frameBytes: Int,
    private val reader: Reader,
) {
    // Firma con primitivos para no boxear argumentos en cada lectura del hilo de audio.
    fun interface Reader {
        fun read(into: ByteArray, offset: Int, size: Int, blocking: Boolean): Int
    }

    private var pendingError = 0

    fun readInto(chunk: ByteArray): Int {
        if (pendingError < 0) return pendingError
        val first = reader.read(chunk, 0, frameBytes, true)
        if (first <= 0 || first == chunk.size) return first
        val extra = reader.read(chunk, first, chunk.size - first, false)
        // Si el grabador falla después del primer frame, entregamos ese frame antes del error.
        if (extra < 0) pendingError = extra
        return first + maxOf(extra, 0)
    }
}
