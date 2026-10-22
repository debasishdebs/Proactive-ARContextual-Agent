### Task 15: Voice-mark user docs generated from `MarkPhrases`, plus a doc-drift test (S85) [CORE 1 h]
**Stream C · 1 h · depends on: 14.**

**Files:**
- create `services/voice/.../commands/MarkPhrasesDoc.kt`: `fun render(): String`, the markdown tables from `MarkPhrases`
- create `docs/voice-marks.md` with the generated block between `<!-- voice-marks:begin -->` and `<!-- voice-marks:end -->`, plus hand-written text covering:
  - **how to mark:** tap the glasses (HUD visible), say a command after push-to-talk on the glasses or the phone, or use the phone notification, tile or home button;
  - **commands**, **intent words** and **undo**;
  - **"I missed that"**;
  - **not supported:** Hindi and other languages (later), the word "forget", any hotword or always-on listening, questions phrased as commands;
  - **how a word maps to an intent label** (the generated table);
  - **privacy:** audio is never stored or uploaded, recognition runs on the phone's on-device recogniser only, and only the time, source and label are kept.
- modify `README.md`: a short "Voice marks" section (5–8 lines, a link to `docs/voice-marks.md`, the generated compact command list between `<!-- voice-marks-readme:begin/end -->`)
- test: `services/voice/src/test/.../commands/VoiceMarksDocTest.kt` (it finds the repo root by walking up, like `MotionModelTrainedTreeTest`)

**Tests:**
- `docBlockEqualsRender`;
- `readmeBlockEqualsRenderCompact`;
- `everyPhraseInTableAppearsInDoc`;
- `noPhraseInDocMissingFromTable`;
- `docMentionsPrivacyAndUnsupported` (fixed sentences present).

**Command:** `gw.sh :services:voice:testDebugUnitTest --tests '*VoiceMarksDocTest'`.

**Commit:** `docs(voice): voice marks guide and README section generated from the phrase table, with a doc-drift test`

