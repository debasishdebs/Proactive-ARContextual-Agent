# Task 9 report: glasses-diag and tools one-liners (S15)
Done: A3-minor (WearingStatus.describe returns one token: worn, not_worn, unknown, off, other; test logTokenAllowListOnly), A19 (Heartbeat.sensorsLabel: sensors=off without a session), A20 (Heartbeat.guarded: a throw logs the class name only, status line still published), A21 (battery-run.sh awk routes batt.log by FILENAME == ARGV[1]), A22 (filter_diag on both capture_diag pulls, 2>/dev/null, `filter-diag` subcommand for the test), A23 (FpsRangePick.Cache: failed or empty read not cached; CameraXSamplingCamera uses it).
Skipped by instruction: D13, D26, D30 (orchestration/phone canvas), D36, D39 (moved to canvas slice 2 per user ruling 4). No source-scan tests added.
RED: HeartbeatTest/FpsRangePickTest compile failures; WearingStatusTest assertion failure; test-battery-run.sh FAIL lines. GREEN: `:glasses *Heartbeat* *FpsRange* *CaptureService*`, `:services:glasses-link *WearingStatus*`, `bash tools/test-battery-run.sh` PASS.
The A19/A20 edits are confined to publishStatus's heartbeat block in CaptureService.kt.

## Fix round 1
I1: the whole heartbeat build (readNormalized, bytesSent, Heartbeat) is inside `Heartbeat.guarded`; the reset-on-read counters (uplink baseline, `screenWakes.take()`, `takeVetoes()`) are consumed last via `copy`, after nothing can throw. Test seam `CaptureService.heartbeatFault`; test `CaptureServiceModeTest.heartbeatThrowStillPublishesStatus` proves the status line is published, the failure is logged by class, and the screen-wake counter survives. M13: `grep -a` in the diag filter and the cpu.raw filter, `; true` removed; test for a NUL byte.
D13/D26/D30 still skipped per the original instruction; the coordinator should confirm the phone stream owns them.
