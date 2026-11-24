VERDICT: NOT READY

1. [P1] tools/install-trip-build.sh:336 - A watch APK staged as `phone-debug.apk` passes package/signature checks and version 41 upgrades phone version 4, replacing the phone app despite serial isolation - Validate APK form factor and expected application components before installation.

2. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:476 - Turning shadow scoring OFF after launch leaves queued work live; it can allocate caches and load/run embeddings because its liveness excludes the flag - Fence and cancel shadow work on OFF, checking before embedder acquisition and inference.

3. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/SuspectSweepWorker.kt:186 - Undo can clear a mark after sweep selection but before deletion; `removeDescriptions` deletes the stale IDs without rechecking, losing an unmarked memory - Revalidate marked-suspect eligibility and replacement/expiry conditions inside the deletion transaction.

4. [P2] tools/post-install-check.sh:719 - Failed or truncated segment reads are ignored, then `TRIP_LOGS=1` allows incomplete logs to produce PASS results - Propagate retrieval failures and report affected checks as UNKNOWN.

Confirmed: default-OFF prevents initial shadow scorer/reference-cache creation; SigLIP remains unwired with no production download/load path. Diagnostics count/snippets share one snapshot. ForgetPolicy, PolicyPlan and ForgetSource have no production consumers or behavior change. Installer mutations require `--go`; no uninstall or `pm clear` execution found.

Static review only; no files edited, Gradle, adb, or tools scripts run.