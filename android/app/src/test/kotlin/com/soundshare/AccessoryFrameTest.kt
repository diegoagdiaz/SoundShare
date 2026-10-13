package com.soundshare

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class AccessoryFrameTest {
    @Test fun roundtripAndShortPackets() {
        val kinds = listOf(1 to 8,2 to 12,4 to 16,5 to 0) + (8..8192 step 8).map { 3 to it }
        for ((kind,size) in kinds) {
            val payload=ByteArray(size) { 42 }
            val bytes=AccessoryFrame.encode(kind,19,payload)
            for (packet in listOf(64,512,1024)) assertNotEquals(0,bytes.size % packet)
            val frame=AccessoryFrame.read(ByteArrayInputStream(bytes))
            assertEquals(kind,frame.kind);assertEquals(19,frame.session);assertArrayEquals(payload,frame.payload)
        }
    }
    @Test fun fragmentedCoalescedAndLargeFdReads() {
        val bytes=AccessoryFrame.encode(2,1,ByteArray(12)) + AccessoryFrame.encode(3,1,ByteArray(2048) { 7 })
        val raw=object: InputStream() {
            var pos=0
            override fun read(): Int = error("lectura chica del fd")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                assertTrue(len>=16384)
                if(pos==bytes.size)return -1
                val n=minOf(17,bytes.size-pos)
                System.arraycopy(bytes,pos,b,off,n);pos+=n;return n
            }
        }
        val input=AccessoryTransport.LargeReads(raw)
        assertEquals(2,AccessoryFrame.read(input).kind)
        assertArrayEquals(ByteArray(2048) {7},AccessoryFrame.read(input).payload)
    }
    @Test fun rejectsBadHeadersPaddingAndTruncation() {
        val bytes=AccessoryFrame.encode(1,1,ByteArray(8))
        for (offset in listOf(0,4,5,6,20)) {
            val bad=bytes.clone().also {it[offset]=(-1).toByte()}
            assertThrows(IOException::class.java) {AccessoryFrame.read(ByteArrayInputStream(bad))}
        }
        for (size in 0 until bytes.size) assertThrows(IOException::class.java) {AccessoryFrame.read(ByteArrayInputStream(bytes.copyOf(size)))}
        assertThrows(IOException::class.java) {AccessoryFrame.encode(3,1,ByteArray(513))}
        assertThrows(IOException::class.java) {AccessoryFrame.encode(1,0,ByteArray(8))}
    }
    @Test fun fixedCrossLanguageVector() {
        val expected=byteArrayOf(83,83,65,79,1,5,0,0,0x78,0x56,0x34,0x12,0)
        assertArrayEquals(expected,AccessoryFrame.encode(5,0x12345678,ByteArray(0)))
    }
}
