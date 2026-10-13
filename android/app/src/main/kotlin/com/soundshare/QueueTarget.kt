package com.soundshare

/**
 * Cuánta cola mantener en el teléfono (en frames) para no cortar con la menor latencia posible.
 *
 * El túnel USB de adb no entrega el audio parejo: a veces pasan 10-20 ms sin datos y después
 * llega todo junto. La cola tiene que cubrir el peor hueco reciente más un burst del mezclador.
 *  - Si aparece un hueco más grande, sube enseguida (antes de que se produzca un corte).
 *  - Si la conexión se calma, baja de a un burst cada [STEP_DOWN_NS]. Bajar despacio importa:
 *    cada cambio de objetivo lo absorbe la PC ajustando su reloj, y cuantos menos cambios,
 *    más estable la afinación.
 *  - Cada corte real sube además un piso que se va olvidando despacio. Un corte con la pista
 *    llena no lo causó la cola (lo produjo la salida de Android) y no la agranda.
 *
 * [LatencyProfile.MINIMAL] cambia la regla por la cola justa para el peor hueco de los últimos
 * [MINIMAL_WINDOW_S] segundos: medio burst de margen, sin redondear a bursts, sin piso por cortes
 * y sin esperar para bajar. Los picos más raros cortan; a cambio, la latencia sigue al jitter habitual.
 */
class QueueTarget(
    private val burst: Int,
    sampleRate: Int,
    private val maxFrames: Int,
    initial: Int,
    profile: LatencyProfile = LatencyProfile.STABLE,
) {
    private val minimal = profile == LatencyProfile.MINIMAL
    private val nsPerFrame = 1_000_000_000.0 / sampleRate
    private val minFrames = if (minimal) LatencyProfile.minimalFrames(burst) else MIN_BURSTS * burst
    private val buckets = IntArray(if (minimal) MINIMAL_WINDOW_S else WINDOW_S)
    private var bucket = 0
    private var bucketStart = 0L
    private var floor = minFrames
    private var lastUnderrun = 0L
    private var lastChange = 0L
    private var firstTick = 0L

    var frames = initial.coerceIn(minFrames, maxFrames)
        private set

    /** Registra el tiempo sin datos entre dos llegadas. Devuelve true si cambió el objetivo. */
    fun onGap(gapNs: Long, now: Long): Boolean {
        val gapFrames = (gapNs / nsPerFrame).toInt()
        if (gapFrames > buckets[bucket]) buckets[bucket] = gapFrames
        return update(now)
    }

    /**
     * [lowestFrames]: lo mínimo que tuvo la pista justo antes de recibir audio, alrededor del corte.
     * Si nunca bajó de un burst, el mezclador tenía datos de sobra y el corte fue de la salida.
     */
    fun onUnderrun(now: Long, lowestFrames: Int = 0): Boolean {
        if (minimal || lowestFrames >= burst) return false
        floor = (frames + burst).coerceAtMost(maxFrames)
        lastUnderrun = now
        return update(now)
    }

    /** Llamar seguido (cada ~10 ms). */
    fun tick(now: Long): Boolean {
        if (bucketStart == 0L) {
            // Primera llamada: recién ahora empieza a contar el tiempo para bajar.
            bucketStart = now
            lastChange = now
            lastUnderrun = now
            firstTick = now
        }
        if (now - bucketStart >= 1_000_000_000L) {
            bucket = (bucket + 1) % buckets.size
            buckets[bucket] = 0
            bucketStart = now
        }
        if (floor > minFrames && now - lastUnderrun > FLOOR_DECAY_NS) {
            floor = maxOf(minFrames, floor - burst)
            lastUnderrun = now
        }
        return update(now)
    }

    private fun update(now: Long): Boolean {
        // Sin IntArray.max(): su primera llamada carga ArraysKt de kotlin-stdlib (miles de métodos)
        // y frenaba ~50 ms el hilo de audio en el moto g play, con cortes al arrancar.
        var worstGap = 0
        for (gap in buckets) worstGap = maxOf(worstGap, gap)
        val needed = if (minimal) {
            // Peor hueco + medio burst, en pasos de medio bloque de la PC para no mover el objetivo por nada.
            val step = LatencyProfile.PC_BLOCK_FRAMES / 2
            ((worstGap + burst / 2 + step - 1) / step) * step
        } else {
            // Peor hueco + un burst de margen, redondeado a bursts enteros.
            ((worstGap + burst + burst - 1) / burst) * burst
        }
        val wanted = maxOf(floor, minFrames, needed).coerceAtMost(maxFrames)
        val next = when {
            wanted > frames -> wanted
            // Sin una ventana entera de huecos medidos, bajar sería adivinar.
            wanted < frames && minimal && now - firstTick >= MINIMAL_WINDOW_S * 1_000_000_000L -> wanted
            wanted < frames && now - lastChange > STEP_DOWN_NS -> maxOf(wanted, frames - burst)
            else -> frames
        }
        if (next == frames) return false
        frames = next
        lastChange = now
        return true
    }

    companion object {
        private const val MIN_BURSTS = 2
        private const val WINDOW_S = 30
        private const val MINIMAL_WINDOW_S = 10
        private const val STEP_DOWN_NS = 20_000_000_000L
        private const val FLOOR_DECAY_NS = 30_000_000_000L
    }
}
