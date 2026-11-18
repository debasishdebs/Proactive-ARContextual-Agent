All six P1s are closed in `050d809a`:

- Unknown driving suppresses recording.
- Commit block re-reads the gates before `store.add`.
- Dependencies include all counted visits/marks, arrival and blank-note pins; pin edits/deletions trigger withdrawal.
- Replay retains a replay-wide lease and checks every returned candidate’s sources at completion.
- Settings observe store changes and reject superseded refreshes.
- Encrypted-store open failure forces master off, refuses writes and blocks replay. Restart recovery is documented.

No new P1 found from lock ordering, replay busy handling or change signals. The documented storage lockout introduces no identified data corruption.

Static review only; Android tests were not run in the read-only workspace.

READY