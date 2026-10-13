package com.soundshare

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** Observa el perfil público de salida de audio; Android se encarga de conectarlo y elegir la ruta. */
internal class BluetoothMonitor(private val context: Context, private val changed: (BluetoothStatus) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var started = false
    private var session: PlayerGeneration? = null
    private var proxy: BluetoothA2dp? = null
    private var last: BluetoothStatus? = null
    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (started) { last = null; refresh(); return }
        started = true
        refresh()
        main.postDelayed(poll, 1000)
    }

    fun stop() {
        started = false
        main.removeCallbacks(poll)
        release()
        last = null
    }

    private fun release() {
        proxy = null
        session?.cancel()
        session = null
    }

    private fun publish(value: BluetoothStatus) {
        if (last != value) { last = value; changed(value) }
    }

    private fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // La comprobación se repite también después de cada callback asíncrono.
    private fun refresh() {
        if (!started) return
        if (!hasPermission()) {
            release()
            publish(BluetoothStatus(BluetoothStatus.Phase.PERMISSION_REQUIRED))
            return
        }
        val bluetooth = adapter
        if (bluetooth == null) {
            publish(BluetoothStatus(BluetoothStatus.Phase.UNAVAILABLE))
            return
        }
        try {
            if (!bluetooth.isEnabled) {
                release()
                publish(BluetoothStatus(BluetoothStatus.Phase.DISABLED))
                return
            }
            val connected = proxy
            if (connected != null) {
                publish(BluetoothStatus.fromA2dp(connected.connectedDevices.map { device ->
                    BluetoothStatus.Destination(device.name?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.bluetooth_device), connected.isA2dpPlaying(device))
                }))
                return
            }
            if (session != null) return
            val run = PlayerGeneration().also { session = it }
            publish(BluetoothStatus(BluetoothStatus.Phase.CHECKING))
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, value: BluetoothProfile) {
                    main.post {
                        // Si cambió el enlace o murió el servicio mientras Android respondía, cerramos el proxy tardío.
                        if (!run.attach(Closeable { bluetooth.closeProfileProxy(profile, value) })) return@post
                        if (!started || session !== run || !hasPermission() || value !is BluetoothA2dp) {
                            run.cancel()
                            return@post
                        }
                        proxy = value
                        refresh()
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    main.post {
                        if (started && session === run) {
                            release()
                            publish(BluetoothStatus(BluetoothStatus.Phase.CHECKING))
                        }
                    }
                }
            }
            if (!bluetooth.getProfileProxy(context, listener, BluetoothProfile.A2DP)) {
                release()
                publish(BluetoothStatus(BluetoothStatus.Phase.UNAVAILABLE))
            }
        } catch (_: SecurityException) {
            release()
            publish(BluetoothStatus(BluetoothStatus.Phase.PERMISSION_REQUIRED))
        } catch (_: RuntimeException) {
            release()
            publish(BluetoothStatus(BluetoothStatus.Phase.UNAVAILABLE))
        }
    }
}
