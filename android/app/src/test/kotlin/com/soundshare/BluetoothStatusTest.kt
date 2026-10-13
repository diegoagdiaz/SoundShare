package com.soundshare

import org.junit.Assert.*
import org.junit.Test

class BluetoothStatusTest {
    @Test fun absenceOfConnectedA2dpNeverClaimsAnAudioConnection() {
        val status = BluetoothStatus.fromA2dp(emptyList())
        assertEquals(BluetoothStatus.Phase.DISCONNECTED, status.phase)
        assertFalse(status.connected)
        assertFalse(status.playing)
        assertEquals("", status.destination)
    }

    @Test fun idleA2dpIsConnectedButDoesNotClaimAudioPlayback() {
        val status = BluetoothStatus.fromA2dp(listOf(BluetoothStatus.Destination("fedora", false)))
        assertTrue(status.connected)
        assertFalse(status.playing)
        assertEquals("fedora", status.destination)
    }

    @Test fun playingDestinationWinsOverAnotherIdleConnectedDevice() {
        val status = BluetoothStatus.fromA2dp(listOf(
            BluetoothStatus.Destination("Auriculares", false), BluetoothStatus.Destination("fedora", true),
        ))
        assertTrue(status.playing)
        assertEquals("fedora", status.destination)
    }

    @Test fun multipleIdleDevicesAreShownWithoutInventingAnActiveDestination() {
        val status = BluetoothStatus.fromA2dp(listOf(
            BluetoothStatus.Destination("Auriculares", false), BluetoothStatus.Destination("fedora", false),
        ))
        assertFalse(status.playing)
        assertEquals("Auriculares, fedora", status.destination)
    }
}
