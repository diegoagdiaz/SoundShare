package com.soundshare

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AccessoryInboxTest {
    @Test fun countsOnlyPayloadAndPreservesPartialReads() {
        val input=AccessoryInbox()
        input.receive(byteArrayOf(1,2,3,4));input.receive(byteArrayOf(5,6))
        assertEquals(6,input.available())
        val b=ByteArray(3)
        assertEquals(3,input.read(b));assertArrayEquals(byteArrayOf(1,2,3),b)
        assertEquals(3,input.available());assertEquals(4,input.read());assertEquals(5,input.read());assertEquals(6,input.read())
        assertEquals(0,input.available())
    }
    @Test fun endUnblocksWaitingReaderAndIgnoresLaterPayload() {
        val input=AccessoryInbox()
        val started=CountDownLatch(1);val done=CountDownLatch(1)
        val reader=Thread {started.countDown();assertEquals(-1,input.read());done.countDown()}
        reader.start();assertTrue(started.await(1,TimeUnit.SECONDS))
        input.finish(true)
        assertTrue(done.await(1,TimeUnit.SECONDS));reader.join(100)
        input.receive(byteArrayOf(7));assertEquals(-1,input.read());assertEquals(0,input.available());assertTrue(input.peerEnded)
    }
    @Test fun overflowClosesWithoutBlockingUsbReader() {
        val input=AccessoryInbox(1)
        input.receive(byteArrayOf(1));input.receive(byteArrayOf(2))
        assertEquals(-1,input.read());assertFalse(input.peerEnded)
    }
    @Test fun endDiscardsBufferedSuffixAndFullQueueWithoutTouchingNewInbox() {
        val old = AccessoryInbox(1)
        old.receive(byteArrayOf(1,2,3))
        assertEquals(1,old.read())
        old.receive(byteArrayOf(4,5))
        assertTrue(old.finish())
        val current = AccessoryInbox()
        current.receive(byteArrayOf(9))
        old.receive(byteArrayOf(6))
        assertFalse(old.finish(true))
        assertTrue(old.peerEnded)
        assertEquals(-1,old.read())
        assertEquals(0,old.available())
        assertEquals(9,current.read())
        assertFalse(current.isEnded)
    }
}
