**P1-1 closed by static inspection. P1-2 and P1-3 remain open.**

- **P1 — Quiet-boundary publication race.** [Delivery.kt:206](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/Delivery.kt:206) arms the watch after publication. If synchronous persistence crosses the boundary after the final quiet-hours check, `arm()` selects tomorrow’s start and cancels the previous timer. The HUD can publish during quiet hours without immediate withdrawal. Recheck at publication; arming inside an active quiet window must withdraw immediately.

- **P1 — Phone death leaves the glasses rendering across quiet-hours closure.** The daemon watch cannot fire in a dead process. Glasses rendering uses the card’s 12-second lifetime, without a quiet-hours deadline ([HudPresenter.kt:76](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/core/hud/src/main/kotlin/com/rokid/context/core/hud/render/HudPresenter.kt:76)). Display at 21:59:58, phone dies, quiet starts at 22:00: the card can remain visible. A glasses-enforced deadline capped at the boundary is needed; ordinary TTL does not close this requirement.

- **P1 — Dismissal durability remains best effort.** [SuggestionStore.kt:186](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/SuggestionStore.kt:186) ignores marker `commit()` failure; production also permits a missing marker store. Four durable dismissals plus a fifth whose marker and ledger writes fail still lose the pause after restart. The dismissal action continues despite neither write being durable.

The immutable snapshot/version check is correctly serialized; callbacks run outside the monitor, and the watchdog test exists. No additional daemon-worker deadlock, withdrawal resurrection, text logging leak, or permanently renewed marker pause established. Marker deletion and backup exclusions are present.

Static review; tests were not run in the read-only sandbox.

NOT READY