package com.ratv.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryGroupsTest {
    private fun ch(id: String, category: String) = Channel(id, 1, "Channel $id", null, category, null, "http://x/$id.ts")

    @Test
    fun sportsCategoriesFirstAndEveryChannelOnce() {
        val cats = listOf(Category("1", "Movies"), Category("2", "Sports | Alwan"), Category("3", "News"))
        val list = listOf(
            ch("10", "1"),
            ch("11", "2"),
            ch("12", "2"),
            ch("13", "3"),
            ch("14", "9"), // a category the subscription does not list
            ch("11", "2") // the same channel twice
        )
        val groups = categoryGroups(list, cats)
        assertEquals(listOf("Sports | Alwan", "Movies", "News", ""), groups.map { it.name })
        assertEquals(listOf("11", "12"), groups[0].channels.map { it.id })
        assertEquals(5, groups.sumOf { it.channels.size })
    }
}
