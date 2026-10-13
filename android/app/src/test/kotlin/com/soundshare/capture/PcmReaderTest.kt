package com.soundshare.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmReaderTest {
    @Test fun drainsAnEntireSubmixBlockWithoutWaitingForTheNextBlock() {
        val source = ByteArray(1024 * 4) { it.toByte() }
        var offset = 0
        var calls = 0
        val reader = PcmReader(4) { into, start, size, blocking ->
            if (calls++ == 0) {
                assertTrue(blocking)
                assertEquals(4, size)
            } else {
                assertFalse(blocking)
            }
            val n = minOf(size, source.size - offset)
            source.copyInto(into, start, offset, offset + n)
            offset += n
            n
        }
        val chunk = ByteArray(source.size)
        assertEquals(source.size, reader.readInto(chunk))
        assertEquals(2, calls)
        assertArrayEquals(source, chunk)
    }

    @Test fun sendsThePartialTailImmediatelyAndPreservesOrderAcrossWrites() {
        val source = ByteArray((1024 + 64) * 4) { (it * 7).toByte() }
        var offset = 0
        val reader = PcmReader(4) { into, start, size, _ ->
            val n = minOf(size, source.size - offset)
            source.copyInto(into, start, offset, offset + n)
            offset += n
            n
        }
        val chunk = ByteArray(1024 * 4)
        val received = java.io.ByteArrayOutputStream()
        repeat(2) {
            val n = reader.readInto(chunk)
            assertEquals(if (it == 0) 1024 * 4 else 64 * 4, n)
            received.write(chunk, 0, n)
        }
        assertArrayEquals(source, received.toByteArray())
    }

    @Test fun doesNotWaitForMoreWhenOnlyOneFrameIsReady() {
        var calls = 0
        val reader = PcmReader(4) { _, _, size, blocking ->
            calls++
            if (blocking) {
                assertEquals(4, size)
                4
            } else 0
        }
        assertEquals(4, reader.readInto(ByteArray(4096)))
        assertEquals(2, calls)
    }

    @Test fun deliversReadFramesBeforeReportingADrainError() {
        var calls = 0
        val reader = PcmReader(4) { into, _, _, blocking ->
            calls++
            if (blocking) {
                byteArrayOf(1, 2, 3, 4).copyInto(into)
                4
            } else -6
        }
        val chunk = ByteArray(4096)
        assertEquals(4, reader.readInto(chunk))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), chunk.copyOf(4))
        assertEquals(-6, reader.readInto(chunk))
        assertEquals(2, calls)
    }

    @Test fun propagatesABlockingReadErrorWithoutDraining() {
        var calls = 0
        val reader = PcmReader(4) { _, _, _, _ -> calls++; -3 }
        assertEquals(-3, reader.readInto(ByteArray(4096)))
        assertEquals(1, calls)
    }
}
