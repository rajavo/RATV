package com.ratv.kora

import java.util.Calendar
import java.util.Locale

/** Dates, times and match states in Arabic, with the digits people read in the Gulf (0-9). */
object Fmt {
    private val WEEKDAYS = arrayOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    private val MONTHS = arrayOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    )
    const val DAY_MS = 24L * 60L * 60L * 1000L

    private fun cal(ms: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ms }

    fun startOfDay(ms: Long): Long = cal(ms).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** The start of the day [n] days after the one of [dayStart] (safe across clock changes). */
    fun addDays(dayStart: Long, n: Int): Long = cal(dayStart).apply { add(Calendar.DAY_OF_MONTH, n) }.let {
        startOfDay(it.timeInMillis)
    }

    /** "9:30 م" */
    fun time(ms: Long): String {
        val c = cal(ms)
        val h = c.get(Calendar.HOUR_OF_DAY)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%d:%02d %s", h12, c.get(Calendar.MINUTE), if (h < 12) "ص" else "م")
    }

    fun weekday(ms: Long): String = WEEKDAYS[cal(ms).get(Calendar.DAY_OF_WEEK) - 1]

    /** "23 أكتوبر" */
    fun date(ms: Long): String {
        val c = cal(ms)
        return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
    }

    /** "اليوم", "بكرة", "أمس", or the weekday. */
    fun dayName(dayStart: Long, today: Long): String = when (dayStart) {
        today -> "اليوم"
        addDays(today, 1) -> "بكرة"
        addDays(today, -1) -> "أمس"
        else -> weekday(dayStart)
    }

    /** "الخميس 23 أكتوبر" */
    fun fullDate(ms: Long): String = weekday(ms) + " " + date(ms)

    /** "قبل 5 دقايق" */
    fun ago(ms: Long, now: Long): String {
        val min = ((now - ms) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "الحين"
            min == 1L -> "قبل دقيقة"
            min == 2L -> "قبل دقيقتين"
            min <= 10 -> "قبل $min دقايق"
            min < 60 -> "قبل $min دقيقة"
            min < 120 -> "قبل ساعة"
            min < 180 -> "قبل ساعتين"
            min < 11 * 60 -> "قبل ${min / 60} ساعات"
            min < 24 * 60 -> "قبل ${min / 60} ساعة"
            else -> fullDate(ms) + " " + time(ms)
        }
    }
}

enum class Phase { SOON, LIVE, DONE, OFF }

/** What a match card shows in place of the score, and how it looks. */
class StatusView(val text: String, val phase: Phase)

fun statusOf(m: Match, now: Long): StatusView {
    when (m.status) {
        "POSTPONED" -> return StatusView("تأجلت", Phase.OFF)
        "CANCELED", "CANCELLED" -> return StatusView("ألغيت", Phase.OFF)
        "ABANDONED" -> return StatusView("أُوقفت", Phase.OFF)
        "SUSPENDED" -> return StatusView("متوقفة", Phase.OFF)
        "FORFEIT" -> return StatusView("انسحاب", Phase.OFF)
    }
    return when (m.state) {
        "in" -> StatusView(
            when (m.status) {
                "HALFTIME" -> "استراحة"
                "SHOOTOUT" -> "ركلات الترجيح"
                "END_OF_REGULATION", "OVERTIME", "FIRST_HALF_EXTRA_TIME", "SECOND_HALF_EXTRA_TIME", "HALFTIME_ET",
                "END_OF_EXTRATIME" -> "أشواط إضافية"
                else -> m.clock?.takeIf { it.isNotBlank() && it != "0'" } ?: "مباشر"
            },
            Phase.LIVE
        )
        "post" -> StatusView(
            when (m.status) {
                "FINAL_AET" -> "انتهت بعد التمديد"
                "FINAL_PEN" -> "انتهت بالترجيح"
                else -> "انتهت"
            },
            Phase.DONE
        )
        else -> when {
            m.tbd -> StatusView("الوقت لاحقاً", Phase.SOON)
            // Only the schedule refresh tells how a match of the Arab leagues went.
            now >= m.time && now < m.time + 115 * 60_000L -> StatusView("جارية الآن", Phase.LIVE)
            now >= m.time + 115 * 60_000L -> StatusView("بانتظار النتيجة", Phase.DONE)
            else -> StatusView(Fmt.time(m.time), Phase.SOON)
        }
    }
}

/** "2 - 1", with the penalties when there were some: "1 - 1 (4-3)". Null before kick-off. */
fun scoreOf(m: Match): String? {
    val h = m.homeScore ?: return null
    val a = m.awayScore ?: return null
    if (m.state == "pre") return null
    val pens = if (m.homePens != null && m.awayPens != null) " (${m.homePens}-${m.awayPens})" else ""
    return "$h - $a$pens"
}
