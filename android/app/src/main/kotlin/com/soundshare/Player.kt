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
class Player(
    private val context: Context,
    private val accessory: AccessoryTransport?,
    private val profile: LatencyProfile,
    private val onStats: (Stats?) -> Unit,
) {

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
        val minimal: Boolean = false,
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
            // Puede cambiar en vivo: si la salida cambia (p. ej. se desconecta el Bluetooth),
            // Android recrea la pista por dentro y vuelve a pedirla rápida.
            var fast = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            val minimal = profile == LatencyProfile.MINIMAL
            val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
            // Arrancamos donde terminó la última sesión: el jitter del USB depende del teléfono y la PC.
            // Cada perfil aprende por separado, así el mínimo no achica la cola del estable.
            val learnedKey = (if (minimal) "target_ms_min_" else "target_ms_") +
                (if (transport == "adb") "" else "aoa_") + fmt.sampleRate
            val initialMs = prefs.getFloat(learnedKey, if (minimal) INITIAL_MINIMAL_TARGET_MS else INITIAL_TARGET_MS)
            val target = QueueTarget(burst, fmt.sampleRate, capacity / 2, (initialMs * fmt.sampleRate / 1000).toInt(), profile)
            var bufferFrames = 0
            fun applyTarget() {
                if (!run.active) return
                // El umbral de escritura queda por encima del objetivo: write() casi nunca bloquea.
                val requested = track.setBufferSizeInFrames((target.frames + 2 * burst).coerceAtMost(capacity))
                bufferFrames = if (requested > 0) requested else track.bufferSizeInFrames
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Arrancar al reunir el objetivo, sin esperar también el margen de escritura.
                    track.setStartThresholdInFrames(minOf(target.frames, bufferFrames).coerceAtLeast(1))
                }
                // Guardar al ajustar: un hilo cancelado no puede persistir por encima del nuevo.
                run.ifActive { prefs.edit().putFloat(learnedKey, target.frames * 1000f / fmt.sampleRate).apply() }
            }
            applyTarget()
            run.ifActive { onStats(Stats(fmt.sampleRate, target.frames, 0, -1, 0, fast, transportName = transport, minimal = minimal)) }
            Log.i(TAG, "transporte=$transport, perfil=${profile.wire}, pista: ${fmt.sampleRate} Hz, burst $burst, capacidad $capacity, rápida=$fast, objetivo ${target.frames}")

            val buf = ByteArray(frameBytes * 1024)
            val direct = ByteBuffer.allocateDirect(buf.size).order(ByteOrder.nativeOrder())
            val silence = ByteBuffer.allocateDirect(burst * frameBytes)
            val status = ByteBuffer.allocate(Protocol.STATUS_BYTES)
            val ts = AudioTimestamp()
            val trim = ExcessTrim(burst)
            var fill = 0
            var framesWritten = 0L
            var headTotal = 0L
            var lastHead = 0L

            /** Frames que el mezclador todavía no leyó de la pista. */
            fun trackLevel(): Long {
                val head = track.playbackHeadPosition.toLong() and 0xffffffffL
                headTotal += (head - lastHead) and 0xffffffffL
                lastHead = head
                return framesWritten - headTotal
            }

            fun writeSilence(frames: Long) {
                var left = frames
                while (left > 0 && run.active) {
                    silence.clear()
                    silence.limit((minOf(left, burst.toLong()) * frameBytes).toInt())
                    val w = run.ifActive { track.write(silence, silence.remaining(), AudioTrack.WRITE_NON_BLOCKING) } ?: return
                    if (w < 0) throw IOException("AudioTrack.write = $w")
                    if (w == 0) return
                    framesWritten += w / frameBytes
                    left -= w / frameBytes
                }
            }

            // Arranque en silencio: lo que llega mientras Android despierta la salida se descarta sin
            // oírse, y el audio real entra con la cola ya en el objetivo. Antes se acumulaba (20-130 ms
            // medidos) y había que descartarlo con un salto en pleno audio. El buffer entero de silencio
            // alcanza el umbral de arranque también en Android 8-11, que no permite bajarlo.
            writeSilence(bufferFrames.toLong())
            run.ifActive { track.play() } ?: return
            val prerollStart = SystemClock.elapsedRealtimeNanos()
            var outputSince = 0L
            var started = false
            var dropFrames = 0L
            var underrunBase = 0
            var lastUnderruns = 0
            // Mínimo de la pista justo antes de cada llegada, en este período de estado y el anterior.
            var lowest = Int.MAX_VALUE
            var lowestBefore = Int.MAX_VALUE
            var playStart = 0L
            var lastStatus = 0L
            var lastUi = 0L
            var lastRead = prerollStart
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
                if (DIAG && started) maxGap = maxOf(maxGap, gap)
                fill += n
                val whole = fill - fill % frameBytes
                var off = 0

                if (!started) {
                    val level = trackLevel()
                    val waited = arrived - prerollStart
                    val presenting = track.getTimestamp(ts) && ts.framePosition > 0
                    if (outputSince == 0L && (presenting || (waited > PREROLL_FALLBACK_NS && headTotal > 0))) outputSince = arrived
                    // Los primeros ciclos del mezclador rápido son irregulares (su warmup): que pasen en silencio.
                    val running = outputSince != 0L && arrived - outputSince > OUTPUT_SETTLE_NS
                    if ((running && level <= target.frames) || waited > PREROLL_MAX_NS) {
                        started = true
                        playStart = arrived
                        // Los cortes del silencio inicial no se oyeron: la cuenta empieza acá.
                        underrunBase = track.underrunCount
                        Log.i(TAG, "salida en marcha a los ${waited / 1_000_000} ms; el audio entra con $level frames en la pista")
                    } else {
                        if (level < target.frames) writeSilence(target.frames - level)
                        off = whole
                    }
                }
                if (dropFrames > 0) {
                    val d = minOf(dropFrames, ((whole - off) / frameBytes).toLong()).toInt()
                    off += d * frameBytes
                    dropFrames -= d
                }
                writeNs = 0
                if (whole > off) {
                    lowest = minOf(lowest, trackLevel().toInt())
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
                        if (w == 0) SystemClock.sleep(1)
                    }
                    writeNs = SystemClock.elapsedRealtimeNanos() - arrived
                }
                if (!run.active) break
                // Lo que sobra (un frame partido) queda al principio para la próxima lectura.
                System.arraycopy(buf, whole, buf, 0, fill - whole)
                fill -= whole
                // La PC recibe el primer estado con el audio real ya entrando, cerca del objetivo.
                if (!started) continue

                val now = SystemClock.elapsedRealtimeNanos()
                if (now - lastStatus < STATUS_PERIOD_NS) continue
                lastStatus = now

                val inSocket = (input.available() + fill) / frameBytes
                val queued = (trackLevel() + inSocket).toInt()

                val underruns = track.underrunCount - underrunBase
                if (underruns > lastUnderruns) {
                    lastUnderruns = underruns
                    val low = minOf(lowest, lowestBefore)
                    if (now - playStart > SETTLE_NS && target.onUnderrun(now, low)) {
                        applyTarget()
                        Log.i(TAG, "corte de audio → objetivo ${target.frames} frames")
                    } else if (!minimal && low >= burst) {
                        Log.i(TAG, "corte de la salida con $low frames en la pista: objetivo sin cambios")
                    }
                }
                lowestBefore = lowest
                lowest = Int.MAX_VALUE
                if (target.tick(now)) applyTarget()
                val t = target.frames
                if (dropFrames == 0L) {
                    // Latencia mínima: lo que se juntó tras un hueco del USB se descarta enseguida.
                    val excess = if (minimal) trim.update(queued, t, now) else 0
                    if (excess > 0) {
                        dropFrames = excess.toLong()
                        if (DIAG) Log.d(TAG, "latencia mínima: descarto $excess frames viejos")
                    } else if (queued > t + maxOf(t, fmt.sampleRate / 20)) {
                        // Si por un tirón del USB se acumuló mucho, descartamos el exceso de una vez:
                        // preferimos un salto corto a quedarnos con latencia de más.
                        dropFrames = (queued - t).toLong()
                        Log.i(TAG, "cola de $queued frames, descarto $dropFrames")
                    }
                }

                var outputLatency = -1
                if (track.getTimestamp(ts)) {
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
                    val nowFast = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                    if (nowFast != fast) {
                        fast = nowFast
                        Log.i(TAG, "la salida cambió: rápida=$fast")
                        // La pista recreada puede no conservar el tamaño de buffer que fijamos.
                        applyTarget()
                    }
                    run.ifActive { onStats(Stats(fmt.sampleRate, target.frames, queued, outputLatency, underruns, fast, inSocket, transportName = transport, minimal = minimal)) }
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
        private const val INITIAL_MINIMAL_TARGET_MS = 10f
        private const val STATUS_PERIOD_NS = 10_000_000L
        /** Lo primero tras arrancar el parlante no cuenta (Android tarda en despertar el hardware). */
        private const val SETTLE_NS = 700_000_000L
        /** Sin timestamps de salida, alcanza con que el mezclador ya lea de la pista. */
        private const val PREROLL_FALLBACK_NS = 300_000_000L
        /** Silencio que sigue después de que la salida empezó a sonar. */
        private const val OUTPUT_SETTLE_NS = 100_000_000L
        /** Si la salida no arranca, el audio entra igual: nunca más que esto en silencio. */
        private const val PREROLL_MAX_NS = 1_500_000_000L
        private const val UI_PERIOD_NS = 250_000_000L
        private val DIAG = Log.isLoggable(TAG, Log.DEBUG)

    }
}
