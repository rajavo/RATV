package com.ratv.app.player

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
    fun timeStampsWrapAroundAfter26Hours() {
        val max = 0x1FFFFFFFFL
        assertTrue(LiveReconnectingDataSource.newer(1_000L, max - 1_000L))
        assertFalse(LiveReconnectingDataSource.newer(max - 1_000L, 1_000L))
    }
}
