# Addendum to HANDOFF-2026-10-24 (written at compaction time)

## A background agent was still running when the parent session was compacted
- Agent task (Sonnet): worktree `~/Desktop/rokid/pca-merged`, branch `feat/merged-view` (from v0.9.2 tip 7257bab5). Work: (1) fix the collapsed composite card overflowing its layout cell and overlapping the next node, with layout by real node height; (2) merged multi-pipeline view (checkboxes on the Pipelines list, read-only merged lanes, signal-name links, dashed captured-frames link capture.image -> input.photos/input.moments, "Show with capture" with the built-in `default-capture`). Stop rule about 6 agent-hours.
- Its report may arrive in a session that lacks this context. It was told to commit a self-contained handoff at `docs/superpowers/plans/handoffs/2026-10-24-merged-view-handoff.md` on its branch before stopping. If that file exists on `feat/merged-view`, the work is finished: read it.
- How to pick it up in a fresh chat (no agent context needed):
  1. `cd ~/Desktop/rokid/pca-merged && git status && git log --oneline -8` (clean tree and the handoff file mean it finished; dirty tree means it stopped midway: read the transcript only if needed, otherwise ask the user whether to redo the missing part).
  2. `cd ~/Desktop/rokid/pca-int1` (branch v0.9.2): `git merge --no-ff feat/merged-view -m "merge: composite spacing fix and merged multi-pipeline view"`.
  3. Full test run as a tracked background task (modules core:model, core:capture, services:memory/ai/orchestration, glasses, phone + `:phone:assembleDebug`); rerun flaky tests alone (PromptEditorTest, LocalModelsPageTest, JsEditorTest, StepSheetTest, CanvasServerTest port tests).
  4. Ask the user for "go", then: phone backup (`adb exec-out run-as com.rokid.context tar -cf - databases shared_prefs`), `adb install -r` of `android-companion/phone/build/outputs/apk/debug/phone-debug.apk`, launch, verify the process is alive. Never uninstall; never during a call.
  5. Update `MORNING.md`/`road-to-v1.md` (bug 22 spacing: fixed or not) in a NEW dated note, not by editing the frozen handoff.
- The Python live-gate driver and the Codex prompts are in the scratchpad (`/private/tmp/claude-501/-Users-debasishkanhar/5f738bb7-228a-40ad-8dd1-9f72f0041163/scratchpad`); that folder is session-local and may be gone: everything needed is in the repo docs.
- The visual-companion server (localhost:51188) was started from `pca-int1/.superpowers`; it stops itself after 4 hours idle. Add `.superpowers/` to .gitignore.
