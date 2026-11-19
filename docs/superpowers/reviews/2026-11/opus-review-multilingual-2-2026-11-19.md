# Re-review of b573738e (fixes for the review-opus-multilingual.md findings), branch feat/v101-ask-eval

Reviewer: Claude Opus, standing in for Codex gpt-6.1-sol at LOW effort. This was a read-only review: no Gradle, no device, no git changes. I checked collisions by simulating `fold()` in Python (scratchpad/coll.py) against the 61 ASCII forms and /usr/share/dict/words.

## P1
None.

## P2
None. The original P2-1 is fixed:
- "tell", "tells", "dud", "aata" and "ata" are stop words, checked before any lookup (IndicLexicon.kt:106).
- "Can you tell me where my keys are?" and "aata hai" now give null (MultilingualAskTest.kt, lexiconIgnoresEnglishQuestionsAndIsBounded).
- The terms are now added in front of the question instead of replacing it (IndicLexicon.kt:135). Any stray hit now only adds extra search words and costs less.
- Correction to the first review: "pal"/"pall" never collided. fold("phal") = "phal", because the trailing-h rule removes only a final 'h'. Stop-listing them is harmless.
- The original P2-2 gating is fixed (AnswerService.kt:242; finding (3) below). The other half of P2-2, rerunning the v1 eval on the dated-query plan, is out of scope here and still open.

## P3
- P3-1 Remaining collisions (finding (1)). After the stop list I could not find 8 more ordinary English words that collide. The dictionary scan found only rare words: chatta, chinee, gaddi, kella, panni.
  - Proper nouns and names that still hit, none of them stop-listed:
    - "Gobi" (desert): cauliflower
    - "Tel" (Aviv): oil
    - "Rasta": road
    - "Seb" (Sebastian): apple
    - "mIRC"/"mirc": chilli
    - "atta" (attaboy): flour
    - "Palak" (a common Indian female given name, exact key): spinach
    - "Khanna" (surname; nn folds to "khana"): food
    - "Allu" (surname; ll folds to "alu"): potato
  - Impact is low now that terms are added rather than substituted. However, Palak, Khanna and Allu contradict the KDoc claim "none is the name of a person" (IndicLexicon.kt:9). The names test (MultilingualAskTest.kt names set) does not include them.
- P3-2 The stop list blocks real romanisations:
  - "aata" is the most common spelling of the flour आटा, not only the verb "comes" (EnglishCommonWords.kt:21).
  - "ata" is another spelling of the same flour (EnglishCommonWords.kt:21).
  - "tala" is a common spelling of the lock ताला (EnglishCommonWords.kt:21).
  - So "aata kahan rakha" no longer expands. Under the additive design, a false "wheat flour" hit costs less than losing this recall. Consider removing "aata" and "tala" from the stop list.
- P3-3 Test coverage (finding (2)). The test checks every exact key against the stop list but deliberately skips the folded forms (IndicLexiconTest.kt, the `exactKeys` loop and its comment). Three folded keys are in fact on the stop list: "ata" (atta), "dud" (doodh) and "tala" (taala). The "English folds onto a key" check only scans the test's own sentences plus the stop words, so it confirms itself and would not catch new collisions. The task statement says the test "runs every key and folded form against it". That is only half true.
- P3-4 Stale docs (finding (4)). The lexicon query is no longer bounded at 12 words or 120 chars: it is up to 12 terms plus 40 question words (IndicLexicon.kt:135). The KDoc at :125-126 still says "at most MAX_WORDS_IN_QUERY words" and "query = the English terms". The query is used only for search, embedding and window relevance (RagCore.kt:205-221), not in the Answer prompt. The probe is now a near-duplicate of the search's tail, so the second LEXICAL pass is mostly redundant.

## Verified OK
- (2) EnglishCommonWords.kt is a plain list of common English words plus five Hinglish verb or spelling forms. It holds no personal data.
- (3) The lexicon fallback runs only when `multilingual && builtIn`. `builtIn` is `plan == FlowPlan.BUILT_IN_QUERY`, a data-class equality check. A custom plan with a condense node cannot be equal to it, so the fallback cannot run for custom plans. The new test flagOnACustomPlanWithACondenseNodeNeverGetsTheLexiconFallback pins this.
- (4) The added terms come only from the fixed ENTRIES table, through `clean()`. The rest of the query is the user's own question words, letters and marks only. None of it reaches the LLM prompt, so it adds no new injection surface.
- (5) The log line `lexicon fallback terms=${words.size}` (AnswerService.kt:185) logs a count only, with no words or question text.

READY
