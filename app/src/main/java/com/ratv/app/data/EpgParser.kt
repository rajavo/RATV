package com.ratv.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Streaming XMLTV parser that keeps only the wanted channels inside a time window. */
object EpgParser {

    private const val MAX_PROGRAMMES = 250_000

    fun parse(input: InputStream, wanted: Set<String>, from: Long, to: Long): Map<String, List<Programme>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val result = HashMap<String, MutableList<Programme>>()
        var count = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && count < MAX_PROGRAMMES) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")?.trim()?.lowercase()
                val start = parseTime(parser.getAttributeValue(null, "start"))
                val stop = parseTime(parser.getAttributeValue(null, "stop"))
                if (channel != null && channel in wanted && start > 0 && stop > start && stop >= from && start <= to) {
                    val programme = readProgramme(parser, start, stop)
                    if (programme != null) {
                        result.getOrPut(channel) { ArrayList() }.add(programme)
                        count++
                    }
                } else {
                    skip(parser)
                }
            }
            event = parser.next()
        }
        result.values.forEach { list -> list.sortBy { it.start } }
        return result
    }

    private fun readProgramme(parser: XmlPullParser, start: Long, stop: Long): Programme? {
        val depth = parser.depth
        var title: String? = null
        var desc: String? = null
        while (true) {
            val e = parser.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && parser.depth == depth) break
            if (e == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> {
                        val t = readText(parser)
                        if (title == null && !t.isNullOrBlank()) title = t.trim()
                    }
                    "desc" -> {
                        val d = readText(parser)
                        if (desc == null && !d.isNullOrBlank()) desc = d.trim().take(400)
                    }
                    else -> skip(parser)
                }
            }
        }
        val t = title ?: return null
        return Programme(start, stop, t, desc)
    }

    private fun readText(parser: XmlPullParser): String? {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Parses XMLTV time such as `20241020200000 +0300`. Returns epoch millis or 0. */
    fun parseTime(value: String?): Long {
        val s = value?.trim() ?: return 0L
        if (s.length < 12) return 0L
        return try {
            val year = s.substring(0, 4).toInt()
            val month = s.substring(4, 6).toInt()
            val day = s.substring(6, 8).toInt()
            val hour = s.substring(8, 10).toInt()
            val minute = s.substring(10, 12).toInt()
            val second = if (s.length >= 14 && s[12].isDigit() && s[13].isDigit()) s.substring(12, 14).toInt() else 0
            var offsetMinutes = 0
            val plus = s.lastIndexOf('+')
            val minus = s.lastIndexOf('-')
            val signIndex = maxOf(plus, minus)
            if (signIndex >= 12 && s.length >= signIndex + 5) {
                val oh = s.substring(signIndex + 1, signIndex + 3).toInt()
                val om = s.substring(signIndex + 3, signIndex + 5).toInt()
                offsetMinutes = (oh * 60 + om) * (if (s[signIndex] == '-') -1 else 1)
            }
            val days = daysFromCivil(year, month, day)
            val epochSeconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
            epochSeconds * 1000L
        } catch (e: Exception) {
            0L
        }
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant). */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
