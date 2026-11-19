# Status audit: Zones + Place Guide Codex findings (2026-11-19)

Reviewer: Opus 5.5, standing in for Codex gpt-6.1-sol (low effort). Read-only static review of `feat/v101-ask-eval` @ `ae6f1b01`. No Gradle, no devices, no tests run.
Paths are relative to `android-companion/` unless noted. Line numbers are for the current tree.

## How the chain closed

- zones-r3 (4 P1): zones-pg-r4 reports all 4 fixed (`7969288a`). Spot checks agree.
- placeguide-r1 → zones-pg-r4 (3 P1) → pg-r5 (3 P1) → batch-r6 **READY** (closes pg-r5).
- z1-r1 (4 P1) → batch-r10 (closes these, raises 2 new) → `e4fdf348` → batch-r11 (1 lockout) → `5a0afdd3`. There has been no Codex re-review since `5a0afdd3`. Checked statically here.
- placev2-r1 (3 P1, 2 P2) → fix `c75c5f55`. **Codex never re-reviewed it.** Checked statically here.
- batch-r9 (sweep zone P1) is included because it is a zone-dispatch finding.

## Finding → status → evidence

| # | Finding | Status | Evidence (current tree) |
|---|---|---|---|
| r4-1 | Zone check before `inner.gone()` suspends; pack fetch after the `fetchLock` wait is unfenced | FIXED | `services/orchestration/.../place/PlaceSamplers.kt:51-53` checks the ticket and zone again after `inner.gone()`. `services/memory/.../place/PlacePack.kt:113,115` calls `zoneCheck()` before and after `fetchLock`. `services/connectors/.../place/PlaceCellSource.kt:28,30,36,81` checks before every Wikipedia, Wikidata and Overpass request. |
| r4-2 | Memory commits before the zone recheck; rollback is not fail-closed | FIXED | `PlaceSamplers.kt:122` writes the memory as `INVALIDATED` (pending), and `:129` runs `allowed()` (ticket + live) inside the transaction, which rolls back on refusal. `PlaceSampler.kt:233` writes a durable ledger mark before the commit. `:243` runs show/rollback under `NonCancellable`. |
| r4-3 | A minimised place pop-up survives zone entry and Forget/Delete all | FIXED | `glasses/.../MainActivity.kt:148-149` withdraws the pop-up when a zone activates (settings or records). `:241` withdraws it on any overlay change, including null. `:251` refuses a new place card while a zone is active. |
| r4-P2a | Dismissal matches by title only | FIXED | `MainActivity.kt:268` sends `DismissPlace(title, card.id)` with the card-instance id. |
| r4-P2b | Pop-up bypasses motion dismissal | FIXED | `MainActivity.kt:130` applies `placePopup.onMotion` to the pop-up. |
| pg5-1 | Provider upload has no fence or cancel during the IO block | FIXED | `services/ai/.../provider/FencedCall.kt:16-25`: registering the hook is the admission check, and the hook calls `call.cancel()`. Used at `OpenAiCompatProvider.kt:78` and `OllamaProvider.kt:56`. |
| pg5-2 | `PlaceHttp` watcher registration race | FIXED | `services/connectors/.../place/PlaceHttp.kt:62`: synchronous `ticket.onClose` registration under the fence lock is the admission check, and the hook disconnects. `:74` rechecks after `connect()`, before the request line or body is sent. |
| pg5-3 | Failed `takeBack` leaves a readable memory indefinitely | FIXED | The memory stays `INVALIDATED` (excluded from every read path, `PlaceSamplers.kt:136-144`) until `reveal()`. A failed take-back schedules `PlaceRecovery.ensure` (`PlaceSamplers.kt:71`, `PlaceRecovery.kt:46-62`). A recovery check also runs at startup (`work/OrchestrationScheduler.kt:345`). |
| zr3-1 | Hub ignores a failed `pushSettings()` | FIXED | `phone/.../hub/Hub.kt:285,297,306` use the returned `pushed` value. |
| zr3-2 | Missed intervals lost across process death | FIXED | `phone/.../zone/ZoneController.kt:190` holds the clear memo while `unstored`. `:316-343` keeps `unstored` until it is covered. |
| zr3-3 | Rollback widening skips `ZoneHold` | FIXED | `phone/.../hub/HubPauses.kt:102,114`: `ZoneHold.covers` is widened by `ClockGuard.widenMs()`. |
| zr3-4 | Failed config recovery clears protected mode | FIXED | `phone/.../zone/ZoneStore.kt:33,59`: `failedRaws` keeps `bad` until `replaceAll` succeeds. |
| z1-1 | Ask startup fails open before the hub judges | FIXED | `services/orchestration/.../AskZoneFence.kt:77,95-104`: `startupBlocked()` holds on the persisted protect flag or any enabled zone, and fails closed on error. `:121-149` reconciles with records, discarding stale results. Installed at `AnswerService.kt:552`. |
| z1-2 | Model dispatch has a 100 ms polling window | FIXED | `AnswerService.kt:350-374`: the ticket is taken before the first check, every step runs under `zoned(ticket)`, and `AskZoneLiveness` sits inside the permit. |
| z1-3 | Delivery checks come before suspensions | FIXED | `phone/.../voice/AppAnswerer.kt:51,56` rechecks after `offNotice`/`answerText`. |
| z1-4 | Recovery backup of a running DB | FIXED | `docs/superpowers/plans/handoffs/v17-upgrade-recovery.md:19,55-56`: force-stop, then `pidof`, then preserve the broken state first. |
| r10-1/2, r11 | Ask fence comes after persistence; stale startup read; clear-store lockout | FIXED | `ZoneController.kt:122-146`: the fence is set first, and clear-store failures are retried a bounded number of times, then released. `AskZoneFence.kt:130-147` uses the decision generation, and `:123-127` reconciles a held, judged-clear fence. |
| pgr1-P2 | Delete all vs an outstanding fetch | FIXED | `PlacePack.kt:67,105,193-195`: `wipeEpoch` plus `fetchLock`. |
| pgr1-P2 | 20 s spacing uses a stale timestamp | FIXED | `PlaceSampler.kt:201,208,214` use the monotonic `elapsed()` at dispatch and again at call end. |
| pgr1-P2 | Cards survive Forget/Delete all | FIXED | `services/orchestration/.../place/PlaceGraph.kt:31-35` trips the fence and withdraws the cards. Glasses side: r4-3. |
| pv2-1 | `place_pins.xml` is included in backup and transfer | FIXED | `phone/src/main/res/xml/backup_rules.xml:25` and `data_extraction_rules.xml:24` (cloud) and `:117` (device-transfer) exclude it. `places.db` (+wal/shm/journal) is excluded too. |
| pv2-2 | Pin coordinates never judged against zones | FIXED | `services/orchestration/.../place/PlacePinning.kt:71-72,82,97`: `ZoneEvaluator.blocksPoint` runs on the draft's own fix at draft time and again at save. `core/model/.../zone/ZoneEvaluator.kt:175`: unreadable zones or a bad fix count as blocked. |
| pv2-3 | Concurrent pin edits/deletes lose changes | FIXED | `PlacePinning.kt:99,118,128,150`: one process-wide `Mutex` covers the load, modify and save steps. Save = `PlaceGraph.savePins` (write plus mirror). |
| pv2-P2 | 3-minute draft retention not enforced | FIXED | `PlacePinning.kt:85` runs a timer that clears the draft. `phone/.../ui/settings/place/PlaceGuideSettings.kt:249` calls `onCleared` → `cancel()`. |
| pv2-P2 | Reserved pins drop out before matching | FIXED | `PlaceSampler.kt:199,291-299`: `cutKeepingPins` runs on the final model list. |
| r9 | Sweep's zone check does not survive the model-permit wait | PARTLY FIXED | Fixed in a running hub: `orchestration/forget/SuspectSweepWorker.kt:118` (`DispatchFence`) and `work/frames/KeyframeExtract.kt:65`. Still open in a cold process; see S1. |

## STILL OPEN (ranked)

**S1 (P1, the same class as z1-1, which Codex rated P1). Place zone fence fails open in a process where the hub never started.**
- **Cause.** `PlaceGraph.fence` (`ZoneFence`) starts open, and `extraBlocked` stays `{ false }` until `phone/.../hub/HubGraph.kt:197` runs. `ZoneController`, which holds the fence from its `init` → `raise()` → `onProtect`, is built only by `ZoneGraph`. Its only non-UI caller is HubGraph (grep shows only `HubGraph.kt` and `AndroidPlaceGuideControl.kt`). `ContextApp.onCreate` wires neither.
- **Who depends on it.** `SuspectSweepWorker.kt:50` uses `PlaceGraph.fence::blocked` as its only "in a zone now" signal: for the `:109` gate, for `DispatchFence` at `:118`, and for the `ProvenanceGate` zone check at `:96`.
- **Failure scenario.** Home is a pause zone. The phone charges at home overnight. The app process is cold (killed, or the hub was never started in it). WorkManager runs the 24 h charger job `SuspectSweepWorker`, and `zoneBlocked()` returns false. The marked suspect photos are then queued and uploaded through a BACKFILL vision call while the user is inside the zone. The dispatch-time recheck asks the same open fence.
- **Minimal fix.** In `SuspectSweepWorker`, use `zoneBlocked = { PlaceGraph.fence.blocked() || AskZoneFence.also { it.install(applicationContext) }.blocked() }`. `AskZoneFence.blocked()` already fails closed until the zones are judged: on the persisted protect flag, on any enabled zone, or on a read error. Guard against the Ask-fence user switch, because `blocked()` returns false when it is off. Use `startupBlocked` semantics directly, or another way that does not depend on that switch. The alternative is to seed `PlaceGraph.fence.hold()` in `ContextApp.onCreate` from the same persisted facts. Add a cold-process test: zones enabled, no HubGraph built, the sweep makes no call.

**S2 (P2, new, the same pattern as pv2-2 but in the sampler). The sampler's on-demand fix is never judged against zones.**
- **Cause.** `phone/.../place/PlaceFixProvider.kt:30-35` returns a `oneShot()` fix without feeding it to the controller and without a `blocksPoint` check. `PlaceSampler.kt:188-204` then fetches the cell and uploads the photo on the controller's earlier OUTSIDE judgement, which stays valid for up to `OUTSIDE_STALE_MS` = 15 min (`ZoneEvaluator.kt:155`).
- **Failure scenario.** The user drives into a zone and makes a dwell photo before the controller has a fresh fix. The coarse cell near the zone goes to Wikipedia/Overpass, and PLACE_MATCH uploads the photo.
- **Minimal fix.** After `fix()` in `PlaceSampler.attempt`, refuse with `ZONE` when `ZoneEvaluator.blocksPoint(zones(), fx.lat, fx.lon, fx.accuracyM)` is true, as `PlacePinning.zoneRefuses` does. Alternatively, pass the one-shot fix to `controller.onFix` before using it.

**S3 (P3, accept or document).** `PlaceHttp.kt:74-78`: a trip between the post-connect `tripped` check and the request write depends on a cross-thread `disconnect()`. The window is microseconds and falls inside the 2 km approach band. This is residual and not a regression.

All P1s in zones-r3, z1-r1, placeguide-r1, zones-pg-r4, pg-r5 and placev2-r1 are FIXED in the current tree. The one open P1 is the cold-process residual of batch-r9's sweep finding (S1).

ZONES+PG: 1 P1 STILL OPEN
