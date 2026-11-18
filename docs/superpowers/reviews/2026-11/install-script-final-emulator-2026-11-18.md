# install-trip-build.sh final emulator healthy-install check (2026-11-18)

Script commit b7b36949, branch feat/v101-ask-eval, emulator-5554 only (MyEmulator headless), v1.1.0 APKs from backups/apks-v110-f1dcf1fb, flags `--self-test --go --allow-emulator`.

| Run | Result | Exit code | Notes |
|---|---|---|---|
| 1 | BENIGN-ONLY | 0 | pid 4180 stable, 0 real errors, 10 allow-listed (sqlcipher_mlock / Long monitor contention) |
| 2 | BENIGN-ONLY | 0 | pid 4463 stable, 0 real errors, 14 allow-listed |
| 3 | BENIGN-ONLY | 0 | pid 4771 stable, 0 real errors, 12 allow-listed |
| 4 (force-stop + logcat -c, then cold-start install) | BENIGN-ONLY | 0 | pid 5086, 0 real errors, 15 allow-listed |

- Self-test fixtures (including the chatty cases) passed in every run.
- No real "chatty" app line appeared in any scan window; the only "chatty" strings in the output are the self-test fixture names.
- The clock-discontinuity and per-buffer FIFO checks did not trigger UNVERIFIED on any healthy run.
- No script change was needed (no false positive found). Variance: only the allow-listed noise count (10-15).
- Heavy log noise could not be generated from the app uid; step 3 was covered by the cold-start run 4.
