# v1.1 Forget lifecycle design

Status: design for review. 2026-11-16. Branch base: `feat/v101-ask-eval`, Room v16.
Rulings it implements: 2026-10-28 (`v1.1-forget-lifecycle-backlog.md`: families with `selfDelete()`, dependency DAG, one global lock, read leases, one place of delete) and 2026-11-16 (architecture confirmed, plus Forget of a single answer and a single nudge).

Paths below are relative to `android-companion/services/`. `mem/` = `memory/src/main/java/com/rokid/context/memory/`, `orch/` = `orchestration/src/main/java/com/rokid/context/`.

## 1. Current architecture and its leak paths

**Entry points.** `orch/orchestration/forget/ForgetActions.kt:196-246` runs every user Forget inside `DataOpGate.shared.delete` (`orch/orchestration/DataOpGate.kt:66`), then does the post-commit work in `apply` (:133: HUD withdrawal, alarms, notifications, `FrameSweep`, cached export, re-summarise). Range Forget runs through `CanvasForgetCoordinator` (`forgetRangeOutcome`, :176). Delete all is `MemoryWipe.deleteAll` (`orch/orchestration/MemoryWipe.kt:86-88`) with the wipe generation as its fence.

**The deleting code is spread over four places:**
- `mem/forget/ForgetService.kt`: `forgetMomentTx` (:62), `forgetFactsTx` (:95), `expireTx` (:107), `expirePhotosTx` (:124) and the shared `cascade` (:150-212), which deletes F, invalidates descendants D, tombstones, drops Moments, drops canvas outputs (:239-254, batch-wide, plus a 10 s time radius) and redacts asks and nudges (:188-191).
- `mem/forget/MomentForget.kt:202-259` (`run`): fences, discovery and the lineage closure (`closureOf`, :112), then the `ForgetService` cascades per photo plus its own deletes (labels, projection, Scenes, run rows by key).
- `CanvasSlice3Dao.forgetRangeTx` (traces, marks, replays, sweeps), called from both of the above.
- `MarkRecorder.kt:164`, `WatchRowCleanup.kt:10` and `OutboxDrainer.kt:48` delete under the gate on their own.

`DeletionGraph` (`mem/forget/DeletionGraph.kt:21-46`) lists only the canvas and moment tables. `ask`, `nudge`, `memory*`, `frame*`, `proposal`, `action_log`, `note` and `summary_trace` are outside its inventory test.

**Writer fences, one per flow:** Ask and Nudge check `ForgetFence.allLive` in the commit transaction (`orch/orchestration/AnswerService.kt:265-273`, `nudge/NudgeEngine.kt:121-125`). Canvas runs use `CanvasDao.insertRunItemsFenced` (`mem/db/CanvasDao.kt:123-138`: wipe generation plus tombstones of row keys and `allMembers`). DerivePass compares snapshots (`orch/canvas/moment/DerivePass.kt:150`). Canvas Save re-reads under `gate().write` (`orch/canvas/CanvasRunSave.kt:116-135`). The Moment and Scene builders read and write in one transaction (`MomentBuilder.kt:116-123`, `SceneBuilder.kt:85-93`).

**Leak and over-reach paths:**
1. **Ask over-redaction.** `AnswerService.kt:271` stores `encodeWithDeps(out.sources, r.fenceIds + r.ids)`, so every retrieved input counts as a dependency. `ForgetService.cascade` redacts every ask whose cited ids or `dependsOn` ids meet `forgotten + invalidated + extraCited + goneCites` (:187-191). `invalidated` includes the CARRIED batch-mate memories (:69-70) and every `memory_source` descendant (:160). So one photo Forget invalidates its batch mates' captions, and every ask that merely *retrieved* any of them shows "Removed — a source was forgotten" (:261). This is how the 12-13 Nov history went. Nudges do the same (`NudgeEngine.kt:125`).
2. **Redaction leaves the question behind.** `ForgetDao.redactAsk` (`mem/db/ForgetDao.kt:234`) clears only the answer and the sources. The question stays in lists and in the export (`mem/db/ExportDao.kt:43`).
3. **History ids are not dependencies.** An answer built with a nudge or an earlier ask in its context records that item's source ids, not the item's own id. Forgetting the nudge or answer itself therefore cannot reach what was built from it (backlog, "Forget single answer / nudge").
4. **Photo runs carry no member fence.** `CanvasRunner.kt:653-658` builds `members` only for Moment and Scene keys, so `allMembers` is empty for photo runs. `flush()` (:690-695) then checks only each row's own key. A singleton flush can write text that a batch call took from a forgotten peer photo.
5. **A canvas run in flight during a Forget.** The run reads, the Forget commits, and the run then commits rows built from forgotten content. Its fences (item 4, the chunk check at :773) see tombstoned photos only, never a fact Forget or an invalidation.
6. **Scoped Forget race.** Moment and Scene Forget only run in Developer mode (`mem/forget/ScopedForget.kt:93,132`) because of finding e3: a peer write that commits after the preview falls outside the confirmed set.
7. **The range acknowledgement has no caller.** `MomentForget.run` throws `ForgetNeedsAck` when the closure has more than 200 peers (:220). The range path passes no `expectedHash`, and `forgetRangeOutcome` (`ForgetActions.kt:176-185`) maps the exception to `FAILED`, so as read a large range Forget just fails, with no way to confirm it.
8. **Preview and delete come from different code.** The preview uses `discover(bounded=true)` plus an estimate. The delete uses `discover` plus a cascade that grows the set as it goes (backlog rounds 7 and 8).

## 2. Target design

### 2.1 Refs and families

Every deletable thing is a `Ref(family, id)`, written as `"<family>:<id>"`. Ids are content-free strings. Each family implements one interface in a new package `mem/forget/lifecycle/`:

```kotlin
interface ForgetFamily {
    val family: Family
    val tables: List<String>                                   // inventory: every table it owns
    suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge>   // downstream edges, typed
    suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) // inside the one transaction
}
enum class Fate { ERASE, REBUILD, DROP }   // ERASE user data; REBUILD = content erased now, regenerated later; DROP = regenerable result
enum class EdgeKind { CITES, DERIVED_FROM, CARRIED, CONTEXT, MEMBER, LINEAGE, TIME, ORIGIN }
```

| Family | Stores | Fate when it is a root | Fate when it is reached as a dependent |
|---|---|---|---|
| Photo | frame, frame_meta/vector/state/source, forget_tombstone, JPEG and sidecar (outbox) | ERASE + tombstone | (never reached: photos are only roots) |
| Memory (fact, caption, summary) | memory, memory_vector, memory_source, FTS | ERASE | REBUILD (INVALIDATED, as today) |
| Moment | moment, moment_part, moment_sensor, moment_tombstone | ERASE + fence | DROP (rebuilt by the builder) |
| Scene | scene, scene_part | ERASE | DROP |
| Derived field (Summary, Caption, Embedding) | object_derived, object_audit | n/a | DROP |
| Mark / Trace | canvas_label, canvas_trace(_event) | ERASE | ERASE (TIME radius, as today) |
| Run result | canvas_run_item, score, live_trace, replay, sweep, helper_job | n/a | DROP |
| Answer | ask, proposal, action_log, queue text | ERASE (row deleted) | REBUILD (re-answer, section 3) |
| Nudge | nudge, proposal, action_log | ERASE | ERASE (row deleted) |
| Audio, Person, Object | no store yet (`moment.transcript` is a null scaffold) | reserved | reserved |

Audio, Person and Object register empty families, so the inventory test fails the day one of them gets a table.

### 2.2 DAG construction (`ForgetPlanner`)

`ForgetPlanner.plan(roots: Set<Ref>, policy: Policy): ForgetPlan` does a breadth-first walk:
1. Start from the roots.
2. Ask each family for its `dependents`, using the existing queries: `childrenOf`, `carriedMemoryIdsOfFrame`, `asksCiting`/`nudgesCiting`, `batchPeersOf`, `momentIdsHolding`, `sceneIdsHoldingMoments`, the labels linked to a photo, and the time radius.
3. Assign each node a fate from its family and the incoming edge kind, using the table above. ERASE beats REBUILD, and REBUILD beats DROP.

Chunking is 500, as today. The caps carry over from `MomentForget` (depth 32, 5,000 user nodes, 5,000 DROP nodes). Past a cap the planner throws `ForgetTooLarge`.

`Policy` replaces the boolean flags of `cascade` (:150): `USER`, `RETENTION` (`keepKeyframes`, `scrubAccepted=false`, no REBUILD of descendants), `SCOPED(spans)` (bounded) and `MARK_UNDO`.

The plan holds:
- the nodes grouped by family and fate
- the counts the dialog shows
- `planHash` = `ForgetPlanHash` over every ERASE and REBUILD node (photos, memories, marks, Moments, Scenes, answers, nudges). DROP nodes are counted, not hashed (the v1 rule).
- `ackHash` (section 2.7)

### 2.3 Lock and read-lease protocol

`DataOpGate` stays the one process-wide gate and gains a third side, the **lease**. The registry lives in `mem/forget/lifecycle/Leases.kt` so DAOs can check it.

- **Open:** `Leases.open(kind, inputs: Set<Ref>, generation)` when a flow starts reading. While a Forget holds the exclusive side, `open` waits (bounded by `WAIT_MS`). `lease.extend(refs)` adds inputs read late.
- **Commit:** only through `lease.commit { }`, inside the flow's own transaction. It writes nothing unless the lease is not revoked, the wipe generation is unchanged and `ForgetFence.allLive` and the tombstone checks pass. The existing fences stay as a second line.
- **Forget:** take the exclusive side (new leases wait), plan, **revoke** every in-flight lease whose inputs meet the plan (a flag: model calls are never waited for), then one transaction: re-plan, check the hash, `ForgetExecutor.execute`. Release, then post-commit effects.
- **Why it is race-free:** SQLite allows one writer at a time. A lease commit that ran before the revocation is in the database, so the in-transaction re-plan includes its output through its recorded dependencies. A commit after the revocation sees the flag and writes nothing.
- **No lease needed** for writers that read and write in one transaction (MomentBuilder, SceneBuilder, extraction with its tombstone fence, MarkRecorder): SQLite serializes them, and the Forget's fences refuse a later rebuild.
- **Participants:** Ask (`answerOrThrow`), Nudge, CanvasRunner (every photo of the run, members, marks, memories read), DerivePass, CanvasRunSave, ResummarizeWorker and the new ReanswerWorker. A revoked flow ends as `Outcome.Fenced` (ask not saved, run CANCELLED). Retention and Delete all take the same exclusive side. Delete all revokes every lease.

### 2.4 One place of delete (`ForgetExecutor`)

`ForgetExecutor.execute(plan)` is the only caller of `selfDelete`. In one transaction it writes the fences (tombstones, Moment ranges, `labelRev`), calls `selfDelete` per family in reverse topological order (results, derived fields, answers and nudges, Scenes, Moments, memories, marks and traces, photos), queues the outbox (`CleanupOutbox.queue`) and returns a `ForgetReceipt` (refs by family, files, proposals, asks to withdraw, re-answers queued).

Every Forget, from fact to the v1.2 multi-select, is `plan(roots, policy)` then `execute`. `ForgetService.cascade` and `MomentForget.run` become thin adapters, then go. A source-scan test (pattern: `DeleterGateInventoryTest`) fails on any `DELETE FROM` or `UPDATE … redacted` of a content table outside a `selfDelete`, with a short allowlist (Delete all, `WatchRowCleanup`). `DeletionGraphInventoryTest` grows to the whole Room entity list.

### 2.5 Preview == delete set

Preview and delete call the same `plan()`. The confirm token is `planHash`. The executor re-plans in the transaction and throws `ForgetContentChanged` on a mismatch (the dialog re-opens with fresh counts). The plan can change only before the lock, never between the check and the delete. DROP counts are an estimate. Crossing the acknowledgement threshold after an un-acked preview throws `ForgetNeedsAck`.

### 2.6 Exports, HUD and previews

Room-backed views are consistent at commit; a REBUILD answer shows only "Updating answer…". Exports cannot run during a Forget (gate), and `MemoryExport.deleteCached` plus the `export_cleanup` retry stay. The HUD, voice card, notifications, alarms and queue log are withdrawn from the receipt through a new `ForgetListener.onForgotten(receipt)`, which replaces `asksRedacted` (`ForgetActions.kt:111`). Logs carry counts only.

### 2.7 Mass Forget and acknowledgement (v1.2-ready)

`plan()` takes any set of roots, so a multi-select is one plan, one lock and one transaction. An acknowledgement is required when there are more than `MAX_UNACKED_RESULTS` (200) DROP nodes, or when the selection holds more than 200 roots or user nodes. `ackHash = planHash + "+ack"`, as today. Range Forget gets the same preview, plan and ack flow, which closes leak 7. Above the ceiling the Forget is refused with `ForgetTooLarge`, and the UI suggests a smaller selection.

### 2.8 Crash and restart safety

The delete is one transaction, and external effects are outbox rows in it. Re-work is durable: INVALIDATED memories are rebuilt by the start-up re-summarise, and ReanswerWorker scans pending-answer rows at start-up. Leases and the lock are in memory, so process death leaves no stale lock (every flow died too). Post-commit effects are best effort: a redacted proposal is never re-armed (`REDACTED_PARAMS`), and the HUD re-reads Room on its next frame.

### 2.9 Performance bounds

| Operation | Bound |
|---|---|
| Plan | O(nodes) queries chunked at 500. Target p95 under 150 ms for one photo, under 2 s at the 5,000-node ceiling. |
| Lock hold | Plan plus one transaction. Never a model call. |
| A new lease during a Forget | Waits at most the hold time (cap 30 s), then the flow fails typed (as `DeleteRunning` does today). |
| Revocation | O(Σ lease inputs) set intersection, a few thousand ids at most. |
| ReanswerWorker | At most `REANSWER_CAP` per Forget (default 25, most recent first), one at a time, `CallClass.BACKGROUND`, paused by Battery Saver. |

## 3. Ask over-redaction and batch over-linking rules

**A1: an answer is rebuilt, not removed.** An Answer reached through CITES, CONTEXT or DERIVED_FROM from any ERASE or REBUILD node gets REBUILD. In the same transaction:
- `answer` becomes the sentinel `REANSWER_PENDING`
- `redacted = 1`, so the origin fence (`ActionDaos.kt:64`) keeps its proposals dead
- `sourcesJson` keeps only the surviving input ids (ids, never text)
- proposals are expired and scrubbed, as today

ReanswerWorker leases those survivors and re-runs the question at its original `nowMs` against exactly them, so retrieval adds nothing new. It commits through the lease. With no survivors, or over `REANSWER_CAP`, the row shows "Answer removed: what it used was forgotten" with an **Ask again** button.

Why this is privacy-safe: the whole answer text is erased in the transaction whether the input was cited or not, which keeps codex r15's rule. The re-answer reads only live inputs (its lease is revoked if a later plan touches them). The question is the user's own words. It is kept, and the single-answer Forget removes it.

**A2: history dependencies name the item.** Asks and nudges add `ask:<id>` and `nudge:<id>` to `dependsOn` for the history items they used. This closes leak 3.

**N1: a hit nudge is erased.** A nudge reached by any dependency is deleted with its proposals. Nudges are time-bound, so a regenerated one would be stale.

**Single answer / nudge Forget.** The root `answer:<id>` or `nudge:<id>` gets ERASE. The row (question and answer) is deleted, along with its proposals, alarms, notifications and queue text. Answers built from it are rebuilt under A1, and nudges built from it are erased under N1. It uses `forgetTarget()` and the single-object dialog (`ForgetKind.single`, `ForgetCopy.SINGLE_SCOPE`).

**B1: batch mates are rebuilt.** A memory reached only by a CARRIED edge gets REBUILD. It is invalidated, then re-described from its own live photo in a solo call. LINEAGE run results stay DROP: the next run recreates them.

**C1: photo-run fence.** A photo run's lease covers every input photo of the run, which closes leak 4.

## 4. Migration plan (each step shippable, current tests green)

| Step | Change | Behaviour |
|---|---|---|
| M0 | Golden delete-set tests for every current path (extend `ResidueOracleTest`, `ForgetServiceTest`, `MomentForget*`) | none |
| M1 | `Ref`, `ForgetFamily`, the registry, and `DeletionGraph` extended to all tables, with its inventory test | none |
| M2 | `ForgetPlanner` in shadow mode: tests assert planner set == golden set | none |
| M3 | `Leases` plus the `DataOpGate` lease side; Ask, Nudge, CanvasRunner (with C1), DerivePass, CanvasRunSave and Resummarize commit through leases; Forget revokes | closes leaks 4 and 5 |
| M4 | `ForgetExecutor` plus each family's `selfDelete`. Switch the paths one per PR: fact, photo, mark, range (with preview and ack), Moment and Scene, retention. Then delete `cascade`/`run` | leaks 7 and 8 |
| M5 | A1, A2 and N1, the ReanswerWorker, and the UI states | fixes the over-redaction |
| M6 | Single answer and single nudge Forget | ruling of 2026-11-16 |
| M7 | B1 solo re-describe | fixes the batch over-linking |
| M8 | Lift the Developer-mode gate on scoped Forget (needs a ruling and a worn test) | leak 6 |
| M9 (v1.2) | Multi-select API and UI on `plan(roots)` | (d) |

## 5. Task breakdown (Sonnet-sized)

Each task is one PR. "T1" means the golden delete sets of task 1. `mem/forget/lifecycle/` is shortened to `lifecycle/`.

| Task | Files | Tests | Acceptance |
|---|---|---|---|
| T1 Golden sets | `GoldenDeleteSetTest` (memory), `ResidueOracleTest` | one fixture per path: fact, photo, batch photo, mark, range, Moment, Scene, retention | green on today's code; lists ids per table |
| T2 Registry | `lifecycle/{Ref,ForgetFamily,Families}.kt`, `DeletionGraph.kt` | `FamilyInventoryTest` | fails when an unowned entity is added |
| T3 Planner | `lifecycle/ForgetPlanner.kt`, `ForgetPlan.kt`, `ForgetPlanHash.kt` | `ForgetPlannerShadowTest`, caps | planner set == T1 for every path |
| T4 Leases | `lifecycle/Leases.kt`, `DataOpGate.kt` | `LeasesTest`, `DataOpGateTest` | a revoked lease never writes; a Forget never waits on a lease |
| T5 Ask and Nudge | `AnswerService.kt:236-273`, `NudgeEngine.kt:90-130` | `AskForgetRaceTest` (forget during a suspended model call) | nothing saved, `Fenced` |
| T6 Canvas and C1 | `CanvasRunner.kt:632-695,773`, `CanvasDao.kt:123`, `CanvasRunSave.kt`, `DerivePass.kt:150` | `CanvasForgetRaceTest` | a singleton flush after a peer Forget writes nothing; a fact Forget mid-run cancels the run |
| T7 Executor, Photo and Memory | `lifecycle/ForgetExecutor.kt`, `families/{Photo,Memory}Family.kt`, `ForgetService.kt` adapter | T1 fact and photo, rollback fault | parity |
| T8 Moment, Scene, Mark, Trace, Run | `MomentForget.kt` adapter, `CanvasSlice3Dao` | T1 scoped and range, existing `MomentForget*`/`ScopedForget*` | parity |
| T9 Range preview and ack | `ForgetActions.kt:176`, `CanvasForgetCoordinator.kt`, range dialog | more than 200 peers previews `needsAck` | confirming with `ackHash` succeeds |
| T10 One delete place | `ContentDeleteScanTest`; remove the old cascades | scan, `DeleterGateInventoryTest` | green |
| T11 A1 re-answer | `families/AnswerFamily.kt`, `forget/ReanswerWorker.kt`, ask list UI | re-answer from survivors; none survive; over the cap; per-surface residue oracle (Room, export, HUD frames) | no forgotten string anywhere |
| T12 A2 and N1 | `AskActions.kt:47`, `NudgeInputs.kt`, `families/NudgeFamily.kt` | a nudge Forget rebuilds the answers that used it; a hit nudge has no row | green |
| T13 Single answer/nudge | `ForgetTarget`, `AnswerPreview`/`NudgePreview.forgetTarget()`, `ForgetDialog` | the question is gone from Room, previews and export; HUD withdrawn; proposals scrubbed | green |
| T14 B1 | `families/MemoryFamily.kt`, extraction worker | 3-photo batch, one forgotten (fake model) | the two mates are re-described with no text from the forgotten photo |

## 6. Risks

- **Re-answer cost and drift.** The re-answers are model calls, and the new answer can differ from the old one. The cap bounds the cost, and the UI marks the answer "updated".
- **Revocation throws away work.** Canvas runs that meet a Forget are cancelled more often. That is acceptable because their results are regenerable. Retention revokes only the leases it intersects.
- **Lock hold for mass Forget.** A 5,000-node transaction can block UI writes and grow the WAL. The ceiling and checkpoint stay, and the target needs measuring on device.
- **Shadow divergence.** The planner may disagree with v1 in edge cases (rounds 2, 3, 7, 8). Any difference needs a ruling before M4 switches that path.
- **Transaction mode assumption.** The design assumes Room `withTransaction` takes the write lock before the re-plan reads. A test must pin this.

## 7. Open questions for the user

1. **Ask rule.** Rebuild by re-answering (A1, recommended), or remove only the answers that *cited* the item and leave the others as they are? The second option is cheaper but not privacy-safe: an uncited input can still shape the text (codex r15). And is the re-answer cap of 25 per Forget right?
2. **Schema.** A1 needs no migration: it uses the `REANSWER_PENDING` sentinel plus `redacted = 1`. Do you prefer an additive v17 `ask.state TEXT` column instead, which is clearer but needs a migration?
3. **Nudges hit by a dependency.** Delete the row (N1, recommended), or keep a "Removed" placeholder as today?
4. **Batch mates.** Is a solo re-describe of the surviving batch photos (B1, one model call each) acceptable, or should v1's invalidate-and-remove stay?
5. **Scoped Forget.** Can Moment and Scene Forget leave Developer mode once M3 and M4 ship and pass a worn test?
6. **Mass Forget size.** One transaction with a 5,000-node ceiling (no schema change, recommended), or a chunked durable job that needs a new `forget_job` table (v17 migration)?

## 8. M4 implementation note and DESIGN_DIVERGENCE (rulings needed, 2026-11-17)

M4 is built: `ForgetExecutor` (`mem/forget/lifecycle/ForgetExecutor.kt`) is the single deleting code path behind the flag `forgetV2` (OFF by default; `DeveloperFlags.forgetV2`, key `forget_v2` in the backup-excluded `pc_dev` preferences, read by `ForgetV2`). With it ON, every Forget is one transaction: re-plan with the `ForgetPlanner`, `Leases.revoke(plan)` (a planner failure revokes every lease), then the families' `selfDelete` in the order of the v1 cascade, then the outbox rows. Switched paths: fact, photo, mark undo, range, Moment ids, Moment/Scene scope, retention (memories and photos), suspect-description removal, Delete all (`ForgetExecutor.wipeMemory`: revoke all, then the existing wipe). With the flag OFF the legacy bodies in `ForgetService` and `MomentForget` run unchanged and the callers keep their `LeaseRevocation` calls; ON, those call sites defer to the executor. `GoldenDeleteSetTest` runs every path twice (flag OFF and ON) and both must equal the same literal set. `ForgetExecutorInventoryTest` fails on any deleting DAO call outside the executor, the families and the DAO layer, with a ratchet for the legacy copies.

**What M4 deliberately does NOT change.** The executor deletes exactly what v1 deletes. Where the design differs, v1 is kept and the difference is listed in code as `DesignDivergence.ALL` (each entry names the golden test that pins v1). Please rule on each; a ruling changes that golden and the family code together (M5/M7 work).

| Id | Design | v1 (implemented) | Golden |
|---|---|---|---|
| CARRIED_BATCH_MATE | B1: the batch mate is rebuilt (solo re-describe) | invalidated with its descendants; its own photos are never locked or deleted; no re-describe | `forgetPhoto` |
| FACT_FORGET_LOCKS_CITED_PHOTO | a fact Forget removes the fact only | every photo the fact cites is tombstoned, and a photo no surviving memory cites loses its vector and file | `forgetFact` |
| SCENE_REBUILD_VS_DROP | a Scene that loses Moments is rebuilt | it shrinks (derived fields hidden) and only an empty or named Scene is dropped | `momentScopeSpans` |
| ASK_NUDGE_REDACTED_IN_PLACE | A1 re-answers, N1 deletes the nudge | answers and nudges are redacted in place (the question stays); proposals expire and are scrubbed | `forgetFact` |
| MULTI_PHOTO_CASCADE | rebuild from the surviving photos | forgetting one photo of a multi-photo memory deletes the memory and cascades to its other photo | `forgetFarPhotoCitedByAMultiPhotoFact` |
| REPLAY_SWEEP_INVALIDATION | replays and sweeps are planned RunResult nodes | invalidated by the range Forget (`invalidateBySources`), helper jobs voided and scrubbed, `moment_sensor` purged by time: none of it is in the plan or its hash | `rangeForget` |

Not implemented, as ruled: the additive ask JSON-state column (needs a migration ruling) and the A1/N1/M5 UI states. Known limits of M4: the plan is used for the lease revocation (and the scoped Forget's confirmed hash); the delete itself follows the v1 steps over the same database state, because the v1 steps are order dependent. The planner gaps (replays, sweeps, helper jobs, `moment_sensor`) stay open: closing them would change the plan hash of the range Forget, so they need the ruling above first.

## 9. M5 implementation note (2026-11-17, rulings applied)

Built behind `forgetV2` (OFF = v1, byte-identical). Rulings: no automatic re-answer; the question stays and the answer becomes "Answered from a forgotten fact" with an **Ask again** button; one additive Room column (v16 -> v17, `ask.sourceStates`, nullable TEXT, `MIGRATION_16_17`, `17.json`). The column holds ids and fixed tokens only: `{"v":1,"state":"LIVE|SOURCE_FORGOTTEN|REASKED","cited":[...],"history":["nudge:<id>"],"reaskedAs":"<id>"}` (`AskSourceStates`). It is always written (eval asks: null) and read only with the flag ON. With the flag ON: A1 an answer is hit by its CITED ids (and, A2, by the earlier nudges/answers it used, to a fixpoint; a row with no states uses the cited array of `sourcesJson`, never its `dependsOn`), its answer text is erased in the same transaction and the state becomes SOURCE_FORGOTTEN; N1 a nudge reached by any dependency is deleted (ERASE) with its proposals expired and scrubbed. Preview == delete: `AskRules.reach` is the one rule used by `ForgetPlanner` and `ForgetExecutor`. `ReanswerWorker.reask` is only the user's tap (stored question through the normal typed Ask; the old row is marked REASKED). Not changed: developer eval asks (`canvas_eval`) keep the v1 rule. **Superseded 2026-11-17 (Codex M5 r1 P1, proxy ruling F4):** dependency tracking stays CONSERVATIVE: with the flag ON an answer is hit by ALL its supplied context (the `sourcesJson` ids, cited and `dependsOn`, plus the states' cited ids and history items), never by its citations alone; a migrated v16 answer keeps its `dependsOn` protection (nudge history included). `AskRules.reach` stays the one rule of preview and delete. N1: when the deleted nudge owned the phone / HUD toast and an older row becomes the newest, the toast is cleared at once (`NudgePort`).

## 10. Stage 2 (2026-11-17, proxy rulings F1, F3, F5, F6; flag `forgetV2` only, OFF byte-identical)

Built and pinned (`GoldenDeleteSetTest` `onFull` expectations, `ForgetStage2Test`); the DESIGN_DIVERGENCE entries CARRIED_BATCH_MATE, SCENE_REBUILD_VS_DROP, MULTI_PHOTO_CASCADE and REPLAY_SWEEP_INVALIDATION are marked ruled. No Room change (v17), no new permission.

- **F1 / F5.** A Forget of photos passes the photos it forgets as `protect`. A photo that a memory ALSO cites (a carried batch caption, a multi-photo memory) and is not forgotten is a survivor: the mixed memory is erased first (v1 already erased the carried one; the mixed one is no longer a lock on the other photo), the survivor is never locked, tombstoned or deleted, its stored outputs are scrubbed, and a survivor with no description left goes back to PENDING with `lastError = solo_rebuild` (`ForgetDao.requeueSolo`). The ordinary extraction pipeline then describes it, one photo per call (`KeyframePipeline.soloFirst`), so its provenance, pause-zone and power gates apply unchanged. A Forget of a FACT keeps v1 (F2).
- **F3.** The plan marks a Scene that keeps Moments REBUILD and its derived rows DROP; the executor erases those rows (`SceneDao.eraseDerived`) after the v1 shrink; the derive pass writes them again. An empty or named Scene is DROPPED as before. No plan (planner failure): every hidden Scene field is erased (fail closed).
- **F6.** The plan (with the flag ON) also names the replays and sweeps `invalidateBySources` would invalidate (RUN_RESULT `replay:` `sweep:`), the helper jobs a Forget voids or scrubs (`job:`) and the `moment_sensor` rows it purges (MOMENT `sensor:`). `MomentForget.hashOfV2` hashes them with a plan version, the surviving photos rebuilt and the Scenes rebuilt: every v1 confirmation token is invalid, the user must preview and acknowledge again; preview and delete compute the same hash from the same planner.

Residual: the rebuilt photo text is only as good as the model call; a rebuilt photo is described only when the pipeline's gates allow it (Battery Saver, heat, offline), until then it has no description. Not changed: F2 (needs the real user).

