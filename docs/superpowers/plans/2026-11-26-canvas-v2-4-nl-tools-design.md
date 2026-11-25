# Canvas v2.4 (natural-language tools, `tool.draft`): design (2026-11-26)

Designer: Opus. Implementer: Sonnet, in the work packages below. Reviewer: Codex sol at medium effort, or Opus at low effort when the Codex quota is out.
Branch `feat/v101-ask-eval`, `android-companion/` and `tools/canvas-helper/`. This is a design only. It changes no source, needs no install and needs no phone.

ROADMAP row **v2.4** (old canvas label; it ships in the **1.9.x** canvas line, see ROADMAP "Versioning"): describe a tool in plain English, generated and verified in an isolated container, shipped as a reviewed patch.

Labels: VERIFIED means read in the code or docs on this branch. UNVERIFIED means inferred or estimated.

---

## 0. What already exists (VERIFIED), and what changed since

**Prior design (not re-invented here):**
- Spec `specs/2026-10-21-canvas-slice3-e2e-configurator-design.md` section 4.5 (rulings S48, S55): describe, freeze, build, review, approve; licence and path allowlists; injection scan; no-CLI export.
- Plan `2026-10-22-canvas-slice3b.md` tasks T7a, T7b, T8, T9a, T9b, T10 and the security test list.
- User ruling 2026-10-23 moved NL tools to v2 as one unit: `2026-10-23-canvas-slice3b-core/3b-LATER-backlog.md` section V (items V1-V10, about 24 h sized, V7 unsized).

**Built (3b CORE):**
- Laptop helper `tools/canvas-helper/pca_helper/`: runner, one profile table (`profiles.py`, rows from `config/resources.json`, one JOB row `py_exec`), pairing, collector (no symlinks), export, expiry, doctor, supervisor; protocol fixtures (`job-id.json`, confirm and hash vectors).
- Phone: `HelperJobService`, `HelperJobStore`, `HelperProtocol`, `HelperRoutes`, `HelperGrants`, `HelperConfirm` (memory-only HMAC, per-run native confirmation), `HelperDevSession`.
- `ExecutorKind.DRAFT` and `TOOL` exist (`NodeTypes.kt:26`). `PlanShape` already refuses to publish any `tool.*`, `DRAFT` or `PYTHON` node (`PlanShape.kt:47-48`).

**Not built:** the `tool.draft` node type, the `gen`/`verify` profiles, the allowlist proxy, the guards, the oracle, the Codex review gate, the bundle and its review view.

**Changed since 23 Oct, and how it bites:**
- **Offline-only mode** (1.4.3) exists. Generation sends text to a CLI's remote API, so the build must be refused under Offline only.
- **The Forget policy table** fails its test when a data family has no row. Drafts and build bundles need rows.
- The spec's path allowlist names `services/tools/canvas/<id>/`, but **no `services/tools` module exists** (modules: `ai, connectors, glasses-link, memory, orchestration, voice`). The allowlist moves to `services/orchestration/src/main/java/com/rokid/context/canvas/tools/generated/<id>/`, its test twin, and one line in `Executors.all()`.
- Threat model A still holds (the user's laptop and key; generated code is untrusted but not treated as hostile). The 3d hardening re-open trigger (c), "generated code auto-installed or auto-merged", must never fire.

## 1. Goal

The user describes a tool in words, tests the pipeline at once with stand-ins, and can ask for real Kotlin code. The code is generated and verified in containers on the laptop and comes back as a diff with tests, an oracle report, a review verdict and a debug APK hash. The user merges and installs it by hand.

## 2. Scope

**In (CORE v2.4):**
- `tool.draft` node with NL text, contract, stand-ins, freeze and export (backlog V8).
- Build path: `gen` and `verify` profiles, snapshot via `git archive`, branch `canvas-tool/<id>`, allowlist proxy, read-only key file, the workspace volume lifecycle, the bundle (V1, V6, V7, V10).
- Guards: path allowlist, licence allowlist, injection scan, name denylist (T7a).
- Oracle, light form: frozen examples plus a 20% held-out split plus boundary cases (T7b).
- In-product Codex read-only diff review, fixed checklist, PASS shown (V1).
- `tool_build` grant (default off) on the existing per-job native confirmation (V2).
- One trusted example tool and its registry line (V3).

**Out:**
- Tools with a photo input or a network effect. v2.4 tools are pure functions over TEXT/JSON. The `effect` field allows `none` or `proposal` only.
- The 200-case generated schema oracle (V9): later.
- Promotion of `py.fn` to Kotlin, CodeMirror, a second build CLI (both are detected; one is pinned).
- Hostile-code defences (3d slice).
- Anything automatic: no auto-merge, no auto-install, no generated code on a device before a human merge.

## 3. Node and API shape

**`tool.draft`** (ASYNC, executor `DRAFT`, `liveable=false`, never published):

| Param | Rule |
|---|---|
| `toolId` | slug, the `job-id.json` regex, not in the class-name denylist |
| `nl` | plain text, at most 4 KB |
| `contract` | in ports TEXT or JSON; out JSON with a `schema_lite` schema; `effect: none \| proposal` |
| `examples` | 3..50 pairs `{in, out}`, typed by the user, each checked against the schema |
| `standIn` | `table` (exact canonical-input lookup; unknown input gives `no_example`) or `prompt` (an `ai.prompt` stand-in under the normal resolver and privacy rules) |

Freeze hashes `nl + contract + examples` together (test `freezeIncludesNlText`).

**Generated node:** `tool.gen.<id>`, executor `TOOL`, code under the allowlisted package, registered by one line. It ships like any built-in node once merged.

**Routes (phone, through `CanvasRouter`, Developer only):**

| Route | Purpose |
|---|---|
| `POST tools/:toolId/export` | returns `tool-<id>/` (`SPEC.md`, `contract.json`, `examples.json`); no secrets, no repo files |
| `POST helper/jobs {type:"tool_build", toolId, freezeHash}` | creates an `AWAITING_CONFIRM` job; refused `offline_only`, `grant_off`, `no_helper` |
| `GET helper/jobs/:id` | state, verdict, diffstat, oracle counts, test counts, APK SHA-256 |
| `GET helper/jobs/:id/diff` | the patch text for `patchview.js` (escaped, no `innerHTML`) |

Laptop: `profiles` gains `tool_gen` and `tool_verify` rows (data in `resources.json`). One `runner.run(job)` turns a profile into container runs, as for `py_exec`.

## 4. Data flow

```
Describe (canvas) -> stand-in runs on the phone (no helper, no network for `table`)
Freeze -> Build request -> checks: Developer, grant tool_build, Model privacy Automatic, helper paired
  -> native confirm card: "NL text, contract and N examples go to <CLI API host> via your laptop"
  -> helper leases the job (60 s lease, heartbeat)
  -> snapshot: git archive at a pinned commit -> scratch copy -> branch canvas-tool/<id>
  -> gen container: CLI + fixed template (NL text as delimited data) ; proxy allows only the CLI API host ; key file read-only
  -> guards: path, licence, injection scan, name denylist   (fail = patch never shown as buildable)
  -> intermediate export -> Codex read-only review (6-item checklist incl. "no identity, no network, no new permission")
  -> verify container: --network none ; unit tests ; oracle ; ./gradlew --offline assembleDebug (2 CPU, 4 GB, 20 min)
  -> bundle: diff, test results, oracle report ("agreement on N cases, M held out", never "equivalent"), licence report, verdict, APK path + SHA-256 (laptop only, 7-day expiry)
  -> phone shows verdict + diffstat + diff
  -> user merges on the laptop by hand -> normal build -> normal install with backup and the user's OK
```

## 5. Privacy and threat model

- **What leaves the phone:** only the NL text, the contract and the typed examples. They go over the paired LAN TLS channel to the laptop, then to the CLI's API. Never photos, frames, memories, traces, coordinates or run outputs. The confirm card names this before each build.
- **Forget coverage.** Drafts are user-authored pipeline data, not photo-derived. They go with the pipeline and with Delete all (UNVERIFIED: check that the wipe covers pipelines; add it if not). Helper jobs keep the CORE fences: Forget, Delete all, grant off and Disconnect void jobs; "Delete laptop copies" clears the laptop side. New Forget policy rows: `tool_draft` and `tool_build_bundle`.
- **Offline-only mode:** a build is refused at request and again at lease (`offline_only`). `table` stand-ins and export work offline. A `prompt` stand-in follows the resolver.
- **Pause zones:** the node takes no capture input, so zones do not apply directly. The v2.1 run rules (fail closed) apply unchanged to any pipeline around it.
- **No identity recognition:** the licence and dependency allowlist rejects face or biometric SDKs; the path allowlist forbids new dependencies outside it; the review checklist has a fixed "no identity, no face, no person matching" item. A draft cannot take photos.
- **No raw photos leaving:** by type. A PHOTOS port cannot connect to `tool.draft` (validator).
- **Generated code:** it cannot touch the manifest, permissions, network or endpoint code, build scripts, keys or `.env` (path allowlist). It reaches a device only after a human merge and the normal install.
- **The key:** placed by the user at `~/.pca-helper/gen.key` (0600). The controller checks only presence and mode. The injection scan rejects key-shaped strings, URLs and IP literals that the NL text did not contain.

## 6. Failure modes

| Failure | Behaviour |
|---|---|
| No helper or no CLI | alert "this tool needs code" plus export; stand-ins still run |
| Runtime missing | `needs_runtime`; never falls back to the host (CORE test `noHostFallback`) |
| Gen timeout or quota | job `FAILED gen_timeout` / `gen_quota`; nothing returned |
| Patch outside allowlist, bad licence, scan hit | `path_denied` / `licence_denied` / `scan_denied`; patch withheld |
| Oracle disagreement | `oracle_failed` with counts; diff shown read-only for diagnosis |
| Codex review NOT READY or unavailable | verdict shown; `review_unavailable` withholds the bundle (owner question 5) |
| Offline Gradle cache missing | `verify_failed` with a log tail scrubbed of secrets |
| Mac disk low (97% today) | free-space check before the job: `disk_low` |
| Forget, Delete all, grant off, Disconnect mid-job | job voided, container killed within 5 s, inputs deleted |

## 7. Test plan

**Python (fake runtime, no laptop VM):** profile rows and argv (`verify` has `--network none`; `gen` has only the proxy), proxy allowlist (`proxyBlocksOtherHost`), snapshot from archive not the live checkout, branch name, path/licence/scan guards, oracle held-out split never shown to gen, report wording, bundle schema, void on revoke.

**Kotlin JVM:** stand-in lookup and `no_example`, freeze hash, publish refused, export content, `offline_only` refusal at both layers, confirm binding to the freeze hash, routes (auth, 403 Developer off, templates), Forget policy rows present.

**JS:** `patchviewEscapesAllHtml`, `patchviewNeverUsesInnerHTML`.

**Live (laptop, user's key, no phone):** spike B re-run: pinned CLI produces one patch, key canary scan, offline APK build in the 4 GB VM, the 11 CORE security cases plus the gen proxy and verify network cases.

**Device:** none for the feature itself (the emulator covers pairing and the confirm card). A merged tool goes through the normal device checklist like any change.

## 8. Work packages (agent hours, UNVERIFIED)

| WP | Content | Hours | Depends on | Needs |
|---|---|---|---|---|
| WP0 | spike B re-run: CLI pin, one patch, offline APK in the VM, disk check | 3.5 | user key, disk space | laptop |
| WP1 | `tool.draft` node, stand-ins, freeze, export, contract form (`paramform.js`) | 4 | none | |
| WP2 | `tool_gen` profile, proxy, snapshot, branch | 4 | WP0 | laptop |
| WP3 | guards: path, licence, scan, templates, name denylist | 2.5 | none | |
| WP4 | light oracle | 2 | WP3 | |
| WP5 | `tool_verify` profile, workspace volume lifecycle, bundle, 7-day expiry | 4 | WP2 | laptop |
| WP6 | Codex review step in the helper | 1.5 | WP5 | |
| WP7 | phone: job type, grant, confirm, routes, result view, `patchview.js` | 4 | WP1 | emulator |
| WP8 | trusted example tool, registry line, Forget rows, fences | 3 | WP5, WP7 | |
| Review | one Codex pass at the end | 1.5 | all | |
| **Total** | | **~30 h** | | |

Stop-loss: past 45 agent-h in total, or WP2 + WP5 past 10 h, stop and ship what is merged (stand-ins plus export, no build) and report. Python (WP0, WP2-WP6) and Kotlin (WP1, WP7) streams run in parallel worktrees.

## 9. Owner questions (yes/no)

1. One pinned build CLI (Codex) with a dedicated spend-limited key that you place at `~/.pca-helper/gen.key` yourself?
2. v2.4 tools are pure TEXT/JSON functions only (no photo input, no network effect)?
3. Examples are typed by hand only (no "copy from a run" button, so no memory text ever reaches the CLI)?
4. Builds are refused under Offline only, while stand-ins and export keep working?
5. If the in-product Codex review is unavailable, withhold the bundle (diff export only)?
6. The full 200-case schema oracle (V9) waits for a later release?
