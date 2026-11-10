# Opus r19 review (substitute for Codex r19; Codex hit its usage limit) — range 5470aa5a..cd873e50

NOT READY: 1 P1, 3 P3.

1. P1 (introduced by 3dd74aa8): a caption saved by Run and Save is not linked to its batch-mate or context photos. CanvasRunSave.kt:130 carriedOf returns `ids.toSet()` when index is null (captions), so ExtractSave.kt:225 `carriedOf(...) - m.frameIds` is empty and no CARRIED row is written; load() (~208) adds gathered.sources[frameId] to facts only. Repro: photos -> Vision(batch=2) -> critic -> write over unrelated A/B, Run and Save, Forget B: CAPTION-A stays in Memories/export (frame_source only FACT A). Same gap when the Vision request included upstream context. Live path (KeyframeExtract.kt:72) is correct. Fix: keep gathered.sources[frameId] per caption and return it from carriedOf when index is null.
2. P3: SUSPECT (ForgetDao.kt:268) lists captions whose photo row is gone; dialog text "retention keeps the photo" wrong for those.
3. P3: suspect cutoff TEXT_RETRY_FIXED_MS is the fix commit time (2026-11-07 21:05:17 UTC), not install time.
4. P3: some Run-and-Save facts inherit imageDelivered=true via sameCaptions (CanvasRunner.kt:~784); no effect (rokid_cv_ rows never describe).

Verified OK: r18 fixes #1-#3; migration 15->16 (structural diff = three nullable INTEGER columns + identity hash; all 51 non-FTS tables match 16.json after the ALTERs; both addMigrations lists include MIGRATION_15_16; no destructive fallback; SQLCipher user_version copy and FTS unaffected); delivery proof cannot be set true without delivery; DESCRIBES only adds restrictions; Mark all confirms with the live count, one UPDATE, no deadlock; flagged photos get re-described by the night job (max 1000 queued/night, MAX_CALLS=60) then expire after grace — cost is paid describe calls; undo exact; export optional fields; frozen values untouched; no r12-r17 regression.
