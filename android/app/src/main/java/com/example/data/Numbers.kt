package com.example.data

import java.util.Locale

/**
 * Numbers are written as digits: a port of desktop Ivy's rulebook 4 (src-tauri/src/rulebooks/numbers.rs), so the
 * phone writes numbers the way the laptop does. Keep the two in step. No model, no Android code.
 *
 * N1 Dates: "March twenty first" / "the twenty first of March" -> "March 21st".
 * N2 Years: "twenty twenty six" -> 2026 (1950-2099 only).
 * N3 Times: "seven thirty pm" -> "7:30 PM", "5pm" -> "5 PM".
 * N4 Number words -> digits: "ten thousand" -> 10000, "one point five" -> 1.5, "six three seven nine" -> 6379.
 *    "one" / "zero" alone, "the two of us", "a day or two" and ordinals outside dates stay words.
 * N5 Percent, money, units after a number: "20 percent" -> "20%", "500 rupees" -> "₹500", "5 kilometers" -> "5 km".
 * N6 Simple math between numbers: "600 plus 500" -> "600 + 500".
 * N7 Phone country codes: "plus 91 98765 43210" -> "+91 98765 43210".
 * N8 Big round amounts keep the scale word instead of a row of zeros: "five billion" -> "5 billion",
 *    "18,00,000" -> "18 lakhs", "$2,000,000" -> "$2 million". Below 1 million (1 lakh in Indian grouping) and
 *    amounts that are not round ("1,15,437") stay digits.
 */
object Numbers {
    private val SMALL = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L, "seven" to 7L,
        "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L,
        "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L,
    )
    private val TENS = mapOf(
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L, "sixty" to 60L, "seventy" to 70L,
        "eighty" to 80L, "ninety" to 90L,
    )
    private val SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val ORDINALS = mapOf(
        "first" to 1L, "second" to 2L, "third" to 3L, "fourth" to 4L, "fifth" to 5L, "sixth" to 6L, "seventh" to 7L,
        "eighth" to 8L, "ninth" to 9L, "tenth" to 10L, "eleventh" to 11L, "twelfth" to 12L, "thirteenth" to 13L,
        "fourteenth" to 14L, "fifteenth" to 15L, "sixteenth" to 16L, "seventeenth" to 17L, "eighteenth" to 18L,
        "nineteenth" to 19L, "twentieth" to 20L, "thirtieth" to 30L,
    )
    private val MONTHS = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november",
        "december",
    )
    private val MONTH_LEAD = setOf(
        "in", "on", "by", "until", "till", "since", "from", "of", "next", "last", "this", "early", "late", "mid",
        "before", "after", "through", "every", "during",
    )
    /** Words after "one"/"zero" that make it a quantity, so it becomes a digit. */
    private val UNIT_WORDS = setOf(
        "percent", "per", "dollar", "dollars", "rupee", "rupees", "euro", "euros", "yen", "hour", "hours", "minute",
        "minutes", "second", "seconds", "week", "weeks", "month", "months", "year", "years", "am", "pm", "a.m", "p.m",
        "o'clock", "kilometer", "kilometers", "kilometre", "kilometres", "km", "kilogram", "kilograms", "kg", "gram",
        "grams", "meter", "meters", "metre", "metres", "mile", "miles", "liter", "liters", "litre", "litres",
        "gb", "mb", "tb", "kb", "gig", "gigs", "gigabyte", "gigabytes", "megabyte", "megabytes", "terabyte",
        "terabytes", "degree", "degrees", "point", "inch", "inches", "foot", "feet", "lakh", "crore", "k",
    )
    private val WESTERN = listOf(Triple(1_000_000_000L, "billion", "billion"), Triple(1_000_000L, "million", "million"))
    private val INDIAN = listOf(Triple(10_000_000L, "crore", "crores"), Triple(100_000L, "lakh", "lakhs"))

    // A token is one whitespace-separated piece; its core is the token without leading and trailing punctuation.
    private class Tok(val l: String, val c: String, val r: String)

    private fun split3(tok: String): Tok {
        val start = tok.indexOfFirst { it.isLetterOrDigit() }
        if (start < 0) return Tok(tok, "", "")
        val end = tok.indexOfLast { it.isLetterOrDigit() } + 1
        return Tok(tok.substring(0, start), tok.substring(start, end), tok.substring(end))
    }

    private fun coreLower(tok: String) = split3(tok).c.lowercase()
    private fun digit(c: Char) = c in '0'..'9'
    private fun isNumberWord(w: String) = w in SMALL || w in TENS || w in SCALES || w == "hundred"
    private fun isDigits(s: String) = s.isNotEmpty() && s.all { digit(it) || it == ',' || it == '.' } && s.any { digit(it) }
    private fun bare(tok: String) = split3(tok).let { it.l.isEmpty() && it.r.isEmpty() }
    private fun two(n: Long) = n.toString().padStart(2, '0')

    fun apply(text: String): String = text.split('\n').joinToString("\n") { line ->
        var toks = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        toks = dates(toks)
        toks = years(toks)
        toks = times(toks)
        toks = numberWords(toks)
        toks = afterNumber(toks)
        toks = math(toks)
        toks = phone(toks)
        toks = bigAmounts(toks)
        toks.joinToString(" ")
    }

    // ---------------------------------------------------------------- N8 big round amounts

    /** The scale word for a big round amount ("5 billion"), or null if it is small or not exact in 2 decimals. */
    private fun scaled(v: Long, scales: List<Triple<Long, String, String>>): String? {
        for ((unit, one, many) in scales) {
            if (v >= unit && v % (unit / 100) == 0L) {
                val x = String.format(Locale.US, "%.2f", v.toDouble() / unit).trimEnd('0').trimEnd('.')
                return "$x ${if (x == "1") one else many}"
            }
        }
        return null
    }

    /** Only numbers written with commas or a currency sign: phone numbers and IDs never are. */
    private fun bigAmounts(toks: List<String>): List<String> = toks.map { tok ->
        val t = split3(tok)
        val groups = t.c.split(',')
        if (t.c.isEmpty() || !groups.all { g -> g.isNotEmpty() && g.all(::digit) }) return@map tok
        val v = groups.joinToString("").toLongOrNull() ?: return@map tok
        val foreign = t.l.endsWith('$') || t.l.endsWith('€') || t.l.endsWith('£')
        val indianCommas = groups.size >= 3 && groups.last().length == 3 && groups.subList(1, groups.size - 1).all { it.length == 2 }
        val westernCommas = groups.size >= 3 && groups.drop(1).all { it.length == 3 }
        val indian = t.l.endsWith('₹') || (indianCommas && !foreign)
        val western = !indian && (foreign || westernCommas)
        val x = (if (indian) scaled(v, INDIAN) else if (western) scaled(v, WESTERN) else null) ?: return@map tok
        "${t.l}$x${t.r}"
    }

    // ---------------------------------------------------------------- N1 dates

    /** An ordinal day at toks[i]: "first", "twenty first", "21st". Returns (day, tokens used). */
    private fun ordinalDay(toks: List<String>, i: Int): Pair<Long, Int>? {
        val w = coreLower(toks.getOrNull(i) ?: return null)
        ORDINALS[w]?.let { return it to 1 }
        // "twenty-first" as one word (the phone model writes the hyphen)
        if (w.startsWith("twenty-") || w.startsWith("thirty-")) {
            val base = if (w[0] == 't' && w[1] == 'w') 20L else 30L
            val d = ORDINALS[w.substringAfter('-')]
            if (d != null && d < 10 && base + d <= 31) return (base + d) to 1
        }
        if ((w == "twenty" || w == "thirty") && bare(toks[i])) {
            val base = if (w == "twenty") 20L else 30L
            val d = ORDINALS[coreLower(toks.getOrNull(i + 1) ?: return null)]
            if (d != null && d < 10 && base + d <= 31) return (base + d) to 2
        }
        val digits = w.takeWhile(::digit)
        val rest = w.substring(digits.length)
        if (digits.isNotEmpty() && rest in setOf("st", "nd", "rd", "th")) {
            val d = digits.toLongOrNull() ?: return null
            if (d in 1..31) return d to 1
        }
        return null
    }

    private fun suffix(d: Long) = when {
        d % 100 in 11..13 -> "th"
        d % 10 == 1L -> "st"
        d % 10 == 2L -> "nd"
        d % 10 == 3L -> "rd"
        else -> "th"
    }

    private fun monthAt(toks: List<String>, i: Int): String? {
        val w = coreLower(toks[i])
        if (w !in MONTHS) return null
        val capitalized = toks[i].firstOrNull { it.isLetter() }?.isUpperCase() == true
        if ((w == "may" || w == "march") && !capitalized) {
            val prev = if (i > 0) coreLower(toks[i - 1]) else ""
            if (prev !in MONTH_LEAD) return null
        }
        return w.replaceFirstChar { it.uppercase() }
    }

    private fun dates(toks: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            // "March twenty first" / "March 21st"
            val month = monthAt(toks, i)
            if (month != null && bare(toks[i])) {
                val od = ordinalDay(toks, i + 1)
                if (od != null) {
                    val (d, used) = od
                    out += month
                    out += "$d${suffix(d)}${split3(toks[i + used]).r}"
                    i += 1 + used
                    continue
                }
            }
            // "the twenty first of March" -> "March 21st"
            val od = ordinalDay(toks, i)
            if (od != null) {
                val (d, used) = od
                if (i + used + 1 < toks.size && coreLower(toks[i + used]) == "of" && bare(toks[i + used - 1])) {
                    val m = monthAt(toks, i + used + 1)
                    if (m != null) {
                        if (out.isNotEmpty() && coreLower(out.last()) == "the" && bare(out.last())) out.removeAt(out.size - 1)
                        out += m
                        out += "$d${suffix(d)}${split3(toks[i + used + 1]).r}"
                        i += used + 2
                        continue
                    }
                }
            }
            out += toks[i]
            i++
        }
        return out
    }

    // ---------------------------------------------------------------- N2 years

    private fun years(toks: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            val century = when (coreLower(toks[i])) { "twenty" -> 20L; "nineteen" -> 19L; else -> null }
            if (century != null && bare(toks[i])) {
                // the rest must be a plain 10..99 number of 1-2 words, not followed by a scale word
                val a = toks.getOrNull(i + 1)?.let(::coreLower) ?: ""
                val b = toks.getOrNull(i + 2)?.let(::coreLower) ?: ""
                val t = TENS[a]
                val s = SMALL[a]
                val (v, used) = when {
                    t != null -> SMALL[b].let { u -> if (u != null && u in 1..9 && bare(toks[i + 1])) (t + u) to 2 else t to 1 }
                    s != null -> s to 1
                    else -> 0L to 0
                }
                val nextAfter = toks.getOrNull(i + 1 + used)?.let(::coreLower) ?: ""
                val year = century * 100 + v
                if (used > 0 && v >= 10 && year in 1950..2099 && !isNumberWord(nextAfter)) {
                    out += "${split3(toks[i]).l}$year${split3(toks[i + used]).r}"
                    i += 1 + used
                    continue
                }
            }
            out += toks[i]
            i++
        }
        return out
    }

    // ---------------------------------------------------------------- N3 times

    /** am/pm at toks[i]: "pm", "p.m.", "PM", or the two tokens "p m". Returns ("PM", tokens used, trailing punctuation). */
    private fun meridiem(toks: List<String>, i: Int): Triple<String, Int, String>? {
        val t = toks.getOrNull(i) ?: return null
        val compact = t.lowercase().filter { it.isLetter() }
        when (compact) {
            "am" -> return Triple("AM", 1, trail(toks, i, split3(t).c))
            "pm" -> return Triple("PM", 1, trail(toks, i, split3(t).c))
            "a", "p" -> {
                val n = toks.getOrNull(i + 1) ?: return null
                if (n.lowercase().filter { it.isLetter() } == "m" && bare(t.trimEnd('.'))) {
                    return Triple(if (compact == "a") "AM" else "PM", 2, trail(toks, i + 1, split3(n).c))
                }
            }
        }
        return null
    }

    /**
     * What follows am/pm in toks[i]. The last dot of "p.m." belongs to the abbreviation and goes, but a full stop
     * after "pm" or at the end of the line is the sentence's own and stays (the desktop book drops that one too).
     */
    private fun trail(toks: List<String>, i: Int, meridiem: String): String {
        val r = split3(toks[i]).r
        return if (meridiem.contains('.') && i < toks.size - 1) r.trimStart('.') else r
    }

    /** Minutes said after an hour: "fifteen", "thirty", "forty five", "oh five" -> (minutes, tokens used). */
    private fun minutes(toks: List<String>, i: Int): Pair<Long, Int>? {
        val a = coreLower(toks.getOrNull(i) ?: return null)
        if (a == "oh" || a == "o") {
            val b = coreLower(toks.getOrNull(i + 1) ?: return null)
            return SMALL[b]?.takeIf { it in 1..9 }?.let { it to 2 }
        }
        val t = TENS[a]?.takeIf { it <= 50 }
        if (t != null) {
            val b = toks.getOrNull(i + 1)
            if (b != null && bare(toks[i])) SMALL[coreLower(b)]?.takeIf { it in 1..9 }?.let { return (t + it) to 2 }
            return t to 1
        }
        return SMALL[a]?.takeIf { it in 10..19 }?.let { it to 1 }
    }

    private fun times(toks: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            val here = split3(toks[i])
            val lead = here.l
            // spoken hour (+ minutes) + am/pm
            val h = SMALL[here.c.lowercase()]?.takeIf { it in 1..12 }
            if (h != null) {
                val mer = meridiem(toks, i + 1)
                if (mer != null) {
                    out += "$lead$h"
                    out += mer.first + mer.third
                    i += 1 + mer.second
                    continue
                }
                val mins = if (bare(toks[i])) minutes(toks, i + 1) else null
                val mer2 = mins?.let { meridiem(toks, i + 1 + it.second) }
                if (mins != null && mer2 != null) {
                    out += "$lead$h:${two(mins.first)}"
                    out += mer2.first + mer2.third
                    i += 1 + mins.second + mer2.second
                    continue
                }
            }
            // written time: "5pm", "5:30pm", "7.30" + am/pm, "5" + "p.m."
            val core = here.c.lowercase()
            val digits = core.takeWhile { digit(it) || it == ':' || it == '.' }
            val rest = core.substring(digits.length).filter { it.isLetter() }
            if (digits.isNotEmpty() && digit(digits[0])) {
                val clock = normalizeClock(digits)
                if (clock != null && (rest == "am" || rest == "pm")) {
                    out += "$lead$clock"
                    out += rest.uppercase() + trail(toks, i, core.substring(digits.length))
                    i++
                    continue
                }
                val mer = if (clock != null && rest.isEmpty() && here.r.isEmpty()) meridiem(toks, i + 1) else null
                if (mer != null) {
                    out += "$lead$clock"
                    out += mer.first + mer.third
                    i += 1 + mer.second
                    continue
                }
            }
            out += toks[i]
            i++
        }
        return out
    }

    /** "7" -> "7", "7:30" -> "7:30", "7.30" -> "7:30" (a clock time, not a decimal, because am/pm follows). */
    private fun normalizeClock(d: String): String? {
        val parts = d.split(':', '.')
        val h = parts[0].toLongOrNull() ?: return null
        if (h !in 1..12) return null
        return when (parts.size) {
            1 -> h.toString()
            2 -> parts[1].toLongOrNull()?.takeIf { it < 60 && parts[1].length == 2 }?.let { "$h:${two(it)}" }
            else -> null
        }
    }

    // ---------------------------------------------------------------- N4 number words

    private enum class Last { START, A, SMALL, TENS, TENS_UNIT, HUNDRED, SCALE, AND }

    /** A cardinal at words[i..]. Returns (value, tokens used). Punctuation inside stops it. */
    private fun parseCardinal(words: List<String>, toks: List<String>, i: Int): Pair<Long, Int>? {
        var total = 0L
        var cur = 0L
        var last = Last.START
        var used = 0
        var j = i
        while (j < words.size) {
            val w = words[j]
            val next = words.getOrNull(j + 1) ?: ""
            val n = SMALL[w]
            val t = TENS[w]
            val s = SCALES[w]
            val opens = last == Last.START || last == Last.HUNDRED || last == Last.SCALE || last == Last.AND
            val counted = last == Last.A || last == Last.SMALL || last == Last.TENS || last == Last.TENS_UNIT
            val ok = when {
                n != null -> (opens || (last == Last.TENS && n in 1..9)).also {
                    if (it) { cur += n; last = if (last == Last.TENS) Last.TENS_UNIT else Last.SMALL }
                }
                t != null -> opens.also { if (it) { cur += t; last = Last.TENS } }
                w == "hundred" -> (counted && cur < 100).also { if (it) { cur = maxOf(cur, 1) * 100; last = Last.HUNDRED } }
                s != null -> (counted || last == Last.HUNDRED).also {
                    if (it) { total += maxOf(cur, 1) * s; cur = 0; last = Last.SCALE }
                }
                w == "and" -> ((last == Last.HUNDRED || last == Last.SCALE) && (next in SMALL || next in TENS)).also { if (it) last = Last.AND }
                w == "a" -> (last == Last.START && (next == "hundred" || next in SCALES)).also { if (it) last = Last.A }
                else -> false
            }
            if (!ok) break
            j++
            used = j - i
            // punctuation after this word ends the number ("two, three")
            if (split3(toks[j - 1]).r.isNotEmpty()) break
        }
        // never end on "and" / "a"
        while (used > 0 && (words[i + used - 1] == "and" || words[i + used - 1] == "a")) used--
        if (used == 0 || last == Last.A) return null
        return (total + cur) to used
    }

    private fun numberWords(toks: List<String>): List<String> {
        // words of hyphenated numbers ("twenty-five") are split first
        val flat = ArrayList<String>()
        for (t in toks) {
            val p = split3(t)
            if (p.c.contains('-') && p.c.lowercase().split('-').all(::isNumberWord)) {
                val parts = p.c.split('-')
                parts.forEachIndexed { k, part -> flat += (if (k == 0) p.l else "") + part + (if (k == parts.size - 1) p.r else "") }
            } else {
                flat += t
            }
        }
        val words = flat.map(::coreLower)
        fun oneDigit(k: Int) = SMALL[words[k]]?.let { it <= 9 } == true
        val out = ArrayList<String>()
        var i = 0
        while (i < flat.size) {
            // 3+ single digits said one by one: a code, PIN, port or phone number
            var k = i
            while (k < flat.size && oneDigit(k) && (k == i || split3(flat[k - 1]).r.isEmpty())) k++
            if (k - i >= 3) {
                out += split3(flat[i]).l + words.subList(i, k).joinToString("") { SMALL.getValue(it).toString() } + split3(flat[k - 1]).r
                i = k
                continue
            }
            val pc = parseCardinal(words, flat, i)
            if (pc != null) {
                val (value, used) = pc
                val first = words[i]
                val next = words.getOrNull(i + used) ?: ""
                val next2 = words.getOrNull(i + used + 1) ?: ""
                val prev = if (i > 0) words[i - 1] else ""
                val prevIsNum = i > 0 && (isDigits(prev) || isNumberWord(prev))
                val nextIsNum = isDigits(next) || isNumberWord(next)
                // "one" / "zero" alone are words unless a quantity follows ("one hour") or they sit in a list of numbers
                val lonePronoun = used == 1 && (first == "one" || first == "zero") && next !in UNIT_WORDS && !prevIsNum && !nextIsNum
                // "the two of us", "a day or two"
                val idiom = (next == "of" && next2 in setOf("us", "them", "you", "these", "those")) ||
                    (prev == "or" && (next.isEmpty() || split3(flat[i + used - 1]).r.isNotEmpty()))
                if (lonePronoun || idiom) {
                    out += flat[i]
                    i++
                    continue
                }
                // digits stay ungrouped (10000, not 10,000), like the training targets
                var text = value.toString()
                var extra = 0
                // decimals: "one point five", "2 point 0"
                if (next == "point" && split3(flat[i + used - 1]).r.isEmpty()) {
                    val dec = StringBuilder()
                    var d = i + used + 1
                    while (d < flat.size && (oneDigit(d) || (words[d].length == 1 && isDigits(words[d])))) {
                        dec.append(SMALL[words[d]]?.toString() ?: words[d])
                        d++
                        if (split3(flat[d - 1]).r.isNotEmpty()) break
                    }
                    if (dec.isNotEmpty()) {
                        text = "$value.$dec"
                        extra = d - (i + used)
                    }
                } else if (scaled(value, WESTERN) != null) {
                    // a big round amount gets its commas here, so N8 turns it into "5 billion" (also after "$")
                    text = String.format(Locale.US, "%,d", value)
                }
                out += split3(flat[i]).l + text + split3(flat[i + used + extra - 1]).r
                i += used + extra
                continue
            }
            out += flat[i]
            i++
        }
        return out
    }

    // ---------------------------------------------------------------- N5 percent, money, units

    private fun afterNumber(toks: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            val p = split3(toks[i])
            if (isDigits(p.c) && p.r.isEmpty() && i + 1 < toks.size && split3(toks[i + 1]).l.isEmpty()) {
                val n = p.c
                val n1 = coreLower(toks[i + 1])
                val n2 = toks.getOrNull(i + 2)?.let(::coreLower) ?: ""
                val n3 = toks.getOrNull(i + 3)?.let(::coreLower) ?: ""
                val t1 = split3(toks[i + 1]).r
                val degrees = n1 == "degrees" || n1 == "degree"
                val abbr = unitAbbrev(n1)
                val (s, used) = when {
                    n1 == "percent" -> "$n%" to 1
                    n1 == "per" && n2 == "cent" -> "$n%" to 2
                    n1 == "dollars" || n1 == "dollar" -> "\$$n" to 1
                    n1 == "rupees" || n1 == "rupee" -> "₹$n" to 1
                    n1 == "euros" || n1 == "euro" -> "€$n" to 1
                    n1 == "yen" -> "¥$n" to 1
                    n1 == "miles" && n2 == "per" && n3 == "hour" -> "$n mph" to 3
                    (n1 == "kilometers" || n1 == "kilometres") && n2 == "per" && n3 == "hour" -> "$n km/h" to 3
                    degrees && (n2 == "celsius" || n2 == "centigrade") && t1.isEmpty() -> "$n°C" to 2
                    degrees && n2 == "fahrenheit" && t1.isEmpty() -> "$n°F" to 2
                    degrees -> "$n°" to 1
                    abbr != null -> "$n $abbr" to 1
                    else -> "" to 0
                }
                if (used > 0) {
                    out += p.l + s + split3(toks[i + used]).r
                    i += used + 1
                    continue
                }
            }
            out += toks[i]
            i++
        }
        return out
    }

    private fun unitAbbrev(w: String): String? = when (w) {
        "kilometer", "kilometers", "kilometre", "kilometres", "km" -> "km"
        "centimeter", "centimeters", "centimetre", "centimetres", "cm" -> "cm"
        "millimeter", "millimeters", "millimetre", "millimetres", "mm" -> "mm"
        "meter", "meters", "metre", "metres" -> "m"
        "kilogram", "kilograms", "kilo", "kilos", "kg" -> "kg"
        "milligram", "milligrams", "mg" -> "mg"
        "gram", "grams" -> "g"
        "milliliter", "milliliters", "millilitre", "millilitres", "ml" -> "mL"
        "liter", "liters", "litre", "litres" -> "L"
        "kilobyte", "kilobytes", "kb" -> "KB"
        "megabyte", "megabytes", "mb" -> "MB"
        "gigabyte", "gigabytes", "gig", "gigs", "gb" -> "GB"
        "terabyte", "terabytes", "tb" -> "TB"
        else -> null
    }

    // ---------------------------------------------------------------- N6 math

    private fun operand(tok: String) = split3(tok).c.let { isDigits(it) || it in setOf("x", "y", "z", "n") }

    private fun math(toks: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            if (out.isNotEmpty() && operand(out.last()) && split3(out.last()).r.isEmpty()) {
                val w = List(5) { k -> toks.getOrNull(i + k)?.let(::coreLower) ?: "" }
                val orEqualTo = w[2] == "or" && w[3] == "equal" && w[4] == "to"
                val (sym, used) = when {
                    w[0] == "greater" && w[1] == "than" && orEqualTo -> ">=" to 5
                    w[0] == "less" && w[1] == "than" && orEqualTo -> "<=" to 5
                    w[0] == "is" && w[1] == "equal" && w[2] == "to" -> "=" to 3
                    w[0] == "not" && w[1] == "equal" && w[2] == "to" -> "!=" to 3
                    w[0] == "divided" && w[1] == "by" -> "/" to 2
                    w[0] == "multiplied" && w[1] == "by" -> "×" to 2
                    w[0] == "greater" && w[1] == "than" -> ">" to 2
                    w[0] == "less" && w[1] == "than" -> "<" to 2
                    w[0] == "plus" -> "+" to 1
                    w[0] == "minus" -> "-" to 1
                    w[0] == "times" -> "×" to 1
                    w[0] == "equals" -> "=" to 1
                    else -> "" to 0
                }
                if (used > 0 && i + used < toks.size && operand(toks[i + used]) && toks.subList(i, i + used).all(::bare)) {
                    out += sym
                    out += toks[i + used]
                    i += used + 1
                    continue
                }
            }
            out += toks[i]
            i++
        }
        return out
    }

    // ---------------------------------------------------------------- N7 phone country codes

    private fun phone(toks: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            val prevNum = out.lastOrNull()?.let { isDigits(split3(it).c) } == true
            if (coreLower(toks[i]) == "plus" && bare(toks[i]) && !prevNum && i + 2 < toks.size) {
                val code = toks[i + 1]
                val c2 = split3(toks[i + 2]).c
                if (code.length <= 3 && code.all(::digit) && c2.length >= 3 && c2.all(::digit)) {
                    out += "+$code"
                    i += 2
                    continue
                }
            }
            out += toks[i]
            i++
        }
        return out
    }
}
