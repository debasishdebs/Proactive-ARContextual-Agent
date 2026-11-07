## v0.9.8-beta

Debug-signed; no APKs are attached. Phone database is Room v15 (additive migrations, one-way: back up before every `adb install -r`, never uninstall).

**RAG core and canvas flows**
- A shared `RagCore` under Ask and Nudge (windowed text and photo search, one shared budget, fail-closed images, history fence), with golden prompt tests proving unchanged behaviour.
- Query and Nudge are canvas pipelines on `RagCore`, live with a built-in fallback; the default plans are byte-identical to the built-in behaviour.

**Triggers and delivery**
- Live Nudge triggers (moment, scene, signal, schedule) with per-publication state, and delivery to the phone, the glasses or the caller (`DeliveryRoute`); one nudge pause gate for every trigger.

**Devices**
- Microphone and display as editable device capabilities (API and phone page; a Devices page in the canvas web UI); `READ_PHONE_STATE` declared again with an optional Settings request row (denial blocks nothing); a ringing call blocks the relaunch.

**One previewer**
- One `previewMoment` entry and one `listView` keyed by a period; every item (memory, photo, mark, answer, nudge) opens through per-kind presenters; Marks rows open the photo or say why there is none; rows load 200 at a time.

**Forget**
- A closure bound with an acknowledgement above 200 and a ceiling of 5000, in-transaction Developer gate, and a v1.1 lifecycle backlog.

**Reviews**
- Codex: RagCore four rounds, canvas flows two, triggers two, capabilities one, slice E two, the preview migration once (4 P1 and 2 P2 findings fixed).

**Known limitations**
- The Listening and Thinking HUD frames are not gated by the display switch; the Home chat's typed answers reach the HUD only through DeliveryRoute (glasses connected and display on, otherwise the phone with the reason recorded; a chat Query with target phone no longer mirrors), while queued and other sources still record no glasses path; no canvas editor for capabilities; the new flows and previewer screens were not device-tested at this tag.
