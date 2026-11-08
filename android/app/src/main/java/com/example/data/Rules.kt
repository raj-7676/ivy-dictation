package com.example.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One dictation the bubble typed somewhere. Everything stays on this phone. */
data class Dictation(
    val id: String, val text: String, val app: String, val durationMs: Long, val at: Long,
    val audio: String? = null, // content:// URI of the WAV in Recordings/Ivy Dictation, null if not saved
)

data class Snippet(val id: String, val trigger: String, val expansion: String)

/** How long History keeps dictations. */
enum class Retention(val label: String, val days: Long?) {
    ONE_DAY("1 day", 1), SEVEN_DAYS("7 days", 7), THIRTY_DAYS("30 days", 30), FOREVER("Forever", null)
}

enum class Day { TODAY, YESTERDAY, EARLIER }

data class Stats(val words: Int, val wpm: Int, val streak: Int)

/**
 * Every time-based rule in one place, with "now" and the time zone passed in, so the tests can move the clock
 * (IvyRulesTest). No Android code here.
 */
object Rules {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Drops what is older than the retention period (1 day = the last 24 hours). */
    fun purge(items: List<Dictation>, retention: Retention, now: Long): List<Dictation> {
        val days = retention.days ?: return items
        return items.filter { it.at > now - days * DAY_MS }
    }

    fun date(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    fun day(at: Long, now: Long, zone: ZoneId): Day {
        val d = date(at, zone)
        val today = date(now, zone)
        return when (d) {
            today -> Day.TODAY
            today.minusDays(1) -> Day.YESTERDAY
            else -> Day.EARLIER
        }
    }

    fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotBlank() }

    /**
     * Words dictated in total; speaking speed (words per minute of recording); streak = days in a row with at
     * least one dictation, ending today (or yesterday, so the streak isn't lost before today's first dictation).
     */
    fun stats(items: List<Dictation>, now: Long, zone: ZoneId): Stats {
        val words = items.sumOf { words(it.text) }
        val minutes = items.sumOf { it.durationMs } / 60000.0
        val wpm = if (minutes > 0) (words / minutes).toInt() else 0
        val days = items.map { date(it.at, zone) }.toSet()
        var d = date(now, zone)
        if (d !in days) d = d.minusDays(1)
        var streak = 0
        while (d in days) { streak++; d = d.minusDays(1) }
        return Stats(words, wpm, streak)
    }

    /** The Home numbers: from the permanent counters, so Clear all / retention never reset them. */
    fun lifetimeStats(l: Lifetime, now: Long, zone: ZoneId): Stats {
        val wpm = if (l.ms > 0) (l.words / (l.ms / 60000.0)).toInt() else 0
        val days = l.days.toSet()
        var d = date(now, zone).toEpochDay()
        if (d !in days) d -= 1
        var streak = 0
        while (d in days) { streak++; d-- }
        return Stats(l.words, wpm, streak)
    }

    fun timeAgo(at: Long, now: Long, zone: ZoneId): String {
        val mins = (now - at) / 60000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            day(at, now, zone) == Day.TODAY -> "${mins / 60}h ago"
            day(at, now, zone) == Day.YESTERDAY -> "Yesterday"
            else -> "${java.time.temporal.ChronoUnit.DAYS.between(date(at, zone), date(now, zone))}d ago"
        }
    }

    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayClock = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

    fun timeLabel(at: Long, now: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        return when (day(at, now, zone)) {
            Day.TODAY -> clock.format(t)
            Day.YESTERDAY -> "Yesterday, " + clock.format(t)
            Day.EARLIER -> dayClock.format(t)
        }
    }

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * Snippets: a trigger said anywhere in the dictation, as whole words (any case), becomes its text. Applied
     * last, after the model (same rule as desktop Ivy). Longer triggers first so "my work email" beats "my email".
     */
    fun applySnippets(text: String, snippets: List<Snippet>): String {
        var out = text
        for (s in snippets.filter { it.trigger.isNotBlank() }.sortedByDescending { it.trigger.length }) {
            val words = s.trigger.trim().split(Regex("\\s+")).joinToString("[\\s,]+") { Regex.escape(it) }
            out = Regex("(?<![\\p{L}\\p{N}])$words(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                .replace(out) { s.expansion } // lambda result is literal text ($ and \ stay as typed)
        }
        return out
    }
}
