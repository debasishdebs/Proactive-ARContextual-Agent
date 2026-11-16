No remaining P1 found in HEAD `71c9ab6c`.

- Both r5 reproductions now fail: retired-epoch check OFF / `worn=false` cannot pause capture; epoch-zero seq `10` cannot override seq `20` within the same session.
- Same-session silence recovery, lower-clock reconnects, and genuinely new hub epochs work.
- The deque stays bounded at 16 through 40 restarts. Random 64-bit epoch reuse presents no practical lockout concern.

Validation: **54 focused tests, 17 replay/recovery assertions, and seven wire assertions passed** using existing compiled outputs. Optional-field compatibility, all ten sensing pins, Room **16**, protocol **1**, trace schema **1**, and plan hash **`f5782e0d8610`** remain intact.

No clean rebuild or device run; the known baseline logging failure was disregarded.

**READY (no P1)**