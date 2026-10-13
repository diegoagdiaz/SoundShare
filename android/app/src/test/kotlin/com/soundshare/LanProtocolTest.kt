package com.soundshare

import org.junit.Assert.*
import org.junit.Test

class LanProtocolTest {
    private val id = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
    private val secret = LanProtocol.hex(ByteArray(32) { it.toByte() })

    @Test fun discoveryRequestMatchesTheWireWithoutNewline() {
        assertEquals("SOUNDSHARE? 1 $id", String(LanProtocol.discoveryRequest(id), Charsets.UTF_8))
        assertEquals(47101, LanProtocol.DISCOVERY_PORT)
    }

    @Test fun parsesReplyWithSpacesUtf8NameAndActualPort() {
        val reply = "SOUNDSHARE 1 $id 47109 La PC de Martín".toByteArray(Charsets.UTF_8)
        assertEquals(LanProtocol.Reply(id, 47109, "La PC de Martín"), LanProtocol.parseReply(reply))
        assertEquals(LanProtocol.Reply(id, 47109, "La PC de Martín"), LanProtocol.parseReply(reply + ByteArray(50), reply.size))
    }

    @Test fun rejectsMalformedDiscoveryAndOtherVersions() {
        for (text in listOf("", "SOUNDSHARE 2 $id 47101 PC", "SOUNDSHARE 1 $id 0 PC",
            "SOUNDSHARE 1 $id 65536 PC", "SOUNDSHARE 1 $id +1 PC", "SOUNDSHARE 1 ${id.uppercase()} 47101 PC",
            "SOUNDSHARE  1 $id 47101 PC", "SOUNDSHARE 1 $id 47101", "SOUNDSHARE 1 $id 47101 ",
            "SOUNDSHARE 1 $id 47101 PC\n", "SOUNDSHARE? 1 $id", "SOUNDSHARE 1 short 47101 PC")) {
            assertNull(text, LanProtocol.parseReply(text.toByteArray()))
        }
        assertNull(LanProtocol.parseReply("SOUNDSHARE 1 $id 47101 ${"x".repeat(512)}".toByteArray()))
        assertNull(LanProtocol.parseReply("SOUNDSHARE 1 $id 47101 ".toByteArray() + byteArrayOf(0xc3.toByte())))
    }

    @Test fun pairingKeepsTheWholeNameAndValidatesTheSecret() {
        assertEquals(LanProtocol.Pairing(id, 47101, secret, "PC del living"),
            LanProtocol.parsePairing("LAN_PAIRED $id 47101 $secret PC del living"))
        for (line in listOf("LAN_PAIRED $id 47101 short PC", "LAN_PAIRED $id 47101 ${secret.uppercase()} PC",
            "LAN_PAIRED $id 65536 $secret PC", "LAN_PAIRED short 47101 $secret PC",
            "LAN_PAIRED $id 47101 $secret", "LAN_PAIRED $id 47101 $secret PC\nMODE off")) assertNull(line, LanProtocol.parsePairing(line))
    }

    @Test fun directedBroadcastUsesPrefixIncludingNonOctetBoundaries() {
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 255.toByte()),
            LanProtocol.broadcast(byteArrayOf(192.toByte(), 168.toByte(), 1, 20), 24))
        assertArrayEquals(byteArrayOf(10, 0, 1, 255.toByte()), LanProtocol.broadcast(byteArrayOf(10, 0, 0, 20), 23))
        assertArrayEquals(byteArrayOf(10, 0, 0, 127), LanProtocol.broadcast(byteArrayOf(10, 0, 0, 20), 25))
        assertNull(LanProtocol.broadcast(ByteArray(16), 64))
        assertNull(LanProtocol.broadcast(ByteArray(4), 32))
    }

    @Test fun idsRoundTripWithoutSignedByteOrPaddingErrors() {
        assertEquals(id, LanProtocol.hex(LanProtocol.bytes(id)))
        assertEquals(secret, LanProtocol.hex(LanProtocol.bytes(secret)))
        assertFalse(LanProtocol.validHex("", 16))
        assertFalse(LanProtocol.validHex(id.replace('a', 'g'), 16))
    }
}
