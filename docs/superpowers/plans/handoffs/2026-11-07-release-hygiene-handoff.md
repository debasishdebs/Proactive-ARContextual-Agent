# Release hygiene handoff (2026-11-07, branch feat/v099-watch)

- .gitignore: added *.jks, *.keystore, *.p12, *.pem, *.aab, *.idsig, keystore.properties, signing/, .env* (local.properties, *.apk already there). Nothing was `git rm`ed.
- Tracked files that match the ignore rules (left in place, decide separately): `.superpowers/sdd/**` (9 files, ignored already but tracked) and test fixtures `android-companion/phone/src/test/resources/canvas-tls-test.p12`, `canvas-tls-other.p12` (test-only TLS certificates; check they hold no real key).
- README.md rewritten for the current system (under 120 lines).
- Backup: `export_cleanup` (MemoryExport.CLEANUP_PREFS) excluded in phone backup_rules.xml and in both sections of data_extraction_rules.xml; BackupRulesTest regex now matches `[A-Z_]*PREFS` constants and rules on it. :watch got `backup_rules.xml` and `data_extraction_rules.xml` (exclude everything) wired in its manifest, and a test in WatchManifestPolicyTest.
- Release notes: pca-v0.9.5 to v0.9.9-beta and pca-v1.0.0-rc1 (DRAFT) in code-agent/releases, from the handoffs and git log, attributed by feature.
- Tests: `:phone:testDebugUnitTest --tests '*BackupRulesTest*' :watch:testDebugUnitTest` green.
