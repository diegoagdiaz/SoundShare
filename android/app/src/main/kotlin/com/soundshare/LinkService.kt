package com.soundshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Servicio en primer plano que mantiene el canal de control con la PC y, cuando toca,
 * el reproductor. La PC es la que decide el modo activo; el teléfono solo lo pide.
 */
class LinkService : Service() {

    @Volatile private var running = false
    @Volatile private var controlSocket: Socket? = null
    @Volatile private var controlOut: OutputStream? = null
    private var controlThread: Thread? = null
    private val writer = Executors.newSingleThreadExecutor()
    private var player: Player? = null
    @Volatile private var quitting = false
    @Volatile private var offAck: CountDownLatch? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            quit()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!running) {
            running = true
            controlThread = Thread(::controlLoop, "ss-control").also { it.start() }
        }
        return START_NOT_STICKY
    }

    @Synchronized override fun onDestroy() {
        running = false
        controlOut = null
        runCatching { controlSocket?.close() }
        stopPlayer()
        writer.shutdownNow()
        instance = null
        connected = false
        mode = Mode.OFF
        notifyChanged()
        super.onDestroy()
    }

    private fun quit() {
        if (quitting) return
        quitting = true
        val ack = CountDownLatch(1)
        offAck = ack
        send("MODE off")
        // El lector sigue vivo para recibir STATE off; no bloqueamos el hilo de la interfaz.
        Thread {
            ack.await(500, TimeUnit.MILLISECONDS)
            main.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                quitRequested = true
                notifyChanged()
            }
        }.start()
    }

    private fun controlLoop() {
        while (running) {
            try {
                Socket().use { s ->
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress("127.0.0.1", Protocol.PORT), 1000)
                    synchronized(this) {
                        if (!running) return@use
                        controlSocket = s
                    }
                    val out = s.getOutputStream()
                    Protocol.writeHello(out, Protocol.ROLE_CONTROL)
                    synchronized(this) {
                        if (!running) return@use
                        controlOut = out
                    }
                    send("HELLO ${Build.MANUFACTURER} ${Build.MODEL}|${Build.VERSION.SDK_INT}")
                    if (quitting) send("MODE off")
                    // Con el túnel de adb armado, el connect siempre "funciona" aunque la PC no esté
                    // escuchando: recién estamos conectados cuando la PC contesta (manda STATE al toque).
                    val reader = BufferedInputStream(s.getInputStream())
                    while (running) {
                        val line = Protocol.readLine(reader) ?: break
                        if (!connected) {
                            connected = true
                            updateNotification()
                        }
                        handleLine(line)
                    }
                }
            } catch (e: IOException) {
                // La PC todavía no está escuchando (o se desconectó el cable): reintentamos.
            } catch (e: Exception) {
                Log.w(TAG, "error en el canal de control", e)
            } finally {
                controlOut = null
                controlSocket = null
                connected = false
                // Siempre: también si falló la sesión antes de actualizar connected o mode.
                applyMode(Mode.OFF)
                updateNotification()
                pcInfo = ""
                notifyChanged()
            }
            if (running) SystemClock.sleep(1000)
        }
    }

    private fun handleLine(line: String) {
        val space = line.indexOf(' ')
        val cmd = if (space < 0) line else line.substring(0, space)
        val arg = if (space < 0) "" else line.substring(space + 1)
        when (cmd) {
            "STATE" -> Mode.parse(arg)?.let {
                applyMode(it)
                if (it == Mode.OFF) offAck?.countDown()
            }
            "INFO" -> pcInfo = arg
            "ERROR" -> lastError = arg
        }
        notifyChanged()
    }

    @Synchronized private fun applyMode(m: Mode) {
        if (m != Mode.OFF && (!running || quitting)) return
        if (m != Mode.PC_TO_PHONE) {
            stopPlayer()
            playerStats = null
        }
        if (m == Mode.PC_TO_PHONE && player == null) {
            player = Player(this) { stats ->
                playerStats = stats
                notifyChanged()
            }.also { it.start() }
        }
        if (m != Mode.OFF) lastError = ""
        mode = m
        updateNotification()
    }

    @Synchronized private fun stopPlayer() {
        player?.stop()
        player = null
    }

    private fun send(line: String) {
        val out = controlOut ?: return
        try {
            writer.execute {
                try {
                    synchronized(out) {
                        out.write((line + "\n").toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "no se pudo enviar '$line': ${e.message}")
                }
            }
        } catch (_: RejectedExecutionException) {
            // El servicio ya está cerrando y no acepta más mensajes.
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this, 1, Intent(this, LinkService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            !connected -> getString(R.string.status_waiting)
            mode == Mode.PC_TO_PHONE -> getString(R.string.status_pc_to_phone)
            mode == Mode.PHONE_TO_PC -> getString(R.string.status_phone_to_pc)
            else -> getString(R.string.status_connected)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_quit), quit).build())
            .build()
    }

    private fun startInForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        main.post {
            if (running) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    companion object {
        private const val TAG = "SoundShare.Link"
        private const val CHANNEL_ID = "stream"
        private const val NOTIF_ID = 1
        const val ACTION_QUIT = "com.soundshare.QUIT"

        @Volatile var connected = false
            private set
        @Volatile var mode = Mode.OFF
            private set
        @Volatile var playerStats: Player.Stats? = null
            private set
        @Volatile var pcInfo = ""
            private set
        @Volatile var lastError = ""
            private set
        @Volatile var quitRequested = false

        @Volatile private var instance: LinkService? = null
        private val main = Handler(Looper.getMainLooper())
        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        fun start(context: Context) {
            quitRequested = false
            context.startForegroundService(Intent(context, LinkService::class.java))
        }

        /** Pide un modo a la PC; el cambio real llega como STATE. */
        fun requestMode(m: Mode) {
            instance?.send("MODE ${m.wire}")
        }

        fun addListener(l: () -> Unit) = listeners.add(l)
        fun removeListener(l: () -> Unit) = listeners.remove(l)

        private fun notifyChanged() {
            main.post { listeners.forEach { it() } }
        }
    }
}
