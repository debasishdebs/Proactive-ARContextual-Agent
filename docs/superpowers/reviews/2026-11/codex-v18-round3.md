VERDICT: NOT READY

Paths below are relative to `android-companion/`. Reviewed through `96aef28b`.

1. [P1] `services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSamplers.kt:153` - Offline Maybe uses the general EMBED binding: with one candidate and an allowed text connection, a remote provider receives the photo caption and place name/note; no zone recheck occurs after captioning - Require an on-device embedding binding before any probe or embedding call.

2. [P1] `services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSamplers.kt:173` - Process death after writing `offline-tier-photo.jpg` bypasses `finally`; startup, Forget and Delete all leave this photo copy behind - Add startup and deletion cleanup, and fence temporary-photo writes against deletion.

3. [P2] `phone/src/main/java/com/rokid/context/phone/canvas/api/DumpRoutes.kt:158` - `/items?batch_size=1` stops prematurely when two marks/scenes share a timestamp: the next request rereads the first row, filters it out, and returns no continuation, losing later rows too - Apply the composite cursor within each source query before LIMIT; test ties in both orders.

4. [P2] `services/memory/src/main/java/com/rokid/context/memory/place/PlacePack.kt:314` - Seeding owns whole cells, but area deletion tests exact rectangles: boundary-cell places/captions survive removal, and places required by another area's shared cell can disappear - Use consistent cell coverage for deletion and surviving-area ownership.

5. [P2] `phone/src/main/java/com/rokid/context/phone/ui/activity/ActivityMapView.kt:133` - Disconnecting after tiles load still paints cached tiles despite the “plain grid” banner; `networkOnline` changes only the background - Gate tile drawing and fetching on network state on all three map screens.

Privacy-policy conflict: the dump deliberately exports captions, pin names and coordinates (`DumpReader.kt:231`), citing an owner exception (`DumpRoutes.kt:22`). This conflicts with this review’s literal prohibition; it cannot be certified content-free.

Both migration SQL checks matched the exported target schemas in in-memory SQLite; production builders have no destructive fallback. Expected database, vault and pin exclusions exist in backup and both transfer rules. No change to the `BLOCKED_INSIDE` mutation guard was found.

Recommended device checks after fixes, first on a disposable encrypted copy:

- Upgrade context v17→18 and places v2→3; verify retained rows, integrity, encrypted reopen and restart.
- Exercise photo/range/selection Forget with v2 ON and OFF, pin deletion, seeded-area deletion, Delete saved locations and Delete all; inspect links, refs, history and open screens.
- Kill the process during captioning, restart, then delete its source; verify temporary JPEG removal and zero remote caption traffic.
- Seed while locked; test network transitions, notification denial, Cancel, restart and Delete all.
- Inside a pause zone, exercise Centre on me and every zone mutation; verify capture remains blocked.
- Test dump authentication, Developer OFF, rate limits, invalid inputs, timestamp ties and pagination completeness.

No files changed. No Gradle, adb or tools scripts ran; Android tests were inspected, not executed.