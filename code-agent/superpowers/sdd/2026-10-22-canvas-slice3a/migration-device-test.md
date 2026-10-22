# SqlCipherMigrationDeviceTest on emulator (slice 3a, s3a/int @ 4c7c0fed)

Emulator: MyEmulator AVD (android-34 google_apis arm64-v8a, existing image, no download), serial emulator-5580, headless. Real phone/glasses were attached over adb; ANDROID_SERIAL pinned to the emulator. Emulator stopped afterwards by pid.
Command: cd android-companion && ANDROID_SERIAL=emulator-5580 ./gradlew :services:memory:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.rokid.context.memory.db.SqlCipherMigrationDeviceTest

Result: 8 tests, 6 passed, 2 failed.

| Test | Result |
|---|---|
| encryptedDbMigratesTo7FromV1Shape (1 to 7) | PASS |
| ...FromV2Shape (2 to 7) | PASS |
| ...FromV3Shape (3 to 7) | PASS |
| ...FromV4Shape (4 to 7) | PASS |
| ...FromV5Shape (5 to 7) | PASS |
| ...FromV6Shape (6 to 7) | FAIL, assertion at test line 151 |
| theDeferredFactoryOpensAnEncryptedDatabaseOffMain | PASS |
| plaintextToEncryptedAndBackKeepsRowsAndSearch | FAIL |

## V6 failure (test bug, not a migration bug)
migratesToV7From asserts at line 151 that `canvas_live_trace` has 0 rows after migration. The v6 test seeds one canvas_live_trace row (publishId 1, fm1) and its own assertAfter expects tracesForFrame("fm1").size == 1. So the generic assertion contradicts the seed: "expected 0 but was 1". The migration preserved the row (correct). Fix: exclude canvas_live_trace from the generic empty check for version 6 (test-only). Not changed.

## plaintextToEncrypted failure (not a v3..v7 migration; cause not yet diagnosed)
SQLiteException "file is not a database (code 26)" when Room opens the file with the key after EncryptionMigrator.prepare converted it (first memoryDao().count()). isPlaintextSqlite was already false. This is the plaintext to encrypted converter path (SqlCipherConverter), independent of Room v7. Unknown whether pre-existing; not checked against an older commit. Candidate causes: converter key/cipher parameters vs Room's SupportOpenHelperFactory(key) settings, or the room(true) open reusing a stale handle. Needs a run of the same test at the previous commit to see if it regressed.

No production code changed. Reports: android-companion/services/memory/build/reports/androidTests/connected/debug/index.html

## Diagnosis update (emulator-5580 again, pinned; emulator stopped by pid after)
- v6 failure: TEST bug, fixed (generic canvas_live_trace-empty check skipped for version 6).
- plaintextToEncrypted, part 1 (code 26 after prepare): TEST bug, fixed. The test passed its shared `key` array into EncryptionMigrator (which zeroes its copy after use, P8 D4) and into SupportOpenHelperFactory/DeferredOpenHelperFactory (which clear the passphrase), so Room then opened with an all-zero key. Production hands out fresh copies. Fixed with key.copyOf() at the four sites. The same test also fails on ctx/int (build 6 source) with the same code 26, so it is not a slice 3a regression.
- plaintextToEncrypted, part 2 (still failing, test line 72, assertFalse(prepare { true }) gets true): PRODUCTION DEFECT in SqlCipherConverter.convert. For encrypted-to-plaintext, the first connection opens the encrypted file with OPEN_READWRITE only (CREATE_IF_NECESSARY only when toEncrypted), but ATTACH DATABASE of the not-yet-existing `<db>.migrating` plaintext file inherits those flags and fails with SQLiteCantOpenDatabaseException (code 14), logged as reason=ATTACH; prepare keeps the encrypted db and sets failed. So "turn encryption off" never works. Introduced by df13ae41 (D-D4 round 3), present on ctx/int. NOT changed in production. Proposed fix (verified on emulator with a throwaway edit, reverted, 8/8 pass): in convert use `val flags = if (first) OPEN_READWRITE or CREATE_IF_NECESSARY else OPEN_READWRITE`; the existing device test already covers it.
- Final run with test-only fixes: 8 tests, 7 passed, 1 failed (plaintextToEncryptedAndBackKeepsRowsAndSearch, at its decrypt step, due to the production defect above).

## Fix applied (emulator-5580, pinned; emulator stopped by pid after)
SqlCipherConverter.convert now opens the first connection with OPEN_READWRITE or CREATE_IF_NECESSARY in both directions (second connection OPEN_READWRITE). Safe: encrypted-to-plaintext source always exists (EncryptionMigrator.prepare returns early when the db file is missing). Result: SqlCipherMigrationDeviceTest 8/8 pass; :services:memory:testDebugUnitTest 422 tests, 0 failures.
