# Codex final pass fixes (findings 1-7, 10; 8 and 9 deferred)
Counts (build/test-results): orchestration 2357 tests, memory 894, ai 1078, core:capture 352, phone 1548; 0 failures, 0 errors.
Commits: ece11321 (1,2), 01648969 (3,4), c5e20ffe (6,7), undo precheck (5), staging dir (10).
Rulings: replay candidate = ReplayCandidates.forHash over stored pipeline versions (body gate ignored, unknown hash 404); PlanTransport split stage/transmit/discard; recovery of PREPARED TRIAL -> FAILED + discard in sender; ack ignored if any group has a higher gen; Delete all keeps only the plan gen counter; PlanSender re-checks epoch after commit and compensates; resetAfterWipe drops persisted msg.
Not fixed: undo has no compensation if the send itself throws after the rollbacks (preconditions are checked first); replay fusion overrides are still caller-supplied.
