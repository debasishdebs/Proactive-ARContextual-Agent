## v1.0.0-rc1

Debug-signed; no APKs are attached (release signing is a key rotation at v1.0.0 final). Phone database is Room v16 (one additive migration from v15, one-way: back up before every `adb install -r`, never uninstall). Versions: phone and glasses versionCode 3, watch 31, versionName `1.0.0-rc1`.

**What it is**
- The candidate that carries the v0.9.5 to v0.9.9 train (hardening and Forget privacy, Moments, Run & Save, a mark that takes a photo, editable signals, Forget scope, one Utterance backend, the shared RAG core with Query and Nudge flows, the PCA watch app, AutoStop, voice pause and resume) plus a week of photo-privacy fixes. See the per-version notes.

**Photo privacy**
- A photo, and anything derived from it, follows Forget: canvas runs, summaries, Moments, marks, queued notes and saved memories are removed or stop being used when the photo is forgotten. A forgotten photo is never sent to a model, and neither is text that came from one.
- Open screens, the glasses HUD, canvas pages and reminders drop deleted content: record lists, previews, Diagnostics and canvas pages refresh after a Forget, a retention delete or Delete all; an answer on the HUD clears as soon as its question or source is redacted; a fired reminder re-checks after posting and withdraws a forgotten one.
- A photo is never described unseen: when a model refuses a photo, the photo stays queued and nothing is written (the text-only retry was removed, because it could produce a made-up description that let the photo be deleted).
- Run & Save saves a photo caption only when the run proves the photo reached the model.

**Database v16 and Suspect descriptions**
- The migration adds a photo-delivery flag (three nullable columns) so a description written without the photo can be told from a real one. It ran cleanly on a real encrypted phone database.
- Diagnostics > Suspect descriptions lists older photo descriptions that cannot be proven. Mark for review (one or all) only sets a flag: no text or photo is deleted. A flagged description no longer counts as the photo's description, so the night job describes the photo again before it expires.

**Search**
- Semantic and hybrid search need the Nomic Embed model: download it in Settings > AI > On-device models and use it for Embeddings. The index is rebuilt after you enable it (leave the phone on a charger). Until then search uses keywords only and the app says why.

**Reviews**
- Eleven further Codex rounds (r13 to r24, except r19) and one Opus review on the photo-privacy, migration and stale-display work; the last Codex round (r24) found no P1.
- Installed and worn-tested on the phone, glasses and watch: no crash; Forget on a worn day and question answering were not yet exercised.

**Known issues**
- Over-linking: a memory can be removed when an unrelated photo that was processed in the same batch is forgotten. Photos are never deleted this way.
- Glasses wear detection: when the glasses report wear detection as off, or the firmware pins the proximity sensor to worn, "not worn" is not detected, so AutoStop never sees the glasses as taken off (it still stops when they disconnect). Turn Wear detection on in the glasses and reboot them. A fallback is planned for v1.0 final.
- The suspect list is broad: it holds every older photo description, not only the bad ones. Re-describing costs model calls, up to 60 a night.
- The vision provider's content filter can refuse some ordinary home photos (HTTP 400). Those photos stay stored without a description (no text-only guess is made); they are never deleted for lacking one.
- Two tests are occasionally flaky (the local models download dialog and a canvas refresh test).
- "Delete this mark" is not a voice command (it is treated as a question); delete a mark from Activity.
- Release signing is pending (v1.0.0 final).
- Carried over from v0.9.9: Moment and Scene Forget is Developer mode only and has an in-flight writer race (v1.1 backlog); no single answer or nudge Forget; the hub is not restarted after a reboot until the app is opened; list caps of 1,000 answers and nudges and 200 marks; the range-Forget acknowledgement path has no caller; the derived-signal try-it is a preview; LIVE guards and values on the glasses wait for the worn gate; Task 38 and the slice 3b live steps must run before v1.0 final.
