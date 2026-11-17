- **P1 — HUD deletion fix is incomplete.** [PhoneNudges.kt:78](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/nudge/PhoneNudges.kt:78): show toast A, receive newer phone/glance nudge B, then forget A. The collector examines only B, whose timestamp is newer, so A’s forgotten text remains until the eight-second timeout. Equal timestamps also bypass clearing. Check the owner’s presence in the emitted rows.

- **P1 — Forgotten descendant text remains stored and indexed.** [ForgetExecutor.kt:176](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/lifecycle/ForgetExecutor.kt:176) retains the existing `INVALIDATED` path for descendants of erased mixed/carried captions. It deletes vectors but preserves `text`, `tags`, `searchText` and FTS terms. Failed/offline resummarization can leave that residue indefinitely. Visibility/export filters hide it; they do not erase it.

- **P1 — F3 misses Scenes whose Moments survive invalidation.** [ForgetPlanner.kt:287](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/lifecycle/ForgetPlanner.kt:287) collects Scenes through removed Moments. A scoped Forget can instead invalidate a remote summary, blank its surviving Moment and merely stale its derived fields and containing Scene. That Scene never enters the rebuild set, leaving forgotten summary/caption/embedding content stored. Plan and erase these affected fields too.

The m5-r1 conservative Ask-dependency P1 appears closed; its HUD P1 remains open. No additional original-photo loss or crash/deadlock found.

**Non-P1:** OFF byte-identity is unmet: the toast branch is unconditional, and persisted solo markers still alter batching after ON→OFF.

Static review plus small in-memory checks only; Gradle/device verification was unavailable in this read-only sandbox.

NOT READY