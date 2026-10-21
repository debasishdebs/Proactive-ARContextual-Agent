# Task 7 report: UI1 hidden HUD + REF start decision (S21)
- Files: AutoStartGate.kt (RefClass, StartDecision, decide/onStart, refClass), MainActivity.kt (hudHidden state, Hud(hidden)/HudContent, testTags hud_hidden/hud_root, diag start line), AutoStartGateTest.kt, MainActivityTest.kt.
- RED: compile failure (decide/StartDecision/RefClass missing). GREEN: `*AutoStartGate* *MainActivity* *Minimise*` BUILD SUCCESSFUL.
- decisionTable uses separate gate instances per call (S21), all 32 combinations plus named rows.
- Diag line: `start src= ref= pending= marker= fromMin= extra= minimise=`, referrer class only.
- hudHidden cleared in onStart for every non-minimising start (Confirm opens and launcher opens both pass through onStart). onNewIntent untouched. The late pc_auto_open marker path is unchanged (R14).
