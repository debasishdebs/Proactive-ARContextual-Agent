VERDICT: NOT READY

Paths below are relative to `android-companion/`. Re-checked all three commits with `git show`.

1. [P1] services/memory/src/main/java/com/rokid/context/memory/place/UserPlace.kt:192 - An otherwise valid pin with `"note":{"moment":"0000abcd","at":123}` passes export validation: `optString` converts the nested object to text, `isValid` accepts it, and `encode` exports the private values inside `note`. [Android documents this coercion](https://developer.android.com/reference/org/json/JSONObject#optString(java.lang.String,%20java.lang.String)). - Require actual JSON scalar types before parsing; reject object/array values in every scalar field.

2. [P1] services/ai/src/main/java/com/rokid/context/prompt/PromptRepository.kt:53 - Migration marks `upgradeChecked` before suspending to load the seed; a concurrent save skips that check and stores a custom prompt, then migration overwrites it. With version history disabled, the custom text is lost (`PromptDao.kt:54`). The new PREVIOUS fingerprint exposes this race for VISION_EXTRACT. - Apply migration through transactional compare-and-set against the template/schema originally inspected.

3. [P2] services/memory/src/main/java/com/rokid/context/memory/place/PlacePack.kt:203 - Two callers seeding the same area overwrite its tracked job; removal at line 271 cancels only the latest job, allowing the earlier seed’s final `writeArea` to resurrect the removed area. Settings and map have separate seeding guards. - Serialize seed registration and removal per area, rejecting duplicate running seeds or tracking and joining all of them.

No fetch-lock/cancelAndJoin cycle found: removal joins before acquiring the lock. Mid-cell gate rejection propagates as cancellation before `fetchAndStore` writes; completed cells remain and the area becomes PARTIAL. The HTTP hook handles cancellation onset, catches disconnect exceptions and is disposed in `finally`; actual Android disconnect responsiveness remains unverified.

Reviewed logs contain counts/status rather than coordinates or addresses. The stored glasses address stays in `rokid_link`, excluded from settings export, backup and device transfer.

Static review only; no files edited, Gradle, adb or tools scripts run.

| Earlier finding | Status | Trace |
|---|---|---|
| Pin r2 #1: schema-safe export | STILL OPEN | `withoutLinks:188 → parseOne:192 → encode:181`; finding 1 |
| Pin r2 #2: older committed obligation | CLOSED | `PinMomentLinks:244` preserves older committed arming; `MemoryWipe:190–198` keeps failed pin settlement independent of wipe completion |
| Pin r2 #3: cancellable HTTP deadline | CLOSED | `PlaceSources:202 → PlaceHttp:79`; cancellation disconnect hook, cleanup at 116 |
| Pin r2 #4: trigger refund | CLOSED | `AutoStopMachine:145` removes the blocked claim’s timestamp before retry |
| Pin r2 #5: address replacement | CLOSED | `GlassesAclReceiver:81–87` preserves a bonded stored address against ACL-only candidates |
| Map #1: admission before every request | CLOSED | `AndroidPlaceGuideControl:50 → SeedAdmission → PlaceHttp:73`; gate checks privacy, free Wi-Fi and BULK_OTHER |
| Map #2: clear seeded flags on removal | CLOSED | `PlacePack:273–277` transaction clears flags and reapplies remaining coverage; pins preserved |
| Map #3: removal versus running seed | STILL OPEN | Single tracked seed is cancelled/joined at 271; overlapping same-area seeds escape tracking, finding 3 |
| Map #4: callback tests | CLOSED | `SeedPanelScreenTest:63–115` records box, confirmation, disabling and removal callbacks |
| Prompt seed/default upgrade | STILL OPEN | `3c118105` adds quoted-sign rule and old fingerprint; pre-existing custom text is skipped at `PromptRepository:48`, but concurrent saves remain vulnerable, finding 2 |