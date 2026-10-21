package com.ratv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {

    @Test
    fun addsMissingHttp() {
        assertEquals(
            "http://server.com:8080/get.php?username=a&password=b&type=m3u_plus",
            Links.clean("server.com:8080/get.php?username=a&password=b&type=m3u_plus")
        )
        assertEquals("http://192.168.1.5:25461", Links.clean("192.168.1.5:25461"))
        assertEquals("http://www.server.com/list.m3u", Links.clean("www.server.com/list.m3u"))
        assertEquals("http://httpbin.org", Links.clean("httpbin.org"))
    }

    @Test
    fun fixesArabicKeyboard() {
        assertEquals(
            "http://server.com:8080/get.php?username=123&password=456",
            Links.clean("server.com:٨٠٨٠/get.php؟username=١٢٣&password=۴۵۶")
        )
    }

    @Test
    fun removesSpacesAndInvisibleMarks() {
        assertEquals("http://a.com/x.m3u", Links.clean(" \u200Fhttp:// a.com/x.m3u\n"))
        assertEquals("http://a.com/x.m3u", Links.clean("\u202Bhttp://a.com/x.m3u\u202C"))
        assertEquals("http://a.com/x.m3u", Links.clean("\"http://a.com/x.m3u\""))
    }

    @Test
    fun fixesSchemeTypos() {
        assertEquals("http://a.com", Links.clean("http//a.com"))
        assertEquals("http://a.com", Links.clean("http:/a.com"))
        assertEquals("http://a.com", Links.clean("http:a.com"))
        assertEquals("http://a.com", Links.clean("htp://a.com"))
        assertEquals("http://a.com", Links.clean("Http://a.com"))
        assertEquals("https://a.com", Links.clean("HTTPS://a.com"))
        assertEquals("http://a.com", Links.clean("http:\\\\a.com"))
    }

    @Test
    fun keepsWhatItShouldNotTouch() {
        assertEquals("", Links.clean("   "))
        assertEquals("content://com.android.providers/doc/1", Links.clean("content://com.android.providers/doc/1"))
        assertEquals("https://a.com:443/p?x=1", Links.clean("https://a.com:443/p?x=1"))
    }

    @Test
    fun tellsUsableLinks() {
        assertTrue(Links.isWebLink(Links.clean("server.com:8080")))
        assertTrue(Links.isWebLink(Links.clean("server.com:٨٠٨٠/get.php؟username=١")))
        assertFalse(Links.isWebLink(Links.clean("http://")))
        assertFalse(Links.isWebLink(Links.clean("server.com:port")))
        assertFalse(Links.isWebLink("rtmp://a.com/live"))
        assertFalse(Links.isWebLink(""))
    }

    @Test
    fun cleansCredentials() {
        assertEquals("user123", Links.credential(" user١٢٣\u200E "))
        assertEquals("p@ss 1", Links.credential("p@ss ١"))
    }
}
