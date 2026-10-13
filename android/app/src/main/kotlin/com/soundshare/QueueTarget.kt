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
 *  - Cada corte real sube además un piso que se va olvidando despacio.
 */
class QueueTarget(private val burst: Int, sampleRate: Int, private val maxFrames: Int, initial: Int) {
    private val nsPerFrame = 1_000_000_000.0 / sampleRate
    private val minFrames = MIN_BURSTS * burst
    private val buckets = IntArray(WINDOW_S)
    private var bucket = 0
    private var bucketStart = 0L
    private var floor = minFrames
    private var lastUnderrun = 0L
    private var lastChange = 0L

    var frames = initial.coerceIn(minFrames, maxFrames)
        private set

    /** Registra el tiempo sin datos entre dos llegadas. Devuelve true si cambió el objetivo. */
    fun onGap(gapNs: Long, now: Long): Boolean {
        val gapFrames = (gapNs / nsPerFrame).toInt()
        if (gapFrames > buckets[bucket]) buckets[bucket] = gapFrames
        return update(now)
    }

    fun onUnderrun(now: Long): Boolean {
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
        }
        if (now - bucketStart >= 1_000_000_000L) {
            bucket = (bucket + 1) % WINDOW_S
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
        // Peor hueco + un burst de margen, redondeado a bursts enteros.
        val wanted = maxOf(floor, minFrames, ((worstGap + burst + burst - 1) / burst) * burst)
            .coerceAtMost(maxFrames)
        val next = when {
            wanted > frames -> wanted
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
        private const val STEP_DOWN_NS = 20_000_000_000L
        private const val FLOOR_DECAY_NS = 30_000_000_000L
    }
}
