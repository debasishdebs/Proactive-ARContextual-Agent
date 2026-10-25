# Forget gaps review

Reviewer: Codex gpt-6.1-sol (read-only, medium). Scope: cached export deletion, residue oracle.
Outcome: NOT READY (3 Important: export publish race, oracle large files, retention seed); fixed in 0021e421

- **Important — concurrent export survives Forget:** `MemoryExport.kt:31`. Cleanup snapshots the directory without coordinating with `write()`. An export containing forgotten text can rename its `.part` to `.json` after enumeration but before deletion. Cleanup deletes the vanished `.part` pathname, ignores `false`, and Forget succeeds with the JSON still present. Serialize export publication and cleanup, or fence publication against deletion.

- **Important — oracle silently ignores large exports:** `ResidueOracleTest.kt:113`. Files ≥1 MB have no content scanned. A retained `proactive-context-memories-123.json` containing forgotten text passes because its filename contains none of the forgotten identifiers. A mutation that skips deletion only for large exports survives the current fixtures.

- **Important — retention test cannot detect missing export cleanup:** `ResidueOracleTest.kt:203–206`. The expired memory is inserted after `world()` creates an export containing only `mem-FORGOT`. Removing retention’s `ForgetActions.apply` call leaves that export, but the assertion searches only for `oldMem`/`oldText`, so it still passes. Seed an export containing the expired memory before running retention.

VERDICT NOT READY