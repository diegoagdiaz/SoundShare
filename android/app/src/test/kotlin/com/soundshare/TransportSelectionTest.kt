package com.soundshare

import org.junit.Assert.*
import org.junit.Test

class TransportSelectionTest {
    private val aoa = TransportCandidate(TransportChoice.AOA)

    @Test fun forcedAdbWinsEvenWithReadyAccessoryAndAoaCandidate() {
        assertFalse(TransportSelection.useAoa(TransportChoice.ADB, aoa))
        assertEquals(TransportSelection.Hint.SELECTED_ADB,
            TransportSelection.hint(TransportChoice.ADB, aoa, AccessoryState.READY, true, true, "adb"))
    }

    @Test fun readyAoaCandidateMayStartButOnlyPlayerStatsConfirmActualTransport() {
        assertTrue(TransportSelection.useAoa(TransportChoice.AOA, aoa))
        assertEquals(TransportSelection.Hint.CONNECTING,
            TransportSelection.hint(TransportChoice.AOA, aoa, AccessoryState.READY, true, true, null))
        assertEquals(TransportSelection.Hint.ACTIVE,
            TransportSelection.hint(TransportChoice.AOA, aoa, AccessoryState.READY, true, true, "aoa"))
    }

    @Test fun localFallbackExplainsMissingPermissionDenialFailureAndDetach() {
        val cases = listOf(
            AccessoryState.PERMISSION_REQUIRED to TransportSelection.Hint.PERMISSION_REQUIRED,
            AccessoryState.PERMISSION_DENIED to TransportSelection.Hint.PERMISSION_DENIED,
            AccessoryState.FAILED to TransportSelection.Hint.FAILED,
            AccessoryState.DISCONNECTED to TransportSelection.Hint.DISCONNECTED,
            AccessoryState.UNAVAILABLE to TransportSelection.Hint.UNAVAILABLE,
        )
        for ((state, expected) in cases) assertEquals(expected,
            TransportSelection.hint(TransportChoice.AOA, aoa, state, true, true, "adb"))
    }

    @Test fun pcFallbackKeepsAoaPreferenceAndRecoversWhenLinkReopens() {
        val preference = TransportChoice.AOA
        for ((reason, expected) in listOf(
            TransportReason.UNAVAILABLE to TransportSelection.Hint.UNAVAILABLE,
            TransportReason.RECONNECT to TransportSelection.Hint.RECONNECT,
            TransportReason.DISCONNECTED to TransportSelection.Hint.DISCONNECTED,
        )) {
            val reply = TransportCandidate.parse("adb ${reason.wire}")!!
            assertFalse(TransportSelection.useAoa(preference, reply))
            assertEquals(expected, TransportSelection.hint(preference, reply, AccessoryState.READY, true, true, "adb"))
        }
        assertTrue(TransportSelection.useAoa(preference, TransportCandidate.parse("aoa")!!))
    }

    @Test fun idleAndDisconnectedDoNotClaimActiveAudioFromOldStats() {
        assertEquals(TransportSelection.Hint.IDLE,
            TransportSelection.hint(TransportChoice.AOA, aoa, AccessoryState.READY, true, false, "aoa"))
        assertEquals(TransportSelection.Hint.WAITING,
            TransportSelection.hint(TransportChoice.AOA, aoa, AccessoryState.READY, false, false, "aoa"))
    }

    @Test fun parsesOldAnnouncementsAndAllOptionalReasons() {
        assertEquals(aoa, TransportCandidate.parse("aoa"))
        assertEquals(TransportCandidate(TransportChoice.ADB), TransportCandidate.parse("adb"))
        for (reason in TransportReason.entries) assertEquals(TransportCandidate(TransportChoice.ADB, reason),
            TransportCandidate.parse("adb ${reason.wire}"))
        assertEquals(TransportChoice.ADB, TransportChoice.parse("adb"))
        assertEquals(TransportChoice.AOA, TransportChoice.parse("aoa"))
    }

    @Test fun rejectsUnknownOrMalformedTransportWithoutInventingFallback() {
        for (value in listOf("", "AOA", "wifi", " adb", "aoa ", "adb unknown", "adb  selected",
                             "adb selected extra", "aoa reconnect", "aoa selected")) {
            assertNull(value, TransportCandidate.parse(value))
        }
        assertNull(TransportChoice.parse("aoa extra"))
    }
}
