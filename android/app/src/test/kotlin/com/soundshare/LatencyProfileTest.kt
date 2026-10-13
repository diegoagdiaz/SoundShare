package com.soundshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyProfileTest {
    @Test
    fun parsesOnlyStoredValues() {
        assertEquals(LatencyProfile.STABLE, LatencyProfile.parse("stable"))
        assertEquals(LatencyProfile.MINIMAL, LatencyProfile.parse("minimal"))
        for (invalid in listOf(null, "", "MINIMAL", "min", "stable ")) assertNull(invalid, LatencyProfile.parse(invalid))
    }

    @Test
    fun minimalFloorIsHalfABurstPlusOnePcBlock() {
        // Burst de 240 frames (A21s, moto g play): 5,2 ms.
        assertEquals(248, LatencyProfile.minimalFrames(240))
        assertEquals(176, LatencyProfile.minimalFrames(96))
    }

    @Test
    fun steadySawtoothNeverTrims() {
        val trim = ExcessTrim(BURST)
        // Con el promedio en el objetivo, las mediciones tras cada llegada van de 248 a 488.
        var now = START_NS
        repeat(1000) { i ->
            assertEquals(0, trim.update(TARGET - BURST / 2 + (i * 37) % BURST, TARGET, now))
            now += STATUS_NS
        }
    }

    @Test
    fun audioPiledUpAfterAGapIsTrimmedWithinOneWindow() {
        val trim = ExcessTrim(BURST)
        var now = START_NS
        // 10 ms de audio de más (480 frames) tras un hueco: la ventana entera queda alta.
        var dropped = 0
        repeat(11) { i ->
            dropped += trim.update(TARGET - BURST / 2 + 480 + (i * 37) % BURST, TARGET, now)
            now += STATUS_NS
        }
        assertEquals(480, dropped)
    }

    @Test
    fun smallExcessIsLeftToTheClockLoop() {
        val trim = ExcessTrim(BURST)
        var now = START_NS
        var dropped = 0
        // Medio burst por encima del piso en toda la ventana: lo corrige la PC con su reloj.
        repeat(30) {
            dropped += trim.update(TARGET, TARGET, now)
            now += STATUS_NS
        }
        assertEquals(0, dropped)
    }

    companion object {
        private const val BURST = 240
        private const val TARGET = 368
        private const val START_NS = 1_000_000_000L
        private const val STATUS_NS = 10_000_000L
    }
}
