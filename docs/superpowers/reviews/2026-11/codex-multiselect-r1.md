- **P1 — Stale confirmations can delete changed content** ([ForgetSelection.kt:30](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/lifecycle/ForgetSelection.kt:30)). The token hashes roots and IDs/fates only; `plan.render()` contains no content or revisions. Preview memory `m`, update its text under the same ID without changing dependencies, then confirm: the hash still matches and the executor deletes the updated, unpreviewed content. The transaction prevents subsequent races but cannot detect this earlier change. Hash affected content/revisions and add a regression requiring `CONTENT_CHANGED` with nothing deleted.

No additional P1 found in flag gating, lease ordering, paging selection, cap/ack enforcement, photo survivor handling, or the Ask lockout fix.

Static review; tests were not run in the read-only sandbox.

NOT READY