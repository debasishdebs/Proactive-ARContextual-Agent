Reviewed HEAD `03a6b91` against feature commit `49b7fb5`.

1. **P1 — Forget/Delete-all can still leak the cached answer.** [CanvasBackendImpl.kt:761](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/canvas/api/CanvasBackendImpl.kt:761)  
   The final checks are separate suspending reads. Concrete interleaving: `dao.ask()` returns an unredacted `now`; Forget then deletes a dependency and redacts the Ask while `liveIds()` runs. The response filters out the deleted source but still returns cached `now.answer`. Delete-all after the generation check has the same problem. Forget after `liveIds()` can also leave cached snippets in the response.  
   **Fix:** after enrichment, transactionally validate generation, Ask existence/redaction, **all** stored dependencies (`AskSources.parseAll`), returned memories, photos and Moments. Construct the response from that validated snapshot; refuse the whole answer when a dependency disappeared.

2. **P2 — Actual answer provenance remains incorrect.** [CanvasBackendImpl.kt:744](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/canvas/api/CanvasBackendImpl.kt:744)  
   `AskDebug.view()` still reruns retrieval using current settings rather than the published Query flow’s actual inputs. Appending citations only repairs some omissions: `AskSources.parse()` excludes uncited `dependsOn` inputs, including recent/history dependencies. Unrelated rerun hits can still claim `inPrompt=true`, with misleading ranks and scores. The new expired-photo fallback additionally returns `mode:null` and `fallback:null`, contrary to the documented string/boolean contract.  
   **Fix:** capture retrieval/composition metadata during the real Ask and enrich that snapshot, including uncited dependencies, photo sources, mode and fallback.

3. **P2 — Newly appended photo citations become empty “memory” sources.** [CanvasBackendImpl.kt:746](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/canvas/api/CanvasBackendImpl.kt:746)  
   A valid `frame:<id>` citation is appended as a `Hit` with that value as `memoryId` and `frameId=null`. Memory lookup finds nothing, and enrichment never strips the prefix to resolve the photo. Liveness nevertheless retains the entry because its frame metadata exists. Consequently, a stored cited photo reports empty `frameIds`, snippet and Moments, with null capture time/place—even when the top-level first-photo field identifies it. Additional cited photos have no usable photo association.  
   **Fix:** distinguish photo citations from memory IDs, resolve their frame IDs explicitly, and enrich every cited photo. Use a typed source representation with nullable memory identity.

Round-1 verification:

| Finding | Result |
|---|---|
| P1 enrichment-time Forget/Delete-all leak | **Not fixed** — finding 1 |
| P1 proposal intake reaching HUD/notifications/actions | **Fixed** — [AnswerService.kt:281](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/AnswerService.kt:281) skips intake for `canvas_eval`; regression test added |
| P2 second-retrieval sources | **Not fixed** — finding 2 |
| P2 expired photos causing 409 | **Fixed for retention-only expiry**, with response defects described above |
| P2 request deadline after commit | **Fixed** — [CanvasApi.kt:158](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/canvas/api/CanvasApi.kt:158) removes the request timeout; mutation handling remains noncancellable |

No additional crash, deadlock, memory/photo loss or auth bypass found in static review. Python syntax passed; Android tests were not run in the read-only sandbox.

**NOT READY**