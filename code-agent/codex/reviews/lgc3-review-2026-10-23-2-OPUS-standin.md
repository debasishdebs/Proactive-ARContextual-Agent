# LGC3 narrow re-review (bd0bca6a vs f0aaa884; branch total f83762b5..HEAD)

Worktree: /Users/debasishkanhar/Desktop/rokid/pca-lgc3 (clean; read-only inspection, no builds run).

## Verification summary

1. **Important #1 (main-thread filesDir) is closed for the logger.** `GlassesRuntime.kt:65` now calls `DiagLog.install(app)`. `DiagLog.install(Context)` (DiagLog.kt:18) calls `GlassesLogging.logDir(app)` (GlassesLogging.kt:29), which returns `File(context.applicationInfo.dataDir, "files")`. That is a string concatenation with no `getFilesDir()` call. For the application context this matches what `ContextImpl.getFilesDir()` resolves to (`getDataDir()/files`, where `getDataDir()` is `applicationInfo.dataDir`), so `run-as ... cat files/capture_diag.log` still finds the file. `GlassesLogging.install` → `GlassesLogSink.create` → `AsyncLineWriter` only builds `File` objects and starts the daemon thread. `RealLogFs.append` runs `parentFile?.mkdirs()` on the writer thread (AsyncLineWriter.kt:24). `GlassesLogDirTest` uses a `ContextWrapper` spy whose `getFilesDir()` records calls and whose `ApplicationInfo` points at a temp data dir that does not exist. It asserts the exact path, zero getter calls and nothing created, so it is meaningful. `GlassesLogSourceScanTest.theRuntimeBuildsTheLogDirWithoutTheFilesDirGetter` pins the call site.
2. **Privacy scan.** `emittedCalls` now captures the full balanced-paren argument text across lines. `producerLines` covers Heartbeat.kt, RestartDiagnostics.kt and TraceRecorder.kt (all three exist under the scanned `glasses/src/main/java` tree, and the existence test asserts this). Negative fixtures cover multiline, intermediate-string and `e.message` cases. TraceRecorder.kt:333 is now `drop("bounds:" + e.javaClass.simpleName)` inside the same `catch (IllegalArgumentException)`. `drop()` (line 382) still sets `job?.dropped = true` and logs, so the trace is still PARTIAL and capture does not crash. The audited dynamic-field comment matches the code. The only remaining free-`String` API is the PlanController/AutoRevert revert reason. It is fed by fixed local strings and is documented as a hardening gap, which is acceptable.
3. **Tests.**
   - `diskFailureNeverReachesTheCaller` waits on a latch counted down inside the failing append before it closes, and `close().get()` observes termination.
   - The refused-install test obtains the refused sink through `sinkFactory`. A closed writer's `fence()` returns `completedFuture(false)` synchronously under the lock, and a control writer shows that an open writer acks `true`. `close().get()` returns the shared `terminated` future, which observes thread exit.
   - The exact-fill test arithmetic checks out. The record is 18 (timestamp) + 5 (" I T ") + msg + 1 = 24 + msg. Four 500-byte records equal the 2000-byte half exactly, and `len + size > half` does not rotate at 1500+500. The fifth record rotates, giving `.1` = 2000 and main = 500.
   - All three tests are deterministic: they use latches and futures, not sleeps.
4. **LGC4 hazard note** (GlassesLogging.kt:15-18) is accurate. `tools/battery-run.sh:143` and `:239` match `/heartbeat v=2/` over the pulled `capture_diag.log{,.1}` (lines 105-106). `tools/m1-run.sh:151`, `:166` and `:273` read `heartbeat v=2` from `files/capture_diag.log{.1,}`.
5. **No regression.**
   - TraceRecorder.kt:145 still emits `trace recovery: found=… sealed=… ms=…` through `log`, which GlassesRuntime.kt:136 binds to `DiagLog.i(CAPTURE_TAG, …)`. With `DIAGNOSTIC_RECORD = true`, `LogPolicy.route` gives `file = true` for I.
   - The line format (`MM-dd HH:mm:ss.SSS L Tag msg`, Locale.US, `\n` → space) is unchanged in this commit.
   - The budget is unchanged: `MAX_BYTES = 3_000_000`, two files of at most half each.
   - The always-on policy is unchanged, so heartbeat I-lines keep reaching the file.

No main-thread IO in the logger path, no data leak, no gate bypass, no format or budget break and no heartbeat loss were found.

## Findings

1. **Minor — the GlassesRuntime constructor still does main-thread filesDir IO on the same path, a few lines later (pre-existing, not introduced by LGC3).**
   - **Where:** `android-companion/glasses/src/main/java/com/rokid/context/glasses/GlassesRuntime.kt:74`, `PauseStore(File(app.filesDir, "pauses.preferences_pb"), ioScope)`. There are more at `:90` (`OutboundBuffer(File(app.filesDir, "outbound"), …)`) and at the `getSharedPreferences` calls at `:71`/`:83`. These are the same at f83762b5.
   - **Failure scenario:** `GlassesRuntime.get(context)` runs from `MainActivity.onCreate`. `ContextImpl.getFilesDir()` still runs its existence check and `mkdir` on the main thread during the same constructor, microseconds after the logger install. A StrictMode disk-read/write policy would still flag the constructor, and the KDoc claim "no filesystem work here" at line 64 holds only for the logger property.
   - **Why not blocking:** the scope was logger startup, and LGC3 introduced no new IO.
   - **Edit:** none required for LGC3. Optionally, record it as a follow-up: derive `val filesRoot = GlassesLogging.logDir(app)` once and use it at :74/:90/:134, or move the runtime construction off the main thread.

2. **Minor — `FeatureLogger.kt` in `producerFiles` is dead: it is never scanned.**
   - **Where:** `android-companion/glasses/src/test/java/com/rokid/context/glasses/log/GlassesLogSourceScanTest.kt`, the `producerFiles` list. FeatureLogger lives in `android-companion/core/capture/src/main/kotlin/com/rokid/context/glasses/capture/motion/FeatureLogger.kt`, but `sources()` walks only `glasses/src/main/java/com/rokid/context/glasses`.
   - **Failure scenario:** someone adds an identifier to FeatureLogger's `String.format` line (its output reaches DiagLog via SensorFeed.kt:132). The scan passes even though the list implies the file is covered.
   - **Current content:** numbers and an enum state only, so there is no leak today.
   - **Edit:** either remove `"FeatureLogger.kt"` from `producerFiles`, or add `../core/capture/src/main/kotlin` to the scanned roots and add `"FeatureLogger.kt"` to the `theNamedProducerFilesExistAndAreScanned` list so a missing file fails.

3. **Minor — the balanced-paren capture in `emittedCalls` does not skip string literals.**
   - **Where:** `GlassesLogSourceScanTest.kt`, the `emittedCalls` loop.
   - **Failure scenario:** a template with an unbalanced `)` inside quotes ends the captured argument early, so any later `${frameId}` in the same call is not examined. Example: `DiagLog.i(TAG, "state :) ${'$'}frameId")`.
   - **Current state:** there is no such template today.
   - **Edit:** in the loop, track `"` (and `\"` escapes) and only count parentheses outside a literal. Alternatively, accept the limitation and document it next to the AUDITED DYNAMIC-FIELD SOURCES comment.

VERDICT: READY
