### Task 14: Live Fit command layer port with the S85 data table (A9 voice, brief M2, S54, S85) [CORE 2.5 h]
**Stream C · 3 h · depends on: base.**

**Files:**
- round 1 Task 11's, plus `services/voice/.../commands/MarkPhrases.kt`: the **single source** for the S85 tables, holding `MARK`, `RETRO`, `UNDO`, `INTENTS: List<IntentEntry(word, canvasLabel, intentLabel: String /* final IntentLabel name, filled here */)>` and `QUESTION_WORDS`
- tests: `MarkCommandParserTest.kt` (data-driven), `EnglishClausesTest.kt`, `VoiceCommandGroupTest.kt`, `LanguagePackTest.kt`

**Implement:**
- `sealed interface MarkCommand { Mark(intent: String?), Retro, Undo }`.
- Normalise as round 1. A rule matches only at the start of a clause, after an optional filler.
- Apply the question-word veto, keeping the two "can you" / "could you" exceptions.
- **"forget" never matches.**
- An intent is read only after a command, through the optional fillers; negation, conflict or an unknown word → `Mark(null)`.
- **Finalise the `IntentLabel` column** (`SITTING_IDLE`, `WORKOUT`, `COMPUTER_WORK`, `DRIVING`, `VEHICLE_PASSENGER`, `FLIGHT_CABIN`, `WALKING`, `OTHER`) with the S85a mapping (reading and writing → COMPUTER_WORK, travelling → VEHICLE_PASSENGER, riding → OTHER). It also appears in Task 15's generated doc.

**Tests:**
- **`everyListedPhraseParses`:** for each MARK, RETRO and UNDO phrase, alone, with "please", and with a trailing intent.
- **`everyIntentWordAfterCommandParses`:** "mark that, <word>", "mark that, I'm <word>", for every word.
- **`questionFormsNeverParse`**, a data list built from every command phrase: "did you <cmd>", "what did I <verb>", "is this important", "was that important", "should I save this", "how do I bookmark this", "can I note this", "where did I save that", "why did I miss that".
- `canYouMarkThatParses`.
- `forgetNeverMatches` ("forget that", "forget this", "forget the last mark").
- `intentWithoutCommandPassesThrough` ("I'm driving").
- `negatedIntentPlainMark`, `twoIntentsPlainMark`, `unknownWordPlainMark`.
- `undoPhrases`.
- `everyIntentHasFinalIntentLabel` (each name exists in `IntentLabel`) and `ridingMapsToOther`.
- The round 1 negatives ("Mark called me", "what did Mark say", …).
- `EnglishClausesTest`, as Live Fit has it.

**Commands:** `gw.sh :services:voice:testDebugUnitTest --tests 'com.rokid.context.voice.commands.*'`, then the whole module.

**Commit:** `feat(voice): mark-moment command layer adapted from Live Fit with the S85 phrase table, anchored rules, question-word veto and final IntentLabel mapping`

