**P1 — Four fail-open gaps remain:**

- **Publish retry still misses production failures (r2-7).** [Hub.kt:283](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:283) ignores `pushSettings()` returning false and swallows exceptions through `guarded`. The controller consequently clears `publishPending`; connected glasses can keep capturing without a retry.

- **Missed intervals are lost across process death (r2-3).** [ZoneController.kt:289](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/zone/ZoneController.kt:289) removes an exited zone from `unstored` before its interval is durable. A subsequent outside fix advances and saves its clear memo while retries still fail. Restart loses the memory-only interval and starts protection after it, admitting buffered inside-zone frames.

- **Rollback widening skips memory-only protection (r2-4).** [HubPauses.kt:102](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/HubPauses.kt:102) checks `ZoneHold` without widening; widening applies only to database queries. During persistence failure, rollback can map an inside frame before the hold’s start, allowing admission. `zoneOverlaps()` has the same gap.

- **Failed configuration recovery can clear protected mode (r2-5).** [ZoneStore.kt:63](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/zone/ZoneStore.kt:63) restores cached `good` into SharedPreferences after failed `replaceAll()`. Even a failed restoration updates Android’s memory; the next read accepts it and clears `bad`. With cached `[]` followed by corrupt configuration, failed recovery can therefore release protection without a successful commit.

**Eight-finding disposition:** 1 fixed (camera closes within the 100 ms poll bound); 2 fixed (synchronous restart barrier); 3 incomplete; 4 incomplete; 5 incomplete; 6 fixed (missing state backdates protection); 7 incomplete; 8 fixed (bulk mutation guards).

**P2/P3:** No additional findings.

Static review of `9a087bd2`; no fresh build or device validation in this read-only workspace.

NOT READY