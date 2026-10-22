package com.ratv.app.player

import com.ratv.app.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionsTest {
    private fun ch(id: String, name: String) = Channel(id, 1, name, null, "1", null, "http://x/$id.ts")

    private val k4 = ch("1", "beIN SPORTS 1 4K")
    private val fhd = ch("2", "beIN SPORTS 1 FHD")
    private val hd = ch("3", "beIN SPORTS 1 HD")
    private val en = ch("4", "beIN SPORTS 1 EN HD")
    private val k4b = ch("5", "beIN SPORTS 1 4K Backup")
    private val all = listOf(k4, fhd, hd, en, k4b)

    @Test
    fun lighterFirstThenBackup() {
        assertEquals(fhd, nextVersion(k4, all, setOf("1")))
        assertEquals(hd, nextVersion(k4, all, setOf("1", "2")))
        assertEquals(k4b, nextVersion(k4, all, setOf("1", "2", "3")))
        assertNull(nextVersion(k4, all, setOf("1", "2", "3", "5")))
    }

    @Test
    fun neverHeavierNorAnotherLanguage() {
        // From HD only the English version is lighter or equal, and it speaks another language.
        assertNull(nextVersion(hd, all, setOf("3")))
        assertNull(nextVersion(en, all, setOf("4")))
        assertEquals("en", languageOf("beIN SPORTS 1 EN HD"))
        assertEquals("ar", languageOf("AR: beIN SPORTS 1 HD"))
        assertEquals("en", languageOf("بي ان سبورت 1 انجليزي"))
    }
}
