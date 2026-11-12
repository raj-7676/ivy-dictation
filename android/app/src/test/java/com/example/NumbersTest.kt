package com.example

import com.example.data.Numbers
import com.example.data.TextClean
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same cases as the desktop rulebook's tests (src-tauri/src/rulebooks/numbers.rs), plus the big-amount rule. */
class NumbersTest {
    @Test fun does() {
        val cases = listOf(
            "book a table for two" to "book a table for 2",
            "we need twenty five chairs" to "we need 25 chairs",
            "it costs fifteen hundred rupees" to "it costs ₹1500",
            "send two thousand five hundred dollars" to "send \$2500",
            "about a hundred and fifty people" to "about 150 people",
            "version one point five" to "version 1.5",
            "port six three seven nine" to "port 6379",
            "meet at seven thirty pm" to "meet at 7:30 PM",
            "meet at five pm tomorrow" to "meet at 5 PM tomorrow",
            "at 5 p.m. sharp" to "at 5 PM sharp",
            "at 5pm" to "at 5 PM",
            "at 7.30 pm" to "at 7:30 PM",
            "call at nine oh five am" to "call at 9:05 AM",
            "on march twenty first twenty twenty six" to "on March 21st 2026",
            "the twenty first of March" to "March 21st",
            "May first is a holiday" to "May 1st is a holiday",
            "in nineteen ninety nine" to "in 1999",
            "a discount of twenty percent" to "a discount of 20%",
            "that's 500 rupees and 300 euros." to "that's ₹500 and €300.",
            "run five kilometers then drink two liters" to "run 5 km then drink 2 L",
            "the 128 gig phone" to "the 128 GB phone",
            "driving sixty miles per hour" to "driving 60 mph",
            "it is 20 degrees celsius" to "it is 20°C",
            "five plus ten equals fifteen" to "5 + 10 = 15",
            "x is greater than or equal to 0" to "x is greater than or equal to 0",
            "if x greater than or equal to 0" to "if x >= 0",
            "ten divided by two" to "10 / 2",
            "start a timer for one hour" to "start a timer for 1 hour",
            "one, two, three, go" to "1, 2, 3, go",
            "call plus 91 98765 43210" to "call +91 98765 43210",
            "twenty-five people" to "25 people",
        )
        for ((input, want) in cases) assertEquals("input \"$input\"", want, Numbers.apply(input))
    }

    @Test fun mustNotTouch() {
        for (s in listOf(
            "No one told me.", "The one on the left.", "One of them is mine.", "The two of us went.",
            "Give it a day or two.", "For the first time.", "You may first check the map.", "We march on.",
            "I lost 20 pounds.", "Take it 2 times a day.", "Someone said hello.", "Bachelor's degrees matter.",
            "That's a plus for us.", "Version 2.0 shipped.",
        )) assertEquals(s, Numbers.apply(s))
    }

    /** Yash's rule: digits up to a million, then the scale word instead of a row of zeros. */
    @Test fun bigRoundAmountsGetTheScaleWord() {
        val cases = listOf(
            "Ten thousand people came." to "10000 people came.",
            "six hundred plus five hundred" to "600 + 500",
            "five hundred thousand" to "500000",
            "five billion" to "5 billion",
            "It cost five billion." to "It cost 5 billion.",
            "one million" to "1 million",
            "twelve million people" to "12 million people",
            "one million two hundred thousand" to "1.2 million",
            "five billion dollars" to "\$5 billion",
            "two point five million dollars" to "2.5 million dollars",
            "one million plus two million" to "1 million + 2 million",
            "one million two hundred thirty four thousand five hundred sixty seven" to "1234567",
            "five lakh rupees" to "5 lakh rupees",
            "two crore" to "2 crore",
            "five trillion" to "5 trillion",
            "salary is ₹18,00,000 a year" to "salary is ₹18 lakhs a year",
            "revenue 18,00,000 this month" to "revenue 18 lakhs this month",
            "just 1,00,000." to "just 1 lakh.",
            "rent ₹1,15,000, paid" to "rent ₹1.15 lakhs, paid",
            "budget 2,50,00,000" to "budget 2.5 crores",
            "raised \$2,000,000 today" to "raised \$2 million today",
            "a 2,000,000 user base" to "a 2 million user base",
            "worth 1,500,000,000" to "worth 1.5 billion",
        )
        for ((input, want) in cases) assertEquals("input \"$input\"", want, Numbers.apply(input))
        for (s in listOf("It costs 68,990.", "exactly 1,15,437 votes", "call 9845012367", "UTR 456789", "18 lakhs", "\$150,000 deal", "5 billion")) {
            assertEquals(s, Numbers.apply(s))
        }
    }

    @Test fun idempotentAndKeepsLines() {
        for (s in listOf(
            "meet at seven thirty pm on march twenty first", "twenty five percent of 500 rupees",
            "five billion dollars and ten thousand people", "one million two hundred thousand",
        )) {
            val once = Numbers.apply(s)
            assertEquals(once, Numbers.apply(once))
        }
        assertEquals("2 apples\n\n3 pears", Numbers.apply("two apples\n\nthree pears"))
    }

    @Test fun fillersThenNumbers() {
        assertEquals("So, I need ₹10000 by 5 PM.", TextClean.afterModel("So, um, I need ten thousand rupees by five pm."))
    }

    /** The phone model writes "twenty-first" with a hyphen (seen on the S26, 2026-11-12). */
    @Test fun hyphenatedDay() {
        assertEquals("The meeting is on March 21st.", Numbers.apply("The meeting is on March twenty-first."))
        assertEquals("on March 31st, 2026", Numbers.apply("on the thirty-first of March, twenty twenty six"))
        assertEquals("the twenty-first century", Numbers.apply("the twenty-first century"))
        assertEquals("We march twenty-first in line.", Numbers.apply("We march twenty-first in line."))
    }

    /** A sentence's own full stop after am/pm stays; only the last dot of "p.m." inside a sentence goes. */
    @Test fun fullStopAfterPmStays() {
        assertEquals("Come at 5 PM. Bring it.", Numbers.apply("Come at 5pm. Bring it."))
        assertEquals("Come at 5 PM. Bring it.", Numbers.apply("Come at five pm. Bring it."))
        assertEquals("see you at 5 PM.", Numbers.apply("see you at 5 p.m."))
        assertEquals("at 7:30 PM, okay", Numbers.apply("at 7.30pm, okay"))
        assertEquals("Come at 5 PM. Bring it.", Numbers.apply("Come at 5 PM. Bring it."))
    }
}
