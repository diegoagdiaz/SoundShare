package com.soundshare

import org.junit.Assert.*
import org.junit.Test

class LinkSelectionTest {
    @Test fun defaultsToAutoForMissingOrUnknownPreference() {
        for (value in listOf(null, "", "AUTO", " usb", "aoa")) assertEquals(LinkChoice.AUTO, LinkChoice.parse(value))
        for (choice in LinkChoice.entries) assertEquals(choice, LinkChoice.parse(choice.wire))
    }

    @Test fun forcedLinksCannotFallbackToTheOtherLink() {
        assertTrue(LinkSelection.tryUsb(LinkChoice.USB))
        assertFalse(LinkSelection.tryWifi(LinkChoice.USB, true))
        assertFalse(LinkSelection.tryUsb(LinkChoice.WIFI))
        assertTrue(LinkSelection.tryWifi(LinkChoice.WIFI, true))
    }

    @Test fun autoTriesUsbThenOnlyPairedWifi() {
        assertTrue(LinkSelection.tryUsb(LinkChoice.AUTO))
        assertFalse(LinkSelection.tryWifi(LinkChoice.AUTO, false))
        assertTrue(LinkSelection.tryWifi(LinkChoice.AUTO, true))
        assertFalse(LinkSelection.tryWifi(LinkChoice.WIFI, false))
    }

    @Test fun savedBluetoothSelectionNeverOpensANativeConnection() {
        val saved = LinkChoice.parse("bluetooth")
        assertEquals(LinkChoice.BLUETOOTH, saved)
        assertFalse(LinkSelection.native(saved))
        assertFalse(LinkSelection.tryUsb(saved))
        for (paired in listOf(false, true)) assertFalse(LinkSelection.tryWifi(saved, paired))
        for (native in listOf(LinkChoice.AUTO, LinkChoice.USB, LinkChoice.WIFI)) {
            assertTrue(LinkSelection.native(native))
            assertTrue(LinkSelection.tryUsb(native) || LinkSelection.tryWifi(native, true))
        }
    }
}
