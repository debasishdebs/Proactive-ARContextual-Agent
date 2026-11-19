# Forget pipeline redesign: one flow for every kind of Forget (design, not built)

Status: DESIGN, written 2026-11-20. Build after the install freeze and the Sikkim/Darjeeling trip (v1.2 row of `ROADMAP.md`), then a full worn test before Sri Lanka. Origin: user ruling 2026-11-20: "source carrying metadata, then a moment selector, then forgetPart which forgets or de-links per policy; the confirmation dialog is part of forgetPart; same experience for every type."

## Correction found 2026-11-20 (read before the Shape section)

A plan/preview/execute engine ALREADY EXISTS: `services/memory/.../forget/lifecycle/ForgetPlanner.kt` and `ForgetExecutor.kt`, wired to multi-select Forget (long-press select, top-right menu, plan/preview/acknowledgement/refusal) behind the Developer flag `forgetV2` (default OFF; commit ff685350). Activity pagination (commits 99054e92, 09ceb09b) and the generic multi-select list screen (1589bc23) are also built. So this redesign is NOT a new engine: it is (1) make the planner/executor the ONLY Forget path (move single, scoped, range, Delete all and the system sweeps onto it, then retire the old per-flow code and the flag), (2) add `ForgetSource` metadata and the one policy table (pins, marks, answers) to the planner, (3) one confirmation dialog built from the plan. The estimate below is therefore an upper bound until the planner's current coverage is mapped (first task of the build: a coverage map of which entry points already use it).

## Why

Forget grew one entry point at a time (single, scoped Photo/Moment/Scene, multi-select, range, Delete all, crash-resume, system expiry and sweeps). Each has its own dialog wiring and its own post-commit cleanup. Consequences seen on 2026-11-19/20:
- range Forget has an engine path (`ForgetActions.forgetRangeOutcome`) but no screen, so no dialog;
- the pin-to-Moment cascade had to be hooked into `ForgetActions`, `MemoryWipe` and `ForgetStartup` separately, and an Opus review found a stale-arming hazard that comes from that duplication;
- the Delete all dialog and the single/multi dialogs name children differently.

## Shape

```
user action or system job
        │  builds
        ▼
ForgetSource ──► MomentSelector ──► ForgetPlan ──► confirm (one dialog) ──► execute ──► cascade ──► report
```

1. **ForgetSource** (immutable): `kind` (SINGLE, MULTI, RANGE, SCOPE, DELETE_ALL, SYSTEM_EXPIRY, SYSTEM_SWEEP), `explicit: Boolean` (the user asked for it), the raw selection (ids, time bounds, scope Photo/Moment/Scene), and the origin screen. No behaviour.
2. **MomentSelector**: the only code that turns a source into target Moment ids (and the photo/fact ids under them). Pure and testable: same source and same data give the same targets. Replaces the per-flow discovery code (`forgetSpans`, range discovery, scoped discovery).
3. **ForgetPlan**: from targets and the policy table. Lists, per object family, what is **forgotten** (deleted) and what is only **de-linked** (link cleared), with counts and, for families that have few items (pins, marks), the names. The plan is the single input of the dialog, the executor and the cascade, so what the dialog says is what runs.
4. **Policy table** (one place): for each (child family, source.explicit) the action. Examples: pin + explicit = FORGET (named in the dialog); pin + system = DE_LINK (pin shows "Needs a Moment"); answers/nudges that cite a forgotten memory = per the existing Forget rules; marks = per existing rules. A new family adds one row and one test.
5. **forgetPart** (confirmation, execution, cascade):
   - confirmation: ONE dialog component built from the plan (title by source kind, counts, named few-item families, "cannot be undone", Cancel / confirm). System sources never show it. Cancel changes nothing.
   - execution: inside the existing DataOpGate and the single lock; commit as today.
   - cascade: runs from the plan after the commit; the plan is persisted before the commit (arming) so a crash is finished at the next start, and it is bound to the commit (an arming that no committed Forget/wipe owns is dropped at startup, never carried over).
6. **Report**: a typed outcome (as today) shown by the same snackbar/dialog for every source.

## Rules that must keep holding

- Only a Forget with `explicit = true` may delete a user-authored object (pin). System sources only de-link.
- The dialog and the execution read the same plan; nothing is computed twice.
- The arming for a cascade names (child id, Moment id) pairs and expires; a stale arming never turns a later system removal into a deletion.
- Delete all and the crash-resume wipe use the same plan type (source kind DELETE_ALL), so their dialog names the same families.
- No pin name, Moment content or id in logs (counts and kinds only), as today.

## Migration plan (incremental, each step shippable)

1. Introduce `ForgetSource`, `ForgetPlan` and the policy table with tests; adapt single Forget and the pin cascade to read the policy (behaviour unchanged).
2. Move multi-select and scoped Forget onto the selector and plan; one dialog component.
3. Wire range Forget to the same flow (the screen that triggers it gets the dialog for free) and Delete all.
4. System expiry and sweeps build `SYSTEM_*` sources; the de-link rows are enforced by the table.
5. Delete the old per-flow discovery and dialog code; keep the inventory tests (`DeletionInventory*`, `FamilyInventoryTest`) as the safety net, and add one test per (family, source kind).

Estimate: 4 to 6 agent-days of build, 1 to 2 days of review and fixes (Forget code has needed Codex rounds every time), then a worn Forget checklist on the real devices.

## Out of scope here

Changing what is retained or for how long (retention rules), the answer/nudge Forget semantics, new object families.

## Step 1 built (2026-11-20, additive, no behaviour change)

Built in `services/memory/.../forget/lifecycle/` and read by NOTHING in production yet (no dialog, executor, `ForgetPlanner`, `ForgetExecutor` or `ForgetActions` change):

- `ForgetSource.kt`: `ForgetSourceKind` (SINGLE, MULTI, RANGE, SCOPE, DELETE_ALL, SYSTEM_EXPIRY, SYSTEM_SWEEP, SYSTEM_PURGE, each with its `explicit`), `SourceSelection` (None, Ids, TimeRange, Scoped) and `ForgetSource(kind, explicit, selection, origin)`. A wrong `explicit` flag fails at construction; `toString` is counts and kinds only.
- `ForgetPolicy.kt`: THE policy table. `ForgetChild` (every lifecycle `Family` plus EMBEDDING, FILES, PIN, GLASSES, LOGS) x source group (user kinds share one cell, DELETE_ALL, SYSTEM_EXPIRY, SYSTEM_SWEEP, SYSTEM_PURGE) -> `FORGET | DE_LINK | CASCADE_BY_EXISTING_RULES | KEEP`. Each cell carries the `file:line` that implements it today; 12 cells copied from the coverage map are flagged `verified = false` (step 2 must re-read them). Thumbnails have no store of their own (rendered from the JPEG), so they sit under FILES.
- `PolicyPlan.kt`: `PolicyPlan.of(source, targetCount, children)` is a PURE function giving forgotten / de-linked / cascaded / kept entries with counts and, for pins and marks (`fewItems`), up to 5 names plus the exact ids. Named `PolicyPlan`, not `ForgetPlan`, because `ForgetPlan` is already the planner's node -> Fate type. It is the user-facing disposition view of the same Forget, not a second planner.
- Tests: `ForgetPolicyTest` (second written copy of the matrix, one failure message per cell, a new `Family` / `Families.ALL` entry / DeletionGraph table owner with no row FAILS, WIPED tables never KEEP, KEPT tables never FORGET, only explicit sources forget a pin), `ForgetPolicyConformanceTest` (the real planner on the golden world, v1 and v2: it never touches a family the table says KEEP, never ERASEs one the table says DE_LINK), `ForgetPolicyPinConformanceTest` in orchestration (the real `PinMomentLinks` explicit cascade, Delete all wipe cascade and sweep against the PIN row for every kind), `PolicyPlanTest`.

Known divergences the table records, not fixes (need the rulings of coverage map section 5): MARK under SYSTEM_EXPIRY is CASCADE (photo expiry erases marks in the radius, G9); PIN under mark undo from the glasses is FORGET with no dialog (G2); legacy-frame expiry and orphan-memory purge use the user cascade (G7), so their cells say CASCADE / FORGET for memories.

### What step 2 will do

1. Thread a `ForgetSource` through `ForgetActions.run`, `MemoryWipe.deleteAll`, `RetentionWorker`, `SuspectSweepWorker` and `InvalidMomentPurge` as a parameter only (default = the kind they are today), still no behaviour change; add the entry-point registry test (coverage map step 0).
2. Add the PIN family to the PREVIEW: build the `PolicyPlan` for each existing dialog (previewer, multi-select, scoped, range, Delete all) and replace the three hand-made `pinsGoing` reaches (`FA:100`, `FA:116-118`, `MemorySettings.kt:109`) with `plan.entry(PIN)`; execution passes the plan's (pin, Moment) pairs to `PinMomentLinks.explicit` instead of `reached = { true }` (medium risk: changes which pins go, so `PinMomentLinksTest`, `MemorySettingsTest`, `SelectionControllerTest` plus the new conformance tests are the net).
3. Re-read the 12 `verified = false` cells and flip them; get the four rulings (marks under expiry, pins under glasses undo, orphan purge, watch-off / canvas run delete).
4. Only then the one dialog (step 4 of the migration plan) reads `PolicyPlan`; the planner's `ForgetPlan` stays the executor's input.
