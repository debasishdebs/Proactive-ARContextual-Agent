# Final fixes Y handoff (2026-10-30)
1. PreviewViewModel.reload: single Job, cancelled when superseded, generation-guarded publish (test: slow old load vs redacted reload).
2. MarkRepository.observeById + RepoActivityData.changes(MARK) observes label/photo/Moment (test in MomentTypeStoreTest).
3. Schedule work carries publication scope (NudgeTrigger.KEY_SCOPE); NudgeEngine.runOnce(scope) drops mismatches as NudgeRun.StalePublication, trace NUDGE_DROPPED/STALE_PUBLICATION.
4. Explicit glasses delivery under SILENT stays phone, reason verbosity_silent (NUDGE_DELIVERY_FALLBACK).
5. FlowPlan.compile refuses Nudge recent=false, history=true, imageQuery=false.
Frozen constants untouched; no JS change. Known: BackupRulesTest hub_restart fails from the watchdog agent's in-progress prefs file (not mine).
