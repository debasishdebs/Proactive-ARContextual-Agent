# Multilingual / Indic retrieval (design)

Date: 2026-11-19 · Status: draft for the user's ruling · Roadmap v1.3 item 3 (`docs/superpowers/plans/2026-11-16-roadmap-v1.1-v1.3.md:41`, "version not assigned yet: ask") · Scope: `android-companion/` Ask path. Docs only: nothing here was run on a device. Paths are under `android-companion/services/` unless full. "Not verified" marks what I did not confirm in code or on a device.

Trigger: the question "Did I see karela at the market?" (eval q06, `tools/testdata/qna-eval/qna-set-v1.jsonl:6`, parked `needs: multilingual`). The memory says "bitter gourd".

## 1. What the pipeline does today

| Stage | Behaviour | Reference |
|---|---|---|
| Memory language | Memories are English captions. The condense seed calls the store "English notes". Whether the Write prompt forces English is not verified (the reply-language var `{{language}}` exists in `system_global.txt:5`). | `ai/src/main/assets/prompts/seeds/condense_query.txt:1` |
| Embedding model | Nomic Embed v1.5, 768 native, 256-d index, prefixes `search_query: ` / `search_document: `. Its training data is English-centred; it has no Indic alignment (model-card claim, not verified here). A Latin-script Hindi word ("karela") reaches it as unknown subword pieces, so its vector is not near "bitter gourd". | `ai/.../local/NomicText.kt:7-35`, `ai/.../embed/EmbeddingSpec.kt:69` |
| Embedding choice | The 2026-10-14 design started English-only (bge-small-en) and kept multilingual-e5-small as a later single catalog entry (~120 MB RAM, worry about phone memory). Which model is the live default now is not verified. | `docs/superpowers/specs/2026-10-14-on-device-embeddings-design.md:9,46` |
| Tokenisation (keyword path) | `SearchNormalizer.normalize`: NFKC, lowercase, punctuation and symbols to spaces, SQLite `simple` FTS tokenizer; Indic combining marks stay inside words. The comment says "English/Hindi". The match query is an OR of quoted prefix terms (>= 2 code points, max 16). There is no stemming, no transliteration and no synonym table. | `memory/.../db/SearchNormalizer.kt:7-50,50-60`, `memory/.../retrieval/LexicalRetriever.kt:20-23` |
| Result for q06 | "karela" prefix-matches only a literal "karela". BM25 gives nothing. The embedding gives an unrelated near-tie. Fusion has nothing to lift. The ANSWER rule 3 then says "don't know yet". | `memory/.../MemoryRetriever.kt:34-60`, retrieval spec §1 |
| Query rewrite | **Already built (T10, retrieval spec §10).** `QueryCondenser` makes one CONDENSE_QUERY call (the ANSWER role's endpoint) that sees the question text and today's date and never a memory. It returns `{"query": short English keywords, "lang"}`. The seed tells the model to translate a foreign food or object word (its example is exactly "karela is bitter gourd") and that Latin-script Hindi is `hi`. For a foreign question it also returns the original question as a second word search ("probe"), added to the first. Timeout 3 s, cache 64 per (question, local date), any failure means the raw question is searched. | `orchestration/.../retrieval/QueryCondenser.kt:12-71`, `ai/.../prompts/seeds/condense_query.txt`, `AnswerService.kt:168-186` |
| Where it is on | Only the `dated-query` seed pipeline (`rag.condense` node: utterance, window, condense, embed, retrieve, order). The default Ask (`BUILT_IN_QUERY`) has no condense step, by design of the retrieval spec §5 (unchanged hash). | `ai/.../canvas/DefaultPipelines.kt:196-211` |
| Evidence | q06 has never run through `dated-query` (the 16 Nov eval runs predate T10). Whether the condense step turns karela into "bitter gourd" with the user's configured ANSWER model is **not verified**. | eval runs `20261116-*` |
| Voice input | Speech is English primary; Hindi is a separate on-device pack. A Hindi voice question arrives as Devanagari or romanised text from the recogniser. Devanagari is handled by `hasNonLatinLetters` in the condenser. | `voice/.../SpeechLanguage.kt`, `QueryCondenser.kt:66` |
| Answer language | `{{language}}` in `system_global.txt:5`. Whether the answer follows the question's language is not verified. | |

Failure modes that remain even with the condenser: (a) it is off in the default Ask; (b) a memory may contain a Hindi word the user's question spells differently ("chulha", "chulah"); (c) a one-word model translation can pick the wrong English name ("lauki" = bottle gourd / opo squash / calabash); (d) the model is cloud, so the question text leaves the phone.

## 2. Options

Ranked by cost, privacy and latency together. "Q" is the question; no option sends memory text anywhere new.

### Option A. Turn on the existing LLM query condenser (fixed prompt) for the default Ask, plus a prompt upgrade

What it is: make `rag.condense` part of the built-in Ask, with `translate=true`. Upgrade the seed with a short list of example transliterations and an instruction to return up to 3 English synonyms ("bitter gourd, bitter melon") so recall covers the caption's wording.

- Cost: low. About 2 small seed edits, one flag change, tests. No new model, no download.
- Latency: +1 short model call before retrieval (the code budget is a 3 s timeout, cached per question and day). Measured value on the phone and endpoint: not measured. Local endpoint, if bound, would be slower; not verified.
- Privacy: Q is sent to the ANSWER endpoint, which already receives Q in the normal answer call, so no new party. No memories are sent (`PayloadSources.NONE`). If the ANSWER role is on-device this is local.
- Offline: no model, no condense. The code falls back to the raw question (English questions still work, "karela" still fails). Same as today.
- Battery: one extra short call per Ask; Asks are user-initiated, so negligible against capture and indexing.
- Risks: wrong translation for ambiguous words; a wrong query hides an answer that the raw question would have found, mitigated by the second "probe" search on the original text. Prompt injection through Q is bounded: output is a 120-char query string only (`QueryCondenser` clean/take). The default-Ask hash change affects every published plan and needs the retrieval spec's gate (§12 question 1).

### Option B. Small curated bilingual lexicon (food, places, household, shop words), on the phone

What it is: a bundled TSV of about 400-800 entries: romanised Hindi/Indic word, Devanagari form, English captions' likely words. Example: `karela, करेला -> bitter gourd, bitter melon`; `chulha, चूल्हा -> stove, gas stove`; `bhindi -> okra, lady finger`. At query time, a pure-Kotlin step (like `TimeWindowRules`) adds the English terms to both the keyword match and the embedded text, with tolerance for spelling variants (a normaliser that folds `aa->a`, `ee->i`, `oo->u`, double letters, trailing `h`, and a small edit-distance of 1 on words >= 5 letters).

- Cost: medium. Writing and reviewing the lexicon is the real work (about 1-2 days to a first 400); code is small (one object, one canvas-less hook before retrieval, table-driven tests). Maintenance never ends (new words).
- Latency: effectively zero (a hash lookup).
- Privacy: best. Nothing leaves the phone, Q is not sent anywhere for this step.
- Offline: fully works.
- Battery: none.
- Risks: coverage is only what is in the table (the user's own words are unpredictable); curated by Claude, so a wrong gloss is silent; romanised spelling varies a lot; no handling of full Hindi sentences, only word-level hits, so it expands rather than understands. Devanagari memory text is not a problem here (memories are English) but a Devanagari query needs the Devanagari column.

### Option C. Swap to a multilingual embedding model (multilingual-e5-small or similar)

What it is: use a model whose vector space aligns Hindi, Hinglish and English. The catalog and `PrefixRules.E5` already exist (`EmbeddingSpec.kt:67`, prefix `query:` / `passage:`), so adding a catalog entry was the plan in the 2026-10-14 design.

- Cost: high. A full re-embed of every memory and photo caption (a new vector space; the index is rebuilt in the background, at the battery cost the user has already tuned around: the index was paused for battery on 16 Nov). ~120 MB RAM (the user's own concern in the 10-14 design). Download size is not verified. Needs an on-device conversion and the same quality check as nomic (retrieval spec §8 M3).
- Latency: query embedding is slightly slower if the model is bigger; no extra call.
- Privacy: best if on-device (already the choice for the default).
- Offline: fully works.
- Battery: the re-embed is the cost; steady state similar.
- Risks: romanised Hindi ("karela" in Latin letters) is the weakest case for multilingual-e5 too, because its Indic training data is mostly native script (general knowledge of the model, not verified here); the gain may not reach q06-type words; Nomic is tuned and verified in this codebase; model swaps also invalidate the "active space" and any saved eval baseline. It does not fix the keyword path at all, so q06 could still fail in `LEXICAL`/`HYBRID` recall.

### Ranking

| Rank | Option | Cost | Privacy | Latency | Offline |
|---|---|---|---|---|---|
| 1 | A. Condenser on by default (existing code) | lowest (days) | Q to the ANSWER endpoint, already the case | +1 call, capped 3 s | falls back to raw question |
| 2 | B. Curated lexicon | medium (data work) | none leaves the phone | ~0 | full |
| 3 | C. Multilingual embedding | highest (re-embed, RAM) | on-device | ~0 | full |

They compose: A handles the long tail online, B covers the common words offline and instantly, C is a v2+ decision after A and B are measured.

## 3. Recommendation and version

Recommend A first, then B as a small offline floor. Do not do C now.

- **A** is nearly built: the retrieval spec T10 already did the hard part (contract, cache, fence, probe). The new work is the seed upgrade (synonym list of up to 3, a few romanised examples), enabling it in the built-in Ask, and the eval below. It is the only option that also handles words nobody listed.
- **B** is the offline and zero-latency answer for the user's real words (market, kitchen, travel signs). Build it as `IndicLexicon` expansion that runs when the condenser is unavailable (offline, timeout) and also always as extra terms for the keyword path, deduplicated. Keep it small and in a plain TSV so the user can edit it.
- **C** only if the eval after A+B still shows romanised misses that the lexicon and the model cannot reach.

Version proposal: **v1.3.1**, a new work package under v1.3 item 3 (not v1.3.0, which is Activity pagination and the Nexus spike). Slicing: v1.3.1 = A (seed, default-on switch, eval, device check); v1.3.2 = B (lexicon and the offline fallback). If the user prefers one release, ship both as v1.3.1. The install freeze for the trip (26 Nov) argues against touching the default Ask before it; target after Sri Lanka (31 Dec) unless the user rules otherwise.

## 4. Eval plan (existing harness)

Harness: `tools/eval_pca.py`, dataset `tools/testdata/qna-eval/*.jsonl` (68 questions today, sets: `2026-11-08 Diwali night`, `2026-11-10 home evening`, `2026-11-12 home afternoon`, `2026-11-15 market evening`, `2026-11-16 home morning`, `cross-session`). A question with `needs` is parked: excluded from scoring and listed (`eval_pca.py:255-256,290,416`). Verdicts: correct / partial / wrong / hallucinated / refused, scored 1 / 0.5 / 0 (`:19-20`).

**Add `tools/testdata/qna-eval/qna-set-v2-multilingual.jsonl`** (new file, same schema `id, set, difficulty, question, expected`, no `needs`), 10 Hinglish questions. They are data only: each points at an already-existing memory of the same session as an existing English question (the `expected` text is copied from that English question's `expected`, so no new personal content is written). Proposed pairs (the real answers must be filled from the existing gold, not invented):

| New id | Hinglish question | Same fact as | Word class |
|---|---|---|---|
| h01 | "Market mein karela dikha tha kya?" | q06 | vegetable (romanised) |
| h02 | "Maine bhutta kaise liya, paise kaise diye?" | q08 | food, payment |
| h03 | "Kal raat chulha jala hua tha kya?" | q53 (gas stove) | household |
| h04 | "Sabzi pehle li ya phal?" | q07 | order (greens vs fruit) |
| h05 | "Aaj raat kitni dukaanon pe ruka?" | q09 | count (market stalls) |
| h06 | "Diwali ki raat mithai kahan thi?" | the Diwali set, sweets | festival, food |
| h07 | "Meri chaabi kahan hai?" | q17 (keys) | household object |
| h08 | "Bachchon ne football kab khela?" | q12/q13 (soccer ball) | synonym plus Hindi |
| h09 | "Gaadi ki parking kahan thi?" | a parking question of the set (to be matched to an existing id) | vehicle, place |
| h10 | "करेला कहाँ दिखा था?" (Devanagari) | q06 | Devanagari script |

The ids q12, q13, q17 and q53 are taken from the retrieval spec §9 and must be checked against the dataset before writing the file (not verified here). h09 and h06 need a matching English gold question chosen by the user or by reading the dataset; if none exists, drop them and keep 8. Also unpark q06 (remove `needs`) in a copy used for the run, not in v1.

**Runs** (phone paired through `tools/m1-run.sh pair`, Developer mode on, index complete, charger):
1. Baseline: default Ask, `tools/eval_pca.py --judge <judge> --dataset <v2 file> --judge ...`. (The exact flag for choosing a pipeline per run does not exist in the header; running the A/B needs either the `dated-query` pipeline set as the active Ask plan on the phone, or a new `--pipeline` option on `/api/ask/eval`. Not verified.)
2. A on: same set, condenser enabled.
3. A+B on.
4. Always re-run the 68 English questions with `--baseline <earlier run>` to catch regressions (q10, q20, q40, q65 are the regression watch from the retrieval spec §9).

**Pass bar** (proposed, for the user to adjust): on the 10 Hinglish questions the Hinglish version scores >= 80% of the score of its English twin, and at least 7 of 10 are `correct` or `partial` with no `hallucinated`; the English set has no new `wrong` or `hallucinated` against the baseline; condense adds no more than 1.5 s median latency; with the model off or unreachable the Ask still answers English questions as before. q06 must go from refused to correct. Two judges, as the harness supports; disagreements are read by a person.

## 5. What needs the user's ruling

1. Priority and version: A first as v1.3.1 and B as v1.3.2 (or together), and whether this waits until after the trip install freeze (26 Nov) and Sri Lanka.
2. Default-Ask switch: should the built-in Ask get the condenser? This overlaps retrieval spec §12 question 1 (whether `dated-query` becomes the built-in). One ruling may cover both.
3. Endpoint for the condense call: stay on the ANSWER binding (cloud, already receives Q), or a role that can be bound on-device? Is it acceptable that a question in another language is sent to that endpoint (the same exposure as today's answer call)?
4. Lexicon content: who curates it (Claude drafts, the user reviews), and which words matter (markets, kitchen, travel, relatives)? Any word the user does not want written into the repo?
5. The 10 Hinglish questions: approve the list, and confirm that twin questions may be reused (so no new personal memory is introduced). Confirm the pass bar.
6. Whether Hindi answers are wanted (answer language follows the question) or English-only answers are fine; this is a separate item and not part of this design.
7. Option C: confirm it stays out until A+B are measured, and whether the 120 MB RAM concern still holds.
