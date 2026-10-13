package com.soundshare

/** Cuánto colchón tiene el audio PC → Móvil. Se elige en la app y queda guardado. */
enum class LatencyProfile(val wire: String) {
    /** Cola adaptativa: cubre el peor hueco reciente del USB para no cortar. */
    STABLE("stable"),
    /** Sin colchón para los huecos: suena lo último que llegó, aunque haya cortes. */
    MINIMAL("minimal");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.wire == value }

        /** Lo que la PC manda por ciclo de PipeWire (SINK_QUANTUM en pc/src/engine.rs). */
        const val PC_BLOCK_FRAMES = 128

        /**
         * Piso del modo mínimo: con entrega perfecta alcanzan medio burst del mezclador más un bloque
         * de la PC (el nivel medido tras cada llegada queda medio burst por encima del mínimo real).
         */
        fun minimalFrames(burst: Int) = burst / 2 + PC_BLOCK_FRAMES
    }
}

/**
 * Latencia mínima: si en toda una ventana la cola nunca bajó a su nivel habitual, lo que sobra es
 * audio viejo (lo que se juntó durante un hueco del USB). Devuelve cuántos frames descartar para
 * volver a sonar lo último que llegó.
 *
 * `queued` se mide justo después de cada llegada. Con la PC sosteniendo el promedio en el objetivo,
 * esas mediciones van de `objetivo - burst/2` a `objetivo + burst/2`, según dónde cayó la última
 * lectura del mezclador; el mínimo de la ventana queda cerca del extremo bajo.
 */
class ExcessTrim(private val burst: Int, private val windowNs: Long = WINDOW_NS) {
    private var windowStart = 0L
    private var lowest = Int.MAX_VALUE

    fun update(queued: Int, target: Int, now: Long): Int {
        if (windowStart == 0L) windowStart = now
        lowest = minOf(lowest, queued)
        if (now - windowStart < windowNs) return 0
        val excess = lowest - (target - burst / 2)
        windowStart = now
        lowest = Int.MAX_VALUE
        return if (excess > burst / 2) excess else 0
    }

    companion object {
        const val WINDOW_NS = 100_000_000L
    }
}
