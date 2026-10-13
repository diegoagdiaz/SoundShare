package com.soundshare

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class LanAuthTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val phoneId = ByteArray(16) { (0xa0 + it).toByte() }
    private val phoneNonce = ByteArray(16) { (0xb0 + it).toByte() }
    private val pcNonce = ByteArray(16) { (0xc0 + it).toByte() }
    private val pcProof = "559986fb2eef2a73102d6ddfa2ce35b0deaea6200fb9c475933a47924352ecd2"
    private val phoneProof = "4c313817e3833ac17d867be912dfc370524369cab770fbe41d212a63a0c7122c"

    private fun response(): ByteArray = ByteArray(56).also {
        System.arraycopy(byteArrayOf(0x53, 0x53, 0x4c, 0x4e, 1), 0, it, 0, 5)
        System.arraycopy(pcNonce, 0, it, 8, 16)
        System.arraycopy(LanProtocol.bytes(pcProof), 0, it, 24, 32)
    }

    @Test fun matchesBothProofsInBindingSpec() {
        assertEquals(pcProof, LanProtocol.hex(LanAuth.pcProof(secret, phoneId, phoneNonce, pcNonce)))
        assertEquals(phoneProof, LanProtocol.hex(LanAuth.phoneProof(secret, phoneId, phoneNonce, pcNonce)))
    }

    @Test fun requestHasExactOffsetsAndZeroReservedBytes() {
        val request = LanAuth.request(phoneId, phoneNonce)
        assertEquals(40, request.size)
        assertArrayEquals(byteArrayOf(0x53, 0x53, 0x4c, 0x4e, 1, 0, 0, 0), request.copyOfRange(0, 8))
        assertArrayEquals(phoneId, request.copyOfRange(8, 24))
        assertArrayEquals(phoneNonce, request.copyOfRange(24, 40))
    }

    @Test fun acceptsFragmentedResponseAndLeavesHelloForCaller() {
        val source = ByteArrayInputStream(response() + byteArrayOf(99))
        val fragmented = object : InputStream() {
            override fun read() = source.read()
            override fun read(b: ByteArray, off: Int, len: Int) = source.read(b, off, minOf(3, len))
        }
        val out = ByteArrayOutputStream()
        LanAuth.authenticate(fragmented, out, phoneId, secret, phoneNonce)
        assertEquals(72, out.size())
        assertArrayEquals(LanAuth.request(phoneId, phoneNonce), out.toByteArray().copyOfRange(0, 40))
        assertEquals(phoneProof, LanProtocol.hex(out.toByteArray().copyOfRange(40, 72)))
        assertEquals(99, fragmented.read())
    }

    private fun rejectsWithoutPhoneProof(reply: ByteArray, key: ByteArray = secret) {
        val out = ByteArrayOutputStream()
        try {
            LanAuth.authenticate(ByteArrayInputStream(reply), out, phoneId, key, phoneNonce)
            fail("debía rechazar la PC")
        } catch (_: IOException) {
            assertArrayEquals(LanAuth.request(phoneId, phoneNonce), out.toByteArray())
        }
    }

    @Test fun noAudioOrPhoneProofIsSentToAnUnknownPc() {
        rejectsWithoutPhoneProof(response().also { it[24] = (it[24].toInt() xor 1).toByte() })
        rejectsWithoutPhoneProof(response(), ByteArray(32))
        rejectsWithoutPhoneProof(response().also { it[8] = 0 })
    }

    @Test fun rejectsBadHeadersStatusesAndPartialResponses() {
        for (offset in listOf(0, 4, 5, 6, 7)) rejectsWithoutPhoneProof(response().also { it[offset] = 9 })
        for (status in listOf(1, 2)) rejectsWithoutPhoneProof(response().also { it[5] = status.toByte() })
        rejectsWithoutPhoneProof(response().copyOf(55))
    }
}
