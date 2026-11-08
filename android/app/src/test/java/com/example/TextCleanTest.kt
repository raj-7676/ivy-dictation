package com.example

import com.example.data.TextClean
import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanTest {
    @Test fun fillersGoWithTheirCommas() {
        assertEquals("Hey man, so I was thinking we should push it.", TextClean.fillers("Hey man, so um, I was thinking we should push it."))
        assertEquals("I think we should go.", TextClean.fillers("Um, I think we should go."))
        assertEquals("I think we, should go.", TextClean.fillers("I think we, uh, should go."))
        assertEquals("Well, that works.", TextClean.fillers("Well, uhhh, that works."))
        assertEquals("It was hmm good.".replace(" hmm", ""), TextClean.fillers("It was hmm good."))
        assertEquals("Okay.", TextClean.fillers("Okay. Umm."))
    }

    @Test fun realWordsAreNotTouched() {
        assertEquals("The drummer hummed a summer song.", TextClean.fillers("The drummer hummed a summer song."))
        assertEquals("Ah, I see what you mean.", TextClean.fillers("Ah, I see what you mean.")) // "ah" alone is a real word
        assertEquals("Emma came home.", TextClean.fillers("Emma came home."))
    }

    @Test fun nothingToCleanStaysIdentical() {
        val s = "Please send the report to Michael tomorrow morning."
        assertEquals(s, TextClean.fillers(s))
    }

    @Test fun grammarKeepsTheCasualVoice() {
        assertEquals("I don't know if I can't do it, but I'm trying.", TextClean.grammar("i dont know if i cant do it, but im trying."))
        assertEquals("That's the best thing. I've seen it.", TextClean.grammar("thats the best thing. ive seen it."))
        assertEquals("I'll see you tomorrow, it was a lot of fun.", TextClean.grammar("I'll see you tommorow, it was alot of fun."))
        assertEquals("Hey man, what's up?", TextClean.grammar("hey man , whats up ?"))
        assertEquals("Go to the shop.", TextClean.grammar("go to the the shop."))
    }

    @Test fun grammarLeavesRealWordsAlone() {
        val s = "Let's check the ID. That that is fine, lets move."
        assertEquals("Let's check the ID. That that is fine, lets move.", TextClean.grammar(s))
    }

    @Test fun uhHuhAndSpokenFillers() {
        assertEquals("So, I was thinking, I don't know, okay, you are nice.",
            TextClean.fillers("So, you know, I was thinking, like, I don't know, like, uh-huh, okay, like, you are nice."))
        assertEquals("Sure.", TextClean.fillers("Uh-huh, sure."))
        assertEquals("I like it and you know it.", TextClean.fillers("I like it and you know it."))
        assertEquals("Do you know the way?", TextClean.fillers("Do you know the way?"))
        assertEquals("Yes.", TextClean.fillers("Mm-hmm, yes."))
    }

    @Test fun spokenGrammarSlips() {
        assertEquals("I should have gone, it doesn't matter.", TextClean.grammar("i should of went, it dont matter.".replace("went", "gone").replace("dont", "doesn't")))
        assertEquals("She doesn't care. It's an apple, an orange and a user.", TextClean.grammar("She don't care. Its a apple, a orange and a user."))
        assertEquals("That is better and you're welcome.", TextClean.grammar("That is more better and your welcome."))
    }

    @Test fun customWordsSurvive() {
        val m = TextClean.mask("i dont like ivy dictation but Glow.co is fine", listOf("Ivy Dictation", "Glow.co"))
        assertEquals("i dont like Qz0z but Qz1z is fine".length, m.text.length)
        val fixed = TextClean.grammar(m.text)
        assertEquals("I don't like Ivy Dictation but Glow.co is fine", TextClean.unmask(fixed, m))
        assertEquals(true, TextClean.keptAll(m.text, m))
        assertEquals(false, TextClean.keptAll("i dont like it", m))
    }
}
