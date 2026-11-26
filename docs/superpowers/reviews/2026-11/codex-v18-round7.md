VERDICT: NOT READY

1. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/data/frames/RokidFrameInbox.kt:66 - The sweep can still delete active import temps: `.imp-…jpg.part` fails timestamp parsing and falls back to its old capture mtime; completed temps also become eligible after an hour-long suspension or forward clock jump before publication. - Coordinate both orphan sweeps with the import writer using a shared lock or active-temp registry covering `.part` and published-temp names.

The production card guard runs inside the insert transaction on the same database; suspend DAO reads preserve Room’s transaction context. No writing path was found that skips the guard, and Forget/Delete all cannot interleave between its check and insert.

The append split preserves live-capture behavior; the import caller uses `appendFrameFenced`. No additional regressions found.

Read-only inspection and Python predicate reproduction only; no Gradle or device commands.