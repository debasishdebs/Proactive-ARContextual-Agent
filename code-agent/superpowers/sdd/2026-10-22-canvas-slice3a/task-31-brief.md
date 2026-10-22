### Task 31: Phone `MarkRecorder` and voice interceptor integration (brief M3, S54, S57, S63, S85) [CORE 3 h; ±3 s time join]
**Stream C · 3.5 h · depends on: 8, 9, 14, 29.**

**Implement:**
- round 1 Task 21, plus:
  - a mark whose mapped time < `wipedAtMs` → dropped (S57);
  - the mark and the listen join by `listenId` in either order, without a duplicate MOMENT;
  - UNDO and RETRO per the S85 table;
  - **only unmatched clauses are forwarded to Ask**;
  - every label write bumps `labelRev`.

**Tests:**
- round 1's, plus:
  - `preWipeMarkDropped`;
  - `markBeforeListenJoins`, `listenBeforeMarkJoins`, `noDuplicateMoment`;
  - `undoCommandDeletesBoundMark`, `retroMarkWindow`;
  - **`realPipelineMarkMakesNoAnswerCall`** (a spy `AnswerService` gets zero calls; the HUD state is "marked · <label>");
  - **`onlyUnmatchedClauseReachesAsk`** (the spy receives exactly "what's this building");
  - **`transcriptNeverPersistedOrLogged`** (a capture of every DAO write, the log sink and the HUD text contains none of the input words beyond the canvas label).
- **Mutation RED:** forward the whole utterance to Ask → `onlyUnmatchedClauseReachesAsk` fails.

**Commit:** `feat(voice,canvas): mark recorder on canvas_label (epoch fence, listenId join, undo and retro) and the voice interceptor (no model call, only unmatched clauses go to Ask)`

