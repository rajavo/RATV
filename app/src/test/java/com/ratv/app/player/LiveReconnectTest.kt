package com.ratv.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReconnectTest {
    @Test
    fun laterTimeStampsAreNewer() {
        assertTrue(LiveReconnectingDataSource.newer(90_000L, -1L))
        assertTrue(LiveReconnectingDataSource.newer(90_001L, 90_000L))
        assertFalse(LiveReconnectingDataSource.newer(90_000L, 90_000L))
        assertFalse(LiveReconnectingDataSource.newer(0L, 360_000L)) // 4 s back
    }

    @Test
    fun clockJumpsAreNoticed() {
        val second = 90_000L
        assertFalse(LiveReconnectingDataSource.clockJumped(100 * second + 3_600L, 100 * second)) // next picture
        assertFalse(LiveReconnectingDataSource.clockJumped(105 * second, 100 * second)) // 5 s gap
        assertTrue(LiveReconnectingDataSource.clockJumped(111 * second, 100 * second)) // 11 s ahead
        assertTrue(LiveReconnectingDataSource.clockJumped(2 * second, 240 * second)) // back to the start
        assertFalse(LiveReconnectingDataSource.clockJumped(99 * second + 50_000L, 100 * second)) // a frame back
        val max = 0x1FFFFFFFFL
        assertFalse(LiveReconnectingDataSource.clockJumped(1_000L, max - 2_000L)) // wraps after 26 h
    }

    @Test
    fun aStoreRunningLowIsNotWaitedOutByAStuckConnection() {
        // A big store keeps the usual wait, stretched to 4.5 s when the usual one is shorter.
        assertEquals(8_000L, LiveReconnectingDataSource.stallWaitMs(8_000L, 60_000L))
        assertEquals(4_500L, LiveReconnectingDataSource.stallWaitMs(3_000L, 60_000L))
        // A small store ends the wait 2 s before it runs out, and never before 3 s.
        assertEquals(3_000L, LiveReconnectingDataSource.stallWaitMs(8_000L, 5_000L))
        assertEquals(4_000L, LiveReconnectingDataSource.stallWaitMs(8_000L, 6_000L))
        assertEquals(3_000L, LiveReconnectingDataSource.stallWaitMs(8_000L, 0L))
    }

    @Test
    fun timeStampsWrapAroundAfter26Hours() {
        val max = 0x1FFFFFFFFL
        assertTrue(LiveReconnectingDataSource.newer(1_000L, max - 1_000L))
        assertFalse(LiveReconnectingDataSource.newer(max - 1_000L, 1_000L))
    }
}
