package com.soundshare

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
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

    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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

        // Tocar el modo activo lo apaga; tocar el otro cambia de dirección.
        btnPcToPhone.setOnClickListener { toggle(Mode.PC_TO_PHONE) }
        btnPhoneToPc.setOnClickListener { toggle(Mode.PHONE_TO_PC) }
        btnStop.setOnClickListener { LinkService.requestMode(Mode.OFF) }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        LinkService.start(this)
    }

    override fun onStart() {
        super.onStart()
        LinkService.addListener(listener)
        render()
    }

    override fun onStop() {
        LinkService.removeListener(listener)
        super.onStop()
    }

    private fun toggle(m: Mode) {
        LinkService.requestMode(if (LinkService.mode == m) Mode.OFF else m)
    }

    private fun render() {
        if (LinkService.quitRequested) {
            finish()
            return
        }
        val connected = LinkService.connected
        val mode = LinkService.mode

        statusText.text = getString(if (connected) R.string.status_connected else R.string.status_waiting)
        statusDot.backgroundTintList = ColorStateList.valueOf(
            getColor(
                when {
                    !connected -> R.color.warn
                    mode != Mode.OFF -> R.color.accent
                    else -> R.color.ok
                },
            ),
        )
        statusHint.text = when {
            !connected -> getString(R.string.status_waiting_hint)
            LinkService.lastError.isNotEmpty() -> LinkService.lastError
            mode == Mode.PC_TO_PHONE -> getString(R.string.status_pc_to_phone)
            mode == Mode.PHONE_TO_PC -> getString(R.string.status_phone_to_pc)
            else -> getString(R.string.status_idle)
        }

        for ((view, m) in listOf(btnPcToPhone to Mode.PC_TO_PHONE, btnPhoneToPc to Mode.PHONE_TO_PC)) {
            view.isEnabled = connected
            view.alpha = if (connected) 1f else 0.45f
            view.setBackgroundResource(if (mode == m) R.drawable.card_active else R.drawable.card)
        }
        btnStop.visibility = if (connected && mode != Mode.OFF) View.VISIBLE else View.INVISIBLE

        latencyCard.visibility = if (connected && mode != Mode.OFF) View.VISIBLE else View.GONE
        val latency = LinkService.latencyEstimate()
        latencyValue.text = latency.milliseconds?.let { getString(R.string.latency_value, it.roundToInt()) }
            ?: getString(R.string.latency_measuring)
        latencyHint.text = if (latency.missing.isEmpty()) getString(R.string.latency_complete) else {
            val missing = latency.missing.joinToString(", ") {
                getString(when (it) {
                    LatencyEstimate.Missing.PC -> R.string.latency_missing_pc
                    LatencyEstimate.Missing.CAPTURE -> R.string.latency_missing_capture
                    LatencyEstimate.Missing.OUTPUT -> R.string.latency_missing_output
                    LatencyEstimate.Missing.USB -> R.string.latency_missing_usb
                })
            }
            getString(R.string.latency_partial, missing)
        }

        val lines = mutableListOf<String>()
        LinkService.playerStats?.let { s ->
            lines += "cola        %5.1f ms  (objetivo %.1f)".format(s.queuedMs, s.targetMs)
            if (s.outputLatencyFrames >= 0) lines += "salida      %5.1f ms".format(s.outputLatencyMs)
            lines += "cortes      %5d".format(s.underruns)
            lines += "pista       %s".format(if (s.fastTrack) "baja latencia" else "normal")
        }
        // PC → Móvil ya muestra las medidas locales; el INFO antiguo trae otra cifra de latencia parcial.
        if (mode != Mode.PC_TO_PHONE && LinkService.pcInfo.isNotEmpty()) lines += LinkService.pcInfo
        statsText.text = lines.joinToString("\n")
    }
}
