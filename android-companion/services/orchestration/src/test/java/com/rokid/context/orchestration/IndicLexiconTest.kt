package com.rokid.context.orchestration

import com.rokid.context.orchestration.retrieval.EnglishCommonWords
import com.rokid.context.orchestration.retrieval.IndicLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Indic lexicon must never fire on plain English (review P2-1): stop list, whole tokens, exact spelling before folding, terms added to the question. */
class IndicLexiconTest {
    private val sentences = listOf(
        "Can you tell me where my keys are?", "Where did I leave the car keys this morning?", "Did I lock the door before I left home?", "What time is the meeting with the team?",
        "Who was at the meeting on Monday?", "Where did I park the car yesterday?", "Is there parking near the office?", "Tell me what I said about the parking permit.",
        "What did I buy at the market last week?", "Where is my pal from the gym staying?", "Did I pay the bill at the hotel?", "What did I put in the kitchen drawer?",
        "Where is the kettle in the kitchen?", "I left a note on the kitchen table.", "Which train did I take to the airport?", "Where did I book the hotel for the trip?",
        "What time does the flight land tomorrow?", "Tell me about my trip to the mountains.", "Did I see a dud battery in the drawer?", "Who told me about the new road?",
        "Where did I leave my umbrella?", "Did I close the window in the bedroom?", "What was the name of the street near the hotel?", "When is my next meeting with the manager?",
        "Which bus goes to the station?", "Did I take my bag to the meeting?", "Where are the spare keys for the garage?", "Did anyone tell you about the meeting room?",
        "What did I eat for lunch at the office?", "Where did I put the bills and the receipts?", "Show me the photo of the parking lot.", "I forgot where I put my wallet in the kitchen.",
        "How long was the train trip to the city?", "What did the driver tell me about the road?", "Who did I meet at the airport?", "Where is the nearest pharmacy to the hotel?",
        "Did I turn off the light in the kitchen?", "What time is the dinner at the hotel?", "Where did I leave the map of the town?", "Is the meeting room free at noon?",
        "Tell me when the parking closes.", "Did I lock my bike near the station?", "What did she tell you about the trip?", "A dud of an idea for the weekend trip.",
        "What is the code for the hotel door?", "Where is my coat, I left it near the front door.",
    )

    @Test fun ordinaryEnglishSentencesProduceZeroLexiconHits() {
        assertTrue(sentences.size >= 40)
        for (s in sentences) assertNull(s, IndicLexicon.expand(s))
    }

    @Test fun everyKeyAndItsFoldedFormIsNotOnTheStopListAndEveryStopWordIsSilent() {
        // a stop word is never a key (else real Hinglish would be blocked), exact or folded
        for (k in IndicLexicon.exactKeys()) assertFalse("key on the stop list: $k", EnglishCommonWords.contains(k))
        // (a folded form such as "ata" may be stopped on purpose: it is not a spelling a person writes for a key, and "aata" is the verb "comes")
        // every common word: no hit, whatever its spelling folds to
        for (w in EnglishCommonWords.WORDS) {
            assertNull(w, IndicLexicon.lookup(w)); assertNull(w, IndicLexicon.lookup(w.uppercase())); assertNull(w, IndicLexicon.expand("is the $w here?"))
        }
        // the folded form of an English word that lands on a key must itself be a stop word (found by folding): tell/tel, dud/doodh, aata/atta ...
        val englishLikeTokens = sentences.flatMap { it.split(Regex("[^A-Za-z]+")) }.filter { it.isNotEmpty() }.map { it.lowercase() }.toSet() + EnglishCommonWords.WORDS
        for (w in englishLikeTokens) {
            if (IndicLexicon.fold(w) in IndicLexicon.keys()) assertTrue("English word folds onto a key and is not stopped: $w", EnglishCommonWords.contains(w) || w in IndicLexicon.exactKeys())
        }
        for (w in listOf("tell", "tells", "pal", "pall", "dud", "pan", "aata", "ata", "mat", "oil")) assertTrue(w, EnglishCommonWords.contains(w))
    }

    @Test fun realHinglishStillHitsExactSpellingsFoldedSpellingsAndDevanagari() {
        assertEquals(listOf("bitter gourd", "bitter melon"), IndicLexicon.lookup("karela"))
        assertEquals(listOf("bitter gourd", "bitter melon"), IndicLexicon.lookup("Karelaa")) // folded
        assertEquals(listOf("bitter gourd", "bitter melon"), IndicLexicon.lookup("करेला"))
        assertEquals(listOf("oil", "cooking oil"), IndicLexicon.lookup("tel")) // exact
        assertEquals(listOf("wheat flour", "atta"), IndicLexicon.lookup("atta"))
        assertEquals(listOf("lentils", "dal"), IndicLexicon.lookup("dal"))
        assertEquals(listOf("milk"), IndicLexicon.lookup("doodh"))
        assertNull("only whole tokens", IndicLexicon.lookup("telephone")); assertNull(IndicLexicon.lookup("seb apple"))
        assertNull(IndicLexicon.expand("telephone and dalton and cattle"))
    }

    @Test fun theLexiconTermsAreAddedToTheQuestionNeverReplaceIt() {
        val q = "Priya ne market mein karela dikha tha kya?"
        val c = IndicLexicon.expand(q)!!
        assertEquals("hi", c.lang); assertEquals(q, c.probe)
        assertTrue(c.query, c.query.startsWith("bitter gourd bitter melon "))
        for (w in listOf("Priya", "market", "mein", "karela", "dikha")) assertTrue("$w kept in ${c.query}", c.query.split(" ").contains(w))
        // exact before folded: an exact hit and a folded variant in one question both resolve, in order
        assertNotNull(IndicLexicon.expand("tel aur aloo"))
        assertTrue(IndicLexicon.expand("tel aur aloo")!!.query.startsWith("oil cooking oil potato"))
        val big = IndicLexicon.expand(IndicLexicon.ENTRIES.joinToString(" ") { it.first.substringBefore('|') })!!
        assertTrue(big.query.split(" ").size <= IndicLexicon.MAX_WORDS_IN_QUERY + IndicLexicon.MAX_QUESTION_WORDS)
    }
}
