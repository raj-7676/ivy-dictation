package com.example

import com.example.data.Day
import com.example.data.Dictation
import com.example.data.Retention
import com.example.data.Rules
import com.example.data.Snippet
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** History, retention, streak and snippets with the clock moved by hand (no waiting real days). */
class IvyRulesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun dict(id: String, at: Long, text: String = "one two three", ms: Long = 6000) = Dictation(id, text, "Notes", ms, at)

    private val now = at(2026, 11, 5, 22, 30)
    private val items = listOf(
        dict("a", at(2026, 11, 5, 22, 0)),   // 30 min ago
        dict("b", at(2026, 11, 5, 0, 10)),   // today, just after midnight
        dict("c", at(2026, 11, 4, 23, 50)),  // yesterday late
        dict("d", at(2026, 11, 4, 21, 0)),   // yesterday, 25.5 h ago
        dict("e", at(2026, 11, 1, 9, 0)),    // 4 days ago
        dict("f", at(2026, 10, 29, 9, 0)),   // 7 days 13.5 h ago
        dict("g", at(2026, 10, 10, 9, 0)),   // 26 days ago
        dict("h", at(2026, 9, 1, 9, 0)),     // 65 days ago
    )

    @Test fun retentionKeepsExactlyTheWindow() {
        assertEquals(listOf("a", "b", "c"), Rules.purge(items, Retention.ONE_DAY, now).map { it.id })
        assertEquals(listOf("a", "b", "c", "d", "e"), Rules.purge(items, Retention.SEVEN_DAYS, now).map { it.id })
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), Rules.purge(items, Retention.THIRTY_DAYS, now).map { it.id })
        assertEquals(items, Rules.purge(items, Retention.FOREVER, now))
    }

    @Test fun retentionMovesWithTheClock() {
        // a week later, 7-day retention keeps nothing from this list; one more minute past 24 h drops "a" from 1 day
        assertEquals(0, Rules.purge(items, Retention.SEVEN_DAYS, now + 8L * 86_400_000).size)
        assertEquals(listOf("a"), Rules.purge(items, Retention.ONE_DAY, at(2026, 11, 6, 21, 59)).map { it.id })
        assertEquals(emptyList<String>(), Rules.purge(items, Retention.ONE_DAY, at(2026, 11, 6, 22, 1)).map { it.id })
    }

    @Test fun groupsFollowTheCalendarNotThe24hWindow() {
        assertEquals(Day.TODAY, Rules.day(items[1].at, now, zone))      // 22 h ago but same date
        assertEquals(Day.YESTERDAY, Rules.day(items[2].at, now, zone))  // 40 min before midnight
        assertEquals(Day.EARLIER, Rules.day(items[4].at, now, zone))
        // just after midnight, 22:00 yesterday is "Yesterday" even though it is 2 h ago
        assertEquals(Day.YESTERDAY, Rules.day(items[0].at, at(2026, 11, 6, 0, 5), zone))
    }

    @Test fun labels() {
        assertEquals("30m ago", Rules.timeAgo(items[0].at, now, zone))
        assertEquals("22h ago", Rules.timeAgo(items[1].at, now, zone))
        assertEquals("Yesterday", Rules.timeAgo(items[2].at, now, zone))
        assertEquals("4d ago", Rules.timeAgo(items[4].at, now, zone))
        assertEquals("10:00 PM", Rules.timeLabel(items[0].at, now, zone))
        assertEquals("Yesterday, 11:50 PM", Rules.timeLabel(items[2].at, now, zone))
        assertEquals("Nov 1, 9:00 AM", Rules.timeLabel(items[4].at, now, zone))
        assertEquals("1:05", Rules.duration(65_000))
    }

    @Test fun stats() {
        val s = Rules.stats(items, now, zone)
        assertEquals(24, s.words)                // 8 x 3 words
        assertEquals(30, s.wpm)                  // 24 words in 48 s
        assertEquals(2, s.streak)                // today + yesterday; Nov 3 missing
        // tomorrow before dictating: streak still 2; the day after: broken
        assertEquals(2, Rules.stats(items, at(2026, 11, 6, 9, 0), zone).streak)
        assertEquals(0, Rules.stats(items, at(2026, 11, 7, 9, 0), zone).streak)
        assertEquals(0, Rules.stats(emptyList(), now, zone).wpm)
    }

    @Test fun snippets() {
        val sn = listOf(Snippet("1", "my email", "yash@example.com"), Snippet("2", "my work email", "work@glow.co"))
        assertEquals("Send it to yash@example.com today", Rules.applySnippets("Send it to my email today", sn))
        assertEquals("Mail work@glow.co.", Rules.applySnippets("Mail my work email.", sn))
        assertEquals("Use yash@example.com, please", Rules.applySnippets("Use My Email, please", sn))
        assertEquals("my emails are full", Rules.applySnippets("my emails are full", sn)) // whole words only
        assertEquals("price \$5", Rules.applySnippets("price p5", listOf(Snippet("3", "p5", "\$5"))))
    }

    @Test fun lifetimeStatsIgnoreHistory() {
        val today = java.time.LocalDate.of(2026, 11, 5).toEpochDay()
        val l = com.example.data.Lifetime(600, 300_000, listOf(today - 2, today - 1, today))
        val st = Rules.lifetimeStats(l, now, zone)
        assertEquals(600, st.words); assertEquals(120, st.wpm); assertEquals(3, st.streak)
    }
}
