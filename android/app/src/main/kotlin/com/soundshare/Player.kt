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
 * PC → Móvil: recibe PCM por AOA o el túnel de adb y lo reproduce con la menor latencia que da el teléfono.
 *
 * El teléfono no remuestrea nada. Cada 10 ms le informa a la PC cuántos frames tiene encolados
 * y la PC ajusta finamente su propio ritmo (resampler adaptativo de PipeWire) para que la cola
 * se quede en [target]. Así los dos relojes quedan enganchados sin cortes ni deriva.
 */
class Player(private val context: Context, private val accessory: AccessoryTransport?, private val onStats: (Stats?) -> Unit) {

    data class Stats(
        val sampleRate: Int,
        val targetFrames: Int,
        val queuedFrames: Int,
        val outputLatencyFrames: Int,
        val underruns: Int,
        val fastTrack: Boolean,
        val socketFrames: Int = 0,
        val measuredAtNs: Long = System.nanoTime(),
        val transportName: String = "adb",
    ) {
        private fun ms(frames: Int) = frames * 1000f / sampleRate
        val targetMs get() = ms(targetFrames)
        val queuedMs get() = ms(queuedFrames)
        val outputLatencyMs get() = ms(outputLatencyFrames)
        val socketMs get() = ms(socketFrames)
    }

    private var generation: PlayerGeneration? = null
    private var thread: Thread? = null

    @Synchronized fun start() {
        if (generation != null) return
        val run = PlayerGeneration()
        generation = run
        thread = Thread({ loop(run) }, "ss-player").also { it.isDaemon = true; it.start() }
    }

    @Synchronized fun stop() {
        generation?.cancel()
        generation = null
        thread?.join(150)
        thread = null
    }

    private fun loop(run: PlayerGeneration) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (run.active) {
            try {
                session(run)
            } catch (e: IOException) {
                if (run.active) Log.w(TAG, "conexión de audio cortada: ${e.message}")
            } catch (e: Exception) {
                if (run.active) Log.e(TAG, "error en el reproductor", e)
            }
            run.ifActive { onStats(null) }
            if (run.active) SystemClock.sleep(300)
        }
    }

    private fun session(run: PlayerGeneration) {
        val usb = run.ifActive { accessory?.connect() }
        if (usb != null) {
            if (!run.attach(usb)) return
            try {
                usb.use {
                    if (!run.active) return
                    Protocol.writeHello(usb.output, Protocol.ROLE_PLAYER)
                    val fmt = Protocol.readFormat(usb.input)
                    if (run.active) play(run, fmt, usb.input, usb.output, "aoa")
                }
            } catch (e: Exception) {
                run.ifActive { if (!usb.peerEnded) accessory?.disable(usb) }
                throw e
            } finally {
                run.detach(usb)
            }
            return
        }
        if (!run.active) return
        val s = Socket()
        if (!run.attach(s)) return
        try {
            s.use {
                if (!run.active) return
                s.tcpNoDelay = true
                s.connect(InetSocketAddress("127.0.0.1", Protocol.PORT), 2000)
                if (!run.active) return
                val input = s.getInputStream()
                val out = s.getOutputStream()
                Protocol.writeHello(out, Protocol.ROLE_PLAYER)
                val fmt = Protocol.readFormat(input)
                if (run.active) play(run, fmt, input, out, "adb")
            }
        } finally {
            run.detach(s)
        }
    }

    private fun play(run: PlayerGeneration, fmt: Protocol.Format, input: java.io.InputStream, out: java.io.OutputStream, transport: String) {
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

        val playback = java.io.Closeable { track.pause(); track.flush() }
        if (!run.attachPlayback(playback)) { track.release(); return }
        try {
            val capacity = track.bufferCapacityInFrames
            val fast = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            // Arrancamos donde terminó la última sesión: el jitter del USB depende del teléfono y la PC.
            val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
            val learnedKey = if (transport == "adb") "target_ms_${fmt.sampleRate}" else "target_ms_aoa_${fmt.sampleRate}"
            val initialMs = prefs.getFloat(learnedKey, INITIAL_TARGET_MS)
            val target = QueueTarget(burst, fmt.sampleRate, capacity / 2, (initialMs * fmt.sampleRate / 1000).toInt())
            fun applyTarget() {
                if (!run.active) return
                // El umbral de escritura queda por encima del objetivo: write() casi nunca bloquea.
                val bufferFrames = track.setBufferSizeInFrames((target.frames + 2 * burst).coerceAtMost(capacity))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Arrancar al reunir el objetivo, sin esperar también el margen de escritura.
                    val actualBufferFrames = if (bufferFrames > 0) bufferFrames else track.bufferSizeInFrames
                    track.setStartThresholdInFrames(minOf(target.frames, actualBufferFrames).coerceAtLeast(1))
                }
                // Guardar al ajustar: un hilo cancelado no puede persistir por encima del nuevo.
                run.ifActive { prefs.edit().putFloat(learnedKey, target.frames * 1000f / fmt.sampleRate).apply() }
            }
            applyTarget()
            run.ifActive { onStats(Stats(fmt.sampleRate, target.frames, 0, -1, 0, fast, transportName = transport)) }
            Log.i(TAG, "transporte=$transport, pista: ${fmt.sampleRate} Hz, burst $burst, capacidad $capacity, rápida=$fast, objetivo ${target.frames}")

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

            while (run.active) {
                val n = input.read(buf, fill, buf.size - fill)
                if (!run.active) break
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
                    while (run.active && direct.hasRemaining()) {
                        val w = run.ifActive {
                            track.write(direct, direct.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                        } ?: break
                        if (w < 0) throw IOException("AudioTrack.write = $w")
                        framesWritten += w / frameBytes
                        if (!run.active) break
                        if (!started && (framesWritten >= target.frames || w == 0)) {
                            run.ifActive { track.play() } ?: break
                            started = true
                            playStart = SystemClock.elapsedRealtimeNanos()
                        }
                        if (w == 0) SystemClock.sleep(1)
                    }
                    writeNs = SystemClock.elapsedRealtimeNanos() - arrived
                }
                if (!run.active) break
                // Lo que sobra (un frame partido) queda al principio para la próxima lectura.
                System.arraycopy(buf, whole, buf, 0, fill - whole)
                fill -= whole

                if (!started && framesWritten >= target.frames) {
                    run.ifActive { track.play() } ?: break
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
                    val pending = framesWritten - presented
                    val timestampAge = System.nanoTime() - ts.nanoTime
                    if (ts.nanoTime > 0 && timestampAge in 0..1_000_000_000L
                        && presented >= 0 && pending in 0..Int.MAX_VALUE.toLong()) {
                        outputLatency = pending.toInt()
                    }
                }

                if (DIAG) {
                    minQueued = minOf(minQueued, queued)
                    if (now - lastDiag >= 1_000_000_000L) {
                        Log.d(TAG, "diag: transporte=$transport, hueco máx ${maxGap / 1000} µs, cola mín $minQueued, objetivo ${target.frames}, cortes $underruns")
                        maxGap = 0
                        minQueued = Int.MAX_VALUE
                        lastDiag = now
                    }
                }
                Protocol.encodeStatus(status, queued, target.frames, underruns, outputLatency)
                if (!run.active) break
                out.write(status.array(), 0, Protocol.STATUS_BYTES)

                if (now - lastUi > UI_PERIOD_NS) {
                    lastUi = now
                    run.ifActive { onStats(Stats(fmt.sampleRate, target.frames, queued, outputLatency, underruns, fast, inSocket, transportName = transport)) }
                }
            }
        } finally {
            run.ifActive { runCatching { playback.close() } }
            run.detachPlayback(playback)
            track.release()
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
