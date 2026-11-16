# Ask retrieval quality: time windows, ordering, per-day context (design)

Date: 2026-11-16 · Branch: `feat/v101-ask-eval` · Status: draft for review · Scope: `android-companion/` Ask path only. Multilingual (karela, chulha) is out of scope.

Evidence: eval runs `20261116-012215` (q01-q50, asked about 01:22 on 16 Nov) and `20261116-120457` (q51-q68, asked about 12:04). The index was paused for battery from 11:35 on 16 Nov, so no 16 Nov memory has a vector.

## 1. Current path (file:line)

Paths are under `android-companion/services/`.

1. `orchestration/.../AnswerService.kt:249` `retrieveWithFlow`. `:309-318` loads the published Query plan. `:313` uses the built-in flow when `plan == FlowPlan.BUILT_IN_QUERY`.
2. `AnswerService.kt:139-151` calls `RagCore.retrieve(RagQuery(q), plan.askSources(), plan.ragParams(), nowMs)` (`FlowPlans.kt:16-19`, `:28-43`).
3. `orchestration/.../RagCore.kt:164-204`: photo lines first, then memory lines bounded by one `maxChars` budget (5,000, `:92`; `AskText.bounded`, `AnswerService.kt:66-75`). With no hits, the fallback is the 10 newest memories (`RagCore.kt:208-216`).
4. `memory/.../MemoryRetriever.kt:34-60`: pool = `max(topK*5, 40)` (`:35`). Lexical BM25 over an any-term FTS match (`LexicalRetriever.kt:20-23`). The semantic cosine is shifted to `(c+1)/2` (`:39`). Fusion runs (`:46`), then score × recency (`:55`, half-life 3 days, `:68-72`), sorted, `take(topK=8)` (`:57-58`).
5. `FusionStrategy.kt:8-12` (weighted 0.6/0.4 over min-max normalised lists). An id missing from the semantic list scores 0 there (`:11`).
6. `RagCore.kt:335-337`: a memory line is `id · yyyy-MM-dd HH:mm (createdAt) · confidence · frame= · text ≤500`. The time shown is `createdAt`, not the capture time.
7. ANSWER seed `ai/src/main/assets/prompts/seeds/read_context_answer.txt:1-14`. It does not contain `{{now}}`, although the variable exists (`PromptVars.kt:15-24`). Rule 3 says "If nothing relevant was retrieved, say you don't know yet."
8. Canvas: the Query chain is `input.utterance → [embed.text] → rag.retrieve → prompt → out.deliver` (`ai/.../canvas/FlowPlan.kt:104-108`, `embed.text` optional at `:120`, compile at `:153-189`). The default pipeline is in `DefaultPipelines.kt:180-190`. The hash is the SHA-256 of the canonical JSON (`PipelineHash.kt:6-7`), which includes `type@version` (`PipelineJson.kt:91`).

No step reads the question's date or time words.

## 2. Root causes found

- **R1. No time window.** "10 Nov" and "tonight" are only BM25 or embedding tokens. Top-8 by score, with recency, returns other days (q25, q27, q34, q35). The model has no `{{now}}`, so it cannot even judge what "today" means.
- **R2. Recency is a soft multiplier (0.5-1.0), not an order.** "Last" questions get the best-scoring old memory (q42, q43).
- **R3. Top-8 across all time.** Counting questions and "this month" questions see about 8 memories, mostly from one day (q41, q44, q48, q50).
- **R4. Index coverage penalty.** In HYBRID, a memory with no vector can score at most 0.4 (`FusionStrategy.kt:11`). On 16 Nov every new memory was lexical-only, so older embedded memories beat them. This is likely why "A gas stove is on with a visible orange flame" (16 Nov 11:37) was not retrieved for q53.
- **R5. Flat semantic scores.** The reported 0.79-0.82 are shifted values, so the raw cosines are about 0.58-0.64. The debug route reports `fused × recency` only (`RetrieveDebug.kt:36-41`), so the components cannot be seen. q13 was asked at 01:22, before the pause, so its flat scores are not a coverage effect if 12 Nov was embedded (check this in §8).

## 3. Temporal window parser (rules on the phone)

`TimeWindowRules.parse(question, nowMs, zone): TimeWindow?` is pure Kotlin with no I/O. `TimeWindow(fromMs, toMs, label, phrase, source = RULES|LLM, nightly: Boolean)`. The zone is the ask's zone (`AnswerService.zone`, today `ZoneId.systemDefault`). All arithmetic uses `ZonedDateTime`, so a day is `[startOfDay, next startOfDay)` and a DST day is 23 or 25 h. Every window is clipped to `toMs ≤ nowMs`. The parser removes the matched phrase from the text that is searched (§8, query condensing).

| Phrase (case-insensitive) | Window (local) |
|---|---|
| today | 00:00 today → now. **Before 06:00**: 00:00 yesterday → now |
| tonight | 18:00 today → 06:00 tomorrow. **Before 06:00**: 18:00 yesterday → now |
| last night | 18:00 yesterday → 06:00 today (before 06:00: same as tonight) |
| this morning / afternoon / evening | 05:00-12:00 / 12:00-17:00 / 17:00-22:00 today |
| yesterday (+ morning/evening) | that day, or that part of it |
| `on`? `10 Nov`, `Nov 10`, `10th November` | that day. Year: the latest occurrence ≤ now |
| night of 7-8 Nov, night of 7 Nov | 7 Nov 18:00 → 8 Nov 06:00 (`nightly`) |
| (on / last) Monday … Sunday | latest past one (today if it is that weekday). "last Mon" means a week back if today is Monday |
| this week / last week | ISO Monday 00:00 → now / the previous ISO week |
| this month / last month | 1st 00:00 → now / the previous calendar month |
| around / at / about 2:30 AM, 21:10, noon, midnight | ±30 min on the named day, else today (yesterday if that time is in the future) |
| before / after HH:MM (+ day) | half-open bound inside that day |
| in the last N hours/days, N days ago | as stated |

Combinations: a day and a time ("2:30 AM on 8 Nov") intersect. A day and a part of the day ("10 Nov evening") intersect.

Edge cases:
- "last" means a window only in "last night/week/month/<weekday>". In "when did I last", "the last time" and "latest" it is an ordering keyword (§5).
- "latest I stayed up" is a night-extent question, not newest-first (rule `AWAKE`, §5).
- A future date is never a window. Numeric dates (10/11) are not parsed in v1.

**LLM fallback contract.** It is called only when a temporal cue remains that no rule matched. The cues are: `on <Capitalised word>` that is not a weekday or month, `on my birthday`, `when (I|we|the) …`, `while the …`, and `before|after|during <noun>` with no time.
- Input: the question and `now` (ISO local, weekday, zone id). No memories.
- Output JSON: `{"kind":"date|range|event|none","from":"yyyy-MM-ddTHH:mm","to":"…","event":"short search text","label":"Diwali 2026 (8 Nov)"}`.
- Validation: `from < to`, `to ≤ now + 1 d`, span ≤ 400 d, else `none`. Timeout 3 s, then no window. Cached per (question, local date).
- `kind=event` ("when the guest came", "after breakfast"): run one lexical-and-semantic retrieval for `event`, take the capture time span of its top hits (episode = hits within 30 min) as the anchor, then apply before / after / during to it.

## 4. Soft filter and widening

`RagSource.MemoryStore(window: TimeWindow?)` takes `notBeforeMs`/`notAfterMs` on the event time = `COALESCE(frame_meta.capturedAtMs, memory.createdAt)`. The bounds go into the SQL and the vector scan, before every limit, as `notAfterMs` does today (`RetrievalDao.kt:17`, `VectorIndex.kt:36`). Inside a window the pool is 200 (`MAX_POOL_OVERRIDE`).

The steps:
1. W.
2. If no hit passes the relevance floor (§5), W ± 1 day.
3. All dates (today's behaviour).

The first line of the memories data block says which window was used (the same pattern as `IMAGE_NOTE`, `RagCore.kt:347`), for example:
- `note: time window 2026-11-10 00:00–23:59 ("on 10 Nov"); memories are in time order`
- `note: nothing matched on 10 Nov; widened to 9–11 Nov`
- `note: nothing matched the asked time; these memories are from other dates`

Memory lines show the event time with the weekday (`Tue 10 Nov 21:10`), not `createdAt`.

## 5. Ordering and selection as canvas operations

Two new **optional** Query nodes, placed like `embed.text`:

`input.utterance → [rag.window] → [embed.text] → rag.retrieve → [rag.order] → prompt → out.deliver`

**`rag.window`** (TEXT→TEXT, category MEMORY) has these params:
- `rules` BOOL (default true)
- `llmFallback` BOOL (default false)
- `widen` ENUM `NONE|DAY|DAY_THEN_ALL` (default `DAY_THEN_ALL`)
- `dayStartHour` INT 0-12 (default 6)
- `nightFrom` INT (default 18), `nightTo` INT (default 6)

**`rag.order`** (RAG→RAG) has these params:
- `defaultOrder` ENUM `RELEVANCE|NEWEST|OLDEST|TIME` (default `RELEVANCE`)
- `relevanceFloor` NUMBER 0-1 (default 0.5; a candidate is relevant when it is in the window pool's top 40 and has fused ≥ floor × top fused)
- `selection` ENUM `TOP_K|PER_DAY` (default `TOP_K`)
- `perDay` INT 1-3 (default 2; a second line must be ≥ 30 min from the first)
- `maxDays` INT (default 31)
- `maxChars` INT (default 8,000; replaces the 5,000 default only when PER_DAY is chosen)
- `rules` TABLE with columns `pattern` (text, matched on word boundaries), `order` (enum), `selection` (enum, blankable), `offset` (int, 0 = newest, 1 = the one before), `ignoreRelevance` (bool)

Rows are tried top-down and the first match wins. The seed rows are:

| pattern | order | selection | offset | ignoreRelevance |
|---|---|---|---|---|
| `last\|latest\|most recent\|when did I last\|where (is\|are) my` | NEWEST | TOP_K | 0 | no |
| `previous\|the time before\|before that` | NEWEST | TOP_K | 1 | no |
| `stayed up\|awake until\|go to bed\|went to sleep` | NEWEST (last capture of each night) | PER_DAY | 0 | yes |
| `how many\|which days\|which evenings\|how often\|every time` | TIME | PER_DAY | 0 | no |
| `did I (turn\|switch) off\|did I (close\|lock)\|is the .* (on\|off)` | NEWEST, plus the latest state memory | TOP_K | 0 | no |

How the order is applied:
- NEWEST sorts the *relevant* set by event time and groups it into episodes (30 min). `offset=1` drops the newest episode.
- "before <time>" is a window bound from §3, not an order.
- When a window spans ≥ 2 days and no rule picked a selection, PER_DAY applies. A single-day window keeps top-k (raised to 12) and lists it in time order, which helps "before or after" questions (q07).
- PER_DAY with `perDay=2` and the compact line (`id · Sun 15 Nov 21:17 · text ≤240`, about 300 chars) fits about 26 lines in 8,000 chars. Over the budget, the second line of the lowest-scoring days goes first, then whole days, always keeping time order.

**Compile and hash impact.**
- `FlowPlan` gets `window: WindowSpec? = null` and `order: OrderSpec? = null`. `BUILT_IN_QUERY` keeps both null, so the equality check at `AnswerService.kt:313` and every existing published plan behave as today.
- `shapeProblems` treats the two types as optional, like `embed.text` (`FlowPlan.kt:120`).
- New `NodeType`s start at version 1. `rag.retrieve` is **not** edited, so its `type@version` and the hash of every existing pipeline stay unchanged (`PipelineJson.kt:91`).
- `DefaultPipelines.defaultQuery()` is unchanged in phase 1: same JSON, same hash. A second seed, `dated-query`, holds both nodes with the behaviour on, for A/B on the eval set.
- An older app refuses a pipeline that uses the new nodes (unknown type).

## 6. Last-seen state in the Answer prompt

The seed `read_context_answer.txt` changes:
- Add `Now: {{now}}`.
- The heading becomes "Retrieved memories (see the note line for order and time window)".
- New rule 3a: "If the question asks whether something was turned off, closed or locked and no memory shows that, say you cannot confirm it and give the last state you saw with its time (e.g. 'I can't confirm it was turned off, but at 11:36 the burner was lit')."
- New rule 3b: "If the note says the window was widened or not matched, say the memories are from other dates; never present them as the asked date."
- Rule 3 gets "only when nothing relevant was retrieved".

The safety rule row (§5) puts the newest relevant state memories first, so the 11:37 flame memory reaches the prompt once R4 is fixed.

## 7. Index coverage fix (R4)

`MemoryRetriever` asks `VectorIndex` which pooled ids have a vector in the ACTIVE space. For an id with **no vector** (missing, not merely low), the fused score is its normalised lexical score at full weight. It is not `0.4 × lexical`.

## 8. Investigation plan: flat semantic scores

Precondition: the index is complete (on the charger; coverage reported by T9 = 100% of OK memories). Tool: `POST /api/retrieve/debug` `{mode:"SEMANTIC", topK:20, snippets:true, notAfterMs:<run time>}`, after T9 exposes `lexical`, `semanticRaw` (the cosine before shift), `recency` and `embedded` per hit.

- **M0 coverage split.** Re-run q51-q68 retrieval with the run's `notAfterMs` before and after the index completes. The difference is the coverage effect. Check also that 12 Nov is embedded, which separates q13.
- **M1 spread.** For 20 eval questions, ask three forms: the full question, the condensed form (date phrase and question words removed: "soccer ball"), and the head noun. Record cosine@1, cosine@10, cosine@20 and the gold memory's rank (gold ids from the eval `sources` and `expected`). Report MRR per form.
- **M2 anisotropy.** Mean and std of query-versus-200-random-memories cosine. If std is under 0.03, use z-scores, not min-max, before fusion.
- **M3 transform check.** Compute the same pairs on the Mac with reference nomic-embed-text-v1.5 (`search_query:`/`search_document:`, LayerNorm, 256-d Matryoshka, L2; `NomicText.kt:10-16`, `EmbeddingSpec.kt:69`). If the cosines differ by > 0.02, there is a transform bug, which is fixed first.
- **M4 dims.** 256 against 768 on M1's set, only if M3 passes.
- **M5 document template.** Mean doc-to-doc cosine; shared caption phrasing compresses the space.
- **M6 synonym.** "football" against the "soccer ball" memory and against 10 distractors, in SEMANTIC and in HYBRID with WEIGHTED and RRF.

Decision rules:
- M3 fails: fix the transform.
- The condensed form raises MRR by ≥ 0.15: embed the condensed query (T10).
- M2 is compressed: z-score fusion behind a `fusion` option.

## 9. Expected impact by question

| Mechanism | Questions | Expected |
|---|---|---|
| Window + note + `{{now}}` | q19, q25, q27, q31, q34, q35, q37, q51, q52, q58, q59, q64, q66, q68 | refusals and wrong-day answers become answers; q25 and q27 also need night rules |
| "tonight" at 01:22 | q01, q02, q04, q05, q09 | right evening, more of its memories (q09 still a hard count) |
| Window + time order | q07, q23, q26, q33, q38, q61 | first and last times become visible (q23 21:54, q26 duration) |
| LLM fallback | q21 (Diwali), q36, q39, q62 (event anchors), q60 | resolved only if the fallback is enabled; q39 and q40 stay "can't tell" by design |
| PER_DAY | q41, q44, q45, q48, q50 | sees all days; correct counts likely, not guaranteed |
| NEWEST rules | q11, q17, q42, q43, q46 (AWAKE), q56 | newest relevant; q17's expected answer needs a recheck (a 15 Nov keys memory may exist) |
| Coverage fix + safety rule | q53, q55, q57, q63, q67 | 16 Nov memories compete again; last-state answers |
| Semantic (after §8) | q12, q13, q14, q51 | depends on findings |
| None expected | q03, q06, q08, q10, q15, q16, q18, q20, q22, q24, q28-q30, q32, q40, q47, q49, q54, q65 | regression watch (q10, q20) |

## 10. Tasks (Sonnet-sized)

**T1 `TimeWindowRules`** (`orchestration/.../retrieval/TimeWindowRules.kt`, new).
- Tests: table-driven `TimeWindowRulesTest`, with now = 16 Nov 01:22 and 12:04 in Asia/Kolkata, plus Europe/Berlin across a DST end. Every phrase in §3, and every temporal phrase in q01-q68.
- Acceptance: all rows pass. Questions with no cue return null.

**T2 Event-time bounds** (`RetrievalDao.kt`, `VectorIndex.kt`, `LexicalRetriever.kt`, `SemanticRetriever.kt`, `MemoryRetriever.kt`).
- Add `notBeforeMs`, and make both bounds use COALESCE(capturedAtMs, createdAt).
- Tests: Robolectric DAO tests showing the bound is applied before the pool cut. Null bounds give byte-identical results.

**T3 Soft filter, widening, note, event-time lines** (`RagCore.kt`, `FlowPlans.kt`).
- Tests: a fake retriever drives the three steps. Golden note texts. With `window=null` the prompt vars are unchanged.

**T4 Missing-vector fusion** (`FusionStrategy.kt`, `MemoryRetriever.kt`, `VectorIndex.kt`).
- Tests: a lexical-only id outranks a weaker embedded id. With full coverage, ranks are unchanged.

**T5 Ordering and selection** (`orchestration/.../retrieval/OrderRules.kt`, `DaySelector.kt`).
- Tests: every seed rule, episode grouping, the offset, the PER_DAY budget cut, time order, and the AWAKE night extent.

**T6 Canvas nodes** (`NodeTypes.kt`, `FlowPlan.kt`, `ParamSchema` TABLE use, `DefaultPipelines.kt` `dated-query`).
- Tests: a golden hash of `defaultQuery()` (unchanged), compile with and without the nodes, refused bad params, and the editor schema route.

**T7 LLM fallback** (`PromptKey.TIME_ANCHOR`, seed `time_anchor.txt`, `TimeAnchorResolver.kt`).
- Tests: a fake runner covering valid, invalid, timeout and cached results, and the event two-stage.
- Acceptance: no call when no cue is unresolved.

**T8 ANSWER seed** (`read_context_answer.txt`, `PromptSeeds.kt`).
- Tests: the seed contains `{{now}}` and the new rules, and the contract tests still pass.

**T9 Debug and eval** (`RetrieveDebug.kt`, `CanvasBackendImpl.kt:708`, `CanvasApi.kt:497`, `tools/eval_pca.py`).
- Add hit components, a coverage count, the window used, and a Developer-mode `nowMs` on `/api/ask/eval` plus `askedAt` in the dataset, so "today" is reproducible.
- Tests: route JSON tests and the eval tool's unit test.

**T10 Query condensing** (only if M6 or M1 justifies it): embed the question without the date phrase and question words.
- Acceptance: the eval MRR gain from M1 is confirmed.

Release gate: on the eval set, `dated-query` beats `default-query` with no new wrong answers in q10, q20, q40 or q65.

## 11. Risks

- A wrong window hides the answer. Widening limits this, but a wrong ±1 day still costs one step.
- "last" is ambiguous (§3). The rules are configurable, but a misfire is silent apart from the note line.
- A user-edited ANSWER prompt does not get the new rules. Only seeds change, so this needs a "prompt update available" hint.
- PER_DAY context (8k chars) adds latency and cost.
- Eval overfitting: the rules are tuned on 68 questions from one user and one week.

## 12. Open questions for the user

1. When `dated-query` passes the gate, should the built-in Ask (`BUILT_IN_QUERY`) switch to it? Or does it stay opt-in through a published pipeline?
2. Which endpoint should the LLM fallback use: the ANSWER binding (cloud, which already receives the question), or a new role that can be bound on-device? And should it be on by default?
3. After midnight and before 06:00, should "today" and "tonight" mean the night that is still going on, as proposed in §3?
4. Event anchors ("when the guest came", "after breakfast") cost a second retrieval pass. Should they be in v1, or deferred, with such questions answered without a window?
