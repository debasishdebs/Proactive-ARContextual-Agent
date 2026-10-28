# v0.9.8: Query and Nudge on one RAG core (design, 2026-10-28)

User rulings (2026-10-28): Ask and Nudge share one base, RAG plus an LLM call on top. One function takes an embedding and an embedding type; the type picks the vector store (TEXT = memories, IMAGE = photos). Ask: user -> STT -> embed text -> core -> synthesise -> show. Nudge: photo -> image embedding -> core (type IMAGE) -> LLM synthesis outside the core -> notify. Both become editable canvas pipelines. Nudge triggers: Moment, Scene, signal aggregation, cron schedule (plus today's "after a summarizer pass"). A saved Query/Nudge runs LIVE on the same cadence as other flows. Less code, more reuse, same spirit as the shared Utterance backend.

## Core
`RagCore.retrieve(query: RagQuery(text?, vector?), sources: List<RagSource>, params: RagParams, nowMs) -> RagContext(memories, memoryLines, recent, recentLines, history, photoLines, photos, images, ids, fallback, hits/mode)`. A text query reaches the memory store and (by words) the photo store; a vector query (a photo embedding, type IMAGE) reaches the photo store. `RagQuery.type` (TEXT|IMAGE) is derived from which one is set.
- Sources are lazy descriptors, nothing is materialised until the core runs them: `MemoryStore(notAfterMs)` (today's MemoryRetriever path, lexical/semantic/hybrid, fallback to the newest trusted memories), `PhotoStore(notAfterMs)` (today's PhotoRecall path: vector search, relevance floor, AskPhotoPolicy gate, endpoint check, decrypted images), `RecentWindow(ms)` (OK memories of the window, newest N, minus memories about old photos), `NudgeHistory(ms)` (what was already said), `Explicit(memoryIds)` (a caller-held list).
- Params are typed: `RagParams(topK, minConfidence, mode, maxChars, maxRecent)`; null = the user's retrieval settings. `RagParams.SCHEMA` (name, type, default, doc) is what the canvas editor reads later.
- The core scores, filters, bounds and formats. Every source passes through the same Forget fence, photo gate and bounds INSIDE the core, so a new wrapper cannot skip them. It never calls an LLM.
- Synthesis stays OUTSIDE the core: Ask = ANSWER role, Nudge = NUDGE role. Each wrapper builds its own PromptVars from RagContext. Ask = wrapper over MemoryStore + PhotoStore; Nudge = wrapper over RecentWindow + NudgeHistory (the IMAGE path exists, no trigger uses it yet).
- Wrapper-level gates stay with the wrapper: quiet hours, cap, dedupe, pause (the photo gate input is the Ask's `photosAllowed`), and the Forget fence re-check at commit.

## Slices
- A: RagCore + refactor AnswerService.retrieve and NudgeInputs.build onto it. Behaviour-preserving: golden tests on prompt vars; no UI change; default-capture plan hash f5782e0d8610 unchanged.
- B: canvas node types `rag.retrieve` (store, topK), `out.deliver` (target: caller | phone | glasses | both), default Query and Nudge pipelines that reproduce today's behaviour exactly, validator, editor, run-and-save.
- C: Nudge triggers `trigger.moment`, `trigger.scene`, `trigger.signal` (agg.window + compare), `trigger.schedule` (cron); delivery targets and return-to-caller through the Utterance surfaces; fallback to the built-in flow on a pipeline error; live via the usual publish path.
- D: mic and display as editable device capabilities (phone, glasses).
Each slice: green tests, Codex review (glasses or deletion touching = fix rounds), handoff note. No device install without the user's go.
