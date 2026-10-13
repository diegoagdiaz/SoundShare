package com.soundshare

import org.junit.Assert.*
import org.junit.Test

class AccessoryPermissionRequestsTest {
    @Test fun denialOrPlayerRetriesDoNotPromptAgainOnSameCable() {
        val permission = AccessoryPermissionRequests()
        assertTrue(permission.request())
        repeat(10) { assertFalse(permission.request()) }
    }

    @Test fun explicitTapAllowsRetryButDoesNotReenableAutomaticPrompts() {
        val permission = AccessoryPermissionRequests()
        assertTrue(permission.request())
        assertTrue(permission.request(explicit = true))
        assertFalse(permission.request())
        assertTrue(permission.request(explicit = true))
        assertFalse(permission.request())
    }

    @Test fun newCableAllowsExactlyOneAutomaticRequestAgain() {
        val permission = AccessoryPermissionRequests()
        assertTrue(permission.request())
        permission.disconnected()
        assertTrue(permission.request())
        assertFalse(permission.request())
    }
}
