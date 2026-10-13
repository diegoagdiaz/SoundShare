package com.soundshare

import org.junit.Assert.*
import org.junit.Test

class LatencyEstimateTest {
    @Test fun phoneOutputAlreadyIncludesTheTrackQueue() {
        val pc = LatencyEstimate.Pc(Mode.PC_TO_PHONE, 1.0, 5.0, null)
        val estimate = LatencyEstimate.calculate(Mode.PC_TO_PHONE, pc,
            phoneQueueMs = 40.0, phoneOutputMs = 50.0, socketMs = 10.0, roundTripMs = 8.0)
        assertEquals(70.0, estimate.milliseconds!!, 0.001)
        assertTrue(estimate.missing.isEmpty())
    }

    @Test fun phoneToPcIncludesCaptureOutputAndHalfTheRoundTrip() {
        val pc = LatencyEstimate.Pc(Mode.PHONE_TO_PC, 26.0, 8.0, 15.0)
        val estimate = LatencyEstimate.calculate(Mode.PHONE_TO_PC, pc, null, null, 0.0, 10.0)
        assertEquals(54.0, estimate.milliseconds!!, 0.001)
        assertTrue(estimate.missing.isEmpty())
    }

    @Test fun unknownCaptureIsMarkedPartialInsteadOfInventingADelay() {
        val pc = LatencyEstimate.Pc(Mode.PHONE_TO_PC, 26.0, 8.0, null)
        val estimate = LatencyEstimate.calculate(Mode.PHONE_TO_PC, pc, null, null, 0.0, 10.0)
        assertEquals(39.0, estimate.milliseconds!!, 0.001)
        assertEquals(setOf(LatencyEstimate.Missing.CAPTURE), estimate.missing)
    }

    @Test fun missingOutputFallsBackToQueueButKeepsThePartialLabel() {
        val pc = LatencyEstimate.Pc(Mode.PC_TO_PHONE, 0.0, 5.0, null)
        val estimate = LatencyEstimate.calculate(Mode.PC_TO_PHONE, pc, 30.0, null, 4.0, null)
        assertEquals(35.0, estimate.milliseconds!!, 0.001)
        assertEquals(setOf(LatencyEstimate.Missing.OUTPUT, LatencyEstimate.Missing.USB), estimate.missing)
    }

    @Test fun previousDirectionAndTransportAloneDoNotProduceAudioLatency() {
        val previous = LatencyEstimate.Pc(Mode.PC_TO_PHONE, 30.0, 5.0, null)
        val estimate = LatencyEstimate.calculate(Mode.PHONE_TO_PC, previous, null, null, 0.0, 2.0)
        assertNull(estimate.milliseconds)
        assertEquals(setOf(LatencyEstimate.Missing.PC, LatencyEstimate.Missing.CAPTURE), estimate.missing)
        assertNull(LatencyEstimate.calculate(Mode.OFF, previous, null, null, 0.0, 2.0).milliseconds)
    }

    @Test fun protocolRejectsMalformedOrNonFiniteMeasurements() {
        assertNotNull(LatencyEstimate.parsePc("phone2pc 26 8 -1"))
        assertNull(LatencyEstimate.parsePc("off 26 8 1"))
        assertNull(LatencyEstimate.parsePc("phone2pc NaN 8 1"))
        assertNull(LatencyEstimate.parsePc("phone2pc 26 Infinity 1"))
        assertNull(LatencyEstimate.parsePc("phone2pc 26 8 -2"))
        assertNull(LatencyEstimate.parsePc("phone2pc 26 8"))
    }

    @Test fun captureTimestampAccountsForFramesReadSinceTheTimestamp() {
        // 480 frames posteriores al timestamp equivalen a 10 ms; pasaron 30 ms.
        assertEquals(20.0, LatencyEstimate.captureAgeMs(48000, 1480, 1000,
            1_000_000_000, 1_030_000_000)!!, 0.001)
        assertNull(LatencyEstimate.captureAgeMs(48000, 1480, 1000, 0, 1_030_000_000))
        assertNull(LatencyEstimate.captureAgeMs(48000, 1480, 1000, 1_030_000_001, 1_030_000_000))
        assertNull(LatencyEstimate.captureAgeMs(48000, 1480, 1000, 1, 2_000_000_000))
        assertNull(LatencyEstimate.captureAgeMs(48000, 1480, 1000, 1_000_000_000, 1_005_000_000))
    }
}
