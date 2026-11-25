package com.rokid.context.memory.forget

/**
 * The deletion inventory (slice 3a, S38, S58; Forget lifecycle M1): EVERY table of `context.db` and `places.db`, and every canvas/mark/privacy preference file and folder, with what Delete all does to it.
 * A new table, prefs file or folder that is not listed here fails `DeletionGraphInventoryTest` (tables, read from Room's exported schema) and `DeletionInventoryFilesTest`
 * (prefs and folders, via a source and backup-rules scan), so a later task cannot add an artefact and forget to delete it. Who OWNS each table is `lifecycle.Families` (`FamilyInventoryTest`).
 * Names only: this holds no data.
 */
object DeletionGraph {
    enum class Fate {
        /** Removed by Delete all (rows or files), and by the Forget cascades where the data is tied to a photo or a time range. */
        WIPED,
        /** Survives Delete all on purpose: it holds no user data (hashes, states, prompts, settings, the fence itself). The reason is the contract. */
        KEPT,
        /** Cleared in part by Delete all: rows deleted or their content blanked, the table stays (open proposals, the fetched places). The reason says which. */
        SCRUBBED,
        /** Follows its base table through database triggers: no statement of its own (the FTS index of `memory`). */
        FOLLOWS,
    }

    data class Entry(val name: String, val fate: Fate, val why: String)

    private fun w(n: String, why: String) = Entry(n, Fate.WIPED, why)
    private fun k(n: String, why: String) = Entry(n, Fate.KEPT, why)
    private fun s(n: String, why: String) = Entry(n, Fate.SCRUBBED, why)
    private fun f(n: String, why: String) = Entry(n, Fate.FOLLOWS, why)

    /**
     * In-memory DERIVED items (never stored, so not tables, prefs or folders): vectors computed from a photo the user can Forget. Each is emptied at once by Delete all (`MemoryWipe.wipeNow`), every Forget listener
     * event (a photo, a range, any user Forget, retention expiry), every pin link change and the shadow flag going off, through the orchestration `ShadowCaches` registry; a test pins these names to it.
     */
    val IN_MEMORY: List<Entry> = listOf(
        w("place_shadow.pin_reference_vectors", "in-memory image vectors of user pins' reference Moment photos (PinReferenceVectors, 1.4.4 B1); max 16, never persisted, dropped with the process; revalidated on every use against the pin link and the photo's tombstone"),
        w("place_shadow.pack_text_vectors", "in-memory text vectors of pack place names (PlaceShadow cache, public text, max 64); not photo-derived, cleared with the others for simplicity"),
        w("place_shadow.pack_moment_counts", "in-memory COUNTS of pack and pin candidates the shadow saw (PackMomentStats, 1.4.6, flag packMoments); four integers, no id, name, text or photo; reset by every Forget and Delete all"),
    )

    val TABLES: List<Entry> = listOf(
        w("canvas_trace", "IMU and hub traces (motion history); a Forget range tombstones them"),
        w("canvas_trace_event", "hub trace events"),
        w("canvas_replay", "replay results derived from traces and labels"),
        w("canvas_sweep", "sweep results derived from traces and labels"),
        w("canvas_label", "marks and intent labels (times, sources and enum names; no text); since v15 `frameId` links a mark to ITS photo (Forget photo deletes the label with it) and `photo` is a fixed state/reason token"),
        w("canvas_calibration", "measured-run calibration artifacts"),
        w("canvas_helper_job", "laptop-helper jobs; they carry photo ids"),
        k("canvas_helper_cleanup", "opaque job ids and reason codes only, no user data; must survive Delete all so the laptop copies are still deleted"),
        w("canvas_run", "photo experiment runs"), w("canvas_run_item", "per-photo run outputs"), w("canvas_score", "per-photo scores"),
        w("canvas_live_trace", "per-photo live outputs"),
        k("canvas_meta", "the monotonic labelRev counter (bumped by the wipe so no earlier result reads as current); the forgetRanges fence value is emptied by the wipe; `canvas_save:<runId>` markers (v0.9.6: a run id and a count, no content; they make a canvas Save idempotent) are deleted with their run and by the wipe; `mark_undone:<labelId>` markers (v0.9.6 slice C: an id, they refuse a late photo of an undone mark) are deleted by the wipe; `mark_claim:<labelId>` (the one frame id that may pass a pause for that mark; deleted with the mark by undo, Forget and the wipe); `mark_cleanup_owed:<labelId>` (a frame id: an undone mark's photo Forget committed with a cleanup notice not yet handed over; deleted when the undo completes, pruned once its frame has no row and no file or cached export is left (MarkRecorder cleanup check and the daily retention pass) and deleted by the wipe); `rx:f:<frameId>` and `rx:t:<capture time>:<frameId>` (retention v3: the expired-photo marker, ids and times only; written with a photo expiry, cleared by every user Forget of that photo, pruned with the tombstone and deleted by the wipe, which also deletes any `lin:` key of older builds); otherwise no user data"),
        k("canvas_group_state", "publish-group recovery record: gen, hashes, mode, install id and state; needed to recover a deployed plan; no user data"),
        w("moment", "stored Moment projection (a rebuildable read model over frames, memories and marks); since v14 its `text` column holds the visible memory text of its photos (dropped with the Moment by every Forget that removes a source memory, rewritten by the rebuild), `transcript` is a null scaffold and `errorReason` a content-free code"),
        w("moment_part", "Moment membership edges (ids and revisions only)"),
        w("moment_tombstone", "forgotten seconds; the deletion epoch is the fence after Delete all, as with the frame tombstones"),
        w("moment_trace_dep", "reserved trace dependencies of a Moment; empty until a trace writer exists"),
        w("place_card_log", "phone-only place card history; user Forget of the photo, any user range (also a photo's radius) and Delete all delete its rows in the same transaction; photo retention sets photoGone and keeps frameId; pin removal deletes the pin's rows before the pin store is written; 90 days / 500 rows"),
        w("moment_location", "optional Moment location (phone fix or media backfill); user Forget of the photo, the mark, any user span, the Moment and Delete all delete it in the same transaction; retention, sweep, purge and Moment rebuilds keep it"),
        w("moment_sensor", "per-second sensor features of a Moment (numbers, state names and a source trace id; no raw samples); deleted with the Moment, with its trace, by every Forget that selects by time, and by Delete all"),
        w("object_audit", "append-only audit of Moment-family objects: ids, op names, times and step names only, no content; deleted with its object and by Delete all"),
        w("object_derived", "derived SUMMARY, CAPTION and EMBEDDING fields of Moment-family objects; deleted with the object and by Delete all"),
        w("scene", "stored Scenes (a rebuildable grouping of Moments: window, boundary rule, activity, revisions; no text, no photo data)"),
        w("scene_part", "Scene membership edges: references to Moments (ids and revisions only); also removed one by one when a Moment is forgotten"),
        k("cleanup_outbox", "ids, paths and ranges only, no user content; unacknowledged rows survive Delete all so glasses, laptop and phone-file cleanup still happens; only acknowledged rows are removed"),
        k("canvas_pipeline", "pipelines are user-authored prompts and settings"), k("canvas_pipeline_version", "frozen pipeline versions: prompts"),
        k("canvas_publish", "publish records: hashes and snapshots of prompts, no photo data"),
        // Forget lifecycle M1: the tables the canvas inventory never listed (what `WipeDao.wipeMemory` has always done to them).
        w("frame", "the photo row and its JPEG path; Forget photo and retention delete the row, Delete all deletes every row and the files"),
        w("frame_meta", "capture metadata of a photo (times, wire id, motion): deleted with the photo"),
        w("frame_vector", "the photo's image vector: deleted with the photo"),
        w("frame_state", "per-photo pipeline state: deleted with the photo"),
        w("frame_source", "memory-to-photo edges (FACT, CARRIED), including a Place Guide memory's FACT row: deleted with the memory or the photo"),
        w("frame_cluster", "scene clusters of photos (ids and states; `repFrameIds` names photos)"),
        w("forget_tombstone", "the extraction lock of a forgotten photo (a frame id and a time); Delete all clears it, the deletion epoch is the new fence"),
        w("memory", "facts, captions and summaries, and the Place Guide memories (type `place`): the user's data, deleted by every Forget of it and by Delete all"),
        f("memory_fts", "the full-text index of `memory`: its triggers delete the rows with the memory; no statement of its own"),
        w("memory_source", "derivation edges between memories (ids only)"),
        w("memory_vector", "text vectors of memories, in every embedding space"),
        w("embedding_space", "the embedding spaces and their states (model ids and fingerprints); rebuilt after a wipe"),
        w("summary_trace", "trace notes of the summariser: ids and counts, deleted by Delete all"),
        w("ask", "saved questions and answers: Forget redacts the answer of an ask whose sources were forgotten; Delete all deletes every row"),
        w("nudge", "saved nudges: Forget redacts a nudge whose sources were forgotten; Delete all deletes every row"),
        s("proposal", "every proposal came from an ask or a nudge: Delete all deletes the open ones and replaces the params of the rest with the redaction marker"),
        s("action_log", "action details are emptied by Delete all and by the Forget that redacts their proposal"),
        k("endpoint", "model endpoints (the key itself is in the key vault): settings, no user content"),
        k("role_binding", "which model serves which role: settings"),
        k("prompt", "active prompt versions: settings"),
        k("prompt_version", "frozen prompt versions: user-authored prompts"),
        k("pause_record", "pauses the user asked for (origin, times): settings and history of switches, no photo or memory content"),
        k("pause_interval", "the effective pause intervals: times and origin names only"),
        k("shopping_item", "the user's shopping list: a user list, not memory"),
        k("note", "the user's notes: a user list, not memory"),
        // places.db (Place Guide, a separate encrypted database): public place text fetched from Wikipedia, Wikidata and OpenStreetMap. NOT derived from a photo; no Forget reaches it.
        s("place", "fetched places (public text) are deleted by Delete all and their recency reset; seeded areas, what they seeded and pinned places stay (`PlacePack.deleteAll`)"),
        s("cell", "fetched 5 km cells: Delete all removes the cells that no seeded area covers"),
        k("area", "seeded download areas (a bounding box and a state): the user's own download choice; Delete all keeps them"),
    )

    /** Phone SharedPreferences names (without `.xml`). */
    val PREFS: List<Entry> = listOf(
        w("canvas_plan_state", "the publish-group and plan state sent to the glasses; cleared so a stale plan is never re-sent (only the generation counter stays: not personal data, and the glasses keep theirs)"),
        w("canvas_collect", "the Collect-for-tuning session window"),
        w("canvas_helper_grants", "the laptop helper's capability grants (py_exec, default off): a wipe turns every grant off, so Delete all never leaves the helper allowed"),
        w("signal_config", "USER CONFIG (v0.9.7): which catalogue signals guards may read, the user's derived-signal expressions and a history of 16 earlier configurations (replay of frozen versions). Holds no memory content, so a Forget of a fact, photo or range does not touch it; Delete all removes it (prefs file and the in-memory store through a ForgetListener). Excluded from backup and transfer"),
        w("export_cleanup", "a boolean and a counter: a cached memory export could not be deleted by a Forget and its retry is due (no user data; Delete all empties the export folder itself and clears the mark)"),
        w("proactive_settings", "v3.0 Suggestions switches (master, place recall, replay flag), all OFF by default: a setting, but per-device consent, so Delete all resets it to OFF (ruling 2026-11-18 #6). Excluded from backup and transfer"),
        w("proactive_shadow", "v3.0 shadow-mode store (ENCRYPTED prefs): would-show suggestions as source ids and fixed tokens (no text), the never-suggest list, counters. Place keys and ids are sensitive: a Forget withdraws records whose sources are gone, Delete all clears everything including the never list (ruling #6). Excluded from backup and transfer"),
        w("proactive_dismiss_marker", "v3.1 r3: one timestamp (plain prefs, no ids, no text): a suggestion dismissal whose ledger write failed or was in flight; a new process pauses delivery for 24 h from it. Delete all clears it. Excluded from backup and transfer"),
        w("online_map", "v1.0.1 opt-in street map: the switch and whether the first-time explanation was accepted (OFF by default; per-device consent). Delete all clears the file (also on the resume of an interrupted wipe) and resets the in-memory switch through a ForgetListener. Excluded from backup and transfer"),
        k("deletion_epoch", "the persisted deletion epoch (install id, counter, wipedAtMs): it IS the fence and holds no user data"),
        k("mark_settings", "reserved: mark toggles (settings, not memory); not written yet"),
        k("pause_zones", "pause zones (the user's own places, radii and switches): a setting; NEVER deleted by a Forget or Delete all, and exported with the settings"),
        k("pause_zone_state", "the latched zone state, the last clear-fix time and the clock-rollback total of THIS phone: a setting, excluded from backup; never deleted by a Forget or Delete all"),
        k("place_pins", "the user's pinned places (names, notes, positions: their own words): user configuration; never deleted by a Forget or Delete all except through its reference Moment (below), carried only by the explicit settings export and EXCLUDED from backup, cloud backup and device transfer (exact positions and private notes; unlike pause_zones, which stays in backup; places.db, their copy, is excluded too and rebuilt from this list). Slice 1.4.2: a pin may hold an OPTIONAL reference Moment link (a Moment id). An EXPLICIT user Forget of that Moment (single, scoped, multi-select, range) and Delete all (also its crash resume) DELETE THE PIN with it (PinMomentLinks: armed before, cascaded after; the confirmation names the pins); a pin is NEVER deleted by an expiry or sweep (retention, suspect-photo sweep): only its link is cleared. The settings export never carries the link"),
        k("place_guide", "the Place Guide switch (default off): a setting; Delete all leaves it, and a published card is withdrawn through the ForgetListener instead"),
        k("data_saver", "roaming data saver (slice 1 and 2): the mode (Wi-Fi and home mobile data by default), the \"I'm abroad\" switch, the small-requests-on-roaming switch, the user's optional daily limit (off by default; two numbers), the Wi-Fi-as-metered-abroad switch, today's skipped-call counts and today's mobile-data byte counters (bytes per kind and a photo count, per local day: numbers only, no ids, no text, no host, no country, no network name): settings and counters that protect the user's mobile data, so Delete all leaves them (a wipe must never silently re-open uploads or reset today's limit). Excluded from backup and transfer"),
        k("ai_backend", "Model privacy (slice 1.4.3): Automatic (default) or Offline only (never call a remote model: only this phone and the user's own network). One setting, no ids, no text: a privacy choice, so Delete all leaves it (a wipe must never silently re-open remote calls; an unreadable value reads as Offline only). Excluded from backup and transfer"),
        k("ai_backend_init", "Model privacy sentinel (slice 1.4.3): two values: a boolean 'a mode was chosen at least once' and last_choice (the name of the mode last chosen: AUTO or OFFLINE_ONLY), so an emptied or corrupted ai_backend.xml reads as Offline only (and the screen says the settings were reset) instead of silently becoming Automatic. No ids, no text: a privacy setting, so Delete all leaves it. Excluded from backup and transfer"),
        k("nexus_host", "the HUD host choice (PCA glasses app by default, Rokid Nexus opt-in) and nothing else: a setting; Delete all leaves it, and whatever the Nexus bridge shows is withdrawn through the ForgetListener instead. Excluded from backup and transfer"),
    )

    /** The GLASSES app's prefs (a different device: Delete all reaches them through the new epoch in `pc_trace_ctl`, not by this process). */
    val GLASSES_PREFS: List<Entry> = listOf(
        w("mark_queue", "unsent pc_mark outbox; MarkOutbox.onEpoch clears it when the phone's installId or deletion counter changes"),
    )

    /** Files and folders of the GLASSES recorder under its `imu` root (names only; the glasses delete them on a new epoch or a forget request). */
    val GLASSES_FILES: List<Entry> = listOf(
        w("imu", "the glasses recorder's root folder (holds the rest)"),
        w("traces", "closed IMU traces; deleted by an epoch wipe and by a forget range or tombstone"),
        w("imu_trace_tmp", "a trace being written; deleted by an epoch wipe, a forget, and at start-up"),
        k("trace_epoch", "the last deletion epoch seen (install id and counter): the fence itself, no user data; written only after a wipe succeeded"),
        k("trace_epoch.part", "its atomic-write temp file"),
        w("trace_forget", "pending forget requests (ranges and ids); removed once carried out"),
        w("trace_forget.part", "its atomic-write temp file"),
        k("trace_cleanup_pending", "the received-cleanup record (LGC8-min): wipe flag, target epoch and forget ids/ranges, no user data; kept until the cleanup it describes succeeded, replayed at start before recovery"),
        k("trace_cleanup_pending.part", "its atomic-write temp file"),
    )

    /** Folders under `filesDir`. */
    val DIRS: List<Entry> = listOf(
        w("canvas_traces", "sealed IMU traces"), w("canvas_import", "pulled trace imports"), w("canvas_tmp", "tmp files of an unpublished write"),
    )

    /** Folders under the app's CACHE dir (`cacheDir`): the street map's downloaded tiles (tile numbers reveal the areas the user looked at). Delete all removes them, and so does its resume. */
    val CACHE_DIRS: List<Entry> = listOf(
        w("online_tiles", "opt-in street map tile cache (at most 50 MB, never backed up); also deleted when the switch goes off"),
    )

    /** Folders under the app's EXTERNAL files dir (`getExternalFilesDir(null)`): adb-pushed staging of raw traces; cleared by Delete all and excluded from backup and device transfer. */
    val EXTERNAL_DIRS: List<Entry> = listOf(
        w("trace-staging", "dev-mode trace import staging (raw sensor traces pushed over adb); cleared by Delete all and its resume, excluded from backup and transfer"),
    )

    val wipedExternalDirs get() = EXTERNAL_DIRS.filter { it.fate == Fate.WIPED }.map { it.name }
    val wipedTables get() = TABLES.filter { it.fate == Fate.WIPED }.map { it.name }
    val wipedPrefs get() = PREFS.filter { it.fate == Fate.WIPED }.map { it.name }
    val wipedCacheDirs get() = CACHE_DIRS.filter { it.fate == Fate.WIPED }.map { it.name }
    val wipedDirs get() = DIRS.filter { it.fate == Fate.WIPED }.map { it.name }
}
