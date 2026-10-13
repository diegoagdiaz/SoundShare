package com.soundshare

import org.junit.Assert.*
import org.junit.Test

class PlayerBufferTest {
    @Test fun wifiReserves500msAndUsesSeparateLearnedTargets() {
        assertEquals(24000, PlayerBuffer.capacityFrames(48000, true))
        assertEquals("target_ms_wifi_48000", PlayerBuffer.learnedKey("wifi", 48000, false))
        assertEquals("target_ms_min_wifi_48000", PlayerBuffer.learnedKey("wifi", 48000, true))
        assertEquals(60f, PlayerBuffer.initialMs(true, false), 0f)
        assertEquals(30f, PlayerBuffer.initialMs(true, true), 0f)
    }

    @Test fun usbRetainsCapacityKeysAndInitialTargets() {
        assertEquals(9600, PlayerBuffer.capacityFrames(48000, false))
        assertEquals("target_ms_48000", PlayerBuffer.learnedKey("adb", 48000, false))
        assertEquals("target_ms_min_48000", PlayerBuffer.learnedKey("adb", 48000, true))
        assertEquals("target_ms_aoa_48000", PlayerBuffer.learnedKey("aoa", 48000, false))
        assertEquals("target_ms_min_aoa_48000", PlayerBuffer.learnedKey("aoa", 48000, true))
        assertEquals(30f, PlayerBuffer.initialMs(false, false), 0f)
        assertEquals(10f, PlayerBuffer.initialMs(false, true), 0f)
    }
}
