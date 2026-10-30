package com.ratv.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreTest {
    @Test
    fun packagesFromFileNames() {
        assertEquals("com.ratv.app", Catalog.packageFor("RATV.apk"))
        assertEquals("com.ratv.kora", Catalog.packageFor("RAKora.apk"))
        assertEquals("com.ratv.net", Catalog.packageFor("RANet.apk"))
        assertEquals("com.ratv.home", Catalog.packageFor("RAHome.apk"))
        assertEquals("com.ratv.store", Catalog.packageFor("RAStore.apk"))
    }

    @Test
    fun githubTimes() {
        assertEquals(1_793_383_252_000L, Catalog.time("2026-10-30T18:00:52Z"))
        assertEquals(0L, Catalog.time("not a time"))
    }

    private fun release(code: Int?, at: Long) = Release("RA Net", "RANet.apk", "u", at, code, null, "com.ratv.net", "")

    @Test
    fun updates() {
        val mine = Installed(15, "1.0.15", 1_000_000L, null)
        assertTrue(Store.hasUpdate(release(16, 0), mine))
        assertFalse(Store.hasUpdate(release(15, 0), mine))
        assertFalse(Store.hasUpdate(release(16, 0), null)) // not installed: an install, not an update
        // Without a version file: by date, with ten minutes' leeway.
        assertTrue(Store.hasUpdate(release(null, 1_000_000L + 11 * 60_000L), mine))
        assertFalse(Store.hasUpdate(release(null, 1_000_000L + 5 * 60_000L), mine))
    }
}
