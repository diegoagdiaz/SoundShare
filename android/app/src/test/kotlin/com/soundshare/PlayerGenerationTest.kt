package com.soundshare

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlayerGenerationTest {
    @Test fun cancellationStopsPlaybackBeforeNextGeneration() {
        val playing = AtomicInteger()
        val old = PlayerGeneration()
        assertTrue(old.attachPlayback(Closeable { playing.decrementAndGet() }))
        old.ifActive { playing.incrementAndGet() }
        old.cancel()
        val next = PlayerGeneration()
        next.ifActive { playing.incrementAndGet() }
        old.ifActive { playing.incrementAndGet() }
        assertEquals(1, playing.get())
        val lateClosed = AtomicInteger()
        assertFalse(old.attachPlayback(Closeable { lateClosed.incrementAndGet() }))
        assertEquals(1, lateClosed.get())
        assertEquals(1, playing.get())
    }

    @Test fun lateReaderCannotResumeOrPublishAfterRestart() {
        val old = PlayerGeneration()
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val done = CountDownLatch(1)
        val writes = AtomicInteger()
        val stats = AtomicInteger()
        val oldClosed = AtomicInteger()
        assertTrue(old.attach(Closeable { oldClosed.incrementAndGet() }))
        val reader = Thread {
            reading.countDown()
            resume.await()
            old.ifActive { writes.incrementAndGet() }
            old.ifActive { stats.incrementAndGet() }
            done.countDown()
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            old.cancel() // El read simulado sigue bloqueado aunque se cerró el recurso.
            val current = PlayerGeneration()
            val currentClosed = AtomicInteger()
            assertTrue(current.attach(Closeable { currentClosed.incrementAndGet() }))
            current.ifActive { writes.incrementAndGet(); stats.incrementAndGet() }
            resume.countDown()
            assertTrue(done.await(1, TimeUnit.SECONDS))
            old.cancel()
            assertFalse(old.active)
            assertTrue(current.active)
            assertEquals(1, writes.get())
            assertEquals(1, stats.get())
            assertEquals(1, oldClosed.get())
            assertEquals(0, currentClosed.get())
            current.cancel()
            assertEquals(1, currentClosed.get())
        } finally {
            resume.countDown()
            reader.join(1000)
        }
    }

    @Test fun resourceCreatedAfterCancellationIsClosedImmediately() {
        val run = PlayerGeneration()
        val closed = AtomicInteger()
        run.cancel()
        assertFalse(run.attach(Closeable { closed.incrementAndGet() }))
        run.cancel()
        assertEquals(1, closed.get())
        assertNull(run.ifActive { fail("generación cancelada") })
    }

    @Test fun lateCleanupCannotDetachTheNextSessionResource() {
        val run = PlayerGeneration()
        val firstClosed = AtomicInteger()
        val nextClosed = AtomicInteger()
        val first = Closeable { firstClosed.incrementAndGet() }
        val next = Closeable { nextClosed.incrementAndGet() }
        assertTrue(run.attach(first))
        run.detach(first)
        first.close()
        assertTrue(run.attach(next))
        run.detach(first)
        run.cancel()
        assertEquals(1, firstClosed.get())
        assertEquals(1, nextClosed.get())
    }
}
