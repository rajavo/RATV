package com.ratv.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTest {
    @Test
    fun movingAnApp() {
        val l = listOf("a", "b", "c", "d", "e", "f", "g")
        assertEquals(listOf("b", "a", "c", "d", "e", "f", "g"), Apps.moved(l, 0, 1))
        assertEquals(listOf("a", "c", "d", "e", "f", "b", "g"), Apps.moved(l, 1, 6 - 1))
        assertEquals(listOf("b", "c", "d", "e", "f", "g", "a"), Apps.moved(l, 0, 99)) // stays inside
        assertEquals(l, Apps.moved(l, 0, -1))
    }

    @Test
    fun hadithsAreWholeAndCited() {
        assertTrue(Hadiths.ALL.size >= 20)
        assertEquals(Hadiths.ALL.size, Hadiths.ALL.map { it.text }.toSet().size)
        for (h in Hadiths.ALL) {
            assertTrue(h.text, h.text.isNotBlank() && h.narrator.isNotBlank())
            assertTrue(h.source, Regex("""(البخاري|مسلم) \(\d+\)""").containsMatchIn(h.source))
        }
    }
}
