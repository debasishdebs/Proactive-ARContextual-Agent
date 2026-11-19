# Status audit: old Codex Forget + release findings (2026-11-19)

Reviewer: Opus (Codex gpt-6.1-sol replacement), LOW effort, static and read-only. Tip of `feat/v101-ask-eval` (`ae6f1b01`). No Gradle, no devices, no edits.
Sources: `codex-m4-r1`, `codex-m5-r1`, `codex-forget-stage-r2`, `codex-multiselect-r1`, `codex-release-pipeline-r1/r2`, `ci-signing-rehearsal-2026-11-19`. There is no `codex-forget-stage-r1` file in the folder.
Fix commits: `d869506a` (M5 P1s), `24b49cc1` (stage r2 P1s and OFF identity), `1dffc771` (multi-select token), `68494418`, `0735dd16`, `ae6ba442` and `3f270ef1` (release). No Forget source commit after `1dffc771` reverts any of these fixes; the later commits touching these files are Place, Nexus and v3 work. Codex never re-reviewed any of these fixes. This audit is the first independent check.

## Forget (flag `forgetV2`, default OFF)

| # | Finding | Status | Evidence (current tree) | Reachable flag OFF? |
|---|---|---|---|---|
| m4-r1 | none (READY) | n/a | | n/a |
| m5-r1 P1-1 | Ask dependencies narrowed to citations; v16 `dependsOn` lost | FIXED | `AskSourceStates.kt:59-64` `dependenciesV2` = stored `sourcesJson` ids (cited plus `dependsOn` via `ForgetService.parseIds:303-313`) + state `cited` + `history`. Used by `Families.kt:246` `AnswerFamily.citing` | No. OFF uses the v1 `parseIds` match (`Families.kt:245`), which was already conservative |
| m5-r1 P1-2 | N1 nudge deletion leaves the toast on the HUD | FIXED | `PhoneNudges.kt:81` (flag ON) clears the toast when `toastOwner` is not among the emitted non-redacted rows | No. N1 ERASE only runs with v2 (`Families.kt:258`) |
| stage-r2 P1-1 | Toast A survives when a newer or equal-timestamp B is the first row | FIXED | Same line `PhoneNudges.kt:81`: owner-presence check, independent of timestamps. If the owner falls out of `WINDOW=16`, the toast clears (fails closed) | v1 residual only, see R3 |
| stage-r2 P1-2 | INVALIDATED descendants keep text, tags, searchText and FTS | FIXED (v2) | `Families.kt:78` REBUILD -> `invalidate` + `deleteVectorsFor` + `if (ForgetV2.on) blankText` (`ForgetDao.kt` `UPDATE memory SET text='', tags='', searchText=''`; the FTS triggers follow). This covers cascade, carried and `removeDescriptions` | v1 path `ForgetService.kt:193` still only invalidates. This is the shipped v1 design, kept for OFF byte-identity, see R2 |
| stage-r2 P1-3 | F3 misses Scenes of surviving Moments in a scoped Forget | FIXED | Planner `ForgetPlanner.kt:208,291-295` (`v2 && scoped`): DROPs the remote Moments' derived fields and adds their Scenes. Executor `ForgetExecutor.kt:291-294` `eraseDerivedHolding` (`Families.kt:108`) erases Moment derived rows and REBUILDs the Scenes. Planner and executor reach the same set | No (v2 only) |
| stage-r2 non-P1 | OFF byte-identity: toast branch, solo markers | FIXED | Toast is gated on `forgetV2()` (`PhoneNudges.kt:81`). `KeyframePipeline.kt:635-636` `soloBatch(rows, v2)` returns the rows unchanged when OFF | n/a |
| multiselect-r1 P1 | Confirmation token ignores content, so a stale confirm deletes edited content | FIXED | `ForgetSelection.kt:31` hashes `plan.renderWithContent()`. `ForgetPlan.kt` carries `revisions`, and `ForgetPlanner.revisionsOf` fingerprints the memory, photo, mark, moment, scene, ask, nudge, proposal and derived columns. Executor `ForgetExecutor.kt:128` re-plans inside the transaction and throws `ForgetContentChanged` before `leases.revoke`, so nothing is deleted. Regression test in `SelectionForgetTest` (commit `1dffc771`) | No. Multi-select is hidden: `ListRoute.kt:39` returns a null port when the flag is OFF |
| m5-r1 note | No downgrade migration v17 -> v16 | OPEN (accepted) | Room v17 is additive and unconditional, so it also runs with the flag OFF. Rolling back to a v16 APK needs a reinstall or data wipe | Yes (install-order only, not a leak) |

## Release pipeline (signing)

| # | Finding | Status | Evidence |
|---|---|---|---|
| r1 P1-1 | Debuggable check fails open (aapt2 failure, SIGPIPE) | FIXED | `verify-release.sh:34-45` captures each tool's output with a checked status, rejects empty output and matches on a here-string. `release.yml:233` `--debuggable-apk-permitted false`; the rehearsal showed a debug APK is refused (rc 1) |
| r1 P1-2 | Signing secrets lack a protected boundary | FIXED in code; external config OPEN | `release.yml:135` `environment: release`. Lines 142-169 run the tag regex, the tag == `GITHUB_SHA` check and the `origin/main` ancestry check before any secret step (199). The environment itself (required reviewers, tag rules, environment-only secrets) is unchecked: `README.md:31` still `[ ]` |
| r1 P2 | v1/v2 enabled, so the single-signer command fails | FIXED | `release.yml:232` turns v1, v2 and v4 off and v3 on, with rotation-min 28. Rehearsed PASS on API 34 |
| r1 P2 | Mutable supply chain | FIXED (residual P3) | Every action is pinned to a SHA (57, 64, 122, 179), Temurin 17.0.13 is pinned, setup-gradle and the cache are removed. Still floating: the `ubuntu-24.04` image family and the `sdkmanager` build-tools install (192) |
| r1 P2 | `grep -vx` regex bypass of the previous tag | FIXED | `TAG_RE` is checked in both jobs (53, 145). `release/prev-tag.sh` parses strictly and fails closed on an invalid `$TAG` (pipefail inside `prev=$(...)`) |
| r2 P2 | Dispatch tag can point at a different commit | FIXED (residual P3) | `release.yml:154-161` rejects an existing tag whose commit is not `GITHUB_SHA`. Residual: a tag created at another commit between the draft and the manual publish would attach these APKs to it. Check the tag at publish time |
| r2 P2 | Prerelease order: rc.10 < rc.2 | FIXED | `prev-tag.sh` compares numeric identifiers numerically, tested by `release/test-prev-tag.sh` |
| r2 P2 | Wrapper JAR and distribution unchecked | FIXED | `release.yml:90-94,171-175` runs `sha256sum -c release/gradle-wrapper-jar.sha256`. Its `76805e32...` equals the committed JAR's hash, recomputed now. `gradle-wrapper.properties` has `distributionSha256Sum=20f1b117...` (gradle-8.13-bin) with `validateDistributionUrl=true` |
| r2 note | BUILD_SHA.txt cannot authenticate a compromised builder | OPEN (accepted design limit) | An independent reproducible-build comparison would be needed to close it. Not a P1 |

## STILL OPEN, ranked (no P1 among them)

1. **R1: CI signing prerequisites (blocks enabling the CI signing job; does not block the trip).** These are outside the code: the `release` Environment with required reviewers, tag and branch rules and environment-only secrets; the real pin (`release/signer-sha256.txt` is a placeholder, so CI fails closed on purpose); the real key and lineage; a real runner run; the lineage on API 28-32 (glasses). Minimal fix: complete the `README.md` step 2 checklist, then do one dispatch dry run.
2. **R2: v1 INVALIDATED descendant text kept (flag OFF, the shipped v1 design).** Scenario: forget fact F. Its derived summary D is hidden (INVALIDATED, no vectors), but D's text, tags and FTS terms stay on disk until resummarisation, and stay indefinitely if that keeps failing offline. Every reader and export filters D out, so nothing is shown, exported or sent to a model. This is not a regression; byte-identity with OFF requires it. It does not block the trip candidate. It is closed by enabling forgetV2 (Families.kt:78).
3. **R3: v1 toast window (flag OFF, P3).** Scenario: toast nudge A shows, a newer non-toast nudge B arrives, and the user forgets A's source within the same 8 s. A is redacted but B is the first row, so A's text stays on the HUD for at most 8 s and then expires. Bounded and self-clearing; does not block the trip. Closed by enabling forgetV2.
4. **R4: P3 residuals.** The tag is not rechecked at publish time; the runner image and sdkmanager float; there is no v17 -> v16 downgrade path.

Blocks (a) the trip candidate with the flag OFF: nothing. Blocks (b) enabling forgetV2: no open code finding. Device and Gradle verification of the ON path is still outstanding, and Codex never re-reviewed the fixes. Blocks (b) the CI signing job: R1 only, which is configuration and rehearsal, not code.

FORGET+RELEASE: ALL P1 CLOSED
