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
    private lateinit var accessory: AccessoryTransport
    private var playerUsesAoa = false
    @Volatile private var quitting = false
    @Volatile private var offAck: CountDownLatch? = null
    @Volatile private var pendingPing = 0L
    @Volatile private var pingQueued = false
    private val ping = object : Runnable {
        override fun run() {
            if (!running) return
            sendPing()
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        transportPreference = TransportChoice.parse(getSharedPreferences("player", Context.MODE_PRIVATE)
            .getString("transport", "aoa") ?: "aoa") ?: TransportChoice.AOA
        playerCandidate = TransportCandidate(TransportChoice.ADB)
        accessory = AccessoryTransport(this) {
            main.post {
                if (instance !== this || !running) return@post
                accessoryState = accessory.state
                if (accessoryState == AccessoryState.READY && shouldUseAoa() && running) restartPlayer()
                notifyChanged()
            }
        }
        accessoryState = accessory.state
    }

    private fun shouldUseAoa() = TransportSelection.useAoa(transportPreference, playerCandidate)

    private fun refreshAccessory() {
        main.post { if (running && transportPreference == TransportChoice.AOA) accessory.prepare() }
    }

    @Synchronized private fun restartPlayer() {
        if (mode != Mode.PC_TO_PHONE || !running || quitting) return
        stopPlayer()
        applyMode(mode)
        notifyChanged()
    }

    @Synchronized private fun selectTransport(choice: TransportChoice) {
        transportPreference = choice
        if (choice == TransportChoice.AOA) accessory.prepare(explicit = true)
        send("TRANSPORT ${choice.wire}")
        restartPlayer()
        notifyChanged()
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
            main.post(ping)
        }
        return START_NOT_STICKY
    }

    @Synchronized override fun onDestroy() {
        running = false
        main.removeCallbacks(ping)
        pcLatency = null
        transport = null
        controlOut = null
        runCatching { controlSocket?.close() }
        stopPlayer()
        accessory.close()
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
                    send("TRANSPORT ${transportPreference.wire}")
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
                playerCandidate = TransportCandidate(TransportChoice.ADB, TransportReason.DISCONNECTED)
                // Siempre: también si falló la sesión antes de actualizar connected o mode.
                applyMode(Mode.OFF)
                updateNotification()
                pcInfo = ""
                pcLatency = null
                transport = null
                pendingPing = 0L
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
            "PLAYER_TRANSPORT" -> TransportCandidate.parse(arg)?.let { candidate ->
                synchronized(this) {
                    playerCandidate = candidate
                    val useAoa = shouldUseAoa()
                    if (useAoa) main.post { if (running && shouldUseAoa()) accessory.prepare() }
                    if (player != null && playerUsesAoa != useAoa) restartPlayer()
                }
            }
            "STATE" -> Mode.parse(arg)?.let {
                applyMode(it)
                if (it == Mode.OFF) offAck?.countDown()
            }
            "INFO" -> pcInfo = arg
            "LATENCY" -> LatencyEstimate.parsePc(arg)?.takeIf { it.mode == mode }?.let {
                pcLatency = TimedPc(it, System.nanoTime())
            }
            "PONG" -> {
                val token = arg.toLongOrNull()
                val now = System.nanoTime()
                if (token != null && token > 0 && token == pendingPing) {
                    val rtt = (now - token) / 1e6
                    if (rtt in 0.0..3000.0) transport = TimedTransport(rtt, now)
                    pendingPing = 0L
                }
            }
            "ERROR" -> lastError = arg
        }
        notifyChanged()
    }

    @Synchronized private fun applyMode(m: Mode) {
        if (m != Mode.OFF && (!running || quitting)) return
        if (m != mode) {
            pcLatency = null
            pcInfo = ""
            if (m == Mode.OFF) transport = null
        }
        if (m != Mode.PC_TO_PHONE) {
            stopPlayer()
            playerStats = null
        }
        if (m == Mode.PC_TO_PHONE && player == null) {
            playerUsesAoa = shouldUseAoa()
            player = Player(this, if (playerUsesAoa) accessory else null) { stats ->
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
        playerStats = null
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

    private fun sendPing() {
        if (!connected || mode == Mode.OFF || quitting || pingQueued) return
        if (pendingPing != 0L && System.nanoTime() - pendingPing < 3_000_000_000L) return
        val out = controlOut ?: return
        pingQueued = true
        try {
            writer.execute {
                try {
                    synchronized(out) {
                        if (controlOut === out && running && connected) {
                            val sent = System.nanoTime()
                            pendingPing = sent
                            out.write("PING $sent\n".toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    }
                } catch (e: IOException) {
                    pendingPing = 0L
                } finally {
                    pingQueued = false
                }
            }
        } catch (_: RejectedExecutionException) {
            pingQueued = false
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

        @Volatile var transportPreference = TransportChoice.AOA
            private set
        @Volatile var playerCandidate = TransportCandidate(TransportChoice.ADB)
            private set
        @Volatile var accessoryState = AccessoryState.UNAVAILABLE
            private set

        fun requestTransport(context: Context, choice: TransportChoice) {
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString("transport", choice.wire).apply()
            instance?.selectTransport(choice)
        }

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
        private data class TimedPc(val value: LatencyEstimate.Pc, val received: Long)
        private data class TimedTransport(val milliseconds: Double, val received: Long)
        @Volatile private var pcLatency: TimedPc? = null
        @Volatile private var transport: TimedTransport? = null

        fun latencyEstimate(): LatencyEstimate.Value {
            val now = System.nanoTime()
            val pc = pcLatency?.takeIf { now - it.received < 3_000_000_000L }?.value
            val player = playerStats?.takeIf { now - it.measuredAtNs < 1_500_000_000L }
            // El PING de CONTROL va por adb y no representa a AOA. Por AOA el bloque cruza el USB en
            // menos de 1 ms y sus irregularidades ya están en la cola medida del teléfono.
            val rtt = if (player?.transportName == "aoa") 0.0
                else transport?.takeIf { now - it.received < 3_000_000_000L }?.milliseconds
            return LatencyEstimate.calculate(mode, pc, player?.queuedMs?.toDouble(),
                player?.takeIf { it.outputLatencyFrames >= 0 }?.outputLatencyMs?.toDouble(),
                player?.socketMs?.toDouble() ?: 0.0, rtt)
        }

        @Volatile private var instance: LinkService? = null
        private val main = Handler(Looper.getMainLooper())
        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        fun start(context: Context) {
            quitRequested = false
            context.startForegroundService(Intent(context, LinkService::class.java))
        }

        /** El intent de accesorio puede conceder permiso con una actividad ya abierta. */
        fun accessoryAttached() { instance?.refreshAccessory() }

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
