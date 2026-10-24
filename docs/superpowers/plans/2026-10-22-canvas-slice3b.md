# Canvas slice 3b: the laptop helper (lean plan)

**Spec:** `specs/2026-10-21-canvas-slice3-e2e-configurator-design.md` §4.4 (S39, S22), §4.4.1 (S52), §4.5 (S48, S55), S40, S6, §16-§18. **Base plan:** `plans/2026-10-22-canvas-slice3a.md` (3a-CORE). **Review driver:** `.superpowers/codex/slice3-spec-review.md` finding #1 (untrusted code ran as the laptop user).
Docs-only plan. Implementers are Sonnet. No Opus reviewer is assumed beyond one review per task group (budget).

## Scope and cuts (binding for this plan)
- **In (3b-CORE):** DEV/DEPLOY (S52), pairing and capabilities (S39), one generic container runner (S22), `py.fn` (experiment only), `tool.draft` NL tools (S48/S55), guards, oracle, debug APK, review UI, deletion fences, checklist.
- **Out, deferred (3b-LATER, about +9 h):** promotion to Kotlin (B6, 6 h), CodeMirror 5 editor (a plain `<textarea>` ships first, 1.5 h later), intent lease B3/B4 (not helper work; follows wave B Task 9).
- **Tiers.** Tier 1 built-in nodes and tier 2 expression nodes (3c) need no helper and never touch it. Tier 3 = `py.fn` (experiment/replay only, never published, S6) and `tool.draft` (NL tool: stand-ins in experiments, code built only on the laptop on `canvas-tool/<id>`, merged by the user). DEPLOY runs tiers 1-2 only; nothing runs on the laptop.
- **Shape (one generic path, no per-tool bespoke code):** a helper job = `(type, profile, input bundle, output spec)`. `PROFILES = {py_exec, tool_build}` is a data table in `profiles.py`; one `runner.run(job)` turns a profile into one or more container invocations. A new job type = one table entry plus one input/output validator. The phone side is one `HelperJobService` over `canvas_helper_job`, same lifecycle as `ReplayService`.
- **Small deviation from spec:** `pca-helper` is a thin launcher plus a stdlib-only `pca_helper/` package (no pip), not one 2,000-line file. Still stdlib only, still a host process.

## Reuse map (read before editing)
| Reused from | What |
|---|---|
| 3a Task 8 | `canvas_helper_job` table and fenced DAO style (add DAO methods only; if a column is missing, add a **new next-free migration**, never edit v7) |
| 3a Task 21 | `ReplayService` job pattern: one job at a time, persisted `jobState`, `markRunningAborted()`, cancel on Developer mode off; `ReplayRoutes.kt` route style |
| 3a Task 22/33 | route registration and composition wiring (`HelperRoutes` is registered the same way) |
| 3a Task 3 | `NodeTypes v2`, node registry, ASYNC projection; `py.fn` and `tool.draft` are registry entries (executors `PYTHON`, `DRAFT`) |
| 3a Task 7 | `PlanCompiler`; S6 already refuses to publish PYTHON/DRAFT/REPLAY_ONLY nodes: add one test, no new code |
| 3a Task 34 | deletion graph: helper jobs carrying a forgotten frame are voided |
| 3a Tasks 35-38 | canvas UI shell, `paramform.js` (contract form), vendored-file rules, device checklist |
| slice 2 | LAN HTTPS canvas server, TLS fingerprint, Developer mode, session auth |

## Rules for every task (Sonnet implementer)
- One agent per worktree; explicit-path commits; NEVER `git stash`; NEVER machine-wide `pkill`/`killall`/`gradlew --stop`; no push; no `--reference` for the llama.cpp submodule.
- **RED first**, then GREEN. Each task lists a **mutation check**: break the named line, confirm the named test fails, restore.
- **No secrets in output.** Tests and logs report counts and names only. The gen API key is read from `~/.pca-helper/gen.key` (0600) and never echoed, logged or committed; redact filter in `log.py` (T3).
- Worktrees: `/Users/debasishkanhar/Desktop/rokid/pca-s3b/{k,h1,h2}` from `s3a/int` (or `main` for Python-only tasks if 3a is not merged: T0, T3, T4, T5, T7, T6a have no 3a dependency).
- Python: `python3 -m pytest tools/canvas-helper/tests -q`. Kotlin: `gw.sh <module>:testDebugUnitTest` (one Gradle per worktree).

## Task table
| # | Task | Depends on | Stream | agent-h | Gradle |
|---|---|---|---|---|---|
| T0 | Protocol v1 fixtures (JSON schema and sample payloads, both sides test against them) | none | controller | 0.5 | no |
| T1 | Phone: pairing, tokens, capabilities, "Allow laptop helper jobs" switch, session end revokes | T0, 3a T8, T22, slice 2 TLS | K | 2.5 | yes |
| T2 | Phone: `HelperJobService` (submit, lease 60 s, heartbeat, result, void, confirm gate), `HelperRoutes` | T1, 3a T21 | K | 2.5 | yes |
| T3 | Helper core (Python): client, pairing, poll, lease/heartbeat, retention, redacting log, `doctor` | T0 | H1 | 2.5 | no |
| T4 | Generic container runner and profile table | T0 | H2 | 2.5 | no |
| T5a | `Containerfile.pyjob/.gen/.verify`, `pca-helper setup`, digest pins | T4, Podman installed | H2 | 1.5 | no |
| T5b | Live security suite (escape attempts, limits, network) | T5a | H2 | 2 | no |
| T6a | `py.fn` container harness (entry point, schema check, per-item timeout) | T4 | H1 | 1.5 | no |
| T6b | `py.fn` node: Kotlin executor (`PYTHON`), `no_helper`, RT lockstep, 10-config sweep cap, textarea editor | T2, T6a, 3a T3 | K | 2.5 | yes |
| T7a | Guards: path allowlist, licence allowlist, injection scan, prompt template | T0 | H3 or H2 | 1.5 | no |
| T7b | Oracle: golden I/O, hash check, 80/20 split, boundary cases, tolerance, harness-touch rejection | T7a | H3 or H2 | 2 | no |
| T8 | `tool.draft` node: stand-ins, contract form, freeze together with NL text, no-CLI alert and export | 3a T3, T7a | K | 2.5 | yes |
| T9a | Build job: `tool_build` profile (gen container on `canvas-tool/<id>`, snapshot via `git archive`, allowlist proxy), no-CLI detection | T4, T5a, T7b | H2 | 2.5 | no |
| T9b | Verify step: offline debug APK in the verify container, result bundle (diff, tests, report, licence, APK path and SHA-256) | T9a | H2 | 1.5 | **yes (container)** |
| T10 | UI: helper status, Disconnect, DEV/DEPLOY indicator, "Start/End dev session" if absent, patchview.js review, build confirm | T2, T8, 3a T35-37 | K | 2.5 | yes |
| T11 | Deletion fences, "Delete laptop copies", S6 test, 3b checklist | T2, T9b, 3a T34, T38 | K | 2 | yes |

**Total: 32.5 agent-h (Sonnet, includes one fix round)**, plus about 4 h controller/user work (Podman install, image builds, device run, review). NL-tools-first path (S55: T0-T5, T7-T10 without T6, T11) is about **26 agent-h**. Add 9 h for 3b-LATER.
**Confidence: medium-low.** About 60 % that the total lands within +/-25 %. Main risks: the gen allowlist proxy under rootless Podman on macOS (a VM), a warm offline Gradle cache, and the CLI's auth model. 3a slips shift T1/T2 only; the Python stream is independent.
**Wall-clock:** Python stream about 17 h and Kotlin stream about 14 h in parallel, so about 20 h end to end plus verification.
**Hard stop-loss:** if cumulative effort passes **45 agent-h** or T9a/T9b together pass **8 h**, stop, ship what is merged (py.fn without NL build, or NL tools exported as `tool-<id>/` with no build) and report to the user. A single task over **2x** its estimate: stop that task, report, do not start a third fix round.

## Dispatch order (first 6) and parallelism
1. **T0** (controller, 0.5 h): writes the protocol fixtures. Everything else reads them.
2. **T3** helper core in worktree `h1` (Python only).
3. **T4** runner in worktree `h2` (Python only).
4. **T1** phone pairing in worktree `k` (the only Gradle user at this point).
5. **T7a** guards in worktree `h3` (Python only; pure functions).
6. **T5a** images, once T4 is merged **and the controller has installed Podman** (see checklist).
Then: T2 (K), T7b, T5b, T6a, T9a, T8, T9b, T10, T6b, T11 in table order, K stream sequential, H streams sequential per worktree.
**Parallel:** T0 first; T1/T3/T4/T7a are mutually independent (disjoint files). Up to **3 agents** at once. Python-only tasks use no Gradle. Gradle budget: at most **3 concurrent builds machine-wide** (8 cores, 8 GB): one for stream K, one for the verify container (T9b, capped 4 GB and 2 CPUs; the Podman machine is sized at 4 GB so never run it while two host builds are active), one spare. Never run T5b (many containers) concurrently with T9b.
**Serial constraints:** T2 after T1 (same Kotlin files); T6b/T8/T10/T11 share `HelperRoutes`/canvas assets, so they stay in stream K in order; T9a after T5a and T7b.

## Task specs
### T0 Protocol v1 fixtures
Files (new): `tools/canvas-helper/protocol/helper-protocol-v1.json` (endpoints under `/api/helper/*`: `pair`, `poll`, `heartbeat`, `result`, `ack`; job types `py_exec`, `tool_build`; states; error codes `no_helper`, `needs_runtime`, `lease_expired`, `voided`), `protocol/samples/*.json`. Fields mirror `canvas_helper_job` (lease, capability, state). `helperProtocol = 1`.
Test: a pytest and a Kotlin test (added in T1/T2) both load every sample and validate required fields. **Mutation:** drop a required field from a sample, the schema test fails.

### T1 Phone pairing and capabilities
New: `HelperPairing.kt`, `HelperTokenStore` (hashed tokens, never plaintext at rest), `HelperPairingTest`. Reuse: slice 2 session auth, `ReplayRoutes` style.
- Pairing: the helper sends the typed fingerprint first; the phone-side test only checks the **6-digit code** path and token scope; the fingerprint refusal is a helper test (T3). Code is single-use, 5 attempts, 2 min.
- Capabilities `py_exec`, `promote` (promote reserved for 3b-LATER but the flag exists, default off). Token valid only for `/api/helper/*` and its own jobs.
- Switch "Allow laptop helper jobs" off by default; turning it off, LAN off, Disconnect, Sign out all, or session end revokes tokens and voids jobs.
- **RED tests:** `helperTokenRejectedOnCanvasRoutes`, `tokenWithoutPyExecCannotLease`, `switchOffVoidsJobs`, `codeSingleUseAndRateLimited`, `tokenNotStoredPlain`. **Mutation:** remove the scope check, `helperTokenRejectedOnCanvasRoutes` fails.

### T2 Phone job service
New: `HelperJobService.kt`, `HelperRoutes.kt`, DAO methods on `canvas_helper_job`. Reuse: the `ReplayService` mutex/persisted state/abort-on-restart pattern and fenced DAO commit.
- Browser submit only while the switch is on; `tool_build` and `promote` also need a **confirm tap** (a `confirmedAt` field set by an app-side route that the helper token cannot call).
- Lease 60 s, renewed by heartbeat; only the leasing token may post the result; late result after expiry is rejected; result committed through the fenced DAO.
- Input size caps (source 16,000 chars; bundle 5,000 cases); results are data only, never rendered with `innerHTML`.
- **RED tests:** `submitRefusedWhenSwitchOff`, `buildNeedsConfirm`, `onlyLeaseHolderPostsResult`, `expiredLeaseVoids`, `abortedAfterRestart`, `noHelperFailsItems`. **Mutation:** skip the lease-holder check, `onlyLeaseHolderPostsResult` fails.

### T3 Helper core
New: `tools/canvas-helper/pca-helper`, `pca_helper/{client,pairing,poll,store,log,doctor}.py`, tests with a fake phone server (stdlib `http.server`, TLS with a test cert). Standard library only.
- `pca-helper dev`: asks for the fingerprint, **refuses a mismatch before sending any code**, pins it, then polls out only (no listening port).
- Inputs deleted at job end; outputs under `~/.pca-helper/` 7 days; "delete laptop copies" command acknowledged on next poll. Token and fingerprint stored 0600 outside any job dir.
- `log.py` redacts anything token-, key- or secret-shaped; `doctor` prints only OK/FAIL per check (runtime, images, CLI key presence, `helperProtocol`).
- **RED tests:** `fingerprintMismatchSendsNoCode`, `noListenSocket` (the package never calls `bind`/`listen`: monkeypatched `socket` raises), `inputsDeletedOnJobEnd`, `retention7d`, `logRedactsToken`, `doctorNoRuntimeReportsNeedsRuntime`. **Mutation:** invert the fingerprint compare, `fingerprintMismatchSendsNoCode` fails.

### T4 Generic container runner
New: `pca_helper/{profiles,runner,runtime}.py`, tests with a `FakeRuntime` that records argv. No Podman needed for these tests.
- `runtime.py` detects `podman` then `docker`; none present: job fails `needs_runtime`.
- `profiles.py` is **data**: `py_exec` (net none, no env, inputs ro, out rw, 2 CPUs, 2 GB, 5 s/item, 120 s/job), `gen` (the gen mounts and proxy network), `verify` (net none, offline Gradle, patch on a fresh snapshot, 4 GB). Common flags for **all**: `--rm --name <job>`, `--read-only`, `--cap-drop=ALL`, `--security-opt no-new-privileges`, `--pids-limit`, non-root user (`--userns=keep-id` on Podman), `--tmpfs /tmp`, **no `--privileged`, no socket mount, no `-v` outside the job dir** (plus the named read-only snapshot and cache for gen/verify), no `--env` except one named key variable for `gen`.
- Cancel/timeout: `<runtime> kill <name>` then `rm -f`, verified by name.
- **RED tests:** `pyProfileHasNetworkNone`, `noMountOutsideJobDir`, `noPrivilegedNoSocket`, `capsDropped`, `limitsPresent`, `timeoutKillsByName`, `unknownProfileRefused`, `noEnvInPy`. **Mutation:** delete `--network none` from the py profile, `pyProfileHasNetworkNone` fails; add a `-v /` to a profile, `noMountOutsideJobDir` fails.

### T5a Images and setup
New: `Containerfile.pyjob` (`python:3.12-slim` by digest, pinned numpy wheels), `Containerfile.gen` (codex and claude CLI at pinned versions), `Containerfile.verify` (Temurin 17, Android cmdline tools), `pca_helper/setup.py` (`podman build`, tag with helper version, one **setup-only** online step that fills a Gradle cache volume with no credentials and no job code), `images.lock` (digests). Tests: argv snapshot of the build commands (fake runtime); a `doctor` test that a missing image fails.
- Real build only when the controller runs `pca-helper setup` after the Podman install (checklist step 3). Verify image about 10 min once.

### T5b Live security suite (needs Podman; marked `@pytest.mark.live`, skipped when no runtime)
See "Security tests". Runs through the real runner with a hostile Python payload set. All must pass before T6b/T9a are merged.

### T6a `py.fn` harness
New: `container/pyjob_entry.py` (reads `inputs.json`, imports the source, runs per item with a 5 s alarm, writes `out.json`, validates each output against the out-port schema and fails the item with a code, never a traceback with paths), tests run it through the FakeRuntime-free local Python (unit) and later live (T5b).
- **RED tests:** `schemaMismatchFailsItem`, `timeoutFailsItemNotJob`, `networkUseFailsCleanly` (isolation is the container's job, not the harness's; the item fails with a code). **Mutation:** remove the schema check, `schemaMismatchFailsItem` fails.

### T6b `py.fn` node on the phone
New: `PyFnExecutor.kt` (builds a `py_exec` job, no per-node code beyond the registry entry), textarea editor and `paramform.js` entry. Reuse 3a registry, ASYNC projection, replay service.
- RT replay in lockstep (about 20 ms per round trip), sweep with a `py.fn` capped at 10 configs, `no_helper` when none, source at most 16,000 chars, never executed on the phone.
- **RED tests:** `pyFnNeverExecutedOnPhone`, `sweepCappedAt10WithPyFn`, `noHelperFailsItems`, `publishRefusedWithPyFn` (S6 fixed reason text). **Mutation:** remove the cap, `sweepCappedAt10WithPyFn` fails.

### T7a Guards (pure Python)
New: `pca_helper/guards.py`, `config/allowed_paths.json`, `config/licences.json`, `templates/build_prompt.txt`, tests.
- Path allowlist: only `services/tools/canvas/<id>/**`, its tests, and one registry line; reject manifest, permissions, network/endpoint code, Gradle files outside the node's entry, other modules, keys, `.env`, `gw.sh`, harness, golden files.
- Licence allowlist: Apache-2.0, MIT, BSD-2/3, ISC, MPL-2.0; reject GPL, AGPL, non-commercial, unknown.
- Injection scan: reject URLs, IP literals and credential-looking strings absent from the NL spec. The NL text enters only through the fixed template between delimiters.
- **RED tests:** table-driven good/bad patch fixtures: `manifestEditRejected`, `gradleOutsideEntryRejected`, `unknownLicenceRejected`, `urlNotInSpecRejected`, `urlInSpecAllowed`, `dotEnvRejected`, `pathTraversalRejected`. **Mutation:** widen the allowlist glob to `**`, `manifestEditRejected` fails.

### T7b Oracle (pure Python)
New: `pca_helper/oracle.py`, `oracle/NodeUnderTest` adapter template kept in the helper tree outside any patch; tests.
- Verifies the golden I/O SHA-256, holds out 20 % plus boundary cases, accepts only 100 % agreement on branches/enums and |d| <= 1e-6 on numbers over all cases, reports "agreement on N sampled cases (M held out)", rejects a patch touching the harness/adapter/golden/`gw.sh`.
- **RED tests:** `hashMismatchRejected`, `heldOutCasesNeverShownToGenerator`, `toleranceBoundary`, `patchTouchingAdapterRejected`, `reportWordingNeverSaysEquivalent`. **Mutation:** leave out the held-out split, `heldOutCasesNeverShownToGenerator` fails.

### T8 `tool.draft` node
New: `DraftExecutor.kt` (stand-in outputs; optional `ai.prompt` stand-in), canvas contract form via `paramform.js`; freezing stores NL text, contract and examples together. Reuse 3a registry and freeze.
- No helper or no CLI: a clear alert ("No codex/claude CLI found; this tool needs code") and an export of `tool-<id>/` (NL spec, contract, examples, `SPEC.md`); offer the deterministic `tool.call`/`expr` route when expressible (only if 3c exists; otherwise just the alert).
- **RED tests:** `standInRunsWithoutHelper`, `freezeIncludesNlText`, `publishRefusedWithDraft`, `exportContainsSpecOnly` (no secrets, no repo files). **Mutation:** omit the NL text from the hash, `freezeIncludesNlText` fails.

### T9a Build job
New: `profiles` entry `tool_build`, `pca_helper/build.py` (snapshot by `git archive` at a fixed commit, writable scratch copy, branch `canvas-tool/<id>`, never `main`), `pca_helper/proxy.py` (stdlib CONNECT proxy allowing only the CLI's API host(s), logs hosts only), CLI detection matrix (`codex`/`claude` present or not).
- The only credential: the mounted read-only key file (see open question 1). No git/ssh/cloud/adb keys, no `.env`, no home.
- Patch is checked by T7a/T7b before anything is shown. Nothing is committed to the user's branch, installed or merged.
- **RED tests:** `branchIsCanvasToolId`, `snapshotFromArchiveNotLiveCheckout`, `proxyBlocksOtherHost`, `noCredentialBeyondKey`, `cliMatrix` (both, one, none), `badPatchNeverReturned`. **Mutation:** point the proxy allowlist at `*`, `proxyBlocksOtherHost` fails.

### T9b Verify and bundle
New: `verify` run (patch on a fresh snapshot, `./gradlew --offline assembleDebug` in the verify container, 2 CPUs, 4 GB, 20 min cap), result bundle (diff, test results, equivalence report, licence report, APK path and SHA-256 on the laptop).
- **RED tests:** fake-runtime unit tests for `verifyHasNetworkNone`, `offlineFlagPresent`, `apkHashRecorded`, `timeoutKillsContainer`; the real build runs only in the live checklist.

### T10 UI
New/changed: canvas assets (status chip "Helper connected (laptop) · caps", Disconnect, DEV/DEPLOY indicator "Canvas: deploy · plan <name> v<N> · no helper", build-confirm dialog, `patchview.js` pure text diff renderer, review panel). Developer mode → Canvas → "Start/End dev session" only if slice 2/3a has no such switch (check first; if absent it is about 1 h of the 2.5).
- The 4 h idle timeout, LAN loss and "End dev session" end the session and revoke helper jobs (reuses T1).
- **RED tests (JS, node test harness already used for canvas):** `patchviewEscapesAllHtml`, `patchviewNeverUsesInnerHTML` (grep test), `indicatorShowsDeployWhenNoSession`. **Mutation:** switch to `innerHTML`, the grep test fails. React Flow/Chart views stay in the manual checklist.

### T11 Fences, laptop copies, checklist
Changes: 3a Task 34 deletion graph registers helper jobs (void and tell helper to delete inputs on Forget/Delete all); "Delete laptop copies" command and ack; tests `forgetVoidsPendingHelperJob`, `deleteLaptopCopiesAcked`, `s6RefusesPyFnPublish`. **Mutation:** skip the void in the Forget path, `forgetVoidsPendingHelperJob` fails. Adds the 3b section to 3a Task 38's device checklist.

## Security tests (T4 argv-level always; T5b live, real container)
Hostile payloads run through `py_exec` via the real runner. Each asserts the job fails or the effect is absent; no payload may print a secret.
1. **Network off:** `socket.connect` to a public IP, DNS lookup, and to the host gateway (`host.containers.internal`, `172.17.0.1`) all fail.
2. **No host reach:** read `~/.ssh`, `~/.aws`, `~/.pca-helper/*` (token, pinned fingerprint), `/etc/shadow`, the repo checkout, and the runtime socket path: all absent. Only the job's inputs (read-only) and the out dir exist.
3. **Write limits:** write to the inputs mount (EROFS), to `/` (read-only root), fill `/tmp` (tmpfs cap), then verify nothing appeared on the host outside the out dir.
4. **Resource limits:** fork bomb (pids limit), 4 GB allocation (OOM-killed at 2 GB), CPU spin (timeout kills), `sleep` past 120 s (killed); in each case the container name is gone (`ps` list empty) and the helper stays alive.
5. **Privilege:** `os.getuid() != 0` effectively, capability set empty, `setuid`/`mount`/`chroot`/`ptrace` fail, `/proc/1/environ` and `/proc/self/environ` contain no secret variable names.
6. **Escape attempts:** `/var/run/docker.sock` or Podman socket search, `--privileged` style syscalls (`unshare`, `mknod`, `open_by_handle_at`), writing `/proc/sys/*`, symlink in the out dir pointing at host files (the helper must not follow symlinks when collecting outputs: collect with `O_NOFOLLOW`, regular files only).
7. **Output hygiene:** output with 200 MB, path traversal names (`../x`), or non-UTF-8 is truncated/rejected by the collector; the result is posted as JSON data only.
8. **Gen profile:** from inside the container, `curl` to a non-allowlisted host fails through the proxy; direct connection (no proxy) fails; the key file is read-only and absent from `env` output captured in results (the scan in T7a also rejects key-shaped strings in the patch).
9. **Verify profile:** `--network none` (any download fails), Gradle cache copy is read-only (a write fails), the patch cannot reach the host or the key.
10. **Cancel/revoke:** switch off, Disconnect, lease expiry and cancel each kill the container by name within 5 s and delete the inputs (verified through T3 retention tests plus a live assertion).
11. **Runtime missing:** with no `podman`/`docker` on PATH every execution job fails `needs_runtime`; **never falls back to running on the host** (RED test `noHostFallback`; mutation: add a fallback and the test fails).

## Laptop and device verification checklist (controller with the user)
The user said on 2026-10-21 that installing Podman or Docker is OK; the controller does it only when T5a starts and tells the user what was installed. Check `command -v brew` first; if Homebrew is missing, stop and ask.
1. `brew install podman` (installs Podman only; Docker is the fallback, not installed unless Podman fails).
2. `podman machine init --cpus 2 --memory 4096 --disk-size 60` then `podman machine start`. With 8 GB RAM, do not start the machine while two host Gradle builds are running.
3. `podman info --format '{{.Host.Security.Rootless}}'` must print `true`; `podman run --rm --network none docker.io/library/alpine:3.20 true` runs; record the Python image digest with `podman image inspect --format '{{.Digest}}'` into `images.lock`.
4. `tools/canvas-helper/pca-helper setup` (about 10 min, builds three images, one setup-only online cache fill) then `pca-helper doctor` (all OK; reports key **presence** only).
5. **Key (the user does this; the controller never reads or prints it):** put the CLI's API key into `~/.pca-helper/gen.key` with mode 0600. Controller checks only `test -s` and `stat` mode.
6. T5b live suite: `python3 -m pytest tools/canvas-helper/tests -q -m live`; all green, then `podman ps -a` empty.
7. **Device:** phone: Developer mode → Canvas → "Start dev session"; switch "Allow laptop helper jobs" on; laptop: `pca-helper dev`, user types the fingerprint from the app, enters the 6-digit code. A wrong fingerprint must refuse. Confirm the chip shows "Helper connected".
8. `py.fn` smoke: a 10-line function over a stored trace; result matches the in-app expectation; switch off mid-job: container gone, inputs deleted.
8b. **Moment run (Moment-lite):** take 3 or more photos over a few seconds (two within the same second if you can), then in the canvas open a pipeline `input.moments → py.fn` (source returns `{'n': len(x['photos'])}`, outSchema `{n:integer}`). Run page: the Moments list shows each second with its photo count; select two, Estimate (the dialog says Moments and photos), Start; confirm on the phone; each result `n` equals that Moment's photo count. The laptop payload shows `momentId`, `startMs`, `photos:[{tsMs}]`, `sensor:null` and **no frame id, file path or image bytes**. Then (a) estimate, take one more photo in the same second, Start: refused with "These Moments changed since the estimate", nothing ran; (b) estimate, Forget a photo of one selected Moment on the phone, Start: same refusal; (c) Start and Forget a member photo before tapping Confirm: job voided, run ends "stopped by a Forget", laptop inputs deleted; (d) after a finished run, Forget a member photo: that Moment's result rows are gone, the other Moment's remain. The page offers no control that confirms anything.
9. NL tool: describe a tiny tool, freeze, build (confirm tap); diff shown by `patchview.js`; branch `canvas-tool/<id>` exists and `main` unchanged; debug APK path and SHA-256 reported; **nothing installed** unless the user says so, using the normal backed-up install procedure.
10. End dev session: canvas home shows "deploy · no helper"; no LAN listener on the phone; laptop shows no helper process; DEPLOY plan still runs on glasses.
11. Forget a frame with a pending job: job voided, laptop inputs deleted.
Scrub: never print `AssistServer`/device-secret logcat lines (memory rule); the checklist runs only the filtered logcat.

## Open questions (defaults apply unless the user objects)
1. **CLI credential:** a subscription login is more than "one API key". Default: require an API key in `~/.pca-helper/gen.key`; never mount `~/.claude` or `~/.codex`.
2. **Gen network:** default = stdlib allowlist proxy on the host plus a Podman network reaching it; if that proves unreliable on macOS within 2 h, the gen profile refuses and the user gets the `tool-<id>/` export (no host-run fallback).
3. **Offline Gradle cache source:** default = a one-time setup copy of the host `~/.gradle` module cache into a read-only volume; stop and ask if the copy exceeds 8 GB.
4. **Docker vs Podman:** default Podman; Docker only if Podman cannot run rootless on this Mac.
5. **Promotion (B6) and CodeMirror:** default deferred to 3b-LATER (+7.5 h) to save budget.
6. **Single script:** default is the launcher plus stdlib package (a deviation from "single script" in S52); say so if a literal single file is wanted.
