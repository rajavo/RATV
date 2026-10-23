package com.ratv.kora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DataTest {
    private val sample = """
        {"v":1,"updated":1792600000,"from":"2026-10-21","to":"2026-11-22",
         "leagues":[{"id":"ksa.1","ar":"دوري روشن السعودي","short":"روشن","en":"Saudi Pro League","logo":"https://x/l.png"},
                    {"id":"egy.1","ar":"الدوري المصري الممتاز","short":"المصري","en":"","logo":""}],
         "teams":{"929":{"en":"Al Hilal","ar":"الهلال","logo":"https://x/929.png"},
                  "817":{"en":"Al Nassr","ar":"النصر","logo":""},
                  "99":{"en":"Wolverhampton Wanderers","s":"Wolves","logo":""},
                  "y:الأهلي":{"en":"","ar":"الأهلي","logo":"https://y/a.webp"}},
         "matches":[
           {"id":"700","lg":"ksa.1","t":1792605600,"h":"929","a":"817","st":"post","sn":"FULL_TIME","hs":2,"as":1,"v":"Kingdom Arena","c":"Riyadh",
            "tv":["Thmanyah","MBC Shahid","beIN Sports Mena 2"]},
           {"id":"701","lg":"ksa.1","t":1792600000,"h":"817","a":"99","st":"pre","sn":"SCHEDULED","tbd":1},
           {"id":"y5","lg":"egy.1","t":1792609999,"h":"y:الأهلي","a":"929","st":"pre","sn":"SCHEDULED","n":"الأسبوع الثامن"}
         ]}
    """.trimIndent()

    @Test
    fun readsTheSchedule() {
        val s = ScheduleParser.parse(sample)
        assertEquals(1792600000000L, s.updated)
        assertEquals(2, s.leagues.size)
        assertEquals(3, s.matches.size)
        // Sorted by kick-off.
        assertEquals("701", s.matches[0].id)
        val m = s.matches.first { it.id == "700" }
        assertEquals(1792605600000L, m.time)
        assertEquals(2, m.homeScore)
        assertEquals(1, m.awayScore)
        assertEquals("Kingdom Arena", m.venue)
        assertTrue(s.matches.first { it.id == "701" }.tbd)
        assertEquals("الأسبوع الثامن", s.matches.first { it.id == "y5" }.note)
        assertTrue(m.fromEspn)
        assertTrue(!s.matches.first { it.id == "y5" }.fromEspn)
    }

    @Test
    fun namesAreArabicFirst() {
        val s = ScheduleParser.parse(sample)
        assertEquals("الهلال", s.team("929").name)
        assertEquals("Wolves", s.team("99").name)
        assertEquals("الأهلي", s.team("y:الأهلي").name)
        assertEquals("?", s.team("missing").name)
        assertEquals(1, s.leagueOrder("egy.1"))
    }

    @Test
    fun liveScoresFromEspn() {
        val board = """
            {"events":[
              {"id":"701","competitions":[{"status":{"displayClock":"67'","type":{"state":"in","name":"STATUS_SECOND_HALF"}},
                "competitors":[{"homeAway":"home","score":"2"},{"homeAway":"away","score":"1"}]}]},
              {"id":"999","competitions":[{"status":{"type":{"state":"in","name":"STATUS_FIRST_HALF"}},"competitors":[]}]},
              {"id":"700","competitions":[{"status":{"type":{"state":"post","name":"STATUS_FINAL_PEN"}},
                "competitors":[{"homeAway":"home","score":"1","shootoutScore":4},{"homeAway":"away","score":"1","shootoutScore":3}]}]}
            ]}
        """.trimIndent()
        val live = ScheduleParser.parseLive(board, setOf("700", "701"))
        assertEquals(2, live.size)
        val l = live.getValue("701")
        assertEquals("in", l.state)
        assertEquals("SECOND_HALF", l.status)
        assertEquals(2, l.homeScore)
        assertEquals("67'", l.clock)
        val s = ScheduleParser.parse(sample)
        val m = s.matches.first { it.id == "701" }.withLive(l)
        assertEquals("2 - 1", scoreOf(m))
        assertEquals(Phase.LIVE, statusOf(m, 0).phase)
        assertEquals("67'", statusOf(m, 0).text)
        val pens = s.matches.first { it.id == "700" }.withLive(live.getValue("700"))
        assertEquals("1 - 1 (4-3)", scoreOf(pens))
        assertEquals("انتهت بالترجيح", statusOf(pens, 0).text)
    }

    @Test
    fun statesInArabic() {
        val s = ScheduleParser.parse(sample)
        val pre = s.matches.first { it.id == "y5" }
        assertEquals(Phase.SOON, statusOf(pre, pre.time - 60_000).phase)
        assertEquals("جارية الآن", statusOf(pre, pre.time + 30 * 60_000).text)
        assertEquals("بانتظار النتيجة", statusOf(pre, pre.time + 200 * 60_000).text)
        assertEquals("الوقت لاحقاً", statusOf(s.matches.first { it.id == "701" }, 0).text)
        assertEquals("تأجلت", statusOf(pre.copy(status = "POSTPONED"), 0).text)
        assertEquals("استراحة", statusOf(pre.copy(state = "in", status = "HALFTIME"), 0).text)
        assertNull(scoreOf(pre))
    }

    @Test
    fun arabicDatesAndTimes() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Riyadh"))
        val c = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.OCTOBER, 22, 21, 5)
        }
        val ms = c.timeInMillis
        assertEquals("9:05 م", Fmt.time(ms))
        assertEquals("الخميس", Fmt.weekday(ms))
        assertEquals("22 أكتوبر", Fmt.date(ms))
        val today = Fmt.startOfDay(ms)
        assertEquals("اليوم", Fmt.dayName(today, today))
        assertEquals("بكرة", Fmt.dayName(Fmt.addDays(today, 1), today))
        assertEquals("أمس", Fmt.dayName(Fmt.addDays(today, -1), today))
        assertEquals("السبت", Fmt.dayName(Fmt.addDays(today, 2), today))
        assertEquals("12:30 ص", Fmt.time(today + 30 * 60_000))
        assertEquals("قبل 5 دقايق", Fmt.ago(ms - 5 * 60_000, ms))
        assertEquals("قبل ساعتين", Fmt.ago(ms - 150 * 60_000, ms))
        assertEquals("مباراة وحدة", Fmt.matches(1))
        assertEquals("مباراتين", Fmt.matches(2))
        assertEquals("7 مباريات", Fmt.matches(7))
        assertEquals("811 مباراة", Fmt.matches(811))
        assertEquals("\u200E67'\u200E", Fmt.ltr("67'"))
        assertEquals("استراحة", Fmt.ltr("استراحة"))
    }

    @Test
    fun channelsOfTheArabWorldByTheirUsualNames() {
        val m = ScheduleParser.parse(sample).matches.first { it.id == "700" }
        assertEquals(listOf("Thmanyah", "MBC Shahid", "beIN Sports Mena 2"), m.tv)
        assertEquals(listOf("ثمانية", "beIN SPORTS 2"), Channels.arab(m.tv))
        assertEquals(
            listOf("beIN SPORTS 1", "beIN SPORTS 1 English", "beIN SPORTS MAX 4", "beIN SPORTS XTRA 1", "الكأس 5", "الكأس 6",
                "أبوظبي الرياضية 1", "دبي الرياضية 1", "الشارقة الرياضية", "SSC 1", "العراقية الرياضية"),
            Channels.arab(
                listOf(
                    "beIN Sports Mena 1", "beIN Sports ENG 1", "Arena 1 Premium", "TOD TV", "beIN Sports MAX 4", "beIN Sports XTRA 1",
                    "beIN SPORTS CONNECT", "ALKASS Five", "ALKASS Six", "Shoof", "Abu Dhabi Sports 1", "Dubai Sport 1",
                    "الشارقة الرياضية", "SSC 1", "Al Iraqiya Sports", "Almanasa", "Viaplay Denmark", "stc tv", "starzplay", "شاشا",
                    "beIN Sports France 1", "beIN Sports Mena 1"
                )
            )
        )
        assertTrue(ScheduleParser.parse(sample).matches.first { it.id == "701" }.tv.isEmpty())
    }
}
