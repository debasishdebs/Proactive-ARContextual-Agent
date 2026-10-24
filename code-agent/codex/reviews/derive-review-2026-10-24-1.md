# Derive step review

Reviewer: Codex gpt-6.1-sol (read-only, medium). Scope: diff 9f5622d5..3373a951 DerivePass, text ports, cadence and kill switch.
Outcome: NOT READY (3 Important); fixed in 88340035, not re-reviewed

- **Important — forgotten/hidden text can reach the prompt:** [DerivePass.kt:96](/Users/<user>/Desktop/rokid/pca-v093/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/DerivePass.kt:96). Input reads are separate suspending queries. After reading one member’s summary/memory, Forget or a review-state change can commit while another member is being read. The cached text still reaches `text.complete`; the guarded write only rejects the eventual result.

- **Important — kill switch is checked only once:** [DerivePass.kt:126](/Users/<user>/Desktop/rokid/pca-v093/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/DerivePass.kt:126). Disable derive during the first model call: the pass still starts subsequent caption/object calls and accepts their writes while disabled.

- **Important — admission permit reused across calls:** [DerivePass.kt:136](/Users/<user>/Desktop/rokid/pca-v093/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/DerivePass.kt:136). One `admit()` authorizes up to 20 field calls. At MODERATE thermal status, this bypasses the shared one-call-per-30-second allowance; later calls also proceed if battery or thermal gates become blocking.

VERDICT NOT READY