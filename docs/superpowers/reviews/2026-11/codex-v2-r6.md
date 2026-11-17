The single r5 P1 is closed. No new P1 found in `b9d7b3ae`.

- Token delivery captures the registering callback directly; replacement cannot redirect it to another caller.
- Reset and adoption share a lock. Old tokens are rejected; replaying the current pair preserves the epoch, and another session cannot reuse it.
- Missing `onConnected` causes a bounded two-second wait per attempt, then fails closed.
- Previous phone/new bridge fails closed through `UNSUPPORTED`; new phone/old bridge cannot obtain a token.
- In-flight session calls retain connection checks across replacement. No new fail-open race found.

Static review only; tests were not rerun in the read-only workspace. The accepted uninterruptible Binder-call limit is excluded.

READY