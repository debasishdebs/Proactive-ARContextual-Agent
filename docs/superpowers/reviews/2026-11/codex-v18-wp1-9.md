**NO P1. Four P2 findings, ranked:**

1. **Legacy history can survive Forget after upgrade** — [ForgetActions.kt:392](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ForgetActions.kt:392). Startup removes the old cleanup obligation without opening places.db. With Place Guide unused, its v2 history remains on disk while ordinary Forgets clean only context.db.  
   **Fix:** Complete the existing places.db’s v3 migration before retiring legacy cleanup obligations.

2. **Location writes can outlive zone protection changes** — [MomentLocationImport.kt:37](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/MomentLocationImport.kt:37). Import caches zones for the entire request and checks current blocking only at route entry; subsequent chunks can save locations after a zone activates or changes. The recorder similarly checks zones and pauses before its transaction.  
   **Fix:** Fence writes against zone changes and revalidate current protection, point checks, and pauses before insertion.

3. **Retention defeats imported-photo export exclusion** — [ExportDao.kt:35](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/memory/src/main/java/com/rokid/context/memory/db/ExportDao.kt:35). The exclusion depends on `frame.source`, but photo retention deletes that row while retaining its memory and metadata. The imported memory then becomes exportable; reproduced using the full export query in SQLite.  
   **Fix:** Persist import provenance beyond photo expiry and use it when filtering exports.

4. **URL photo import performs networking on the UI thread** — [DevTestImport.kt:127](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/devtest/DevTestImport.kt:127). The Compose coroutine directly invokes blocking OkHttp `execute()`, causing `NetworkOnMainThreadException` or UI blocking.  
   **Fix:** Run the blocking fetch inside `withContext(Dispatchers.IO)`.

No files edited. In-memory SQLite checks performed; Android tests were not run.