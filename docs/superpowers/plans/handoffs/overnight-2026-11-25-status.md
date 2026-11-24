# Overnight status (resume section first) - UPDATED 2026-11-24 15:40 IST

## STATE NOW
- Branch feat/v101-ask-eval tip cdef4c7e (+ docs): full verification 15/15 rc=0 (verify-final.txt start 15:12). Candidate staged (NOT INSTALLED): ~/Desktop/rokid/backups/apks-v110-train13-cdef4c7e (README, SHA256SUMS). Installed on the user's phone: 18c1b76a (19 Nov) with retention settings changed by the user/me to 48h/14d(medium)/photos Forever/long 365/cap 2000 MB.
- DEADLINE: install the candidate BEFORE Thu 26 Nov 00:50 IST (the old build's nightly run expires 23 Nov facts and drops Moments). The user needs to say go; phone connected; installer tools/install-trip-build.sh dry run first, --go only on the user's explicit go. Trip: Tue 1 Dec 05:00 (Sikkim/Darjeeling 1-6 Dec); the old '26 Nov install freeze' is dropped (Andaman plan).
- In flight: emulator rehearsal agent (ab5be69f979617627, branch emu13, result doc handoffs/emulator-smoke-train13-2026-11-24.md); Codex place r4 re-check (docs/superpowers/reviews/2026-11/codex-place-fixes-r4.md).
- Laptop slept 06:32-12:03 (battery): nothing ran then. Keep it on power.
- Accepted residuals (documented, not fixed): retry-supersede deleting trailing facts (pre-existing in 18c1b76a, backlog P2); leftover JPEG sweep under Pause (rows already deleted by the old build).
- Older Codex units still NOT READY and unfixed: mark (2 P1), mp/Offline-only (2 P1; feature not used), see open-backlog-2026-11-21.md.
- Next: read emulator + Codex r4 results; fix any P1 (Sonnet in worktree, run_wt.sh lock); rebuild/stage train14 if code changes; update ROADMAP rows; ask the user for the install go (before Wed night).

## (older notes below)

Goal (user): fixes planned, overnight, maps + pins + v2 groundwork, fully tested; emulator if no device; Opus for design/plan/RCA, Sonnet for build/test, Codex sol for reviews. 8 GB Mac: ONE Gradle job at a time (run_wt.sh lock in the session scratchpad), never the emulator while Gradle runs. No install on the phone without the user's explicit go.

## On feat/v101-ask-eval now (tip after b40dc2f9 + docs)
pin fixes r1-r3, partial-cell backoff, hub auto-start (acl/watch), retention hotfix (9ff8c9f0, 9b3c7184, 01e5ee87, bcd0490c), replay harness (4c02167a..2473c338), map zones P0 (1e34ab93, 64560bfb).
Verification of b40dc2f9: 13 of 15 rc=0 at 05:35 (phone tests + assemble still running).

## Codex verdicts (docs/superpowers/reviews/2026-11/)
- codex-pin-r2-r1: fixed in b40dc2f9 (needs a Codex r2 re-check).
- codex-retention-r1: NOT READY, 6 P1 (photo expiry/legacy/carried/lineage/suggestions/repair overwrite) -> agent branch ret-p1 in progress; candidate must ship with Pause clean-up ON by default until ret-p1 is merged and re-reviewed.
- codex-map-p0-r1: NOT READY, 1 P1 (seed path re-check per request) -> agent branch map-p0-fix.
- codex-mark-r1 / codex-mp-r1: NOT READY (old trip-build units), logged in open-backlog-2026-11-21.md, not fixed yet.

## Agents (worktrees under .claude/worktrees)
Track A place-fixes-A (sampler F1-F4, F7), Track B place-fixes-B (card history, places.db v2), Track C place-fixes-C (extract prompt signage), ret-p1, map-p0-fix. Merge each by cherry-pick onto feat/v101-ask-eval after its report; then full verification (verify-final.sh in the scratchpad, recreate from this list if gone: core:model, core:hud, core:capture, core:ui, services:ai, memory, connectors, glasses-link, voice, orchestration, nexus-port, nexus-bridge, glasses, phone tests + assembleDebug phone/glasses/nexus-bridge); Codex review of every unit; Opus reviewer only if Codex quota is out.

## Deadlines and asks for the user
- Retention: the 26 Nov ~00:50 run will drop 23 Nov facts/Moments on the phone unless a candidate with the fix (or Pause clean-up ON) is installed before it. Needs the user's install go.
- Photo retention on the phone was set by me to forever / long-term 365 / cap 2000 MB (user accepted).
- Open rulings: R1-R8 retention design, R1-R11 map design, place-fixes open points (defaults taken).
- Rokid-Coder: hands off.

## Next steps after the agents report
1. merge + verify + Codex review; 2. build candidate APKs (release signing as trip builds; see tools/install-trip-build.sh) with Pause clean-up default ON; 3. emulator rehearsal (Gradle idle first): upgrade over the installed 18c1b76a with the dump's databases, check places.db v2 migration, retention paused log line, hub auto-start logs; 4. stage in ~/Desktop/rokid/backups/ with README + SHA256SUMS; 5. update ROADMAP.md (v1.4.x rows: map zones P0, hub auto-start, retention fixes, place fixes; v1.9/v2 backlog: glasses app closes on glasses ~10% capture loss, P2 retention re-pointing, Moment location field Room v18).

**Card history decision (train14):** place card history is OFF by default in train14 (Developer flag `placeCardHistory`, default false, also false on a prefs error; no row is written, the Place Guide list and Home "Last place card" are hidden, "Show on glasses again" is disabled). Every Forget / Delete all deletion path still runs regardless of the flag, so rows from earlier runs are deleted. Redesign: move place_card_log into context.db (Room v18, additive) so Forget deletes it in the same transaction, removing the owed-obligation machinery (see reviews/2026-11/codex-place-fixes-r4.md).
