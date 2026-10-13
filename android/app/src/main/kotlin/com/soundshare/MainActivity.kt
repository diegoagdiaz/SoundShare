package com.soundshare

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusHint: TextView
    private lateinit var statsText: TextView
    private lateinit var btnPcToPhone: View
    private lateinit var btnPhoneToPc: View
    private lateinit var btnStop: View
    private lateinit var latencyCard: View
    private lateinit var latencyValue: TextView
    private lateinit var latencyHint: TextView
    private lateinit var transportValue: TextView
    private lateinit var transportHint: TextView
    private lateinit var btnTransportAoa: TextView
    private lateinit var btnTransportAdb: TextView
    private lateinit var transportCard: View
    private lateinit var linkValue: TextView
    private lateinit var linkHint: TextView
    private lateinit var btnLinkAuto: TextView
    private lateinit var btnLinkUsb: TextView
    private lateinit var btnLinkWifi: TextView
    private lateinit var phoneToPcHint: TextView
    private lateinit var pcToPhoneHint: TextView
    private lateinit var bluetoothCard: View
    private lateinit var bluetoothHint: TextView
    private lateinit var btnBluetooth: TextView
    private lateinit var profileCard: View
    private var captureFlow = false
    private var notificationPermissionPending = false
    private var openingBluetoothSettings = false
    private var bluetoothPermissionPending = false
    private lateinit var profileHint: TextView
    private lateinit var btnProfileStable: TextView
    private lateinit var btnProfileMinimal: TextView

    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureFlow = savedInstanceState?.getBoolean("capture_flow") ?: false
        notificationPermissionPending = savedInstanceState?.getBoolean("notification_permission_pending") ?: false
        bluetoothPermissionPending = savedInstanceState?.getBoolean("bluetooth_permission_pending") ?: false
        setContentView(R.layout.activity_main)
        transportCard = findViewById(R.id.transportCard)
        linkValue = findViewById(R.id.linkValue)
        linkHint = findViewById(R.id.linkHint)
        btnLinkAuto = findViewById(R.id.btnLinkAuto)
        btnLinkUsb = findViewById(R.id.btnLinkUsb)
        btnLinkWifi = findViewById(R.id.btnLinkWifi)
        phoneToPcHint = findViewById(R.id.phoneToPcHint)
        pcToPhoneHint = findViewById(R.id.pcToPhoneHint)
        bluetoothCard = findViewById(R.id.bluetoothCard)
        bluetoothHint = findViewById(R.id.bluetoothHint)
        btnBluetooth = findViewById(R.id.btnBluetooth)
        profileCard = findViewById(R.id.profileCard)
        btnLinkAuto.setOnClickListener { LinkService.requestLink(this, LinkChoice.AUTO) }
        btnLinkUsb.setOnClickListener { LinkService.requestLink(this, LinkChoice.USB) }
        btnLinkWifi.setOnClickListener { LinkService.requestLink(this, LinkChoice.WIFI) }
        btnBluetooth.setOnClickListener {
            if (LinkService.linkPreference == LinkChoice.BLUETOOTH) openBluetoothSettings()
            else {
                LinkService.requestLink(this, LinkChoice.BLUETOOTH)
                requestBluetoothPermission()
                render()
            }
        }
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        statusHint = findViewById(R.id.statusHint)
        statsText = findViewById(R.id.statsText)
        btnPcToPhone = findViewById(R.id.btnPcToPhone)
        btnPhoneToPc = findViewById(R.id.btnPhoneToPc)
        btnStop = findViewById(R.id.btnStop)
        latencyCard = findViewById(R.id.latencyCard)
        latencyValue = findViewById(R.id.latencyValue)
        latencyHint = findViewById(R.id.latencyHint)
        transportValue = findViewById(R.id.transportValue)
        transportHint = findViewById(R.id.transportHint)
        btnTransportAoa = findViewById(R.id.btnTransportAoa)
        btnTransportAdb = findViewById(R.id.btnTransportAdb)
        btnTransportAoa.setOnClickListener { LinkService.requestTransport(this, TransportChoice.AOA) }
        btnTransportAdb.setOnClickListener { LinkService.requestTransport(this, TransportChoice.ADB) }
        profileHint = findViewById(R.id.profileHint)
        btnProfileStable = findViewById(R.id.btnProfileStable)
        btnProfileMinimal = findViewById(R.id.btnProfileMinimal)
        btnProfileStable.setOnClickListener { LinkService.requestLatency(this, LatencyProfile.STABLE); render() }
        btnProfileMinimal.setOnClickListener { LinkService.requestLatency(this, LatencyProfile.MINIMAL); render() }

        // Tocar el modo activo lo apaga; tocar el otro cambia de dirección.
        btnPcToPhone.setOnClickListener { toggle(Mode.PC_TO_PHONE) }
        btnPhoneToPc.setOnClickListener {
            if (LinkService.linkPreference == LinkChoice.BLUETOOTH) openBluetoothSettings() else toggle(Mode.PHONE_TO_PC)
        }
        btnStop.setOnClickListener { LinkService.requestMode(Mode.OFF) }

        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionPending &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionPending = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        LinkService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) LinkService.accessoryAttached()
    }

    override fun onStart() {
        super.onStart()
        LinkService.activityVisible = true
        LinkService.addListener(listener)
        render()
    }

    override fun onStop() {
        LinkService.activityVisible = false
        LinkService.removeListener(listener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        openingBluetoothSettings = false
        LinkService.bluetoothPermissionChanged()
        requestCaptureConsent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("capture_flow", captureFlow)
        outState.putBoolean("notification_permission_pending", notificationPermissionPending)
        outState.putBoolean("bluetooth_permission_pending", bluetoothPermissionPending)
        super.onSaveInstanceState(outState)
    }

    private fun requestCaptureConsent() {
        if (Build.VERSION.SDK_INT < 29 || captureFlow || notificationPermissionPending || bluetoothPermissionPending || openingBluetoothSettings ||
            !LinkService.activityVisible ||
            !LinkService.captureConsentNeeded || !LinkService.beginCaptureConsent()) return
        captureFlow = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST)
        } else openProjectionConsent()
    }

    private fun openProjectionConsent() {
        try {
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), PROJECTION_REQUEST)
        } catch (_: Exception) {
            captureFlow = false
            LinkService.captureConsentResult(RESULT_CANCELED, null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            notificationPermissionPending = false
            if (LinkService.linkPreference == LinkChoice.BLUETOOTH) requestBluetoothPermission()
            requestCaptureConsent()
            return
        }
        if (requestCode == BLUETOOTH_REQUEST) {
            bluetoothPermissionPending = false
            LinkService.bluetoothPermissionChanged()
            render()
            return
        }
        if (requestCode != RECORD_AUDIO_REQUEST) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) openProjectionConsent()
        else {
            captureFlow = false
            LinkService.capturePermissionDenied()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_REQUEST) {
            captureFlow = false
            LinkService.captureConsentResult(resultCode, data)
        }
    }

    private fun toggle(m: Mode) {
        LinkService.requestMode(if (LinkService.mode == m) Mode.OFF else m)
    }

    private fun openBluetoothSettings() {
        // La elección Bluetooth ya detuvo el enlace nativo; Android administra la salida de audio.
        openingBluetoothSettings = true
        for (action in listOf(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action))
                if (action == Settings.ACTION_SETTINGS) {
                    Toast.makeText(this, R.string.bluetooth_settings_fallback, Toast.LENGTH_SHORT).show()
                }
                return
            } catch (_: ActivityNotFoundException) {
                // Algunos fabricantes no publican una pantalla Bluetooth independiente.
            } catch (_: SecurityException) {
                // Si el acceso directo está restringido, probamos la pantalla general de Ajustes.
            }
        }
        openingBluetoothSettings = false
        Toast.makeText(this, R.string.bluetooth_settings_unavailable, Toast.LENGTH_LONG).show()
        requestCaptureConsent()
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && !notificationPermissionPending && !bluetoothPermissionPending &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermissionPending = true
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_REQUEST)
        }
    }

    private fun bluetoothDescription(status: BluetoothStatus): String = when (status.phase) {
        BluetoothStatus.Phase.PLAYING -> getString(R.string.bluetooth_playing, status.destination)
        BluetoothStatus.Phase.CONNECTED -> getString(R.string.bluetooth_connected, status.destination)
        BluetoothStatus.Phase.PERMISSION_REQUIRED -> getString(R.string.bluetooth_permission_missing)
        BluetoothStatus.Phase.DISABLED -> getString(R.string.bluetooth_disabled)
        BluetoothStatus.Phase.UNAVAILABLE -> getString(R.string.bluetooth_unavailable)
        BluetoothStatus.Phase.DISCONNECTED -> getString(R.string.bluetooth_disconnected)
        BluetoothStatus.Phase.CHECKING -> getString(R.string.bluetooth_checking)
    }

    private fun bluetoothInstructions(status: BluetoothStatus): String = when {
        status.playing -> getString(R.string.bluetooth_playing_hint, status.destination)
        status.connected -> getString(R.string.bluetooth_connected_hint)
        status.phase == BluetoothStatus.Phase.PERMISSION_REQUIRED -> getString(R.string.bluetooth_permission_hint)
        else -> getString(R.string.bluetooth_disconnected_hint)
    }

    private fun render() {
        if (LinkService.quitRequested) {
            finish()
            return
        }
        val connected = LinkService.connected
        val mode = LinkService.mode
        val wifi = LinkService.currentLink == LinkChoice.WIFI
        val bt = LinkService.linkPreference == LinkChoice.BLUETOOTH
        val bluetooth = LinkService.bluetoothStatus
        bluetoothCard.isSelected = bt
        bluetoothCard.setBackgroundResource(if (bt) R.drawable.card_active else R.drawable.card)
        btnBluetooth.text = getString(if (bt) R.string.bluetooth_settings else R.string.bluetooth_open)
        bluetoothHint.text = if (bt) bluetoothInstructions(bluetooth) else getString(R.string.bluetooth_hint)
        linkValue.text = if (bt) getString(R.string.bluetooth_selected) else when (LinkService.currentLink) {
            LinkChoice.USB -> getString(R.string.link_usb)
            LinkChoice.WIFI -> getString(R.string.link_current_wifi, LinkService.currentPcName)
            else -> getString(R.string.link_waiting)
        }
        linkHint.text = if (bt) bluetoothDescription(bluetooth) else getString(when {
            !LinkService.hasPairedPc -> R.string.link_pair_hint
            LinkService.linkPreference == LinkChoice.USB -> R.string.link_usb_hint
            LinkService.linkPreference == LinkChoice.WIFI -> R.string.link_wifi_hint
            else -> R.string.link_auto_hint
        })
        for ((view, choice) in listOf(btnLinkAuto to LinkChoice.AUTO, btnLinkUsb to LinkChoice.USB, btnLinkWifi to LinkChoice.WIFI)) {
            view.isSelected = LinkService.linkPreference == choice
            view.setBackgroundResource(if (view.isSelected) R.drawable.card_active else R.drawable.card)
            view.contentDescription = if (view.isSelected) getString(R.string.transport_selected, view.text) else view.text
        }
        transportCard.visibility = if (bt || wifi || LinkService.linkPreference == LinkChoice.WIFI) View.GONE else View.VISIBLE
        profileCard.visibility = if (bt) View.GONE else View.VISIBLE
        pcToPhoneHint.text = getString(if (bt) R.string.bluetooth_pc_to_phone_hint else R.string.pc_to_phone_hint)
        phoneToPcHint.text = getString(if (bt) R.string.bluetooth_phone_to_pc_hint else if (wifi) R.string.phone_to_pc_wifi_hint else R.string.phone_to_pc_hint)

        statusText.text = if (bt) bluetoothDescription(bluetooth) else getString(if (connected) R.string.status_connected else R.string.status_waiting)
        statusDot.backgroundTintList = ColorStateList.valueOf(
            getColor(
                when {
                    bt && bluetooth.playing -> R.color.accent
                    bt && bluetooth.connected -> R.color.ok
                    bt -> R.color.warn
                    !connected -> R.color.warn
                    mode != Mode.OFF -> R.color.accent
                    else -> R.color.ok
                },
            ),
        )
        statusHint.text = if (bt) bluetoothInstructions(bluetooth) else when {
            !connected -> getString(if (LinkService.hasPairedPc && LinkService.linkPreference != LinkChoice.USB)
                R.string.status_waiting_wifi_hint else R.string.status_waiting_hint)
            LinkService.lastError.isNotEmpty() -> LinkService.lastError
            mode == Mode.PC_TO_PHONE -> getString(R.string.status_pc_to_phone)
            mode == Mode.PHONE_TO_PC -> getString(R.string.status_phone_to_pc)
            else -> getString(R.string.status_idle)
        }

        for ((view, m) in listOf(btnPcToPhone to Mode.PC_TO_PHONE, btnPhoneToPc to Mode.PHONE_TO_PC)) {
            view.isEnabled = if (bt) m == Mode.PHONE_TO_PC else connected
            view.alpha = if (view.isEnabled) 1f else 0.45f
            view.setBackgroundResource(if ((bt && m == Mode.PHONE_TO_PC && bluetooth.playing) || (!bt && mode == m)) R.drawable.card_active else R.drawable.card)
        }
        btnStop.visibility = if (!bt && connected && mode != Mode.OFF) View.VISIBLE else View.INVISIBLE

        latencyCard.visibility = if (!bt && connected && mode != Mode.OFF) View.VISIBLE else View.GONE
        val latency = LinkService.latencyEstimate()
        latencyValue.text = latency.milliseconds?.let { getString(R.string.latency_value, it.roundToInt()) }
            ?: getString(R.string.latency_measuring)
        latencyHint.text = if (latency.missing.isEmpty()) getString(if (wifi) R.string.latency_complete_wifi else R.string.latency_complete) else {
            val missing = latency.missing.joinToString(", ") {
                getString(when (it) {
                    LatencyEstimate.Missing.PC -> R.string.latency_missing_pc
                    LatencyEstimate.Missing.CAPTURE -> R.string.latency_missing_capture
                    LatencyEstimate.Missing.OUTPUT -> R.string.latency_missing_output
                    LatencyEstimate.Missing.USB -> if (wifi) R.string.latency_missing_wifi else R.string.latency_missing_usb
                })
            }
            getString(R.string.latency_partial, missing)
        }

        val preference = LinkService.transportPreference
        val playing = connected && mode == Mode.PC_TO_PHONE
        val actual = LinkService.playerStats?.transportName?.takeIf { playing }
        transportValue.text = when {
            !playing -> getString(R.string.transport_inactive)
            actual != null -> getString(R.string.transport_actual, when (actual) { "wifi" -> "WiFi"; "aoa" -> "AOA"; else -> "adb" })
            else -> getString(R.string.transport_connecting)
        }
        val hint = TransportSelection.hint(preference, LinkService.playerCandidate,
            LinkService.accessoryState, connected, playing, actual)
        transportHint.text = getString(when (hint) {
            TransportSelection.Hint.WAITING -> R.string.transport_hint_waiting
            TransportSelection.Hint.IDLE -> R.string.transport_hint_idle
            TransportSelection.Hint.CONNECTING -> R.string.transport_hint_connecting
            TransportSelection.Hint.ACTIVE -> R.string.transport_hint_active
            TransportSelection.Hint.SELECTED_ADB -> R.string.transport_hint_adb
            TransportSelection.Hint.PERMISSION_REQUIRED -> R.string.transport_hint_permission
            TransportSelection.Hint.PERMISSION_DENIED -> R.string.transport_hint_denied
            TransportSelection.Hint.UNAVAILABLE -> R.string.transport_hint_unavailable
            TransportSelection.Hint.FAILED -> R.string.transport_hint_failed
            TransportSelection.Hint.DISCONNECTED -> R.string.transport_hint_disconnected
            TransportSelection.Hint.RECONNECT -> R.string.transport_hint_reconnect
        })
        for ((view, choice) in listOf(btnTransportAoa to TransportChoice.AOA, btnTransportAdb to TransportChoice.ADB)) {
            view.isSelected = preference == choice
            view.setBackgroundResource(if (view.isSelected) R.drawable.card_active else R.drawable.card)
            view.contentDescription = if (view.isSelected) getString(R.string.transport_selected, view.text) else view.text
        }

        val profile = LinkService.latencyProfile
        profileHint.text = getString(if (profile == LatencyProfile.MINIMAL) R.string.profile_hint_minimal else R.string.profile_hint_stable)
        for ((view, p) in listOf(btnProfileStable to LatencyProfile.STABLE, btnProfileMinimal to LatencyProfile.MINIMAL)) {
            view.isSelected = profile == p
            view.setBackgroundResource(if (view.isSelected) R.drawable.card_active else R.drawable.card)
            view.contentDescription = if (view.isSelected) getString(R.string.transport_selected, view.text) else view.text
        }

        val lines = mutableListOf<String>()
        LinkService.playerStats?.let { s ->
            lines += "cola        %5.1f ms  (objetivo %.1f%s)".format(s.queuedMs, s.targetMs, if (s.minimal) ", fijo" else "")
            if (s.outputLatencyFrames >= 0) lines += "salida      %5.1f ms".format(s.outputLatencyMs)
            lines += "cortes      %5d".format(s.underruns)
            lines += "pista       %s".format(if (s.fastTrack) "baja latencia" else "normal")
        }
        // PC → Móvil ya muestra las medidas locales; el INFO antiguo trae otra cifra de latencia parcial.
        if (mode != Mode.PC_TO_PHONE && LinkService.pcInfo.isNotEmpty()) lines += LinkService.pcInfo
        statsText.text = if (bt) "" else lines.joinToString("\n")
        requestCaptureConsent()
    }

    companion object {
        private const val RECORD_AUDIO_REQUEST = 2
        private const val PROJECTION_REQUEST = 3
        private const val BLUETOOTH_REQUEST = 4
    }
}
