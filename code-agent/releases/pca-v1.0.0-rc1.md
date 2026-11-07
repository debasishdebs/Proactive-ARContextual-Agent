## v1.0.0-rc1 (DRAFT)

Draft; not tagged. Debug-signed; no APKs are attached. Phone database is Room v15 (additive migrations, one-way: back up before every `adb install -r`, never uninstall).

**What it is**
- The candidate that carries the v0.9.5 to v0.9.9 train: hardening and Forget privacy, Moments with text, Run & Save, a mark that takes a photo, editable signals, Forget scope, one Utterance backend, the shared RAG core with Query and Nudge canvas flows, triggers and delivery routes, device capabilities, one previewer, the PCA watch app, background policy and AutoStop, and voice pause and resume. See the per-version notes.

**Reviews**
- Codex reviewed each slice (several rounds on RAG core, Forget scope and the utterance backend), then a final pass of three parallel area reviews plus a verify review, all P1 fixed; an Opus review produced the release hygiene work (ignore rules, README, backup rules, release notes).

**Known limitations**
- Voice pause and resume replies never claim or deny the glasses' low-battery capture hold (the phone has no signal for it); a resume says "Your phone pause was cleared ..." and names the glasses only when the settings push was accepted by the link.
- Moment and Scene Forget scope has an in-flight writer race (v1.1 backlog) and is Developer mode only; no single answer or nudge Forget.
- The hub is not restarted after a reboot until the app is opened.
- List caps: 1,000 answers and nudges and 200 marks.
- The range-Forget acknowledgement path has no caller; the derived-signal try-it is a preview.
- LIVE guards and values on the glasses wait for the worn gate.
- Task 38 and the slice 3b live steps must run before v1.0 final; a release-signed build and version bumps are still to do.
