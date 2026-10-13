package com.soundshare

/** Mediciones por etapa; los valores ausentes nunca se presentan como cero medido. */
object LatencyEstimate {
    enum class Missing { PC, CAPTURE, OUTPUT, USB }
    data class Pc(val mode: Mode, val queueMs: Double?, val deviceMs: Double?, val captureMs: Double?)
    data class Value(val milliseconds: Double?, val missing: Set<Missing>)

    fun parsePc(text: String): Pc? {
        val fields = text.trim().split(Regex("\\s+"))
        if (fields.size != 4) return null
        val mode = Mode.parse(fields[0])?.takeUnless { it == Mode.OFF } ?: return null
        val numbers = fields.drop(1).map { it.toDoubleOrNull() ?: return null }
        if (numbers.any { !it.isFinite() || (it != -1.0 && it !in 0.0..5000.0) }) return null
        return Pc(mode, numbers[0].takeIf { it >= 0 }, numbers[1].takeIf { it >= 0 }, numbers[2].takeIf { it >= 0 })
    }

    fun calculate(mode: Mode, pc: Pc?, phoneQueueMs: Double?, phoneOutputMs: Double?,
                  socketMs: Double, roundTripMs: Double?): Value {
        if (mode == Mode.OFF) return Value(null, emptySet())
        val missing = linkedSetOf<Missing>()
        var total = 0.0
        var measurements = 0
        fun add(value: Double?, component: Missing) {
            if (value != null && value.isFinite() && value >= 0) {
                total += value
                measurements++
            } else missing += component
        }
        val current = pc?.takeIf { it.mode == mode }
        add(current?.queueMs, Missing.PC)
        add(current?.deviceMs, Missing.PC)
        if (mode == Mode.PHONE_TO_PC) {
            add(current?.captureMs, Missing.CAPTURE)
        } else if (phoneOutputMs != null) {
            // AudioTrack ya incluye su cola: no volvemos a sumarla. El socket está antes de la pista.
            add(phoneOutputMs + socketMs.coerceAtLeast(0.0), Missing.OUTPUT)
        } else {
            add(phoneQueueMs, Missing.OUTPUT)
            missing += Missing.OUTPUT
        }
        // Aproximación de un sentido, suponiendo transporte simétrico. No es latencia acústica.
        val audioMeasurements = measurements
        add(roundTripMs?.div(2), Missing.USB)
        return Value(total.takeIf { audioMeasurements > 0 }, missing)
    }

    fun captureAgeMs(rate: Int, framesRead: Long, position: Long, timestampNs: Long, nowNs: Long): Double? {
        if (rate <= 0 || framesRead < 0 || position < 0 || timestampNs <= 0 || nowNs < timestampNs) return null
        if (nowNs - timestampNs > 1_000_000_000L) return null
        val age = (nowNs - timestampNs) / 1e6 - (framesRead - position) * 1000.0 / rate
        return age.takeIf { it.isFinite() && it in 0.0..5000.0 }
    }
}
