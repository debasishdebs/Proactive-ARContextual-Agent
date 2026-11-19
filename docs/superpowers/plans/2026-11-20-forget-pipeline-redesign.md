# Forget pipeline redesign: one flow for every kind of Forget (design, not built)

Status: DESIGN, written 2026-11-20. Build after the install freeze and the Sikkim/Darjeeling trip (v1.2 row of `ROADMAP.md`), then a full worn test before Sri Lanka. Origin: user ruling 2026-11-20: "source carrying metadata, then a moment selector, then forgetPart which forgets or de-links per policy; the confirmation dialog is part of forgetPart; same experience for every type."

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
