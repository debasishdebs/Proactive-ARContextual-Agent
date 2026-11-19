package com.rokid.context.orchestration.retrieval

/**
 * The English stop list of [IndicLexicon] (data only, version 1): a compact list of common English words (plus a few Hinglish verb forms that only collide after folding, such as "aata" = comes
 * -> "atta" flour). A token that is on this list never matches the lexicon, neither by its exact spelling nor by a folded one, so "tell" is never the key "tel" (oil). No lexicon key is on this
 * list (a test pins that); a word is added here when an English or verb word folds onto a key.
 */
internal object EnglishCommonWords {
    private const val RAW = """
a about above across add after again ago all also am an and another any anyone are around as ask asked at ate away back bag bags bank bar be because bed been before behind being bell below best
better between big bill bit black blue book boot both box bring brought but buy by call came can car card care carry case catch chair change check clean close coat coffee cold come comes coming
could cup dad dark date day deal did die do does dog done door down draw drew drink drive drove dud due during each early eat eight either else end enough even ever every fan far fast few find fine
first five fix floor food for forgot found four free fresh friend from front full gate gave get gift girl give glass go goes going gone good got great green group had half hall hand has have he hear
heard help her here hers hi high him his hold home hot hotel hour house how i if in inside into is it its job just keep kept key keys kind kitchen knew know lamp last late lay left less let lie life
light like list little live lock long look lost lot loud low made mail make man many map market mat may me meal meet meeting mess met might mind mine minute miss mom moment more morning most move much
must my near need never new next nice night nine no none nor not note nothing now of off office often oil old on once one only open or other our out over own pack paid pal pall pan park parking part
pass past pay pen people pick place plan plane plate play please pocket pot put quick quiet rain read ready real red rest ride right ring road room run said same sat save saw say see seem seen sell
send sent set seven shall she shelf shop short should show shut sit six sleep slow small so some soon sorry speak stay still stop store street such sure table take taken talk tall tea tell tells ten
than thank that the their them then there these they thing think third this those three through time to today together told too took top town train travel tree trip true try turn two under until up
upon us use used very wait walk want was wash watch water way we wear week well went were what when where which while white who whom whose why will window wish with without woman won work would
write wrong yes yet you your aata aate aati ata tala
"""
    val WORDS: Set<String> by lazy { RAW.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet() }

    fun contains(token: String) = token in WORDS
}
