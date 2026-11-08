package com.example.data

/**
 * Light, rule-based text cleanup (no model, no Android code, same input = same output).
 *  - [fillers]: runs on EVERY dictation, removes "um / uh / er / hmm" and tidies the commas around them.
 *  - [grammar]: the "Fix grammar" chip. Keeps the casual voice: only adds missing apostrophes ("dont" -> "don't"),
 *    a capital "I", sentence capitals, doubled words ("the the"), common typos and spacing. It does NOT rewrite.
 */
object TextClean {
    // whole-word filler sounds (any length of the vowel / m, any case)
    private val filler = Regex("""(?i)(?<![\p{L}\p{N}'-])(?:u+h+[\s-]*h+u+h+|u+m+[\s-]*h+m+|m+[\s-]*h+m+|m+h+m+|u+h*m+|u+h+|e+r+m*|e+h+m*|h+m+|m{2,}|a+h+m+)(?![\p{L}\p{N}'-])""")
    // "you know", "like", "I mean" ... only when the speaker set them off with commas (so "do you know" and "I like it" stay)
    private val discourse = Regex("""(?i)\s*,\s*(?:you know|i mean|like|basically|actually|kind of|sort of|right)\s*(?=[,.!?])""")
    private val discourseStart = Regex("""(?i)^\s*(?:like|you know|i mean|basically)\s*,\s*""")

    fun fillers(text: String): String {
        if (!filler.containsMatchIn(text) && !discourse.containsMatchIn(text) && !discourseStart.containsMatchIn(text)) return text
        var out = discourseStart.replace(discourse.replace(text, ""), "")
        out = filler.replace(out) { "\u0000" }
        // ", \0," -> ","   |   "\0, " -> ""   |   " \0" -> ""   (the comma that belonged to the filler goes with it)
        out = out.replace(Regex("""\s*,\s*\u0000\s*,"""), ",")
        out = out.replace(Regex("""\u0000\s*,\s*"""), "")
        out = out.replace(Regex("""\s*,?\s*\u0000"""), "")
        out = out.replace("\u0000", "")
        out = out.replace(Regex("""([.!?])\s*\.(?!\.)"""), "$1") // "Okay. Umm." must not end as "Okay.."
        return tidy(out)
    }

    // missing-apostrophe contractions people (and speech models) produce; none of these is a real English word
    private val contractions = mapOf(
        "dont" to "don't", "cant" to "can't", "wont" to "won't", "doesnt" to "doesn't", "didnt" to "didn't",
        "isnt" to "isn't", "wasnt" to "wasn't", "arent" to "aren't", "werent" to "weren't", "hasnt" to "hasn't",
        "havent" to "haven't", "hadnt" to "hadn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
        "wouldnt" to "wouldn't", "youre" to "you're", "theyre" to "they're", "thats" to "that's", "whats" to "what's",
        "theres" to "there's", "heres" to "here's",
    )
    // "Im" / "Ive" are written like that on purpose ("im" alone is a typo for "I'm"; the lowercase word "id", "lets" are real words: left alone)
    private val iForms = mapOf("im" to "I'm", "Im" to "I'm", "ive" to "I've", "Ive" to "I've")
    private val typos = mapOf(
        "recieve" to "receive", "recieved" to "received", "definately" to "definitely", "seperate" to "separate",
        "seperated" to "separated", "occured" to "occurred", "truely" to "truly", "untill" to "until",
        "alot" to "a lot", "neccessary" to "necessary", "tommorow" to "tomorrow", "tomorow" to "tomorrow",
        "goverment" to "government", "calender" to "calendar", "wierd" to "weird", "accomodate" to "accommodate",
        "teh" to "the", "adn" to "and", "thier" to "their", "becuase" to "because", "beleive" to "believe",
    )
    // words that may never be doubled on purpose ("that that", "had had" are real English, so they are left alone)
    private val dupable = setOf("the", "a", "an", "to", "of", "in", "on", "is", "and", "it", "we", "you", "my", "for", "so", "i", "me", "be")

    private fun word(w: String, ignoreCase: Boolean) =
        Regex("""(?<![\p{L}\p{N}'])${Regex.escape(w)}(?![\p{L}\p{N}'])""", if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

    // spoken-English slips that a speech model writes down faithfully
    private val phrases = listOf(
        Regex("""(?i)\b(should|could|would|must|might) of\b""") to { m: MatchResult -> m.groupValues[1] + " have" },
        Regex("""(?i)\b(he|she|it) don't\b""") to { m: MatchResult -> m.groupValues[1] + " doesn't" },
        Regex("""(?i)\b(has|have|had) went\b""") to { m: MatchResult -> m.groupValues[1] + " gone" },
        Regex("""(?i)\b(has|have|had) (saw|seen)\b""") to { m: MatchResult -> m.groupValues[1] + " seen" },
        Regex("""(?i)\b(has|have|had) did\b""") to { m: MatchResult -> m.groupValues[1] + " done" },
        Regex("""(?i)\bmore (better|easier|faster|bigger|smaller|cheaper)\b""") to { m: MatchResult -> m.groupValues[1] },
        Regex("""(?i)\byour welcome\b""") to { _: MatchResult -> "you're welcome" },
        Regex("""(?i)\bits a\b""") to { _: MatchResult -> "it's a" },
        Regex("""(?i)\bi seen\b""") to { _: MatchResult -> "I've seen" },
        Regex("""\b([Aa]) (?=(?!one\b|once\b|use\b|user\b|useful\b|unit\b|union\b|university\b|unique\b|usual\b|european\b)[aeiAEI]\w|(?:o(?!ne\b|nce\b)\w))""") to { m: MatchResult -> m.groupValues[1] + "n " },
    )

    fun grammar(text: String): String {
        var out = text
        for ((rx, f) in phrases) out = rx.replace(out) { m -> val r = f(m); if (m.value.first().isUpperCase()) r.replaceFirstChar { it.uppercase() } else r }
        for ((bad, good) in contractions + typos) {
            out = word(bad, true).replace(out) { m ->
                if (m.value.first().isUpperCase()) good.replaceFirstChar { it.uppercase() } else good
            }
        }
        for ((bad, good) in iForms) out = word(bad, false).replace(out) { good }
        out = word("i", false).replace(out) { "I" }                                                       // i -> I
        out = Regex("""(?i)\b(${dupable.joinToString("|")})(\s+\1\b)+""").replace(out) { it.groupValues[1] } // the the
        return tidy(out)
    }

    /** Spaces, spaces before punctuation, and a capital at the start of every sentence. */
    fun tidy(text: String): String {
        var s = text.replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\s+([,.!?;:])"""), "$1")
            .replace(Regex(""",\s*,+"""), ",")
            .trim()
        s = s.replace(Regex("""^[,;:\s]+"""), "")
        // capital at the start and after . ! ?
        val sb = StringBuilder(s)
        var cap = true
        for (i in sb.indices) {
            val c = sb[i]
            if (cap && c.isLetter()) { sb.setCharAt(i, c.uppercaseChar()); cap = false }
            else if (c == '.' || c == '!' || c == '?') cap = true
            else if (!c.isWhitespace() && c != '"' && c != '\'' && c != '(') cap = false
        }
        return sb.toString()
    }

    /** The user's own words (Dictionary) and snippet texts: never to be reworded. They are swapped for placeholders before
     *  any rewrite and put back, spelled exactly as saved, afterwards. */
    class Masked(val text: String, val map: List<Pair<String, String>>)

    fun mask(text: String, terms: List<String>): Masked {
        var out = text
        val map = ArrayList<Pair<String, String>>()
        for (t in terms.map { it.trim() }.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }) {
            val rx = Regex("""(?<![\p{L}\p{N}])${Regex.escape(t)}(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
            if (!rx.containsMatchIn(out)) continue
            val ph = "Qz${map.size}z"
            out = rx.replace(out) { ph }
            map += ph to t
        }
        return Masked(out, map)
    }

    fun unmask(out: String, m: Masked): String {
        var r = out
        for ((ph, t) in m.map) r = Regex(Regex.escape(ph), RegexOption.IGNORE_CASE).replace(r) { t }
        return r
    }

    /** True if every protected word that went in came back out (a rewrite that lost one is not trusted). */
    fun keptAll(out: String, m: Masked) = m.map.all { (ph, _) -> out.contains(ph, ignoreCase = true) }
}
