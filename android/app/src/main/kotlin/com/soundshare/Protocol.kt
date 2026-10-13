package com.soundshare

import java.io.DataInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocolo entre el teléfono y la PC (ver docs/PROTOCOLO.md).
 *
 * CONTROL y CAPTURE abren las conexiones hacia 127.0.0.1:[PORT]; `adb reverse`
 * las lleva por el cable USB hasta el servidor de la PC. PLAYER también admite AOA.
 */
object Protocol {
    const val PORT = 47100
    const val VERSION: Byte = 1

    const val ROLE_CONTROL: Byte = 1
    const val ROLE_PLAYER: Byte = 2
    const val ROLE_CAPTURE: Byte = 3

    const val FORMAT_S16LE: Byte = 1
    const val FORMAT_F32LE: Byte = 2

    const val STATUS_BYTES = 16
    private const val MAX_LINE_BYTES = 4096

    /** El límite incluye LF y se mide en bytes, igual que del lado de la PC. */
    fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8").removeSuffix("\r")
            if (bytes.size() >= MAX_LINE_BYTES) {
                throw IOException("línea de control demasiado larga")
            }
            if (b == 10) return bytes.toString("UTF-8").removeSuffix("\r")
            bytes.write(b)
        }
    }

    private val MAGIC = byteArrayOf('S'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte())

    fun writeHello(out: OutputStream, role: Byte) {
        out.write(MAGIC + byteArrayOf(VERSION, role, 0, 0))
        out.flush()
    }

    data class Format(val sampleRate: Int, val channels: Int, val sampleFormat: Byte) {
        val bytesPerSample get() = if (sampleFormat == FORMAT_F32LE) 4 else 2
        val frameBytes get() = channels * bytesPerSample
    }

    fun writeFormat(out: OutputStream, format: Format) {
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(format.sampleRate)
        b.put(format.channels.toByte())
        b.put(format.sampleFormat)
        out.write(b.array())
        out.flush()
    }

    fun readFormat(input: InputStream): Format {
        val raw = ByteArray(12)
        DataInputStream(input).readFully(raw)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val rate = b.int
        val channels = b.get().toInt() and 0xff
        val sampleFormat = b.get()
        require(rate in 8000..384000 && channels in 1..8) { "formato inválido: $rate Hz, $channels canales" }
        require(sampleFormat == FORMAT_S16LE || sampleFormat == FORMAT_F32LE) { "muestra desconocida: $sampleFormat" }
        return Format(rate, channels, sampleFormat)
    }

    /** Estado del reproductor que la PC usa para ajustar su reloj al del teléfono. */
    fun encodeStatus(into: ByteBuffer, queuedFrames: Int, targetFrames: Int, underruns: Int, outputLatencyFrames: Int) {
        into.clear()
        into.order(ByteOrder.LITTLE_ENDIAN)
        into.putInt(queuedFrames)
        into.putInt(targetFrames)
        into.putInt(underruns)
        into.putInt(outputLatencyFrames)
        into.flip()
    }
}

enum class Mode(val wire: String) {
    OFF("off"),
    PC_TO_PHONE("pc2phone"),
    PHONE_TO_PC("phone2pc");

    companion object {
        fun parse(s: String): Mode? = entries.firstOrNull { it.wire == s.trim() }
    }
}
