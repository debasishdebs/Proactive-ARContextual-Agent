# Review of 1707b367 (multilingual Ask, flag multilingualAsk), branch feat/v101-ask-eval

Reviewer: Claude Opus (stand-in for Codex gpt-6.1-sol, LOW effort). Read-only; no Gradle, no device, no git changes.

## P1
None.

## P2
- P2-1 IndicLexicon.kt:98-104 (fold) together with :58 `tel`: when you fold the English word "tell" by collapsing doubled letters, you get the key `tel`. So "Can you tell me where my keys are?", with the flag ON and the condenser failed (offline or after the 3 s timeout), expands to the query "oil cooking oil". Other false hits: "pal"/"pall" and Hinglish "pal" (moment) fold to `phal` (:45, trailing h), so they become fruit. Hinglish "aata"/"aata hai" (comes) folds to `atta` (:48), so it becomes wheat flour. English "dud" folds to `doodh` (:53), so it becomes milk. Impact: RagQuery.search replaces the question for the embedding, the primary memory search and the photo search (RagCore.kt:205-221). The raw question only survives as the word probe, so these asks get worse retrieval. The impact is limited because the flag is opt-in and this happens only offline, and the answer is never blocked. Fix options: (a) stop collapsing doubled consonants for short keys, or add a stop list ("tell", "pal", "pall", "aata", "dud"); (b) or (better) add the lexicon terms to the question's own topic words instead of replacing them, e.g. `search = q + " " + terms`, so non-lexicon words such as names and English nouns are kept. MultilingualAskTest.kt:163 claims that "tel" does not collide, but it never tests "tell". Add "tell me ..." and "aata hai" cases.
- P2-2 condense_query.txt (the shared seed): the "12 words in all" rule and the synonyms rule also change the dated-query pipeline (plan.condense). The synonyms rule is scoped to non-English questions, so English twins should barely move. But the commit has no v1 eval rerun on the dated-query plan (the newest run dir is 20261116). Rerun qna-set-v1 on the dated-query pipeline before you claim no regression. Related: with the flag ON, the lexicon fallback also applies to custom plans that carry a condense node (AnswerService.kt:241, `if (multilingual)` is not gated on `builtIn`). That is a behaviour change for the dated-query pipeline when the flag is ON. Either gate it on `builtIn` or document it.

## P3
- P3-1 qna-set-v2-multilingual.jsonl h10: the question is a "where" question ("kahan dikha tha"), but the expected answer starts "Yes, ...". Reword it to "At the first stall (bitter gourd, about 20:41)". The new `twin` field is an extension to the v1 format (v1 q06 carries `needs`, the twin does not). Confirm the eval runner ignores unknown fields.
- P3-2 MultilingualAskTest.kt: there is no direct test for the 3 s TimeoutCancellationException path or for a reply over 120 characters going through the service. The code paths are correct (QueryCondenser catches the timeout and returns null, then the lexicon runs). Only the tests are missing.
- P3-3 The names test list contains "mandi" (a market, not a person). This is harmless.

## Verified OK
- (1) Flag OFF: `condenseSpec = plan.condense ?: null`, so there is no condense call and no lexicon lookup. BUILT_IN_QUERY.condense stays null. MultilingualFlagUnchangedTest pins hash f5782e0d8610, the defaultQuery nodes and the compiled plan.
- (2) Privacy: condenseCall sends only the question data block and the date, with PayloadSources.NONE and CallClass.USER, through the runner and router, so the offline, battery, thermal and text gates apply. zoneGuard.check() runs first, and AskZoneBlockedException propagates past both catch layers, so the lexicon never runs. Logs carry counts and exception class names only.
- (3) Failure handling: null, blank, broken (INVALID_OUTPUT), exception and timeout replies all fall back to the lexicon, then to the raw question. The Answer prompt keeps the original q. Model output reaches only the search terms, through clean(): letters, digits and `' & . -` only, at most 12 words and 120 characters. The schema maxLength is 120 and additionalProperties is false.
- (5) There are 60 entries, all plain nouns, VERSION=1, and keys are unique after folding. Devanagari is NFKC-only, so nukta forms match. The only folding problems are the collisions in P2-1.
- (6) The eval jsonl follows the v1 format, with twins mapped to the matching v1 ids. It contains no personal data beyond the existing v1 scenario wording.

READY (no P1; fix P2-1 before you enable the flag on devices, and rerun v1 eval on the dated-query plan for P2-2)
