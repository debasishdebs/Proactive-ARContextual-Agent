package com.rokid.context.orchestration.retrieval

import java.text.Normalizer
import java.util.Locale

/**
 * Multilingual Ask, option B (design 2026-11-19): a small curated, versioned, DATA-ONLY lexicon of plain Hindi/Hinglish food, household and place words and the English words a caption would
 * use for them. Used only as the offline floor when the query condenser gave nothing (offline, timeout, broken reply) and the developer flag `multilingualAsk` is ON. Pure and local: nothing
 * here reaches the network or a log (no question text, no word). Every entry is a common noun or verb-free object word. What is guaranteed (pinned by a test): no entry is a person's name that is not also a plain object word. Two entries are
 plain words that are ALSO given names and are kept on purpose: "palak" (spinach) and "aloo" (potato). Surnames and name spellings that merely FOLD onto a key ("khanna" -> khana, "allu" -> aloo)
 are on the stop list, so they never expand. An expansion only ADDS terms to the question, so a given-name hit on "palak" costs at most two extra search words.
 Stop-list note: the spellings "aata", "ata" and "tala" are blocked on purpose (they are ambiguous with English-like tokens and verb forms: "aata" = comes). Cost: "aata kahan rakha" does not expand;
 write "atta" or "taala" (or Devanagari) for flour and lock. This is documented, not worked around.
 *
 * Matching folds the usual romanisation variants (`aa`->`a`, `ee`->`i`, `oo`->`u`, doubled letters, a trailing `h`), so "karela", "karelaa" and "kareela" are one key. Only whole words match.
 */
object IndicLexicon {
    /** Bump when an entry changes; a plain integer, not a hash. */
    const val VERSION = 1
    const val MAX_TERMS_PER_WORD = 3
    const val MAX_WORDS_IN_QUERY = 12
    const val MAX_QUESTION_WORDS = 40

    /** Romanised word (or Devanagari form, same entry separated by '|') -> up to [MAX_TERMS_PER_WORD] English terms, most likely caption wording first. */
    internal val ENTRIES: List<Pair<String, List<String>>> = listOf(
        // vegetables and fruit
        "karela|करेला" to listOf("bitter gourd", "bitter melon"),
        "bhindi|भिंडी" to listOf("okra", "lady finger"),
        "lauki|लौकी" to listOf("bottle gourd", "opo squash"),
        "baingan|बैंगन" to listOf("brinjal", "eggplant"),
        "aloo|आलू" to listOf("potato", "potatoes"),
        "pyaz|प्याज" to listOf("onion", "onions"),
        "tamatar|टमाटर" to listOf("tomato", "tomatoes"),
        "gajar|गाजर" to listOf("carrot", "carrots"),
        "mooli|मूली" to listOf("radish"),
        "palak|पालक" to listOf("spinach", "greens"),
        "gobi|गोभी" to listOf("cauliflower", "cabbage"),
        "matar|मटर" to listOf("peas", "green peas"),
        "mirch|मिर्च" to listOf("chilli", "chili", "pepper"),
        "adrak|अदरक" to listOf("ginger"),
        "lehsun|लहसुन" to listOf("garlic"),
        "dhaniya|धनिया" to listOf("coriander", "cilantro"),
        "nimbu|नींबू" to listOf("lemon", "lime"),
        "kela|केला" to listOf("banana", "bananas"),
        "seb|सेब" to listOf("apple", "apples"),
        "angoor|अंगूर" to listOf("grapes"),
        "bhutta|भुट्टा" to listOf("corn", "corn cob", "roasted corn"),
        "sabzi|सब्ज़ी" to listOf("vegetables", "vegetable"),
        "phal|फल" to listOf("fruit", "fruits"),
        // kitchen and food
        "paneer|पनीर" to listOf("paneer", "cottage cheese"),
        "atta|आटा" to listOf("wheat flour", "atta"),
        "chawal|चावल" to listOf("rice"),
        "dal|दाल" to listOf("lentils", "dal"),
        "roti|रोटी" to listOf("flatbread", "roti", "chapati"),
        "dahi|दही" to listOf("curd", "yogurt"),
        "doodh|दूध" to listOf("milk"),
        "chai|चाय" to listOf("tea"),
        "paani|पानी" to listOf("water"),
        "chini|चीनी" to listOf("sugar"),
        "namak|नमक" to listOf("salt"),
        "tel|तेल" to listOf("oil", "cooking oil"),
        "ghee|घी" to listOf("ghee", "clarified butter"),
        "mithai|मिठाई" to listOf("sweets", "dessert"),
        "nashta|नाश्ता" to listOf("breakfast", "snack"),
        "khana|खाना" to listOf("food", "meal"),
        // household
        "chulha|चूल्हा" to listOf("stove", "gas stove"),
        "chaabi|चाबी" to listOf("key", "keys"),
        "taala|ताला" to listOf("lock", "padlock"),
        "darwaza|दरवाज़ा" to listOf("door"),
        "khidki|खिड़की" to listOf("window"),
        "bijli|बिजली" to listOf("electricity", "light"),
        "pankha|पंखा" to listOf("fan", "ceiling fan"),
        "kursi|कुर्सी" to listOf("chair"),
        "mez|मेज़" to listOf("table"),
        "bistar|बिस्तर" to listOf("bed"),
        "chhata|छाता" to listOf("umbrella"),
        "thaila|थैला" to listOf("bag", "carry bag"),
        "bartan|बर्तन" to listOf("utensils", "dishes", "vessels"),
        "kadhai|कढ़ाई" to listOf("wok", "pan"),
        "patakha|पटाखा|patakhe|पटाखे" to listOf("crackers", "firecrackers", "fireworks"),
        // places and travel
        "bazaar|बाज़ार" to listOf("market"),
        "dukaan|दुकान" to listOf("shop", "store", "stall"),
        "gaadi|गाड़ी" to listOf("car", "vehicle"),
        "mandir|मंदिर" to listOf("temple"),
        "ghar|घर" to listOf("home", "house"),
        "rasta|रास्ता" to listOf("road", "way"),
    )

    /** Exact (unfolded) spellings, lowercase. */
    private val EXACT: Map<String, List<String>> by lazy {
        buildMap { for ((forms, terms) in ENTRIES) for (f in forms.split('|')) putIfAbsent(normalize(f), terms.take(MAX_TERMS_PER_WORD)) }
    }

    /** Folded spellings: the variants of the same keys. */
    private val INDEX: Map<String, List<String>> by lazy {
        buildMap { for ((forms, terms) in ENTRIES) for (f in forms.split('|')) putIfAbsent(fold(f), terms.take(MAX_TERMS_PER_WORD)) }
    }

    /** Every exact spelling (tests). */
    internal fun exactKeys(): Set<String> = EXACT.keys

    internal fun normalize(word: String): String = Normalizer.normalize(word, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /** The lexicon terms of one token, or null: a word on the English stop list never matches; the exact spelling is tried before the folded one. */
    internal fun lookup(token: String): List<String>? {
        val t = normalize(token)
        if (EnglishCommonWords.contains(t)) return null
        return EXACT[t] ?: INDEX[fold(t)]
    }

    /** All keys, folded (tests). */
    internal fun keys(): Set<String> = INDEX.keys

    /** Lowercase, NFKC, then the romanisation folds. Devanagari (no ASCII letters) is only NFKC'd. */
    internal fun fold(word: String): String {
        val w = normalize(word)
        if (w.any { it.code > 0x24F }) return w
        val sb = StringBuilder()
        var i = 0
        val v = w.replace("aa", "a").replace("ee", "i").replace("oo", "u").replace("ii", "i").replace("uu", "u")
        while (i < v.length) { if (sb.isEmpty() || sb.last() != v[i]) sb.append(v[i]); i++ }
        return sb.toString().removeSuffix("h").ifEmpty { sb.toString() }
    }

    /**
     * The condensed form of [question] from the lexicon: [QueryCondenser.Condensed.query] = the English terms of every lexicon word in the question (in order, no duplicates, cleaned to at most [MAX_WORDS_IN_QUERY] lexicon words)
     * followed by the question's own words (only its first [MAX_QUESTION_WORDS] words are read), so the query is at most [MAX_WORDS_IN_QUERY] + [MAX_QUESTION_WORDS] words, [probe] = the question as asked (the second own-language word search of the condenser contract), lang "hi". Null = no lexicon word, the raw question is searched as before. Never throws.
     */
    fun expand(question: String): QueryCondenser.Condensed? {
        val words = question.split(Regex("[^\\p{L}\\p{M}]+")).filter { it.isNotEmpty() }.take(MAX_QUESTION_WORDS)
        val terms = LinkedHashSet<String>()
        for (w in words) lookup(w)?.let { terms.addAll(it) }
        if (terms.isEmpty()) return null
        val added = QueryCondenser.clean(terms.joinToString(" ")) ?: return null
        // The lexicon terms are ADDED to the question's own words, never a replacement (names and English nouns stay in the search).
        val query = (added + " " + words.joinToString(" ")).trim()
        return QueryCondenser.Condensed(query, question.trim().takeIf { it.isNotEmpty() }, "hi")
    }
}
