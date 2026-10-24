# 3b WP6 review 1 (Opus stand-in for Codex): branch 3b/wp6, 04b3c619..1b61b734 (eb5fd42a, 1b61b734)

Read-only review. I read the real files in /Users/<user>/Desktop/rokid/pca-3b-wp6 (not only the diff): HelperRoutes, HelperBrowserRoutes, HelperDevSession, HelperConfirmAdapter, HelperBackend, HelperNative, CanvasGraph, CanvasService, CanvasServer, CanvasRouter, CanvasHome VM, HelperAuth, CanvasAuth, TlsIdentity, HelperJobService, HelperDeletion and CanvasSlice3Dao (the parts used), CanvasExtras/CanvasStartup, constants.json. I checked them against tools/canvas-helper/pca_helper/client.py, supervisor.py and journal.py. I ran no Gradle and no pytest; the WP6 diff does not touch tools/.

## Verified OK (no finding)

- **Wire shapes vs the Python helper, field by field.**
  - Poll request: keys exactly `{helperProtocol, active, cleanupAcks, ready}`. Matches supervisor.py:143.
  - Poll answer: `{sessionGen, caps, serverTimeMs, available:["py_exec"], cancel:[id], cleanup:[{cleanupId:"<decimal string>", jobId}]}`. supervisor.py:125 filters cleanups on `isinstance(cleanupId, str)`, and journal.py:147 echoes the same strings back.
  - A numeric ack gets 400 (HelperRoutes.kt:126-129; tested HelperRoutesTest:134).
  - Lease: `{"job":null}` when nothing is available; otherwise job `{jobId,type,leaseGen,leaseUntilMs,deadlineMs,bundleHash,payloadBytes}` plus `serverTimeMs`, all ints. Matches `_lease_fields_ok` (supervisor.py:204-207).
  - Payload: the raw UTF-8 bytes. `payloadBytes` is `LENGTH(CAST(payloadJson AS BLOB))` (CanvasSlice3Dao.kt:548/552), which equals what supervisor.py:249 checks.
  - Heartbeat answer: `{leaseUntilMs, serverTimeMs}`. Result answer: `{committed, duplicate}`. Fail: 200 `{ok:true}` or 409.
  - Request bodies are parsed with exact key sets, `helperProtocol` and `leaseGen` must be non-string integers, and a value above Long range gets 400.
- **Denial table.** `HelperRoutes.statusOf` equals constants.json `denials` (tested against the fixture). 401/403/404/409 come only from the table, which is what supervisor.py:23 treats as "kill now". Grant off answers 403 `capability_revoked` on lease, payload, heartbeat, result and fail. Poll stays 200 and delivers cleanup and `cancel`, and supervisor.py:163-165 kills on `cancel`.
- **Per-route auth.**
  - `/api/helper/*` runs only after `helperAuth.check` (CanvasRouter.kt:115-117).
  - `/api/helper-status` and `/api/helper-jobs` are BROWSER-principal (principalFor) and return only counts and an expiry.
  - No route holds the adapter, the grant writers or the pairing opener. The 20k-request matrix in HelperProductionSurfaceTest runs against the real routes, adapter, session and DAO.
- **Confirm adapter.**
  - It is the only caller of the port's `confirm`.
  - The binding is built from the port's row. Before the port is called, the code checks the grant, the HMAC and the expiry (`now >= expiresAtMs` refused).
  - The DAO re-checks state, hash, session and fence. A duplicate tap is refused before the port, because the job is no longer pending.
  - `refresh()` is the suspend bridge. `clear()` runs on session end, and the key rotates at start and at end.
- **Session end.** HelperDeletion.endSession turns the grant off, then revokes (token and key), then voids every open job and writes cleanup in one call. The server stop sits in `finally`. Ending is idempotent through `needsEnd`, and the first terminate of a process always runs the full end.
  - Each stop path is wired: notification Stop, idle stop, LAN loss (session-owned callback with the screen closed), developer mode off (graph collector and service), Disconnect, service destroy, refused FGS start, server-off status and time limit.
  - `recoverAtStart` turns the grant off, revokes and ABORTs open jobs. It does not stop the server.
  - The NetworkWatch registers once per session (guarded) and unregisters inside terminate. Lock order cannot deadlock, because callbacks are asynchronous.
- **Fingerprint.**
  - `TlsMaterial.build` hashes `chain[0].encoded`, the leaf DER that PinnedKeyManager serves.
  - `fingerprint16` is the first 16 hex digits, upper case, in 4 groups of 4. client.py strips spaces and lowercases.
  - HelperPairingFingerprintTest compares against the certificate the bound socket actually presents.
- **View model.** The refresh loop starts in onStart and is cancelled in onStop (the route's ON_START/ON_STOP observer plus onDispose). It reads on `io`. `tick()` no longer touches prefs. Card actions run on `io`.
- **Wiring.**
  - `HelperBackend.forApp` is built once and uses one logger port.
  - `MemoryGraph.get` is deferred and safe on main.
  - `composeExecutors` refuses a clash with a built-in node.
  - The defaults are inert: `terminateSession` defaults to `stop()` and `helperJobs` is null.
  - The CanvasService log moved to `PhoneLogging.canvasPort()`.
  - Every new log line is fixed text plus a count. The router logs the template path, so no job id is logged.

## Findings

### 1. Important (blocking): an unauthenticated LAN peer can now make the phone allocate 2 MiB + 4 KiB per connection, 8x the old bound

**Where:** CanvasServer.kt:192-206 (`toRequest`), CanvasServer.kt:37-41 (`bodyLimit`), CanvasRouter.kt:106.

`toRequest` runs before the router's Host, Origin and token checks. It allocates `ByteArray(len)` up front from the client's `Content-Length` alone. Since WP6, `len` may be up to 2,101,248 for any `POST /api/helper/jobs/<anything matching [A-Za-z0-9:_-]{1,64}>/result`, without a token.

**Failure scenario:**
1. Any LAN host completes a TLS handshake (no client authentication) and sends `POST /api/helper/jobs/x/result` with `Content-Length: 2101248` and no body bytes.
2. It does this on all 16 connections (`MAX_CLIENTS`).
3. Each buffer is held until `DEADLINE_MS` (15 s). The attacker then reconnects.

The result is a sustained ~32 MiB allocated for zero bytes sent. Before WP6 the bound was 16 x 256 KiB = 4 MiB. The app has no `largeHeap`, and the same process hosts the glasses link and the photo and bitmap work. This is exactly the "unauthenticated memory/DoS amplification beyond what the existing server already allows" class.

**Edit:** decide on the large limit before allocating, only for an allowed Host with the live helper token, and read incrementally.

- CanvasRouter.kt (next to `bearer`), add:
  ```kotlin
  /** Before any body byte is read: only an allowed Host carrying the live HELPER token may send more than MAX_BODY (the one result route). */
  fun mayReadLargeBody(method: String, path: String, headers: Map<String, String>): Boolean {
      if (CanvasServer.bodyLimit(method, path) <= CanvasServer.MAX_BODY || !hosts.hostAllowed(headers["host"])) return false
      val h = headers["authorization"] ?: return false
      if (h.length <= 7 || !h.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return false
      return helperAuth?.check(h.substring(7).trim()) == true
  }
  ```
- CanvasServer.kt:196-206, replace the limit and the allocation:
  ```kotlin
  val path = s.uri.substringBefore('?')
  val limit = if (len > MAX_BODY && router.mayReadLargeBody(method, path, headers)) MAX_RESULT_BODY else MAX_BODY
  if (len > limit) return HttpRequest(method, s.uri, query, headers, ByteArray(0), bodyTooLarge = true, remoteIp = s.remoteIpAddress)
  val out = java.io.ByteArrayOutputStream(minOf(len, 64L * 1024).toInt())
  val buf = ByteArray(64 * 1024)
  var left = len
  val input = s.inputStream
  while (left > 0) { val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
  return HttpRequest(method, s.uri, query, headers, out.toByteArray(), remoteIp = s.remoteIpAddress)
  ```
  With the incremental read, a stalled client costs only the bytes it actually sent.
- CanvasRouter.kt:106 can keep `bodyLimit(...)`: an oversized unauthenticated request already arrives with `bodyTooLarge` set.
- **Test:** a real-socket test (the existing CanvasServer tests start a Daemon) sends `Content-Length: 2101248` without a token. It must get 413 without reading, and with the helper token it must be accepted.

### 2. Minor: the token check and the session lookup are not atomic

**Where:** CanvasRouter.kt:116 + HelperRoutes.kt:80.

The router calls `helperAuth.check(token)`. `HelperRoutes.dispatch` then calls `auth.session()` under a separate lock.

**Failure scenario:**
1. A request carrying the OLD token passes `check`.
2. The user taps Accept pairing and a new helper pairs before `session()` runs.
3. That request is then served under the NEW helper's `helperId`. For example, it could lease the new helper's job.

The window is microseconds and needs a user tap, so this is not blocking.

**Edit:**
- Add `fun sessionFor(token: String?): Session? = synchronized(lock) { val m = this.token ?: return null; if (token.isNullOrEmpty() || !MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), m)) null else session }` to HelperAuth.
- Use it in HelperRoutes.kt:80 with the bearer parsed from `req.headers["authorization"]`.

### 3. Minor: a malformed result gets 400, so the helper retries until its watchdog kills the job

**Where:** HelperRoutes.kt:193-195.

A non-object `result` (for example a runner outcome with `result: null`) or a `resultHash` that is not 64 lowercase hex answers 400. supervisor.py:435-436 treats 400 as retry-later and re-posts every second until the job deadline kills it. It never posts `fail(bad_output)`.

**Edit:** `val result = o["result"] as? JsonObject ?: throw Denied("invalid_result")`. Use the same for the hash regex. supervisor.py:433-434 then posts `fail(bad_output)` at once.

Optional: `resultHash` is format-checked but never compared. HelperJobService computes its own hash, which is documented. You could compare it with `BundleHash.ofCanonical(...)` and answer 422 on a mismatch, so a canonicalisation drift between Kotlin and Python surfaces as an error.

### 4. Minor: duplicate JSON keys are accepted (last one wins)

**Where:** HelperRoutes.kt:97-101.

`Json.parseToJsonElement` in kotlinx-serialization 1.7.3 builds a LinkedHashMap with no duplicate check. `{"leaseGen":1,"leaseGen":2}` therefore passes the "exact keys" check with leaseGen 2. Only the helper principal reaches this route, and it is trusted under threat model A, so this does not block.

**Edit:** either document the behaviour in the class KDoc ("duplicate keys: last wins"), or add a small pre-scan that rejects a repeated key at the top level and in `active[]` items.

### 5. Minor: the server stop became asynchronous and can stop a server started after it

**Where:** HelperDevSession.kt:201-203.

`CanvasService.shutDown` used to call `graph.stop()` synchronously. Now every stop goes through `terminate`. When the session already ended, terminate queues `scope.launch { stopServer() }`.

**Failure scenario:**
1. A shutDown, or `onDestroy` (CanvasService.kt:123), queues the stop.
2. Before it runs, a new `onStartCommand` → `graph.start(false)` arrives (for example, the user re-opens the canvas screen right after notification Stop).
3. The queued stop then stops the new server.
4. `launchStatus` sees the status go off and calls `shutDown(SHUTDOWN)`, so the service also ends while the screen is up.

**Edit:** in the already-ended branch, call `stopServer()` inline (it is a cheap `@Synchronized` stop). Return `running ?: kotlinx.coroutines.CompletableDeferred(Unit)` so the caller still gets a completed Job.

### 6. Minor: the TIME_LIMIT cause is never what the real graph records

**Where:** CanvasService.kt:102-105.

`timedOut()` first calls `graph.stopWithNotice`, which calls `terminateSession(SHUTDOWN)` (CanvasGraph.kt:326-330). Only then does it call `shutDown(SessionEnd.TIME_LIMIT)`, which is a no-op because an end is already running. Both causes map to `SESSION_END`, so nothing changes functionally. However:
- CanvasServiceTest.kt:95-100 passes only because FakeCanvasHost records both calls (`contains`).
- CanvasServiceTest.kt:112 asserts `terminated.isNotEmpty() || calls.contains("stopNotice")`, which proves nothing about the cause.

**Edit:**
- Give `stopWithNotice(text, cause: SessionEnd = SessionEnd.SHUTDOWN)` a cause parameter.
- Call it with `TIME_LIMIT` in `timedOut()` and with `TIME_LIMIT` in `enterForegroundSafely` (its KDoc says "a start the system refused").
- Assert `assertEquals(listOf(SessionEnd.TIME_LIMIT), host.terminated.take(1))` in both tests.

### 7. Minor (UX, a plan decision): Disconnect stops the canvas server and the service while the user is on the screen

**Where:** HelperBackend.kt:47-50.

This follows the plan's section 6 and WP6 row ("explicit disconnect" is one of the ends, and every end stops the server and service). The resulting UX is still poor:
1. The user unpairs the laptop.
2. The browser LAN session and the screen's server both go down.
3. `onStart` already ran, so nothing restarts the server until the user leaves the screen or toggles LAN.

**Proposed alternative, for the user to decide:**
- Add `val stopsServer: Boolean = true` to `SessionEnd`, set to `false` for `DISCONNECT`.
- In HelperDevSession.kt:194, make the stop `if (cause.stopsServer) stopServer()`.
- Disconnect would still turn the grant off, revoke the token and key, and void jobs with cleanup. Only the browser server stays up.
- Record the change as a plan amendment (section 6, "Session termination").

### 8. Minor: the four-hour expiry is effectively unreachable, and its timer loop is untested

**Where:** HelperDevSession.kt:158 and HelperHarness.kt:112.

IdlePolicy (30 min without a browser request, no run active, no screen open) stops the service, and with it the session, long before four hours. While a screen is open, `checkExpiry` refreshes `lastScreenAt`. Expiry can therefore only fire if a run stays active for four hours with no browser activity, and `py_exec` is bounded at about 135 s. As a result, a helper-only session always ends at 30 min. That is restrictive and matches the plan ("IdlePolicy keeps stopping the service as today"), so it is not a bug.

The harness sets `checkEveryMs = Long.MAX_VALUE / 4` and the tests call `checkExpiry()` directly. Deleting the `watcher = scope.launch {...}` line (HelperDevSession.kt:158) would survive every test.

**Edit:** add one test that builds the session with `checkEveryMs = 10` on a test dispatcher, advances the fake clock past `inactivityMs`, and asserts the end ran.

### 9. Minor: turning LAN off restarts the server and aborts open helper jobs, but the helper session lives on

**Where:** CanvasGraph.kt:251-257, CanvasExtras.kt:30 → CanvasStartup.run → CanvasSlice3Dao.kt:225.

Every server start runs `CanvasExtras.start` → `CanvasStartup.run` → `markRunningAborted`, which includes `abortOpenHelperJobs`. So the LAN toggle and certificate rotation ABORT the live session's AWAITING_CONFIRM, QUEUED and LEASED jobs. That is benign: on loopback the helper is unreachable anyway, and cleanup is enqueued.

However, the session itself survives with the token valid and the grant on. When LAN comes back on (same certificate), the old helper resumes without a new tap.

**Edit:** in `setLan(false)` (CanvasGraph.kt:251), when `lanOn` was true and `helperSession?.active == true`, call `helperSession.terminate(SessionEnd.LAN_LOSS)` first. Alternatively, record the current behaviour as intended.

### 10. Minor: the Developer-mode switch in Settings bypasses the single end path

**Where:** SettingsDestinations.kt:310.

`CanvasGraph.get(app).let { it.stop(); it.revokeAll() }` stops the server directly. The session end still happens, through the graph's `devMode` collector (CanvasGraph.kt:211), so nothing leaks. But this breaks the "terminate is the only end path" rule and briefly leaves the session active with the server down.

**Edit:** `CanvasGraph.get(app).let { it.terminateSession(SessionEnd.DEVELOPER_MODE_OFF); it.revokeAll() }`.

### 11. Minor: a start() between terminate() and its asynchronous end leaves an "active" session with dead credentials

**Where:** HelperDevSession.kt:148-161 vs 178-199.

**Failure scenario:**
1. `terminate()` sets `needsEnd = false` and launches the end.
2. The user taps Accept pairing or grant-on before that end runs. `start()` sets `active = true`, rotates the key and opens a pairing window.
3. The queued end then revokes, closes that window, turns the grant off and stops the server.

The new session stays `active` with its watcher and network callback registered while nothing works. It fails closed, and a later terminate still ends it.

**Edit:** in `start()`, `if (endJob?.isActive == true) return`. The card refresh then shows the true state and the user taps again.

### 12. Minor (WP11, cross-WP; recommend fixing before B2): the helper accepts an 8-digit pin

**Where:** client.py:49 and HelperNative.kt:18.

The card and its KDoc say the helper "requires at least 16 digits". In fact `normalize_pin` accepts 8 or more (`len(p) < 8`). A 32-bit prefix of a certificate hash is cheap to second-preimage: vary the serial number and hash, about 2^32 SHA-256. So a user who types only 8 digits can have the pairing code MITM'd on the LAN.

**Edit:**
- client.py:49: `if len(p) < 16 ...`, and update the docstring at client.py:45.
- If the helper is not changed, correct the HelperNative.kt:18 KDoc.

### 13. Minor (WP11, cross-WP): an envelope just over 2 MiB crashes the helper loop

**Where:** client.py:150-151, called from supervisor.py:421.

`_post_result` checks `len(canonical(result)) <= maxResultBytes`. `PhoneClient._call` then checks the whole envelope (about 110 bytes larger) against the same `maxResultBytes` and raises `ValueError`. That is not a `NetworkError`, so it escapes `job_step` → `run()`, and the helper exits. This happens for a result within about 110 bytes of 2 MiB. The phone would accept it (limit 2 MiB + 4096).

**Edit:** pass `max_body=self._max_result + 4096` in `PhoneClient.result`, mirroring `CanvasServer.MAX_RESULT_BODY`. Or catch `ValueError` in `_post_result` → `_post_fail(job, "too_large")`.

### 14. Minor (test quality, unverified parts)

**Where:** HelperRoutesTest.kt:214-232.

- The 2 MiB limit is tested only at `bodyLimit` and the router level, never through `CanvasServer.toRequest` on a real socket. Finding 1's test closes this gap.
- No test sends Python-produced bodies byte for byte. The Kotlin fake helper builds its own JSON. A replay of `json.dumps(body, separators=(",",":"), ensure_ascii=False)` for poll, lease, heartbeat, result and fail would pin the contract. That is cheap, because constants.json is already read.
- `ConnectivityNetworkWatch` (the real callback, including `registerNetworkCallback` without Wi-Fi) and the real FGS Stop are verified only via fakes and Robolectric. They must stay on the B2 device checklist: Wi-Fi off with the screen closed ends the session; notification Stop ends it.
- I cannot verify the "7 test mistakes fixed" claim from the diff alone; no list is in the commits.
- The plan's mutations (polls extend activity, observer only while the screen is open, route renews a voided job, payload releases AWAITING_CONFIRM, VM subscribed to status only) are plausibly killed by the named tests.

VERDICT: NOT READY
