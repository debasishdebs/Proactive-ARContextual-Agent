## v0.9.7-beta

Debug-signed; no APKs are attached. Phone database is Room v15 (additive migrations, one-way: back up before every `adb install -r`, never uninstall).

**Signals (slices A and B)**
- Editable classifier thresholds with safe ranges and presets, signal switches and derived signals (by inlining, through the same guard validator, shadow/trial only) on Settings > Capture > Signals; the glasses apply the thresholds and echo a hash; frozen versions stay replayable when settings change.
- A derived-signal editor with live validation, configurable phone fusion thresholds (11 keys, presets, back to defaults, applied without a restart), and a Developer-mode sensor recording on the glasses (batches stay on the glasses, size-capped, wiped with the traces; `tools/trace-pull.sh` pulls them).

**Forget scope (slice C)**
- A Forget scope setting (Photo, Moment, Scene) with a scoped memory-detail Forget that shows counts first; the confirm deletes exactly the previewed set (typed `CONTENT_CHANGED` if it changed); Moment and Scene scope are experimental and offered only with Developer mode on; a range Forget runs the same post-commit cleanup as a fact Forget.

**One Utterance backend (slice D)**
- Voice, glasses voice and Home chat share one text backend; phone-origin marks ask the glasses for a photo (`pc_mark_photo_request`, time-bounded and rate limited); undo replies truthfully.

**Reviews**
- Codex rounds: slice A two, B three, C five, D two; the last two Forget-scope findings stay open at this tag (both reachable only in Developer mode, fixed in v0.9.8).

**Known limitations**
- Moment and Scene Forget scope is Developer mode only (the engine does not re-check Developer mode, and scrubbing an outside photo deletes its batch peers' results uncounted; both fixed in v0.9.8); LIVE guards and values wait for the worn gate.
