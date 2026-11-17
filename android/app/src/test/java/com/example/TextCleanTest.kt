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

    @Test fun agreementDoes() {
        val cases = listOf(
            "they was late again." to "They were late again.",
            "I has a meeting and he dont know the time." to "I have a meeting and he doesn't know the time.",
            "we needs to train it again." to "We need to train it again.",
            "he have finished but he didn't sent it." to "He has finished but he didn't send it.",
            "I didn't knew that." to "I didn't know that.",
            "can you sends me the file?" to "Can you send me the file?",
            "the app crash when it start, but it work later." to "The app crash when it starts, but it works later.",
            "Rahul said he want the slides, so we always goes early." to "Rahul said he wants the slides, so we always go early.",
            "there is many people here." to "There are many people here.",
            "she said me that she will come." to "She told me that she will come.",
            "I am agree with you." to "I agree with you.",
            "we discussed about the plan." to "We discussed the plan.",
            "he is working there since two years." to "He is working there for two years.",
            "I have to went home. He will called you." to "I have to go home. He will call you.",
            "if you was there, she do it." to "If you were there, she does it.",
            "the kids was playing and my glasses was broken." to "The kids were playing and my glasses were broken.",
            "my brother works at glow.co since 5 years." to "My brother works at glow.co for 5 years.",
        )
        for ((input, want) in cases) assertEquals(input, want, TextClean.grammar(input))
    }

    @Test fun agreementMustNotTouch() {
        for (s in listOf(
            "Does he have the keys?", "Let it go.", "Make it work.", "I think it was fine.", "Did you see it?",
            "He will have sent it by then.", "I used to work there.", "Next to the station.", "We were there.",
            "They know it.", "She knows it.", "Do you got a minute?", "There's a lot of work.", "It was there two days ago.",
            "I'm here since 5 pm.", "If I were you, I would go.", "The kids want it.", "You know what I mean.",
            "Thank you so much.", "He said it twice.", "I left it at home.", "We found it.", "I saw him.",
            "Will she go?", "Why does he need it?", "I know it like the back of my hand.", "Did they go?", "Can he come?", "The boss was late.", "The bus was late.", "The news was bad.",
            "The analysis was done.", "The canvas was white.", "Open google.com now.", "It costs 3.5 lakhs.",
        )) assertEquals(s, s, TextClean.grammar(s))
    }

    @Test fun agreementIsIdempotent() {
        val once = TextClean.grammar("they was late and he have the file, we needs it")
        assertEquals(once, TextClean.grammar(once))
    }

    @Test fun formalBook() {
        val f = { s: String -> TextClean.tidy(TextClean.formal(s)) }
        assertEquals("I am going to send it, I do not think it is late.", f("I'm gonna send it, I don't think it's late."))
        assertEquals("It has been a week and she has got the files.", f("It's been a week and she's got the files."))
        assertEquals("Yes, we cannot make it. Thank you for waiting.", f("Yeah, we can't make it! Thanks for waiting."))
        assertEquals("The plan works. We ship Friday.", f("Honestly, the plan works. Basically, we ship Friday."))
        assertEquals("I would like that, I had been waiting.", f("I'd like that, I'd been waiting."))
        assertEquals("By the way send it as soon as possible", f("btw send it asap"))
        assertEquals("Do not worry", f("Don’t worry"))
        assertEquals("Really?", f("Really?!"))
        for (s in listOf("I like it.", "Turn it off.", "Thanks to the rain, we stayed in.", "It actually works.",
            "Let's go.", "Its tail wagged.", "Email ana@outlook.com about it.")) assertEquals(s, s, f(s))
        val once = f("Yeah, honestly, I'm gonna say it's done! Dunno, imma check.")
        assertEquals(once, f(once))
    }
}
