PROXY RULINGS (Codex gpt-6.1-sol, HIGH effort, standing in for the user while away). NOT confirmed by the real user. Written 2026-11-19 03:04.

**Product-owner proxy rulings — 19 November 2026**

These extend the [first](</Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/decisions/2026-11-17-codex-proxy-rulings.md>), [second](</Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/decisions/2026-11-17-codex-proxy-rulings-2.md>) and [third proxy rulings](</Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/decisions/2026-11-18-codex-proxy-rulings-3.md>). Preserve the phone database and originals, fail closed on privacy, and protect the 22–23 November field build.

**DECIDE-NOW** authorizes the stated implementation or release-scope decision. **NEEDS-THE-REAL-USER** reserves the specified contract or activation decision; its safest default applies meanwhile. Neither label authorizes installation or destructive execution on personal data.

**1 — Forget F2: cited-photo extractability — NEEDS-THE-REAL-USER**

**DECISION:** Keep the cited-photo tombstone for the field build. A fact Forget must not leave its cited photo eligible for extraction under the current contract. Changing that contract requires the real user.

**WHY:** Deleting a fact while allowing its source photo to be described again can recreate the forgotten information. Conversely, tombstoning the photo suppresses unrelated memories, and current orphan-photo cleanup can remove the original file. The [Forget design’s F2 divergence](</Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/specs/2026-11-16-forget-lifecycle-design.md:199>) therefore represents a consequential choice about what “forget” means. The proxy should preserve the existing privacy fence while leaving that choice explicit.

**RISK / SAFEST DEFAULT:** Preserve existing behavior; do not add further original-photo deletion. The preview must disclose cited-photo tombstones and any orphan-file removal. Keep the revised path OFF until the user accepts its scope. Surviving batch photos under F1/F5 remain a separate case.

**2 — Enable `forgetV2` for real use — NEEDS-THE-REAL-USER**

**DECISION:** **Yes, it may remain OFF throughout Golconda on 22 November and Ramoji on 23 November.** Neither trip is an activation deadline.

**WHY:** The [device checklist](</Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/handoffs/device-checklist-2026-11-17.md:14>) requires a clean shadow run and a worn test before activation. Real use also needs verified encrypted upgrade/reopen and key-preserving recovery, resolved privacy blockers, conservative dependencies covering all supplied context, and evidence that preview, revocation, execution and cleanup agree. Travel usefulness does not justify activating a destructive lifecycle with unresolved semantics or device evidence.

**CONDITIONS / SAFEST DEFAULT:** Default OFF. Before a deliberate user activation, require clean planner-shadow results with every difference ruled upon; relevant race, residue, survivor and restart checks; and a successful worn rehearsal on disposable data covering preview/acknowledgement, marked-photo Forget, surface withdrawal and tap-only **Ask again**. Blocked reconstruction may leave a survivor undescribed; it must not restore stale text or bypass power, zone or network gates. Scoped and mass Forget retain their separate restrictions. Holding ForgetV2 OFF also holds dependent v3 delivery.

**3 — Room v18 for v3 Suggestions — NEEDS-THE-REAL-USER**

**DECISION:** **Stay on v17 through both trips.** Keep switches in backup-excluded prefs and prototype through the storage interface. Do not integrate or ship v18 merely because it is additive.

**WHY:** An additive migration still runs when Suggestions is OFF and can prevent the existing encrypted database from opening. The [third proxy ruling](</Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/decisions/2026-11-18-codex-proxy-rulings-3.md:3>) explicitly reserves v18 for the user. The immediate benefit does not outweigh introducing another database transition before travel, especially when atomic nudge/provenance persistence and Forget cleanup remain activation prerequisites.

**RISK / SAFEST DEFAULT:** Prefs hold switches, not an unbounded suggestion history. Keep shadow metadata in memory initially. Any necessary persisted prototype metadata must be encrypted, app-private, backup-excluded, bounded and purged by Forget/Delete all. Do not overload `sourceIdsJson` or `why`. Reconsider v18 after the trip with encrypted v17→v18 upgrade/reopen, preservation and recovery evidence.

**4 — v3.1 delivery switches and first worn test — NEEDS-THE-REAL-USER**

**DECISION:** No routine or unattended delivery before the user has inspected cards while wearing the glasses. **A deliberate, supervised first worn acceptance test may temporarily enable only the necessary switches.** Turn them OFF afterward; passing that test does not activate everyday delivery.

**WHY:** The wearer must judge readability, distraction, dismissal and exposure of personal context. Emulator checks cannot establish those properties. A bounded fixture-based acceptance test resolves the first-card problem without waiving the existing requirement for **seven worn shadow days, applicable evaluation bars and deliberate activation**. The [v3 design and clock addenda](</Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/specs/2026-11-18-v3-proactive-loop-design.md:161>) also make delivery timing and suppression part of acceptance.

**FIRST WORN TEST / SAFEST DEFAULT:** Use harmless synthetic content, stationary and outside a zone. Demonstrate:

- Readable title, body and source label at MEDIUM/MAX; no Suggestions card below the permitted verbosity.
- Expiry within 12 seconds, head-motion dismissal after 2.5 seconds, and withdrawal of both popup and minimised overlay.
- Refusal when unworn, paused, zone-blocked, driving or vehicle state is uncertain, or budget is exhausted; no later backlog.
- Withdrawal on zone entry, source Forget and switch-off, including an in-flight card.
- Quiet-boundary expiry on the glasses clock; no quiet-bounded draw with unknown offset; deadline recomputation after reconnect or changed offset.
- Dismissal/snooze/never surviving the supported restart path, and fail-closed delivery on storage failure.

Keep all live delivery switches OFF pending acceptance and the remaining rollout gates. Record the known total-storage-failure restart limitation explicitly. Nexus requires its separate hardware approval.

**5 — Untested Overpass mirror reachability — DECIDE-NOW**

**DECISION:** **Acceptable to ship**, provided all-mirror failure produces a clean, visibly labelled partial seed. Phone-network reachability remains unverified; do not claim otherwise.

**WHY:** Overpass supplies optional OSM extras. Losing those extras is an acceptable bounded degradation when Wikipedia/Wikidata results survive, cached data remains intact, and retries cannot stall capture or flood the network. The [seed coverage report](</Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/reviews/2026-11/hyderabad-seed-coverage-2026-11-18.md:23>) documents fake-HTTP coverage and failed Mac reachability, which supports shipping the fallback behavior but does not establish real-phone availability.

**CONDITIONS / SAFEST DEFAULT:** Retain bounded mirror timeouts, backoff and cooldown; cancellation and privacy fences must stop subsequent requests. Store failure as PARTIAL, preserve successful results, show “seeded without OSM extras,” and allow a later retry. Never treat partial coverage as a complete pack or relax place-match thresholds. Seed Hyderabad and the separate Ramoji preset beforehand when possible. A phone-network probe is useful preparation, not a release blocker for this optional source.

**What the real user must confirm**

- F2’s permanent contract, including suppression of unrelated photo content and possible orphan-file removal.
- Whether and when to activate ForgetV2 after its evidence gates; leaving it OFF for both trips is acceptable.
- Whether to authorize v18 after reviewing migration and recovery evidence.
- Participation in and acceptance of the first worn card test, shadow labels and eventual delivery activation; Nexus remains separate.