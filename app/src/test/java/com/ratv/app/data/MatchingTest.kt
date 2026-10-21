package com.ratv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {

    @Test
    fun stripsProviderTags() {
        assertEquals("Devour (2024)", stripTags("AR-SUBS: Devour (2024)"))
        assertEquals("صقر وكناريا", stripTags("4K-AR: صقر وكناريا"))
        assertEquals("Movie", stripTags("|AR| Movie"))
        assertEquals("Movie", stripTags("EN - Movie"))
        assertEquals("The Man from Nowhere", stripTags("AR-AS: The Man from Nowhere"))
        assertEquals("Mission: Impossible", stripTags("Mission: Impossible"))
        assertEquals("Spider-Man - No Way Home", stripTags("Spider-Man - No Way Home"))
    }

    @Test
    fun cleansTitlesForSearch() {
        assertEquals("Devour", cleanTitle("AR-SUBS: Devour (2024)"))
        assertEquals("The Fix", cleanTitle("AR-CAM: The Fix (2024) [4K]"))
        assertEquals("صقر وكناريا", cleanTitle("صقر وكناريا FHD"))
    }

    @Test
    fun sameFilmGetsTheSameKey() {
        assertEquals(vodKey("صقر وكناريا FHD"), vodKey("4K-AR: صقر و كناريا (2025)"))
        assertEquals(vodKey("AR-SUBS: Devour (2024)"), vodKey("Devour 4K"))
        assertEquals(vodKey("أحمد"), vodKey("احمد"))
        assertNotEquals(vodKey("Devour"), vodKey("Devil"))
    }

    @Test
    fun qualityFromNameOrSection() {
        assertEquals("4K", vodQuality("صقر وكناريا", "4K افلام عربية فائقة الوضوح"))
        assertEquals("4K", vodQuality("صقر وكناريا", "افلام فائقة الوضوح"))
        assertEquals("FHD", vodQuality("صقر وكناريا FHD", "افلام عربية حديثة"))
        assertNull(vodQuality("صقر وكناريا", "افلام عربية حديثة"))
    }

    @Test
    fun sameGameInArabicAndEnglish() {
        assertTrue(sameEvent(eventTokens("LaLiga: Real Madrid v Barcelona"), eventTokens("الدوري الإسباني: ريال مدريد × برشلونة")))
        assertTrue(sameEvent(eventTokens("Al Hilal vs Al Nassr - Roshn Saudi League"), eventTokens("الهلال والنصر | دوري روشن")))
        assertTrue(sameEvent(eventTokens("Premier League: Liverpool vs Arsenal"), eventTokens("Liverpool v Arsenal (Live)")))
        assertFalse(sameEvent(eventTokens("Real Madrid vs Barcelona"), eventTokens("Real Madrid TV Magazine")))
        assertFalse(sameEvent(eventTokens("Liverpool vs Arsenal"), eventTokens("Chelsea vs Arsenal")))
        assertFalse(sameEvent(eventTokens("News"), eventTokens("News")))
    }

    @Test
    fun recognisesSportsChannels() {
        assertTrue(looksSports("beIN SPORTS 1 HD"))
        assertTrue(looksSports("AR| BEIN SPORTS 4K"))
        assertTrue(looksSports("قنوات رياضية"))
        assertFalse(looksSports("MBC 2"))
    }
}
