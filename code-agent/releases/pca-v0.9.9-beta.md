## v0.9.9-beta

Debug-signed; no APKs are attached. Phone database is Room v15 (additive migrations, one-way: back up before every `adb install -r`, never uninstall).

**PCA watch app**
- A `:watch` Wear OS module (`com.rokid.context`, capability `pca_watch`) with a passive motion sender, a phone Data Layer receiver, a `watch_app` source, a status row and a setup step; watch UI polish with a last-sent line. The watch manifest has a strict permission allowlist and no backup.
- The LiveFit motion push was removed from PCA.

**Background policy**
- `BackgroundPolicy` gates the glasses link machinery; the watchdog and relauncher back off (5, 15, 30, 60 minutes); a needed hub is restarted with a persisted backoff; the hub service runs only while needed.
- AutoStop: the background hub stops by itself while the glasses are away (default 20 minutes, 5 minutes to 2 hours or Never), starts again when the app is opened or Reconnect is tapped, with an optional battery-exemption wake ladder; the banner says why it stopped.

**Voice**
- Voice pause and resume commands; replies say only what was confirmed (a resume says the phone pause was cleared, never that capture resumed).

**Final review fixes**
- Forget for answers, nudges and photo-less marks was added then removed again (v1.1 backlog); marks fenced against an undone parent; preview reload race, mark preview updates, stale schedule scope, glasses SILENT ceiling and history-only nudge refusal fixed; "Show that time" scrolls the day view.

**Reviews**
- Final Codex pass: three parallel area reviews plus a verify review; all P1 findings fixed.

**Known limitations**
- Voice pause and resume replies name the glasses only when the settings push was accepted and never report the glasses' low-battery hold; no event restarts a stopped hub when the glasses are put on (app open, Reconnect or the wake ladder do); watch messages are dropped while the hub is down; no single answer or nudge Forget; list caps of 1,000 answers and nudges and 200 marks.
