## v0.9.0-beta

The most recent build (previously tagged v0.9-beta). It contains v0.7.0-beta (wave 1, formerly v1.0.0-rc1) and about 340 later commits. Next: v1.0.0-alpha (slice 3b), v1.0.0-beta (slice 3c phase 1), then v1.0.0-rc1.

**Since rc1**
- Battery: glasses capture drain brought from about 1.6 to 0.4-0.5 %/min worn (target 4 h); fewer camera opens, settle wake, context-aware rates.
- Context hub: phone, car Bluetooth, activity, speed and watch motion fused into a rest / walking / vehicle / flight state that tunes capture (walking never throttled).
- Recall: find a photo, describe on demand, Ask with matching photos (off by default), Forget that redacts photo-cited answers.
- Experiment canvas: React Flow pipeline editor served from the phone, publish with shadow/live and automatic rollback, A/B runs, replay of Ask.
- Slice 3a: capture plan editor for the glasses capture half, IMU trace recorder, replay and sweep on recorded traces, publish gates, undo / restore, tap and voice marks, deletion graph (Forget and Delete all reach every artefact), wear-detection settings, phone-side watchdog that reopens the glasses app.

**Known limitations**
- Milestone M1 (replay parity on a worn trace) is not passed: TRIAL and LIVE publishes are refused; SHADOW works. Replay is approximate.
- Rokid force-stops the glasses app on calls, messages and glasses removal; the watchdog reopens it but this is not yet verified on device.
- The phone database is Room v7 and migrations are one-way: never sideload an older phone build over it. Back up before every `adb install -r`.
- Debug-signed build for testers with adb. No APKs are attached to this release.
