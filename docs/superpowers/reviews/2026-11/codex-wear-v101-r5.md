Two P1 findings remain in HEAD `6b56dbde`.

1. **Retired epochs can falsely pause a worn user.** [HudController.kt:269](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/link/HudController.kt:269) treats every different epoch as new, including one previously replaced.

   Reproduced against compiled code: accept epoch A, then restarted Hub epoch B with check ON and worn true; replay A’s check OFF. After fresh quiet coverage, `glassesEstimate=true`, `phoneEstimate=false`, and capture blocks as `parked`. Replaying A’s `worn=false` instead blocks as `not_worn`, surviving interaction and motion. Remember retired epochs while allowing genuinely new epochs.

2. **Epoch-zero messages bypass sequence ordering.** [HudController.kt:265](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/link/HudController.kt:265) excludes `phoneEpoch=0` from all ordering checks.

   Reproduced with a pre-epoch peer: same session, seq `20` check ON, then delayed seq `10` check OFF. The stale update is accepted and capture blocks as `parked`. Preserve legacy same-session sequence ordering when `seq` is supplied.

The original R4 reproductions now fail within one nonzero epoch. Same-session silence recovery, lower-clock reconnect, new-Hub epoch acceptance, and fresh-receiver ordering work.

Validation: **54 focused tests passed**, seven wire assertions passed, optional-field/default compatibility passed, and all ten sensing pins match. Room **16**, protocol **1**, trace schema **1**, and plan hash **`f5782e0d8610`** remain intact. Used existing compiled outputs; no clean rebuild or device run. Ignored the known baseline logging failure.

**NOT READY**