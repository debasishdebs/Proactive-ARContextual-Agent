1. **Minor — start-record bytes can remain counted after successful publication.** A failed chunk write triggers `recount()`, which includes `start.json`. If subsequent writes and publication succeed, [TraceStorage.kt:196](/Users/debasishkanhar/Desktop/rokid/pca-anr/android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/trace/TraceStorage.kt:196) deletes that file without adjusting `used`. This conservatively advances the cap gate until another recount. **Edit:** call `recount()` after the successful final directory rename; add a failed-chunk-write → successful-close test asserting disk-byte equality and cap gating.

The blocking round-3 findings are resolved: record-read I/O failures preserve finishable traces; missing/invalid records remain rejected; torn tails preserve the valid prefix. Failed publication/chunk writes clean up `.part` files and recount both directories, including start records. Successful recovery removes stale parts.

No blocking regression found versus `v0.9-beta`: killed traces still seal PARTIAL, schema-1 encoding is unchanged, and recovery stays on the writer without waiting on the main thread or lazy lock.

Validation: **56 existing compiled codec/scan/freeze tests passed.** Filesystem-writing tests were inspected, not rerun, to preserve read-only scope. No files changed.

VERDICT: READY