VERDICT: NOT READY

1. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/dumpimport/ImportStores.kt:44 - Delete all can finish between the importer’s generation check and this fresh generation capture; the resumed import then stores a photo after the wipe - Pass the original request generation through every writer and enforce it inside the gated commit.

2. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/dumpimport/ImportCore.kt:495 - A paired import can persist coordinates inside a protected zone, including while BLOCKED_INSIDE; only the location switch and deletion fences are checked - Enforce the zone state and geometry at commit unless an explicit owner-approved exemption exists.

3. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/dumpimport/ImportStores.kt:55 - If live ingestion commits the same frame ID after import preflight, import overwrites its archive file, receives DUPLICATE, then deletes that live photo - Reserve the ID and use exclusive file creation; delete only files owned by this import.

4. [P1] android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/place/PlaceGuideSettings.kt:429 - S3 reports success for committed cleanup-failure outcomes, hiding that forgotten photos or cached exports remain - Preserve and display the typed cleanup/pin-pending notices.

5. [P1] tools/dev/sign_with_rotation.sh:8 - Sourcing Java properties as shell code misinterprets passwords containing spaces or metacharacters; a synthetic whitespace-password check printed a password fragment in stderr - Parse properties as data and supply passwords through environment variables without shell evaluation.

6. [P2] tools/push_import.py:181 - On a clean restore, Moment cards referencing user pins are processed before those pins exist and skipped; subsequent dedup imports never revisit those cards - Add a deferred card-restoration phase after pins and allow it for existing Moments.

7. [P2] tools/install-trip-build.sh:237 - Signer-set intersection can approve divergent rotations: installed A→B versus candidate A→C passes through ancestor A although B never authorized C - Compare the device-effective current signer against an ordered, verified lineage.

Recommended device checks:

- Restore a disposable dump containing pins, cards, marks and Scenes; verify links, repeat-import behavior, and recovery after a partial failure.
- Interrupt import with Forget and Delete all; assert zero residual rows/files. The current mid-import test checks `stopped` without checking residue.
- Exercise S3 with changed previews and forced cleanup failures; verify unrelated data survives and pending cleanup remains visible.
- Check Centre on me with permission denied/granted, offline GPS, Cancel and screen exit; inspect persistence and logs.
- Exercise sequential seeds, 429/network failures, retry exhaustion, process death and FAILED recovery.
- Rehearse valid rotation and divergent-lineage rejection on an emulator, verifying encrypted-data readability and zero uninstall operations.

Read-only review of `ea0fb49e..295be471`. Shell syntax, Python parsing and diff whitespace checks passed; no Gradle or device commands ran.