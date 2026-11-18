Three P1 findings at `577bc068`:

- **Allow-list hides real failures** ([line 462](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:462)). Whole-line subtraction turns `E sqlcipher_mlock errno=12 SQLiteException: file is not a database` into **BENIGN-ONLY**. The added fixture explicitly accepts this.
- **Missing signatures produce CLEAN** ([line 434](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:434)). Synthetic checks miss `net.sqlcipher.database.SQLiteException`, unqualified `SQLiteException`, `no such column`, `no such table`, `duplicate column`, and openHelper’s `java.lang.IllegalStateException: A migration from 17 to 18 was required but not found.` These matter when caught/logged without an AndroidRuntime crash. Covered: the two explicitly qualified SQLiteException classes, `SQLiteDatabaseCorruptException`, `file is not a database`, `Migration didn't properly handle`, and `Room cannot verify the data integrity`.
- **Restart race can produce CLEAN** ([line 444](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:444)). PID is sampled once; all-buffer and PID logs are separate snapshots. A crash after the all-buffer snapshot can leave nonempty, clean historical logs for the sampled PID while the replacement PID’s database failures go unscanned. No PID recheck or restart detection exists. Earlier Java crashes with retained `Process: <pkg>` lines are caught.

Literal empty logs remain **UNVERIFIED**, exit 3. A detected phone FAIL prints the required warning and exits 3 before glasses installation.

Verified with synthetic grep inputs; no adb run.

NOT READY