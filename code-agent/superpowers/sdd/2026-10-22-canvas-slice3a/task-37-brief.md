### Task 37: Canvas UI 2b: mark timeline, publish and rollback, full gate [CORE 35b part; timeline LATER LA15]
**Stream B · 2.5 h · depends on: 28, 33, 36.**

**Implement:** `marktimeline.js`, `timelineview.js`, `planpublish.js` (the gate texts, the TRIAL countdown, the three actions), and the `views.js`/`app.js` wiring.

**Tests:** round 1's `marktimeline` and `planpublish` tests, plus `retroMarkBumpsInvalidationNotice`.

**Final gate:** `tools/gate.sh /Users/<user>/Desktop/rokid/pca-s3/gate-3a.log` exits 0. A known-flaky `PushToTalkTest` alone may be re-run once.

**Commit:** `feat(canvas-ui): mark timeline with retro marks, publish gates and rollback actions`

