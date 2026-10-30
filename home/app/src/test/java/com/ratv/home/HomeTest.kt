package com.ratv.home

import org.junit.Assert.assertEquals
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
}
