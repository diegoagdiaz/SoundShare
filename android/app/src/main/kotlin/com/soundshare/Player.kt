package com.soundshare

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PC → Móvil: recibe PCM por el túnel de adb y lo reproduce con la menor latencia que da el teléfono.
 *
 * El teléfono no remuestrea nada. Cada 10 ms le informa a la PC cuántos frames tiene encolados
 * y la PC ajusta finamente su propio ritmo (resampler adaptativo de PipeWire) para que la cola
 * se quede en [target]. Así los dos relojes quedan enganchados sin cortes ni deriva.
 */
class Player(private val context: Context, private val onStats: (Stats) -> Unit) {

    data class Stats(
        val sampleRate: Int,
        val targetFrames: Int,
        val queuedFrames: Int,
        val outputLatencyFrames: Int,
        val underruns: Int,
        val fastTrack: Boolean,
    ) {
        private fun ms(frames: Int) = frames * 1000f / sampleRate
        val targetMs get() = ms(targetFrames)
        val queuedMs get() = ms(queuedFrames)
        val outputLatencyMs get() = ms(outputLatencyFrames)
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "ss-player").also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.join(1500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            try {
                session()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "conexión de audio cortada: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "error en el reproductor", e)
            }
            if (running) SystemClock.sleep(300)
        }
    }

    private fun session() {
        val s = Socket()
        socket = s
        s.use {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", Protocol.PORT), 2000)
            val input = s.getInputStream()
            val out = s.getOutputStream()
            Protocol.writeHello(out, Protocol.ROLE_PLAYER)
            val fmt = Protocol.readFormat(input)
            play(fmt, s, input, out)
        }
    }

    private fun play(fmt: Protocol.Format, s: Socket, input: java.io.InputStream, out: java.io.OutputStream) {
        val am = context.getSystemService(AudioManager::class.java)
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        val frameBytes = fmt.frameBytes

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(
                        if (fmt.sampleFormat == Protocol.FORMAT_F32LE) AudioFormat.ENCODING_PCM_FLOAT
                        else AudioFormat.ENCODING_PCM_16BIT,
                    )
                    .setSampleRate(fmt.sampleRate)
                    .setChannelMask(if (fmt.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            // Capacidad holgada (200 ms); la latencia real la fija bufferSizeInFrames.
            .setBufferSizeInBytes(fmt.sampleRate / 5 * frameBytes)
            .build()

        val capacity = track.bufferCapacityInFrames
        val fast = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        // Arrancamos donde terminó la última sesión: el jitter del USB depende del teléfono y la PC.
        val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
        val learnedKey = "target_ms_${fmt.sampleRate}"
        val initialMs = prefs.getFloat(learnedKey, INITIAL_TARGET_MS)
        val target = QueueTarget(burst, fmt.sampleRate, capacity / 2, (initialMs * fmt.sampleRate / 1000).toInt())
        fun applyTarget() {
            // El umbral de escritura queda por encima del objetivo: write() casi nunca bloquea.
            val bufferFrames = track.setBufferSizeInFrames((target.frames + 2 * burst).coerceAtMost(capacity))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Arrancar al reunir el objetivo, sin esperar también el margen de escritura.
                val actualBufferFrames = if (bufferFrames > 0) bufferFrames else track.bufferSizeInFrames
                track.setStartThresholdInFrames(minOf(target.frames, actualBufferFrames).coerceAtLeast(1))
            }
        }
        applyTarget()
        Log.i(TAG, "pista: ${fmt.sampleRate} Hz, burst $burst, capacidad $capacity, rápida=$fast, objetivo ${target.frames}")

        val buf = ByteArray(frameBytes * 1024)
        val direct = ByteBuffer.allocateDirect(buf.size).order(ByteOrder.nativeOrder())
        val status = ByteBuffer.allocate(Protocol.STATUS_BYTES)
        val ts = AudioTimestamp()
        var fill = 0
        var framesWritten = 0L
        var headTotal = 0L
        var lastHead = 0L
        var dropFrames = 0L
        var started = false
        var lastUnderruns = 0
        var playStart = 0L
        var lastStatus = 0L
        var lastUi = 0L
        var lastRead = SystemClock.elapsedRealtimeNanos()
        var writeNs = 0L
        // Diagnóstico (logcat): huecos entre lecturas del socket y cola mínima por segundo.
        var maxGap = 0L
        var minQueued = Int.MAX_VALUE
        var lastDiag = lastRead

        try {
            while (running) {
                val n = input.read(buf, fill, buf.size - fill)
                if (n < 0) throw EOFException("la PC cerró el audio")
                val arrived = SystemClock.elapsedRealtimeNanos()
                // Tiempo sin datos, sin contar lo que tardamos nosotros en escribir.
                val gap = arrived - lastRead - writeNs
                lastRead = arrived
                if (started && arrived - playStart > SETTLE_NS && target.onGap(gap, arrived)) applyTarget()
                if (DIAG) maxGap = maxOf(maxGap, gap)
                fill += n
                val whole = fill - fill % frameBytes
                var off = 0

                if (dropFrames > 0) {
                    val d = minOf(dropFrames, (whole / frameBytes).toLong()).toInt()
                    off += d * frameBytes
                    dropFrames -= d
                }
                writeNs = 0
                if (whole > off) {
                    direct.clear()
                    direct.put(buf, off, whole - off)
                    direct.flip()
                    // AudioTrack puede aceptar solo parte del bloque; su posición conserva el sufijo.
                    while (running && direct.hasRemaining()) {
                        val w = track.write(direct, direct.remaining(),
                            if (started) AudioTrack.WRITE_BLOCKING else AudioTrack.WRITE_NON_BLOCKING)
                        if (w < 0) throw IOException("AudioTrack.write = $w")
                        framesWritten += w / frameBytes
                        if (!started && (framesWritten >= target.frames || w == 0)) {
                            track.play()
                            started = true
                            playStart = SystemClock.elapsedRealtimeNanos()
                        }
                        if (w == 0) SystemClock.sleep(1)
                    }
                    writeNs = SystemClock.elapsedRealtimeNanos() - arrived
                }
                // Lo que sobra (un frame partido) queda al principio para la próxima lectura.
                System.arraycopy(buf, whole, buf, 0, fill - whole)
                fill -= whole

                if (!started && framesWritten >= target.frames) {
                    track.play()
                    started = true
                    playStart = SystemClock.elapsedRealtimeNanos()
                }

                val now = SystemClock.elapsedRealtimeNanos()
                if (now - lastStatus < STATUS_PERIOD_NS) continue
                lastStatus = now

                val head = track.playbackHeadPosition.toLong() and 0xffffffffL
                headTotal += (head - lastHead) and 0xffffffffL
                lastHead = head
                val inSocket = (input.available() + fill) / frameBytes
                val queued = (framesWritten - headTotal + inSocket).toInt()

                val underruns = track.underrunCount
                if (started && underruns > lastUnderruns) {
                    lastUnderruns = underruns
                    if (now - playStart > SETTLE_NS && target.onUnderrun(now)) {
                        applyTarget()
                        Log.i(TAG, "corte de audio → objetivo ${target.frames} frames")
                    }
                }
                if (target.tick(now)) applyTarget()
                // Si por un tirón del USB (o el arranque del parlante) se acumuló mucho, descartamos
                // el exceso de una vez: preferimos un salto corto a quedarnos con latencia de más.
                val t = target.frames
                if (started && dropFrames == 0L && queued > t + maxOf(t, fmt.sampleRate / 20)) {
                    dropFrames = (queued - t).toLong()
                    Log.i(TAG, "cola de $queued frames, descarto $dropFrames")
                }

                var outputLatency = -1
                if (started && track.getTimestamp(ts)) {
                    val presented = ts.framePosition + (System.nanoTime() - ts.nanoTime) * fmt.sampleRate / 1_000_000_000L
                    outputLatency = (framesWritten - presented).toInt()
                }

                if (DIAG) {
                    minQueued = minOf(minQueued, queued)
                    if (now - lastDiag >= 1_000_000_000L) {
                        Log.d(TAG, "diag: hueco máx ${maxGap / 1000} µs, cola mín $minQueued, objetivo ${target.frames}, cortes $underruns")
                        maxGap = 0
                        minQueued = Int.MAX_VALUE
                        lastDiag = now
                    }
                }
                Protocol.encodeStatus(status, queued, target.frames, underruns, outputLatency)
                out.write(status.array(), 0, Protocol.STATUS_BYTES)

                if (now - lastUi > UI_PERIOD_NS) {
                    lastUi = now
                    onStats(Stats(fmt.sampleRate, target.frames, queued, outputLatency, underruns, fast))
                }
            }
        } finally {
            if (started) prefs.edit().putFloat(learnedKey, target.frames * 1000f / fmt.sampleRate).apply()
            runCatching { track.pause(); track.flush() }
            track.release()
            if (!s.isClosed) runCatching { s.close() }
        }
    }

    companion object {
        private const val TAG = "SoundShare.Player"
        /** Primer arranque en un teléfono nuevo: un valor típico del túnel de adb. */
        private const val INITIAL_TARGET_MS = 30f
        private const val STATUS_PERIOD_NS = 10_000_000L
        /** Lo primero tras arrancar el parlante no cuenta (Android tarda en despertar el hardware). */
        private const val SETTLE_NS = 700_000_000L
        private const val UI_PERIOD_NS = 250_000_000L
        private val DIAG = Log.isLoggable(TAG, Log.DEBUG)

    }
}
