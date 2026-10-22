package com.rokid.context.voice.commands

/** One intent word that may follow a mark command (S85). [intentLabel] is a final `IntentLabel` enum name. */
data class IntentEntry(val word: String, val canvasLabel: String, val intentLabel: String)

/**
 * The single source for the S85 voice phrase tables. The parser and the generated user docs both read this object
 * and nothing else, so they cannot drift. English only. All phrases are lower case, letters, spaces and apostrophes.
 *
 * "forget" is deliberately absent everywhere: it collides with the Forget feature and never matches.
 */
object MarkPhrases {
    /** MOMENT for [t - 10 s, t], t = the push-to-talk start. */
    val MARK: List<String> = listOf(
        "mark that", "mark this", "mark it", "remember this", "remember that", "save this", "save that",
        "note this", "note that", "bookmark this", "bookmark that", "capture this", "this is important", "that was important",
    )

    /** MOMENT for the previous ~10 s (the same anchor), source VOICE. */
    val RETRO: List<String> = listOf("i missed that")

    /** Deletes this listen's mark, or the newest voice/tap mark within 2 min. */
    val UNDO: List<String> = listOf("undo mark", "cancel mark", "remove mark")

    /** Leading fillers, optional, before a command. */
    val FILLERS: Set<String> = setOf("please", "ok", "okay", "hey")

    /** Words that may sit between a command and its intent word ("mark that, I'm just driving"). */
    val INTENT_FILLERS: Set<String> = setOf(
        "i'm", "im", "i", "am", "we're", "were", "we", "are", "just", "currently", "as", "and", "please", "also",
    )

    /** An intent after a command that contains one of these is a negation and saves a plain MOMENT. */
    val NEGATIONS: Set<String> = setOf(
        "not", "no", "never", "don't", "dont", "isn't", "isnt", "aren't", "arent", "wasn't", "wasnt", "won't", "wont", "can't", "cant", "nothing",
    )

    /** A clause whose first word is one of these is never a command (except "can you" / "could you" + a command). */
    val QUESTION_WORDS: Set<String> = setOf(
        "what", "which", "who", "whom", "whose", "where", "when", "why", "how", "is", "are", "am", "was", "were",
        "do", "does", "did", "can", "could", "should", "would", "will", "shall", "may",
    )

    /** The two exceptions to the question-word veto: "can you <command>" and "could you <command>". */
    val POLITE_PREFIXES: List<List<String>> = listOf(listOf("can", "you"), listOf("could", "you"))

    private fun e(word: String, label: String) = IntentEntry(word, word, label)

    val INTENTS: List<IntentEntry> = listOf(
        e("sitting", "SITTING_IDLE"), e("resting", "SITTING_IDLE"), e("relaxing", "SITTING_IDLE"), e("sleeping", "SITTING_IDLE"),
        e("working", "COMPUTER_WORK"), e("studying", "COMPUTER_WORK"), e("reading", "COMPUTER_WORK"), e("writing", "COMPUTER_WORK"),
        e("computer work", "COMPUTER_WORK"),
        e("meeting", "OTHER"), e("talking", "OTHER"), e("conversation", "OTHER"),
        e("eating", "OTHER"), e("cooking", "OTHER"), e("shopping", "OTHER"), e("watching tv", "OTHER"), e("gaming", "OTHER"), e("cleaning", "OTHER"),
        e("driving", "DRIVING"),
        e("passenger", "VEHICLE_PASSENGER"), e("commuting", "VEHICLE_PASSENGER"), e("travelling", "VEHICLE_PASSENGER"),
        // A two-wheeler rider may be the driver, so riding is never guessed (S85a).
        e("riding", "OTHER"),
        e("flying", "FLIGHT_CABIN"),
        e("walking", "WALKING"),
        e("exercising", "WORKOUT"), e("workout", "WORKOUT"), e("running", "WORKOUT"), e("cycling", "WORKOUT"),
        e("other", "OTHER"),
    )

    fun intentFor(word: String): IntentEntry? = INTENTS.firstOrNull { it.word == word }
}
