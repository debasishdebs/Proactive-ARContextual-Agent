# Retention without silent loss: design (2026-11-24)

Author: Opus (design). Status: **proposal; needs the user's rulings R1-R8 (§8)**. Findings: `docs/superpowers/plans/handoffs/retention-redaction-analysis-2026-11-24.md`.
Room stays at **v17** for every phase that lands before the next outing. v18 is held (ruling 2026-11-18); §6 lists only what v18 would add later.

## 1. Problem in one paragraph
The daily retention pass treats storage hygiene like a privacy Forget. Every extracted fact is SHORT (`KeyframeExtract.kt:88`, `ClusterSave.kt:86`), so after 24 h:
- `ForgetService.expire` deletes the facts;
- it redacts every nudge and answer that cites any one of them (`ForgetService.kt:213-218`), using the Forget wording (`:290`);
- it drops every stored Moment holding them (`:197`).

Nothing rebuilds those Moments, because the cadence covers only the last two hours (`MomentBuildTrigger.kt:110-115`). On the 21-23 Nov trip this cost:
- 123/130 nudges and 72/111 answers;
- 2,448 photo-seconds of Moments (19-22 Nov) and their Scenes;
- the Moment attachment of all 20 place cards.

The captions, cluster summaries and place memories that already describe the same photos were LONG and intact. They were simply never used as the surviving record.

## 2. Principles (the invariants the tests enforce)
- **I1 — System expiry never changes a dependent's text.** Nudges, answers, Moments, Scenes, marks, pins, place cards and accepted proposals keep their user-visible text through every retention step. Only a **user** Forget or Delete all redacts. The privacy model is unchanged for explicit Forget.
- **I2 — Resolve before removing.** Before a memory expires, each dependent is resolved by exactly one of:
  - **PROMOTE**: the source is kept;
  - **RE-ANCHOR**: the dependent is re-pointed to a durable record of the same photo or second;
  - **DIGEST**: a durable minimal record is written, then re-anchored.

  The resolution is recorded in the dependent's own JSON.
- **I3 — Explicit importance pins its sources.** These are explicit-importance objects:
  - place cards (`type='place'` memories);
  - marks (`canvas_label`);
  - pins and their reference Moment;
  - rated or re-asked answers;
  - accepted proposals (EXECUTING / EXECUTED);
  - nudges with a proposal or opened by the user (R8).

  Their sources and their photo are exempt from tier and photo expiry while the object exists. They go only by user Forget (of the object or the source) or by Delete all.
- **I4 — Projections are rebuilt, never just dropped, by expiry.** A Moment or Scene affected by expiry is rebuilt in place from the surviving sources, in the same run.
- **I5 — Expired is not forgotten.** Different state tokens and different wording (§4.3).
- **I6 — Auditable.** Every run logs a counts-only plan before acting and a counts-only decision record after. A read-only preview shows what the next run would do (§4.4).

## 3. The resolution algorithm (one retention chunk)
Input: the expiring memory ids `E` of one tier or kind chunk, as today: `ForgetDao.expiredIds`, `expiredIdsOfType`.

1. **Exempt.** Remove from `E` every id that an explicit-importance object depends on (I3): one SQL `NOT EXISTS` per family, the same families `ForgetPlanner` already walks. These ids are not expired. They are counted as `promoted` (logically pinned, with no tier change; see §6 for why no new enum value).
2. **Dependents of the rest.** Collect them:
   - `D_n`: nudges citing `E`, with `parseIds` including `dependsOn`;
   - `D_a`: answers citing `E`, through `sourcesJson` or `sourceStates`;
   - `D_m`: Moments holding `E` (`momentIdsHolding(MEMORY, SUMMARY)`);
   - `D_s`: Suggestions records citing `E` (`ProactiveForget` liveness).
3. **Anchor per expiring id.** For each `m ∈ E` with frame `F`, the anchor is the first live of:
   - (a) the LONG caption of `F`;
   - (b) the place memory of `F`;
   - (c) the cluster summary whose `frame_source` holds `F`;
   - (d) an existing digest of `F`.

   If there is none (no frame, or an uncaptioned frame), write a **digest** first (§4.2).
4. **Re-anchor, in the same transaction as the delete:**
   - **Nudge.** Rewrite `sourceIdsJson`: each `m` becomes its anchor id, and a trailing `{"expired":["m",…]}` object is appended. `parseIds` already ignores objects other than `dependsOn` (`ForgetService.kt:305-310`), so older code reads only the anchors. `text`, `why` and `redacted` are untouched.
   - **Answer.** `sourcesJson`: same substitution. `sourceStates` (v17 column, `AskSourceStates.kt`) gets a new state token `SOURCE_EXPIRED` (answer kept) and an `"expired"` id list. Ids and tokens only, per that file's rule.
   - **Moments.** Collect the affected Moment ids (seconds) instead of calling `dropHolding` for memories. Photo expiry keeps its own rule until R4.
   - **Suggestions.** Treat a re-anchored source as live. Withdraw only when no anchor exists.
5. **Delete `E`** as today: rows, vectors, edges. Keyframes are never tombstoned (`keepKeyframes`). No redaction and no proposal scrub; accepted proposals are already kept.
6. **After commit** (orchestration side; the memory module stays free of orchestration, `MemoryHasNoOrchestrationDependencyTest`): `RetentionWorker` gets the affected seconds from a new `ForgetService.Result.rebuildMoments` field, then:
   - calls `MomentTriggers.builder(app).build(seconds)`;
   - calls `SceneBuilder.rebuildWindow` for the affected hours.

   Both are idempotent and fenced, so a Forget that lands in between wins.

The same change applies to `ForgetExecutor.expire` (`ForgetExecutor.kt:146-150`) and to the retention fate of nudges in `Families.kt:260`, so `forget_v2` on and off behave the same.

## 4. Components

### 4.1 Retention hotfix (P0)
- `cascade(...)` gets `redact: Boolean`. Every `Policy.Retention` caller passes `false`; Forget, scoped Forget, suspect removal and Delete all keep `true`. Suspect removal is a user-marked action, so it stays a redaction.
- With `redact=false`: append the `expired` marker only, and skip `dropHolding(memories)`. Return the affected seconds for rebuild (§3.6).
- Developer switch **"Pause clean-up"**: a `pc_dev` key, read at the top of `RetentionWorker.retain()`. It logs `retention paused` and returns success. This is the safety valve for outings until P2 lands.
- **Repair pass**, one-shot. A `canvas_meta` marker records it (no user data), and it is bounded to the last 30 days in chunks. For every day that has live photo seconds without a stored Moment, it runs `MomentBuilder.rebuildWindow` per day, then `SceneBuilder.rebuildWindow` per hour. On this phone that restores about 2,448 Moments for 19-22 Nov from photos and LONG memories. Also add `scenes` to `POST /api/moments/rebuild` (`MomentRoutes.kt`) so a day can be repaired by hand.

### 4.2 Durable minimal record: the digest (P2)
- A LONG memory with `type='digest'`, `derived=1`, `reviewState=OK`, one per frame, and a `frame_source` row with role `DIGEST`. Because `derived=1`, it never counts as a description (`DESCRIBED_BY_EXTRACTION`), so photo-deletion rules are unchanged.
- Fields carried in `text` and `tags`:
  - **what**: the expiring facts' texts, deduplicated and capped at 300 chars, deterministic with no model call (R2 may choose structured-only or an LLM summary instead);
  - **when**: the frame `ts`;
  - **where**: `frame.placeLabel` or the place memory;
  - **confidence**: the maximum;
  - **source kind**: fact types and counts.
- Written only when step 3 finds no caption, place memory or cluster summary. On this phone that is rare: 13,213 captions for about 15k keyframes.

### 4.3 Wording (P3)
| Case | Feed / preview text | State token |
|---|---|---|
| User Forget reached the source | "Removed — you forgot a source" (today's string is fine for this case) | `SOURCE_FORGOTTEN` / `redacted=1` |
| System expiry | Text kept. A small footer in the nudge or answer detail: "Some details behind this were short-term and have expired; the summary is kept." | `expired` list / `SOURCE_EXPIRED` |
| Photo expired, memory kept | "Photo expired" (exists today) | — |

The settings note (`MemorySettings.kt:187-192`) is rewritten: "Short-term details are folded into captions and summaries before they are removed. Nudges, answers, Moments, marks and place cards are never removed by this clean-up; only Forget removes them."

### 4.4 Preview and decision log (P4)
- **Before acting**, one line: `PcRetention plan: shortDue=N mediumDue=N longDue=N kindDue=N exempt=N reanchor=N digest=N nudgesKept=N answersKept=N momentsRebuild=N photosDue=N skippedDue=N vectorsDue=N legacy=N`. Counts only, no ids, no text.
- **After acting**, `PcRetention done:` with the same fields as committed, plus `failedChunks` and a duration bucket. Today retention logs nothing on success, which is why this incident could not be read from the logs.
- **Decision record.** A ring buffer of the last 30 runs in `canvas_meta` key `retention_runs` (JSON: run time, settings in force, the counts above per capture day). No ids, no text, no Room change. It appears in Diagnostics as "Retention history" and is included in Export.
- **Preview route.** `GET /api/retention/preview` (Developer mode) is read-only. It runs steps 1-3 in a read transaction against the current settings and returns the plan counts per capture day for "the next run" and "the next 7 runs". `tools/quality-eval.sh` can then call it before a trip, or `tools/trip-install-checklist`.

### 4.5 Photos of explicit-importance objects (P2, needs R3)
`linkedPhotosDue` and `skippedPhotosDue` (`ForgetDao.kt:177-209`) get one more `NOT EXISTS`. That clause excludes frames that hold a place memory or a mark, are a pin reference, or belong to a Moment an explicit object depends on. The photo and its Moment then survive `photo_days`.

## 5. What this would have done on 21-23 Nov
- **Nudges.** All 123 kept with their text. Their SHORT ids would be re-anchored to the same frame's caption (`rokid_kf_<batch>_cN`). That is verified for the 6 intact 23 Nov nudges: all 17 cited ids' frames have a caption. For the redacted ones it is inferred, because their sources were emptied.
- **Answers.** All 72 kept, marked `SOURCE_EXPIRED`.
- **Moments.** Rebuilt in place each night: still there for 19-22 Nov, with captions, place cards and cluster summaries.
- **Scenes.** Kept.
- **Place-card photos.** Kept past 7 days (R3).
- **SHORT facts.** Still removed at 24 h (or 48 h), as the user configured.

## 6. Migration and compatibility (Room v17; v18 notes only)
- **P0-P4 need no schema change.** They use existing columns and JSON: `nudge.sourceIdsJson`, `ask.sourcesJson`, `ask.sourceStates`, new string values `type='digest'` and role `DIGEST`, and `canvas_meta` keys.
- **Downgrade / older build:**
  - older `parseIds` ignores the `{"expired":…}` object;
  - an older build that reads `sourceStates.state = SOURCE_EXPIRED` must treat an unknown state as LIVE. **Verify** `AskSourceStates.parse` before shipping, or write `LIVE` plus an `expired` list instead;
  - `type='digest'` memories show as ordinary memories in older builds, which is acceptable.
- **Do not add a `MemoryTier` enum value** (for example PINNED). Room stores the enum name, and an older build's converter would throw on an unknown value. Exemption is computed by query instead.
- **v18, later and additive:**
  - `nudge.sourceStates TEXT NULL`, mirroring `ask` v17;
  - `retention_decision` table (run, day, kind, counts), replacing the `canvas_meta` ring buffer;
  - `moment.momentType = RECORD` (a no-photo Moment anchored by durable memories) if R4 says yes.

  Each needs a `MIGRATION_17_18`, `18.json`, and the backup/restore and export inventories updated (`DeletionGraphInventoryTest`, `DeletionOutboxInventoryTest`).

## 7. Tests
| Test | Invariant |
|---|---|
| `RetentionNeverRedactsTest` (new, memory + orchestration) | For every retention step (SHORT, MEDIUM and LONG tiers; caption; cluster summary; skipped photo; linked photo; vectors; legacy orphan), on a golden world with nudges incl. `dependsOn`, answers, Moments, Scenes, marks, pins, place memories and proposals: after the run every nudge and answer keeps `text`, `why`, `answer` and `redacted=0`. Each dependent's source ids are live or listed as `expired`, with at least one live anchor or digest. |
| `RetentionRebuildsMomentsTest` | The number of stored Moments for seconds with a live photo is unchanged by a tier-expiry run. No `moment_part` references a deleted memory. Scenes are rebuilt for the touched hours. |
| `ExplicitImportanceExemptTest` | The memories and photo of a place card, a mark, a pin reference Moment, a rated answer and an accepted proposal survive `short_hours`, `long_days` and `photo_days`, and go on user Forget. |
| `UserForgetStillRedactsTest` | Existing `ForgetServiceTest`, `ScopedForgetTest` and `ForgetFenceTest` stay green. A user Forget of an anchor still redacts the re-anchored dependents (the privacy chain holds through anchors). |
| `RetentionWorkerTest` | **Invert** `redactsAsksCitingExpiredMemory` (`RetentionWorkerTest.kt:101-106`) to `keepsAsksCitingExpiredMemory`. |
| `ForgetCopyTest` | Expiry never produces `REDACTED`. Forget does. |
| `RetentionPreviewMatchesRunTest` | The preview counts equal the decision record of a real run on the same snapshot. |
| Both `forget_v2` modes | Every test above runs with `ForgetV2.testOverride = true` and `false`. |
| `MomentRepairPassTest` | The repair is idempotent, bounded, fenced by tombstones and Moment Forget ranges, and runs once (marker). |
| **Device test** (required before hand-off) | Emulator restored from the 24 Nov dump copy: run retention with `short_hours=24` and assert 0 new redactions and the 19-23 Nov Moments present. Then the same on the phone after a fresh dump. Never hand over without this. |

## 8. Rulings needed from the user
- **R1** System expiry never redacts or drops dependents; it only marks sources expired (I1)? *Proposed: yes.*
- **R2** Digest content: (a) deterministic join of fact texts, capped (proposed); (b) structured fields only (what kind, when, where, confidence); or (c) an LLM summary per hour (model cost; later).
- **R3** Exempt the photos of place cards, marks and pin references from photo retention? *Proposed: yes.*
- **R4** May a Moment outlive its photo as a no-photo "record" Moment (needs v18)? This changes the rule "a Moment with no live photo is not a Moment". *Proposed: later; keep the photos via R3 instead.*
- **R5** Tier of extracted facts: keep SHORT (with re-anchoring and digest), or MEDIUM? And add 72 h / 7 d to Short-term (`SHORT_HOURS`)? *Proposed: keep SHORT, add 72 h and 7 d.*
- **R6** "Pause clean-up": Developer-only (proposed for P0) or a user-visible "Pause clean-up while travelling"?
- **R7** The exact strings in §4.3.
- **R8** Which nudges count as explicit importance: with a proposal, opened, or all? *Proposed: with a proposal or opened. The rest are re-anchored, so their text stays either way.*

## 9. Order, effort and timing (next outing Sikkim / Darjeeling 1-6 Dec)
| Phase | Scope | Effort | Lands by |
|---|---|---|---|
| **P0** | "Pause clean-up" switch; `redact=false` and no memory `dropHolding` on retention (§4.1, both `forget_v2` modes); invert the test; emulator device test on the dump copy | **S** | **Before 26 Nov ~00:50 IST**, which is when the 23 Nov facts, 6 nudges and 856 Moments go under the 48 h setting. If it cannot be installed and device-tested in time, the user flips nothing else: the in-app maximum is 48 h. |
| P1 | Moment and Scene repair pass + scenes in the rebuild route | S | 27 Nov |
| P3 | Wording + settings note | S | with P1 |
| P4 | Plan / done log lines, decision record, preview route, Diagnostics row | S | 28 Nov |
| P2 | Re-anchoring (nudges, answers, Suggestions), exemption of explicit importance incl. photos (R3), digest (R2) | M | 29-30 Nov (install freeze before 1 Dec) |
| P5 | v18 items (§6), LLM digests | L | after the trip |

Review: Codex (sol, medium) on each phase's diff, or Opus at low effort if the Codex quota is out; one final review pass over P0-P4. Sonnet implements, in parallel worktrees per phase. P0 and P1 touch `ForgetService`, `ForgetExecutor` and `RetentionWorker`, so they share one worktree. P3 and P4 can go in parallel.
