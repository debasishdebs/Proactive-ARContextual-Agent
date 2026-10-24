## v0.9.2-beta

Builds on v0.9.0-beta (about 290 commits: v0.9.1 bug fixes and slice 3b, then the v0.9.2 work below). Debug-signed; no APKs are attached.

**Canvas and Python (slice 3b)**
- Helper jobs for Python experiments (laptop helper + Podman VM, one confirm tap per run on the phone), float results hashed by exact text, Forget kills running jobs, Delete all wipes helper state. Live gate 16/16 on podman 6.1.4.
- Moment-lite (`input.moments`) as a canvas input; built-in read-only `Default capture` pipeline (the glasses capture half, never active by existing); merged multi-pipeline view (`#/merged`, signal links, dashed captured-frames link, "Show with capture"); composite expandable nodes phase A (Confidence check with real aggregate/operator/cutoff settings, defaults unchanged); card spacing fix.

**Capture expressions (slice 3c, guards only)**
- `expr.guard`: a yes/no rule over the seven motion features and the activity context that can only veto a capture; boxed two-level editor; glasses runner and replay parity; SHADOW/TRIAL only (LIVE stays refused until the battery work).

**Logging and diagnostics**
- Always-on diagnostic recording on the glasses (one logger, one gate, state lines that name every blocker), space policy and cleanup fence, wear-window hand-over (`tools/handover.sh`), replay stall model, M1 gate (failing windows <= 3%, cap 5%, plus shadow parity).

**Moments**
- Stored Moments (Room v9/v10) on by default with a kill switch, Forget cascade with tombstones and a durable range fence, trace clock fix, object audit and derived-field tables with guarded writes.

**Known limitations**
- Nothing here has had a real-browser visual pass or a full worn day; the 3c guards run only under SHADOW; M1 parity is off by one open on one real trace.
- Phone database is Room v10 and migrations are one-way: never sideload an older phone build over it. Back up before every `adb install -r`.
