package com.soundshare

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import java.io.Closeable
import java.io.IOException
import java.net.Socket

/** Captura pública por WiFi: duplica el audio de las apps que permiten capturarlo. */
class AppCapture(private val endpoint: LinkEndpoint, private val projection: MediaProjection,
                 private val onLatency: (Double) -> Unit, private val onFailed: (String) -> Unit) {
    private val run = PlayerGeneration()
    private var thread: Thread? = null

    fun start() {
        thread = Thread(::record, "ss-capture").apply { isDaemon = true; start() }
    }

    fun stop() {
        run.cancel()
        thread?.join(150)
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        if (Build.VERSION.SDK_INT < 29) {
            onFailed("La captura por WiFi necesita Android 10 o posterior")
            return
        }
        val socket = Socket()
        if (!run.attach(socket)) return
        var recorder: AudioRecord? = null
        var playback: Closeable? = null
        try {
            endpoint.connect(socket)
            if (!run.active) return
            val out = socket.getOutputStream()
            Protocol.writeHello(out, Protocol.ROLE_CAPTURE)
            Protocol.writeFormat(out, Protocol.Format(RATE, 2, Protocol.FORMAT_S16LE))
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(maxOf(minimum, RATE / 10 * FRAME_BYTES))
                .build()
            recorder = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("Android no permitió abrir la captura")
            val stop = Closeable { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            playback = stop
            if (!run.attachPlayback(stop)) return
            // Prepará clases y buffers antes de entrar en el hilo de audio.
            val chunk = ByteArray(RATE / 200 * FRAME_BYTES)
            val timestamp = AudioTimestamp()
            LatencyEstimate.captureAgeMs(RATE, 0, 0, 0, 0)
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            run.ifActive { rec.startRecording() } ?: return
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Android no permitió capturar el audio")
            var framesRead = 0L
            var lastLatency = 0L
            while (run.active) {
                val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (!run.active) break
                if (n < 0) throw IOException("AudioRecord.read = $n")
                if (n == 0) continue
                framesRead += n / FRAME_BYTES
                val now = System.nanoTime()
                if (now - lastLatency >= 250_000_000L) {
                    lastLatency = now
                    val age = if (rec.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                        LatencyEstimate.captureAgeMs(RATE, framesRead - n / FRAME_BYTES / 2,
                            timestamp.framePosition, timestamp.nanoTime, now) else null
                    run.ifActive { onLatency(age ?: -1.0) }
                }
                out.write(chunk, 0, n)
            }
        } catch (e: Exception) {
            if (run.active) onFailed(e.message ?: "No se pudo capturar el audio")
        } finally {
            playback?.let { runCatching { it.close() }; run.detachPlayback(it) }
            recorder?.release()
            socket.close()
            run.detach(socket)
        }
    }

    companion object {
        private const val RATE = 48000
        private const val FRAME_BYTES = 4
    }
}
