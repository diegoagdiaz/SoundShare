package com.soundshare.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.Looper
import android.os.Process
import com.soundshare.Protocol
import com.soundshare.LatencyEstimate
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Móvil → PC. La PC lo lanza por adb con permisos de shell:
 *
 *   CLASSPATH=<apk> app_process / com.soundshare.capture.CaptureMain [--port N] [--keep-volume] [--debug]
 *
 * Graba la mezcla final del teléfono desde REMOTE_SUBMIX. Mientras graba, Android desvía toda la salida
 * de medios a esa mezcla y el parlante se calla, igual que al enchufar un cable auxiliar. El audio sale
 * en el formato nativo de la mezcla (PCM 16 bits, 48 kHz, estéreo), sin conversiones.
 */
object CaptureMain {
    private const val RATE = 48000
    private const val CHANNELS = 2
    private const val FRAME_BYTES = CHANNELS * 2
    private const val CHUNK_FRAMES = 1024

    @Volatile private var stopped = false

    @JvmStatic
    fun main(args: Array<String>) {
        var port = Protocol.PORT
        var keepVolume = false
        var debug = false
        var latency = false
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--port" -> port = args[++i].toInt()
                "--keep-volume" -> keepVolume = true
                "--debug" -> debug = true
                "--latency" -> latency = true
            }
            i++
        }
        val code = try {
            run(port, keepVolume, debug, latency)
            0
        } catch (e: Throwable) {
            log("error: $e")
            1
        }
        // Los hilos internos de AudioRecord no dejan terminar la JVM sola.
        exitProcess(code)
    }

    @SuppressLint("MissingPermission")
    private fun run(port: Int, keepVolume: Boolean, debug: Boolean, latency: Boolean) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        // En Android 11 su inicialización consulta recursos del contexto real del sistema.
        // Cargarlo antes de instalar el contexto mínimo del capturador evita esa consulta tardía.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) Class.forName("android.app.AppOpsManager")
        ShellIdentity.install()

        // Primero la PC: si no está escuchando no tiene sentido desviar el audio del teléfono.
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
        val out = socket.getOutputStream()
        Protocol.writeHello(out, Protocol.ROLE_CAPTURE)
        Protocol.writeFormat(out, Protocol.Format(RATE, CHANNELS, Protocol.FORMAT_S16LE))

        val rec = openRecorder()
        try {
            log("capturando ${RATE} Hz, ${CHANNELS} canales, 16 bits")
            if (!keepVolume) Thread { ensureFullVolume() }.start()

            // Si la PC cierra la conexión, terminamos (y el teléfono vuelve a sonar por su parlante).
            Thread {
                try {
                    while (socket.getInputStream().read() >= 0) Unit
                } catch (_: IOException) {
                }
                stopped = true
                runCatching { rec.stop() }
            }.apply { isDaemon = true }.start()

            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            pump(rec, out, debug, latency)
        } finally {
            runCatching { rec.stop() }
            rec.release()
            runCatching { socket.close() }
            log("captura terminada")
        }
    }

    private fun pump(rec: AudioRecord, out: java.io.OutputStream, debug: Boolean, latency: Boolean) {
        val chunk = ByteArray(CHUNK_FRAMES * FRAME_BYTES)
        // REMOTE_SUBMIX suele entregar 1024 frames juntos. Pedir siempre 240 bloqueantes
        // retiene el resto hasta la siguiente entrega; esperamos solo un frame y drenamos
        // lo que ya llegó. La capacidad limita cada escritura, sin agregar espera.
        val reader = PcmReader(FRAME_BYTES) { into, offset, size, blocking ->
            rec.read(into, offset, size,
                if (blocking) AudioRecord.READ_BLOCKING else AudioRecord.READ_NON_BLOCKING)
        }
        var reads = 0
        var maxGapNs = 0L
        var last = System.nanoTime()
        var window = last
        val timestamp = AudioTimestamp()
        var framesRead = 0L
        var lastLatency = 0L
        while (!stopped) {
            val n = reader.readInto(chunk)
            if (n < 0) {
                if (stopped) break
                throw IOException("AudioRecord.read = $n")
            }
            if (n == 0) continue
            framesRead += n / FRAME_BYTES
            val now = System.nanoTime()
            if (latency && now - lastLatency >= 250_000_000L) {
                lastLatency = now
                val age = runCatching {
                    if (rec.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                        // Edad del punto medio del bloque, no solo de su último frame recién llegado.
                        LatencyEstimate.captureAgeMs(RATE, framesRead - n / FRAME_BYTES / 2,
                            timestamp.framePosition, timestamp.nanoTime, System.nanoTime())
                    else null
                }.getOrNull()
                log("LATENCY ${age ?: -1.0}")
            }
            try {
                out.write(chunk, 0, n)
            } catch (e: IOException) {
                break
            }
            if (debug) {
                val now = System.nanoTime()
                maxGapNs = maxOf(maxGapNs, now - last)
                last = now
                reads++
                if (now - window >= 1_000_000_000L) {
                    log("lecturas/s=$reads hueco máx=${maxGapNs / 1000} µs")
                    reads = 0
                    maxGapNs = 0
                    window = now
                }
            }
        }
    }

    /**
     * Android 11 necesita una actividad del shell para habilitar la captura. Si el primer intento falla,
     * la iniciamos en un display privado fuera de pantalla y lo liberamos al arrancar el grabador.
     */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord {
        try {
            return createAndStartRecorder()
        } catch (e: Exception) {
            // El arranque auxiliar solo corresponde a Android 11.
            if (Build.VERSION.SDK_INT != Build.VERSION_CODES.R) throw e
            log("no se pudo iniciar el grabador: $e")
        }

        log("preparando la captura de Android 11 fuera de pantalla")
        return Android11CaptureBootstrap.open(::createAndStartRecorder) { command -> exec(*command) }
    }

    @SuppressLint("MissingPermission")
    private fun newRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.REMOTE_SUBMIX, RATE,
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, RATE / 10 * FRAME_BYTES),
        )
        if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
        rec.release()
        return null
    }

    @SuppressLint("MissingPermission")
    private fun createAndStartRecorder(): AudioRecord {
        val rec = checkNotNull(newRecorder()) { "Android no permitió abrir REMOTE_SUBMIX" }
        try {
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Android no permitió capturar el audio" }
            return rec
        } catch (e: Throwable) {
            rec.release()
            throw e
        }
    }

    /**
     * El volumen de medios se aplica antes de la captura. Lo ponemos al máximo solo para el dispositivo
     * virtual remote_submix (el parlante conserva su volumen): así la señal llega intacta, bit a bit,
     * y el volumen se maneja desde la PC.
     */
    private fun ensureFullVolume() {
        val got = exec("cmd", "media_session", "volume", "--stream", "3", "--get")
        val m = Regex("""volume is (\d+) in range \[\d+\.\.(\d+)]""").find(got) ?: return
        val (cur, max) = m.destructured
        if (cur.toInt() < max.toInt()) {
            exec("cmd", "media_session", "volume", "--stream", "3", "--set", max)
            log("volumen de captura $cur → $max (100 %)")
        }
    }

    private fun exec(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        // Drenar en paralelo evita que readText() anule el timeout o se llene la tubería.
        val output = FutureTask { p.inputStream.bufferedReader().use { it.readText() } }
        Thread(output, "ss-shell-output").apply { isDaemon = true }.start()
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw IOException("tiempo agotado: ${cmd.joinToString(" ")}")
        }
        val text = try {
            output.get(200, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            throw IOException("no se pudo leer la salida: ${cmd.joinToString(" ")}", e)
        }
        if (p.exitValue() != 0) throw IOException(text.trim())
        return text
    }

    private fun log(msg: String) {
        println("[captura] $msg")
        System.out.flush()
    }
}
