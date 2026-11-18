**One P1 remains at `ab059ac8`: false CLEAN after pruning.**

[Line 460](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:460) treats consumption below 90% as “never wrapped.” **Consumed measures current retained memory, not cumulative writes.** Android allocates quarter-capacity chunks, prunes whole chunks, and accounts for compression; consumption can fall below 90% after logs were lost. The rule is therefore unsafe, rather than inverted. [AOSP buffer implementation](https://android.googlesource.com/platform/system/logging/+/c85ab856b99936c4d4bd893e26ae2f1b06357b31/logd/SerializedLogBuffer.cpp), [chunk accounting](https://android.googlesource.com/platform/system/logging/+/57ec2e1fd961a5232b5f11916d3c79676c9483bc/logd/SerializedLogChunk.h).

Mocked reproduction: `16 MiB (12 MiB consumed...)`, oldest retained record **after install start** → `buf_covered` returns **0**. Six healthy retained app records produce zero errors; a stable PID permits CLEAN and glasses installation despite an earlier caught SQLite failure having been pruned. Require independent retention evidence before bypassing the oldest-record check.

Other checks passed:

- Full buffer with oldest record far before start passes; full buffer with oldest after start is UNVERIFIED. Flooring and three-second boundary work.
- KiB/MiB parsing, truncated-unit bounds, and buffer-name selection work. Legacy `Kb` and localized text fall back to timestamps.
- Unprefixed continuations inherit PID/timestamp and retain failure detection.
- Adb shell normally reads system/crash via log-group credentials. Mocked denial of either exits 3; FAIL/UNVERIFIED prevent glasses proceeding. [AOSP permissions](https://android.googlesource.com/platform/system/logging/+/refs/heads/main/logd/LogPermissions.cpp).

`bash -n` and in-memory probes completed. No adb; temporary-file fixtures unavailable in the read-only sandbox.

NOT READY