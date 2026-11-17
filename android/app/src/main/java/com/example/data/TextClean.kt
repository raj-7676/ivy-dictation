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

    /** Every dictation and every History retry goes through this once: fillers out, then numbers as digits. */
    fun afterModel(text: String): String = Numbers.apply(fillers(text).trim())

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

    // ---- Agreement book (Grammar stop only). The small model fixes about half of these (PC test 2026-11-17:
    // "they was", "we needs", "I has", "didn't sent" passed through), so fixed rules finish the job.
    // Laws as in the desktop rulebooks: whole words, fixed lists, a subject only at the start of a clause (so
    // "does he have", "let it go", "make it work" are never touched), and every rule has a must-not-touch test.
    private val thirdOf = mapOf( // base -> he/she/it form
        "need" to "needs", "want" to "wants", "go" to "goes", "do" to "does", "like" to "likes", "know" to "knows",
        "think" to "thinks", "say" to "says", "make" to "makes", "take" to "takes", "get" to "gets", "work" to "works",
        "come" to "comes", "see" to "sees", "look" to "looks", "feel" to "feels", "try" to "tries", "play" to "plays",
        "use" to "uses", "live" to "lives", "love" to "loves", "hate" to "hates", "start" to "starts", "stop" to "stops",
        "tell" to "tells", "give" to "gives", "call" to "calls", "send" to "sends", "keep" to "keeps", "eat" to "eats",
        "write" to "writes", "buy" to "buys", "pay" to "pays", "help" to "helps", "mean" to "means", "seem" to "seems",
        "understand" to "understands", "remember" to "remembers", "agree" to "agrees", "crash" to "crashes",
        "watch" to "watches", "finish" to "finishes", "check" to "checks", "open" to "opens", "show" to "shows",
        "reply" to "replies", "speak" to "speaks", "talk" to "talks", "ask" to "asks", "wait" to "waits",
    )
    private val baseOf = thirdOf.entries.associate { (b, t) -> t to b } + ("has" to "have")
    private val pastToBase = mapOf( // only forms that are never a base verb ("found", "left", "saw", "read" are left out; "got" too: "do you got a minute")
        "went" to "go", "sent" to "send", "knew" to "know", "came" to "come", "ate" to "eat", "took" to "take",
        "gave" to "give", "made" to "make", "told" to "tell", "bought" to "buy", "brought" to "bring",
        "thought" to "think", "wrote" to "write", "ran" to "run", "spoke" to "speak", "broke" to "break",
        "forgot" to "forget", "understood" to "understand", "did" to "do", "met" to "meet", "paid" to "pay",
        "heard" to "hear", "drove" to "drive", "chose" to "choose", "began" to "begin", "drank" to "drink",
        "said" to "say", "called" to "call", "checked" to "check", "asked" to "ask", "wanted" to "want",
        "needed" to "need", "worked" to "work", "tried" to "try", "started" to "start", "finished" to "finish",
        "played" to "play", "looked" to "look", "helped" to "help", "replied" to "reply", "received" to "receive",
        "changed" to "change", "watched" to "watch", "joined" to "join", "reached" to "reach", "liked" to "like",
    )
    // a subject counts only at the start of a clause: text start, after , . ! ? ; : or a joining word
    private const val CS = """(?:^|(?<=[.!?,;:\n]\s)|(?<=\n)|(?<=\b(?:and|but|so|because|when|if|that|then|while|since|although|though|or|until|before|after|where|once|cause)\s))"""
    private const val ADV = """(?:(?:always|never|usually|often|also|just|really|still|only|sometimes|actually|even|already)\s)?"""
    private fun alt(words: Collection<String>) = words.sortedByDescending { it.length }.joinToString("|")
    private fun keepCase(old: String, new: String) = if (old.first().isUpperCase()) new.replaceFirstChar { it.uppercase() } else new

    private val agreeRules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // he don't -> he doesn't (again here: "dont" only became "don't" after the phrase rules ran)
        Regex("""(?i)\b(he|she|it)\s($ADV)don't\b""") to { m -> "${m.groupValues[1]} ${m.groupValues[2]}doesn't" },
        // they/we/you was -> were. I, we, they, he, she are always the subject; "you" and "it" can be the object
        // ("I told you was..." is not a thing, but "make it work" is), so those two only count at a clause start.
        Regex("""(?i)(?:\b(they|we)|$CS(you))\s($ADV)was\b""") to { m ->
            "${m.groupValues[1] + m.groupValues[2]} ${m.groupValues[3]}were" },
        // I/we/they/you + he-form -> base ("we needs", "I has", "they goes")
        Regex("""(?i)(?:\b(I|we|they)|$CS(you))\s($ADV)(${alt(baseOf.keys)})\b""") to { m ->
            "${m.groupValues[1] + m.groupValues[2]} ${m.groupValues[3]}${keepCase(m.groupValues[4], baseOf.getValue(m.groupValues[4].lowercase()))}" },
        // he/she/it + base -> he-form ("he have", "it work", "she want"); never after a helper verb ("does he have",
        // "will she go", "let it go")
        Regex("""(?i)(?:(?<!\b(?:do|does|did|can|could|will|would|should|shall|may|might|must|won't|didn't|doesn't|don't|can't|couldn't|wouldn't|shouldn't|let|make|help|to)\s)\b(he|she)|$CS(it))\s($ADV)(have|${alt(thirdOf.keys)})\b(?!['’])""") to { m ->
            val v = m.groupValues[4].lowercase()
            "${m.groupValues[1] + m.groupValues[2]} ${m.groupValues[3]}${keepCase(m.groupValues[4], if (v == "have") "has" else thirdOf.getValue(v))}" },
        // after do/does/did/not/can't or "he can": the plain verb ("didn't sent", "can you sends", "he will goes")
        Regex("""(?i)\b(didn't|doesn't|don't|did not|does not|do not|can't|cannot|won't|wouldn't|shouldn't|couldn't|(?:do|does|did|can|could|will|would|should)\s(?:I|you|we|they|he|she|it)|(?:I|you|we|they|he|she|it)\s(?:can|could|will|would|should|must|might|may)(?:\snot)?)\s(${alt(pastToBase.keys + baseOf.keys)})\b""") to { m ->
            val v = m.groupValues[2].lowercase()
            "${m.groupValues[1]} ${keepCase(m.groupValues[2], pastToBase[v] ?: baseOf.getValue(v))}" },
        // "to went" -> "to go" (irregular forms only: "used to" and "next to" stay)
        Regex("""(?i)\b(want to|have to|need to|going to|has to|had to|wants to|needs to|try to|able to)\s(went|sent|knew|came|took|gave|made|told|bought|brought|thought|wrote|spoke|broke|forgot|did|met|paid|drove|chose|began|ate)\b""") to { m ->
            "${m.groupValues[1]} ${pastToBase.getValue(m.groupValues[2].lowercase())}" },
        // "the kids was" -> "the kids were": a plural noun after the/my/these..., never -ss/-us/-is words ("the boss
        // was", "the bus was", "the analysis was") or the singular -s words below
        Regex("""(?i)\b(the|my|our|your|their|his|her|these|those|all the|both|some|many)\s(people|children|men|women|guys|(?!(?:news|series|species|lens|gas|bonus|status|virus|campus|focus|physics|maths|mathematics|economics|politics|ethics|athletics|this|plus|canvas|atlas|alias|chaos|christmas|diabetes|thanks|always|perhaps|yes|its|ours|yours|hers|does|goes)\b)[a-z]{2,}[^sui\s]s)\swas\b""") to { m ->
            "${m.groupValues[1]} ${m.groupValues[2]} were" },
        // there is many -> there are many
        Regex("""(?i)\b(there)\s(is|'s|was)\s(many|several|lots of|so many|too many|a few|few)\b""") to { m ->
            "${m.groupValues[1]} ${if (m.groupValues[2].lowercase() == "was") "were" else "are"} ${m.groupValues[3]}" },
        Regex("""(?i)\bsaid me\b""") to { _ -> "told me" },
        Regex("""(?i)\b(I|we|they|you)\s(am|are)\sagree\b""") to { m -> "${m.groupValues[1]} agree" },
        Regex("""(?i)\b(he|she)\sis\sagree\b""") to { m -> "${m.groupValues[1]} agrees" },
        Regex("""(?i)\b(discuss|discussed|discussing)\sabout\b""") to { m -> m.groupValues[1] },
        Regex("""(?i)\bsince\s(\d+|two|three|four|five|six|seven|eight|nine|ten|many|several|a few)\s(years|months|weeks|days|hours|minutes)\b(?!\sago)""") to { m ->
            "for ${m.groupValues[1]} ${m.groupValues[2]}" },
    )

    // ---- Formal book: the desktop's Professional tone (src-tauri/src/rulebooks/tone.rs), same lists and rules.
    // Runs on the model's Formal text, and is the whole Formal stop when the model is missing or fails.
    private val slang = mapOf(
        "gonna" to "going to", "wanna" to "want to", "gotta" to "have to", "kinda" to "kind of", "sorta" to "sort of",
        "dunno" to "do not know", "cuz" to "because", "imma" to "I am going to", "i'ma" to "I am going to",
        "lemme" to "let me", "gimme" to "give me",
    )
    private val fullForms = mapOf(
        "don't" to "do not", "doesn't" to "does not", "didn't" to "did not", "can't" to "cannot", "won't" to "will not",
        "isn't" to "is not", "aren't" to "are not", "wasn't" to "was not", "weren't" to "were not",
        "haven't" to "have not", "hasn't" to "has not", "hadn't" to "had not", "couldn't" to "could not",
        "shouldn't" to "should not", "wouldn't" to "would not", "mustn't" to "must not", "needn't" to "need not",
        "i'm" to "I am", "you're" to "you are", "we're" to "we are", "they're" to "they are",
        "i've" to "I have", "you've" to "you have", "we've" to "we have", "they've" to "they have",
        "i'll" to "I will", "you'll" to "you will", "we'll" to "we will", "they'll" to "they will",
        "he'll" to "he will", "she'll" to "she will", "it'll" to "it will", "that'll" to "that will",
        "you'd" to "you would", "we'd" to "we would", "they'd" to "they would", "he'd" to "he would", "she'd" to "she would",
        "y'all" to "you all",
    )
    private val isHas = listOf("it", "that", "there", "here", "what", "who", "where", "how", "he", "she")
    private val chat = mapOf(
        "yeah" to "yes", "yep" to "yes", "yup" to "yes", "nope" to "no", "btw" to "by the way",
        "fyi" to "for your information", "asap" to "as soon as possible", "thx" to "thank you", "pls" to "please",
        "plz" to "please", "thanks" to "thank you",
    )
    private val wordEnd = """(?![\p{L}\p{N}'’])"""
    private val wordStart = """(?<![\p{L}\p{N}'’])"""

    fun formal(text: String): String {
        var out = text.replace('’', '\'')
        fun swap(map: Map<String, String>) {
            out = Regex("""(?i)$wordStart(${alt(map.keys.map { Regex.escape(it) })})$wordEnd""").replace(out) { m ->
                keepCase(m.value, map.getValue(m.groupValues[1].lowercase()))
            }
        }
        swap(slang)
        out = Regex("""(?i)\boff of\b""").replace(out) { keepCase(it.value, "off") }
        swap(fullForms)
        // x's -> "x has" before been/got/done..., else "x is"
        out = Regex("""(?i)$wordStart(${isHas.joinToString("|")})'s(\s+(?:been|got|gotten|had|done|gone)\b)?""").replace(out) { m ->
            keepCase(m.value, m.groupValues[1].lowercase() + (if (m.groupValues[2].isNotEmpty()) " has" + m.groupValues[2] else " is"))
        }
        out = Regex("""(?i)${wordStart}i'd(\s+(?:been|better|had|done|gone|seen|already|never)\b)?""").replace(out) { m ->
            (if (m.groupValues[1].isNotEmpty()) "I had" else "I would") + m.groupValues[1]
        }
        swap(chat.filterKeys { it != "thanks" })
        out = Regex("""(?i)${wordStart}thanks$wordEnd(?!\s+to\b)""").replace(out) { keepCase(it.value, "thank you") }
        // "Honestly, ..." / "Basically, ..." at a sentence start go; the next word takes the capital
        out = Regex("""(?i)(^|[.!?]\s+)(?:honestly|basically|literally|actually),\s+(\p{L})""").replace(out) { m ->
            m.groupValues[1] + m.groupValues[2].uppercase()
        }
        out = out.replace("?!", "?").replace(Regex("""!+"""), ".")
        out = word("i", false).replace(out) { "I" }
        return out
    }

    fun agreement(text: String): String {
        var out = text
        for ((rx, f) in agreeRules) out = rx.replace(out) { m -> keepCase(m.value, f(m)) }
        return out
    }

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
        out = agreement(out)
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
            // only a stop followed by a space ends a sentence: "Glow.co", "google.com", "3.5" keep their letters
            else if (c == '.' || c == '!' || c == '?') cap = i + 1 >= sb.length || sb[i + 1].isWhitespace()
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
