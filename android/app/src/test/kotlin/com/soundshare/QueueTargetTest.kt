package com.soundshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueTargetTest {
    @Test
    fun largerGapRaisesTargetImmediatelyAndKeepsOneBurstOfMargin() {
        val target = target(initial = 512)
        target.tick(START_NS)

        assertTrue(target.onGap(20_000_000L, START_NS + 1))
        assertEquals(1280, target.frames)
    }

    @Test
    fun nonAlignedTargetStopsAtWantedLevelWithoutBouncingBackUp() {
        val target = target(initial = 1440) // 30 ms is not a multiple of the 256-frame burst.
        target.tick(START_NS)
        assertFalse(target.onGap(20_000_000L, START_NS + 1))

        for (second in 1..20) target.tick(START_NS + second * SECOND_NS)
        assertEquals(1440, target.frames)

        assertTrue(target.tick(START_NS + 20 * SECOND_NS + 1))
        assertEquals(1280, target.frames)
        assertFalse(target.onGap(20_000_000L, START_NS + 20 * SECOND_NS + 2))
        assertEquals(1280, target.frames)
    }

    @Test
    fun nonAlignedTargetNeverDropsBelowTwoBursts() {
        val target = target(initial = 704)
        target.tick(START_NS)

        for (second in 1..20) target.tick(START_NS + second * SECOND_NS)
        assertTrue(target.tick(START_NS + 20 * SECOND_NS + 1))
        assertEquals(512, target.frames)
        assertFalse(target.onGap(0, START_NS + 20 * SECOND_NS + 2))
        assertEquals(512, target.frames)
    }

    @Test
    fun underrunFloorDecaysGraduallyToTheMinimumFromANonAlignedTarget() {
        val target = target(initial = 704)
        target.tick(START_NS)
        assertTrue(target.onUnderrun(START_NS + 1))
        assertEquals(960, target.frames)

        for (second in 1..30) target.tick(START_NS + second * SECOND_NS)
        assertEquals(960, target.frames)
        assertTrue(target.tick(START_NS + 30 * SECOND_NS + 2))
        assertEquals(704, target.frames)

        for (second in 31..60) target.tick(START_NS + second * SECOND_NS)
        assertEquals(704, target.frames)
        assertTrue(target.tick(START_NS + 60 * SECOND_NS + 3))
        assertEquals(512, target.frames)

        for (second in 61..100) {
            target.tick(START_NS + second * SECOND_NS)
            assertEquals(512, target.frames)
        }
    }

    @Test
    fun worstGapIsKeptForThirtySecondsThenTargetDecreasesByOneBurst() {
        val target = target(initial = 512)
        target.tick(START_NS)
        target.onGap(20_000_000L, START_NS + 1)

        for (second in 1..29) {
            target.tick(START_NS + second * SECOND_NS)
            assertEquals(1280, target.frames)
        }
        assertTrue(target.tick(START_NS + 30 * SECOND_NS))
        assertEquals(1024, target.frames)
    }

    @Test
    fun gapAndUnderrunGrowthRespectCapacityLimit() {
        val target = QueueTarget(256, 48_000, maxFrames = 1500, initial = 1440)
        target.tick(START_NS)

        assertTrue(target.onGap(100_000_000L, START_NS + 1))
        assertEquals(1500, target.frames)
        assertFalse(target.onUnderrun(START_NS + 2))
        assertEquals(1500, target.frames)
    }

    @Test
    fun underrunWithAFullTrackLeavesTheTargetAlone() {
        val target = target(initial = 768)
        target.tick(START_NS)

        // Medido en el moto g play: dos cortes con 27 ms en la pista, a los 6-7 s de arrancar.
        assertFalse(target.onUnderrun(START_NS + 1, lowestFrames = 1300))
        assertEquals(768, target.frames)
        assertFalse(target.onUnderrun(START_NS + 2, lowestFrames = 256))
        assertEquals(768, target.frames)

        assertTrue(target.onUnderrun(START_NS + 3, lowestFrames = 255))
        assertEquals(1024, target.frames)
    }

    private fun target(initial: Int) = QueueTarget(256, 48_000, maxFrames = 4800, initial = initial)

    companion object {
        private const val START_NS = 1_000_000_000L
        private const val SECOND_NS = 1_000_000_000L
    }
}
