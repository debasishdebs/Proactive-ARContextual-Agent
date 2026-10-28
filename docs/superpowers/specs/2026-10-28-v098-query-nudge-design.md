# v0.9.8: Query and Nudge on one RAG core (design, 2026-10-28)

User rulings (2026-10-28): Ask and Nudge share one base, RAG plus an LLM call on top. One function takes an embedding and an embedding type; the type picks the vector store (TEXT = memories, IMAGE = photos). Ask: user -> STT -> embed text -> core -> synthesise -> show. Nudge: photo -> image embedding -> core (type IMAGE) -> LLM synthesis outside the core -> notify. Both become editable canvas pipelines. Nudge triggers: Moment, Scene, signal aggregation, cron schedule (plus today's "after a summarizer pass"). A saved Query/Nudge runs LIVE on the same cadence as other flows. Less code, more reuse, same spirit as the shared Utterance backend.

## Core
`RagCore.retrieve(RagQuery(type: TEXT|IMAGE, text?, vector?), RagLimits, nowMs) -> RagContext(memories, photoLines, images, ids, fallback)`.
- TEXT: today's MemoryRetriever path (lexical/semantic/hybrid, fallback to newest trusted memories).
- IMAGE: today's PhotoRecall path (vector search, relevance floor, AskPhotoPolicy gate, decrypted images, endpoint check).
- Synthesis stays OUTSIDE the core: Ask = ANSWER role, Nudge = NUDGE role. Each caller builds its own PromptVars from RagContext.
- All existing gates stay at the caller: Forget fence, quiet hours/cap/dedupe, photo gate, pause.

## Slices
- A: RagCore + refactor AnswerService.retrieve and NudgeInputs.build onto it. Behaviour-preserving: golden tests on prompt vars; no UI change; default-capture plan hash f5782e0d8610 unchanged.
- B: canvas node types `rag.retrieve` (store, topK), `out.deliver` (target: caller | phone | glasses | both), default Query and Nudge pipelines that reproduce today's behaviour exactly, validator, editor, run-and-save.
- C: Nudge triggers `trigger.moment`, `trigger.scene`, `trigger.signal` (agg.window + compare), `trigger.schedule` (cron); delivery targets and return-to-caller through the Utterance surfaces; fallback to the built-in flow on a pipeline error; live via the usual publish path.
- D: mic and display as editable device capabilities (phone, glasses).
Each slice: green tests, Codex review (glasses or deletion touching = fix rounds), handoff note. No device install without the user's go.
