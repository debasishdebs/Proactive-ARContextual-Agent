# Codex review round 6 (aadf52a2, 9736356d, 9a4cd517, gpt-6.1-sol, medium)

VERDICT: NOT READY

1. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/dumpimport/ImportCore.kt:408 - A card-only resend can pass the existing-Moment check, then restore a card after that Moment is forgotten: cards with `frameId=null` bypass frame tombstones, and `ImportCardStore` captures fresh authority after the Forget. - Check parent Moment existence and Forget fences inside the card-insert transaction.

2. [P2] android-companion/services/orchestration/src/main/java/com/rokid/context/data/frames/RokidFrameInbox.kt:66 - `writeStamped` gives a fresh `.imp-*` temp the original capture mtime; importing a photo older than an hour makes its active temp immediately eligible for concurrent sweeping, causing publication to fail. - Determine temp age from creation time encoded in its name and exclude active writes.
