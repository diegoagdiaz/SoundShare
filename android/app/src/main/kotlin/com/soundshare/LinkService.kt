package com.soundshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
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
    @Volatile private var controlRun: PlayerGeneration? = null
    @Volatile private var controlEndpoint: LinkEndpoint? = null
    private lateinit var lan: LanStore
    private lateinit var discovery: LanDiscovery
    private lateinit var bluetooth: BluetoothMonitor
    private var wifiLocks = emptyList<WifiManager.WifiLock>()
    private var projection: MediaProjection? = null
    private var capture: AppCapture? = null
    private var captureRequested = false
    private var consentInFlight = false
    private val writer = Executors.newSingleThreadExecutor()
    private var player: Player? = null
    private lateinit var accessory: AccessoryTransport
    private var playerUsesAoa = false
    @Volatile private var quitting = false
    @Volatile private var offAck: CountDownLatch? = null
    @Volatile private var bluetoothOffAck: CountDownLatch? = null
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
        lan = LanStore(this)
        discovery = LanDiscovery(this, lan)
        bluetoothStatus = BluetoothStatus(BluetoothStatus.Phase.CHECKING)
        bluetooth = BluetoothMonitor(this) { status ->
            if (instance === this && running && linkPreference == LinkChoice.BLUETOOTH) {
                bluetoothStatus = status
                currentPcName = status.destination
                updateNotification()
                notifyChanged()
            }
        }
        hasPairedPc = lan.paired().isNotEmpty()
        currentLink = null
        captureConsentNeeded = false
        val prefs = getSharedPreferences("player", Context.MODE_PRIVATE)
        linkPreference = LinkChoice.parse(prefs.getString("link", null))
        transportPreference = TransportChoice.parse(prefs.getString("transport", "aoa") ?: "aoa") ?: TransportChoice.AOA
        latencyProfile = LatencyProfile.parse(prefs.getString("latency", null)) ?: LatencyProfile.STABLE
        playerCandidate = TransportCandidate(TransportChoice.ADB)
        accessory = AccessoryTransport(this, { controlEndpoint?.wifi != true && LinkSelection.tryUsb(linkPreference) }) {
            main.post {
                if (instance !== this || !running) return@post
                accessoryState = accessory.state
                if (accessoryState == AccessoryState.READY && shouldUseAoa() && running) restartPlayer()
                notifyChanged()
            }
        }
        accessoryState = accessory.state
    }

    private fun shouldUseAoa() = controlEndpoint?.wifi != true && LinkSelection.tryUsb(linkPreference) &&
        TransportSelection.useAoa(transportPreference, playerCandidate)

    private fun refreshAccessory() {
        main.post {
            if (running && controlEndpoint?.wifi != true && LinkSelection.tryUsb(linkPreference) &&
                transportPreference == TransportChoice.AOA) accessory.prepare()
        }
    }

    @Synchronized private fun restartPlayer() {
        if (mode != Mode.PC_TO_PHONE || !running || quitting) return
        stopPlayer()
        applyMode(mode)
        notifyChanged()
    }

    @Synchronized private fun selectTransport(choice: TransportChoice) {
        transportPreference = choice
        if (controlEndpoint?.wifi != true && LinkSelection.tryUsb(linkPreference)) {
            if (choice == TransportChoice.AOA) accessory.prepare(explicit = true)
            send("TRANSPORT ${choice.wire}")
        }
        restartPlayer()
        notifyChanged()
    }

    @Synchronized private fun selectLink(choice: LinkChoice) {
        if (linkPreference == choice) return
        val previousRun = controlRun
        val previousOut = controlOut
        val handoff = choice == LinkChoice.BLUETOOTH && previousRun?.active == true && previousOut != null
        linkPreference = choice
        if (handoff) {
            // La captura shell de USB sobrevive al cierre de CONTROL: hay que entregar MODE off antes.
            val ack = CountDownLatch(1)
            bluetoothOffAck = ack
            send("MODE off", previousOut)
            Thread {
                ack.await(500, TimeUnit.MILLISECONDS)
                synchronized(this) {
                    if (instance === this && linkPreference == LinkChoice.BLUETOOTH && controlRun === previousRun) {
                        controlOut = null
                        previousRun?.cancel()
                    }
                    if (bluetoothOffAck === ack) bluetoothOffAck = null
                }
            }.apply { isDaemon = true; start() }
        } else {
            controlOut = null
            previousRun?.cancel()
        }
        connected = false
        applyMode(Mode.OFF)
        controlEndpoint = null
        currentLink = if (choice == LinkChoice.BLUETOOTH) LinkChoice.BLUETOOTH else null
        currentPcName = ""
        pcLatency = null
        transport = null
        pendingPing = 0L
        releaseWifiLock()
        if (choice == LinkChoice.BLUETOOTH) {
            bluetoothStatus = BluetoothStatus(BluetoothStatus.Phase.CHECKING)
            consentInFlight = false
            val granted = projection
            projection = null
            granted?.stop()
            accessory.pause()
            startInForeground()
        }
        refreshBluetoothMonitor()
        controlThread?.interrupt()
        notifyChanged()
    }

    @Synchronized private fun selectLatency(profile: LatencyProfile) {
        if (latencyProfile == profile) return
        latencyProfile = profile
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
        currentLink = if (linkPreference == LinkChoice.BLUETOOTH) LinkChoice.BLUETOOTH else currentLink
        refreshBluetoothMonitor()
        return START_NOT_STICKY
    }

    @Synchronized override fun onDestroy() {
        running = false
        bluetooth.stop()
        main.removeCallbacks(ping)
        controlRun?.cancel()
        controlThread?.interrupt()
        captureConsentNeeded = false
        consentInFlight = false
        captureRequested = false
        stopCapture()
        projection?.stop()
        projection = null
        releaseWifiLock()
        currentLink = null
        currentPcName = ""
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
            val run = synchronized(this) {
                if (!running) return
                PlayerGeneration().also { controlRun = it }
            }
            val choice = linkPreference
            try {
                var hadSession = false
                if (LinkSelection.tryUsb(choice)) hadSession = tryControl(LinkEndpoint(), run)
                if (!hadSession && run.active && LinkSelection.tryWifi(choice, lan.paired().isNotEmpty())) {
                    // Antes de emitir broadcasts probamos las direcciones ya conocidas.
                    for (endpoint in discovery.known()) {
                        if (!run.active) break
                        if (tryControl(endpoint, run)) { hadSession = true; break }
                    }
                    if (!hadSession && run.active) {
                        for (endpoint in discovery.discover(run)) {
                            if (!run.active) break
                            if (tryControl(endpoint, run)) break
                        }
                    }
                }
            } catch (e: Exception) {
                if (run.active) Log.w(TAG, "no se pudo buscar la PC por WiFi", e)
            } finally {
                run.cancel()
                synchronized(this) { if (controlRun === run) controlRun = null }
            }
            if (running) try { Thread.sleep(1000) } catch (_: InterruptedException) { }
        }
    }

    private fun tryControl(endpoint: LinkEndpoint, run: PlayerGeneration): Boolean {
        var responded = false
        val s = Socket()
        if (!run.attach(s)) return false
        try {
            endpoint.connect(s, 1000)
            synchronized(this) {
                if (!running || !run.active || controlRun !== run) return false
                controlSocket = s
                controlEndpoint = endpoint
                currentLink = if (endpoint.wifi) LinkChoice.WIFI else LinkChoice.USB
                currentPcName = endpoint.pc?.name ?: ""
                if (endpoint.wifi) acquireWifiLock()
            }
            val out = s.getOutputStream()
            Protocol.writeHello(out, Protocol.ROLE_CONTROL)
            synchronized(this) {
                if (!running || !run.active) return false
                controlOut = out
                send("HELLO ${Build.MANUFACTURER} ${Build.MODEL}|${Build.VERSION.SDK_INT}")
                if (!endpoint.wifi) {
                    send("LAN_PAIR ${lan.phoneId}")
                    send("TRANSPORT ${transportPreference.wire}")
                }
                if (quitting) send("MODE off")
            }
            // adb reverse puede aceptar TCP sin una PC detrás. La primera respuesta confirma el enlace.
            s.soTimeout = if (endpoint.wifi || linkPreference == LinkChoice.AUTO) 1500 else 0
            val reader = BufferedInputStream(s.getInputStream())
            while (running && run.active) {
                val line = Protocol.readLine(reader) ?: break
                synchronized(this) {
                    if (!running || !run.active || controlRun !== run) return responded
                    if (!responded) {
                        responded = true
                        s.soTimeout = if (endpoint.wifi) 5000 else 0
                        connected = true
                        endpoint.pc?.let {
                            // Recién el preámbulo TCP prueba la identidad; un anuncio UDP no cambia la caché.
                            lan.authenticated(it.id, endpoint.address, endpoint.port, it.name)
                        }
                        updateNotification()
                    }
                    handleLine(line)
                }
            }
        } catch (_: IOException) {
            // PC ausente, dirección vieja o cable desconectado: probamos el siguiente candidato.
        } catch (e: Exception) {
            if (run.active) Log.w(TAG, "error en el canal de control", e)
        } finally {
            s.close()
            run.detach(s)
            synchronized(this) {
                if (controlRun === run && instance === this) {
                    controlOut = null
                    controlSocket = null
                    connected = false
                    playerCandidate = TransportCandidate(TransportChoice.ADB, TransportReason.DISCONNECTED)
                    applyMode(Mode.OFF)
                    controlEndpoint = null
                    currentLink = if (linkPreference == LinkChoice.BLUETOOTH) LinkChoice.BLUETOOTH else null
                    currentPcName = if (linkPreference == LinkChoice.BLUETOOTH) bluetoothStatus.destination else ""
                    releaseWifiLock()
                    pcInfo = ""
                    pcLatency = null
                    transport = null
                    pendingPing = 0L
                    updateNotification()
                    notifyChanged()
                }
            }
        }
        return responded
    }

    private fun acquireWifiLock() {
        if (wifiLocks.isNotEmpty()) return
        val manager = applicationContext.getSystemService(WifiManager::class.java)
        // Alto rendimiento conserva el caudal también en segundo plano antes de Android 14.
        // Desde API 34 Android lo reemplaza por baja latencia: pedimos directamente ese único lock.
        val kind = if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WIFI_MODE_FULL_HIGH_PERF
        wifiLocks = listOf(manager.createWifiLock(kind, "soundshare-control").apply {
            setReferenceCounted(false)
            acquire()
        })
    }

    private fun releaseWifiLock() {
        wifiLocks.forEach { if (it.isHeld) it.release() }
        wifiLocks = emptyList()
    }

    private fun handleLine(line: String) {
        val space = line.indexOf(' ')
        val cmd = if (space < 0) line else line.substring(0, space)
        val arg = if (space < 0) "" else line.substring(space + 1)
        if (linkPreference == LinkChoice.BLUETOOTH) {
            // CONTROL sigue abierto solo para confirmar la detención; los STATE/adverts viejos no reabren USB/WiFi.
            if (cmd == "STATE" && Mode.parse(arg) == Mode.OFF) {
                offAck?.countDown()
                bluetoothOffAck?.countDown()
            }
            return
        }
        when (cmd) {
            "LAN_PAIRED" -> if (controlEndpoint?.wifi != true) LanProtocol.parsePairing(line)?.let {
                lan.pair(it)
                hasPairedPc = true
            }
            "CAPTURE" -> if (arg == "app" && controlEndpoint?.wifi == true) requestCapture()
            "PLAYER_TRANSPORT" -> if (controlEndpoint?.wifi != true) TransportCandidate.parse(arg)?.let { candidate ->
                synchronized(this) {
                    playerCandidate = candidate
                    val useAoa = shouldUseAoa()
                    if (useAoa) main.post { if (running && shouldUseAoa()) accessory.prepare() }
                    if (player != null && playerUsesAoa != useAoa) restartPlayer()
                }
            }
            "STATE" -> Mode.parse(arg)?.let {
                applyMode(it)
                if (it == Mode.OFF) { offAck?.countDown(); bluetoothOffAck?.countDown() }
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
        if (m != Mode.OFF && (!running || quitting || !LinkSelection.native(linkPreference))) return
        if (m != mode) {
            pcLatency = null
            pcInfo = ""
            if (m == Mode.OFF) transport = null
        }
        if (m != Mode.PHONE_TO_PC) {
            captureRequested = false
            captureConsentNeeded = false
            stopCapture()
        }
        if (m != Mode.PC_TO_PHONE) {
            stopPlayer()
            playerStats = null
        }
        if (m == Mode.PC_TO_PHONE && player == null) {
            playerUsesAoa = shouldUseAoa()
            player = Player(this, if (playerUsesAoa) accessory else null, latencyProfile, controlEndpoint ?: LinkEndpoint()) { stats ->
                playerStats = stats
                notifyChanged()
            }.also { it.start() }
        }
        if (m != Mode.OFF) lastError = ""
        mode = m
        if (m == Mode.PHONE_TO_PC && captureRequested) startCaptureIfReady()
        updateNotification()
    }

    @Synchronized private fun stopPlayer() {
        player?.stop()
        player = null
        playerStats = null
    }

    @Synchronized private fun requestCapture() {
        if (!running || quitting || !LinkSelection.native(linkPreference) || controlEndpoint?.wifi != true) return
        captureRequested = true
        if (Build.VERSION.SDK_INT < 29) {
            captureFailed("La captura por WiFi necesita Android 10 o posterior")
            return
        }
        if (projection != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCaptureIfReady()
        } else {
            captureConsentNeeded = true
            updateNotification()
            notifyChanged()
        }
    }

    /** La actividad solicita los permisos; la proyección pertenece al servicio, no a la pantalla. */
    @Synchronized private fun beginConsent(): Boolean {
        if (!running || !captureConsentNeeded || consentInFlight) return false
        consentInFlight = true
        return true
    }

    @Synchronized private fun acceptProjection(resultCode: Int, data: Intent?) {
        if (!running || !consentInFlight) return
        consentInFlight = false
        if (Build.VERSION.SDK_INT < 29 || resultCode != Activity.RESULT_OK || data == null) {
            captureFailed("No se autorizó la captura de audio")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            captureFailed("Falta el permiso para grabar audio")
            return
        }
        try {
            // Android 14 exige el tipo mediaProjection antes de getMediaProjection(), con consentimiento ya dado.
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val granted = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("Android no concedió la proyección")
            projection = granted
            granted.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    synchronized(this@LinkService) {
                        if (projection !== granted) return
                        projection = null
                        captureFailed("Android detuvo la captura de audio")
                        startInForeground()
                    }
                }
            }, main)
            captureConsentNeeded = false
            startCaptureIfReady()
            updateNotification()
            notifyChanged()
        } catch (e: Exception) {
            projection?.stop()
            projection = null
            captureFailed(e.message ?: "No se pudo preparar la captura de audio")
            startInForeground()
        }
    }

    @Synchronized private fun startCaptureIfReady() {
        if (!running || quitting || mode != Mode.PHONE_TO_PC || !captureRequested || capture != null) return
        if (Build.VERSION.SDK_INT < 29) return
        val endpoint = controlEndpoint?.takeIf { it.wifi } ?: return
        val granted = projection ?: return
        val session = controlRun ?: return
        val sessionOut = controlOut ?: return
        lateinit var started: AppCapture
        started = AppCapture(endpoint, granted, { milliseconds ->
            // No tomamos el monitor del servicio desde el hilo de audio mientras stop() espera su cierre.
            if (session.active && controlRun === session) send("CAPTURE_LATENCY $milliseconds", sessionOut)
        }, { reason ->
            main.post {
                synchronized(this) {
                    if (capture === started && session.active && controlRun === session) captureFailed(reason)
                }
            }
        })
        capture = started
        started.start()
    }

    @Synchronized private fun captureFailed(reason: String) {
        if (captureRequested && controlEndpoint?.wifi == true) {
            // Los errores de Android pueden traer saltos de línea; CONTROL siempre lleva una sola línea.
            send("CAPTURE_FAILED ${reason.replace('\n', ' ').replace('\r', ' ').take(1000)}")
        }
        captureRequested = false
        captureConsentNeeded = false
        stopCapture()
        updateNotification()
        notifyChanged()
    }

    @Synchronized private fun stopCapture() {
        capture?.stop()
        capture = null
    }

    private fun send(line: String, expectedOut: OutputStream? = controlOut) {
        val out = expectedOut ?: return
        try {
            writer.execute {
                try {
                    synchronized(out) {
                        if (controlOut !== out || !running) return@execute
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
        if (!connected || (mode == Mode.OFF && currentLink != LinkChoice.WIFI) || quitting || pingQueued) return
        if (currentLink != LinkChoice.WIFI && pendingPing != 0L && System.nanoTime() - pendingPing < 3_000_000_000L) return
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
            linkPreference == LinkChoice.BLUETOOTH -> when {
                bluetoothStatus.playing -> getString(R.string.bluetooth_playing, bluetoothStatus.destination)
                bluetoothStatus.connected -> getString(R.string.bluetooth_connected, bluetoothStatus.destination)
                else -> getString(R.string.bluetooth_selected)
            }
            captureConsentNeeded -> getString(R.string.capture_consent_notification)
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
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                (if (projection != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        main.post {
            if (running) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun refreshBluetoothMonitor() {
        main.post {
            if (instance !== this || !running) return@post
            if (linkPreference == LinkChoice.BLUETOOTH) bluetooth.start() else bluetooth.stop()
        }
    }

    companion object {
        private const val TAG = "SoundShare.Link"
        private const val CHANNEL_ID = "stream"
        private const val NOTIF_ID = 1
        /** Obsoleto desde Android 14 pero vigente en los anteriores; el valor no cambia. */
        @Suppress("DEPRECATION") private const val WIFI_MODE_FULL_HIGH_PERF = WifiManager.WIFI_MODE_FULL_HIGH_PERF
        @Volatile var linkPreference = LinkChoice.AUTO
            private set
        @Volatile var currentLink: LinkChoice? = null
            private set
        @Volatile var currentPcName = ""
            private set
        @Volatile var hasPairedPc = false
            private set
        @Volatile var captureConsentNeeded = false
            private set
        @Volatile var activityVisible = false
        @Volatile var bluetoothStatus = BluetoothStatus(BluetoothStatus.Phase.CHECKING)
            private set

        fun bluetoothPermissionChanged() { instance?.refreshBluetoothMonitor() }

        fun beginCaptureConsent() = instance?.beginConsent() ?: false
        fun captureConsentResult(resultCode: Int, data: Intent?) { instance?.acceptProjection(resultCode, data) }
        fun capturePermissionDenied() {
            instance?.let { service ->
                synchronized(service) {
                    service.consentInFlight = false
                    service.captureFailed("No se concedió el permiso para grabar audio")
                }
            }
        }

        fun requestLink(context: Context, choice: LinkChoice) {
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString("link", choice.wire).apply()
            instance?.selectLink(choice) ?: run { linkPreference = choice }
        }

        const val ACTION_QUIT = "com.soundshare.QUIT"

        @Volatile var transportPreference = TransportChoice.AOA
            private set
        @Volatile var playerCandidate = TransportCandidate(TransportChoice.ADB)
            private set
        @Volatile var accessoryState = AccessoryState.UNAVAILABLE
            private set

        @Volatile var latencyProfile = LatencyProfile.STABLE
            private set

        /** Cambiar el perfil reinicia solo PLAYER; la PC no participa. */
        fun requestLatency(context: Context, profile: LatencyProfile) {
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString("latency", profile.wire).apply()
            instance?.selectLatency(profile) ?: run { latencyProfile = profile }
        }

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
            // Por WiFi CONTROL y PCM recorren la misma red: usamos siempre el RTT real.
            // El PING de adb no representa a AOA, cuya entrega ya está en la cola medida del teléfono.
            val rtt = if (currentLink != LinkChoice.WIFI && player?.transportName == "aoa") 0.0
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
            if (LinkSelection.native(linkPreference)) instance?.send("MODE ${m.wire}")
        }

        fun addListener(l: () -> Unit) = listeners.add(l)
        fun removeListener(l: () -> Unit) = listeners.remove(l)

        private fun notifyChanged() {
            main.post { listeners.forEach { it() } }
        }
    }
}
