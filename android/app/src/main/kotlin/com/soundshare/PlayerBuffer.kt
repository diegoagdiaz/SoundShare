package com.soundshare

/** WiFi aprende su propia cola; adb conserva las claves de las versiones anteriores. */
object PlayerBuffer {
    fun capacityFrames(rate: Int, wifi: Boolean) = if (wifi) rate / 2 else rate / 5
    fun learnedKey(transport: String, rate: Int, minimal: Boolean) =
        (if (minimal) "target_ms_min_" else "target_ms_") +
            (if (transport == "adb") "" else "${transport}_") + rate
    fun initialMs(wifi: Boolean, minimal: Boolean) =
        if (wifi) { if (minimal) 30f else 60f } else { if (minimal) 10f else 30f }
}
