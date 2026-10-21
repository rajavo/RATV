package com.ratv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitlesTest {

    @Test
    fun readsSrt() {
        val srt = "1\n00:00:01,000 --> 00:00:03,500\n<i>Hello</i> there\n\n2\n00:00:04,000 --> 00:00:06,000\nSecond line\nwith two rows\n"
        val cues = SubtitleParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(Cue(1000, 3500, "Hello there"), cues[0])
        assertEquals("Second line\nwith two rows", cues[1].text)
    }

    @Test
    fun readsSrtWithoutBlankLines() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nOne\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nTwo"
        assertEquals(listOf("One", "Two"), SubtitleParser.parse(srt).map { it.text })
    }

    @Test
    fun readsWebVtt() {
        val vtt = "WEBVTT\n\n00:01.500 --> 00:03.000 align:start\nHi\n\n01:00:00.000 --> 01:00:02.250\nLate"
        val cues = SubtitleParser.parse(vtt)
        assertEquals(Cue(1500, 3000, "Hi"), cues[0])
        assertEquals(Cue(3_600_000, 3_602_250, "Late"), cues[1])
    }

    @Test
    fun readsAss() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\\an8}Hello, world\\Nsecond"
        assertEquals(listOf(Cue(1000, 2500, "Hello, world\nsecond")), SubtitleParser.parse(ass))
    }

    @Test
    fun decodesArabicCodePageAndUtf8() {
        val text = "مرحبا بكم"
        assertEquals(text, SubtitleParser.decode(text.toByteArray(charset("windows-1256")), "ar"))
        assertEquals(text, SubtitleParser.decode(text.toByteArray(Charsets.UTF_8), "ar"))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, SubtitleParser.decode(bom, null))
    }

    @Test
    fun findsTheLineToShow() {
        val cues = listOf(Cue(1000, 2000, "a"), Cue(3000, 4000, "b"))
        assertEquals(-1, SubtitleParser.cueAt(cues, 500))
        assertEquals(0, SubtitleParser.cueAt(cues, 1500))
        assertEquals(-1, SubtitleParser.cueAt(cues, 2500))
        assertEquals(1, SubtitleParser.cueAt(cues, 3999))
        assertEquals(1, SubtitleParser.firstAtOrAfter(cues, 2500))
        assertEquals(2, SubtitleParser.firstAtOrAfter(cues, 9000))
    }

    @Test
    fun readsSearchResults() {
        val json = """{"data":[
            {"id":"abc","language":"en","flags":[],"url":"/x","releases":["Devour.2024.1080p.WEB"],"custom_releases":[],
             "movie":{"title":"Devour","type":"movie","year":2024},"stats":{"downloads":120}},
            {"id":"def","language":"ar","flags":[],"url":"/y","releases":[],"custom_releases":[],
             "movie":{"title":"Devour","type":"movie","year":2024},"stats":{"downloads":5}},
            {"id":"abc","language":"en","releases":[],"custom_releases":[],"movie":{"title":"Devour","year":2024}}
        ],"page":1,"all_pages":1}"""
        val hits = Podnapisi.parseSearch(json)
        assertEquals(listOf("abc", "def"), hits.map { it.id })
        assertEquals("Devour.2024.1080p.WEB", hits[0].release)
        assertEquals("Devour 2024", hits[1].release)
        assertEquals("ar", hits[1].language)
    }

    @Test
    fun takesTheSubtitleOutOfTheZip() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHi\n"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("readme.txt"))
            z.write("x".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("Movie.srt"))
            z.write(srt.toByteArray())
            z.closeEntry()
        }
        val out = Podnapisi.unzipFirstSubtitle(bos.toByteArray())
        assertNotNull(out)
        assertEquals(srt, String(out!!))
    }
}
