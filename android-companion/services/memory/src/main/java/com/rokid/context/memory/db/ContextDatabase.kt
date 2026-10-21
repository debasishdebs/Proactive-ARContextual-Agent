package com.rokid.context.memory.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FrameEntity::class, MemoryEntity::class, MemoryFts::class, MemorySourceEntity::class, ForgetTombstoneEntity::class,
        EmbeddingSpaceEntity::class, MemoryVectorEntity::class, SummaryTraceEntity::class,
        EndpointEntity::class, RoleBindingEntity::class, PromptEntity::class, PromptVersionEntity::class, AskEntity::class,
        NudgeEntity::class, ProposalEntity::class, ActionLogEntity::class, PauseRecordEntity::class, PauseIntervalEntity::class,
        ShoppingItemEntity::class, NoteEntity::class,
        FrameMetaEntity::class, FrameStateEntity::class, FrameSourceEntity::class, FrameClusterEntity::class, FrameVectorEntity::class,
        CanvasPipelineEntity::class, CanvasPipelineVersionEntity::class, CanvasRunEntity::class, CanvasRunItemEntity::class, CanvasScoreEntity::class, CanvasPublishEntity::class, CanvasLiveTraceEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class ContextDatabase : RoomDatabase() {
    abstract fun frameDao(): FrameDao
    abstract fun memoryDao(): MemoryDao
    abstract fun traceDao(): TraceDao
    abstract fun endpointDao(): EndpointDao
    abstract fun roleBindingDao(): RoleBindingDao
    abstract fun pauseDao(): PauseDao
    abstract fun frameViewDao(): FrameViewDao
    abstract fun memoryViewDao(): MemoryViewDao
    abstract fun traceViewDao(): TraceViewDao
    abstract fun askDao(): AskDao
    abstract fun nudgeDao(): NudgeDao
    abstract fun proposalDao(): ProposalDao
    abstract fun promptDao(): PromptDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun retrievalDao(): RetrievalDao
    abstract fun wipeDao(): WipeDao
    abstract fun exportDao(): ExportDao
    abstract fun actionDao(): ActionDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun noteDao(): NoteDao
    abstract fun forgetDao(): ForgetDao
    abstract fun nudgeGateDao(): NudgeGateDao
    abstract fun frameStateDao(): FrameStateDao
    abstract fun frameMetaDao(): FrameMetaDao
    abstract fun frameSourceDao(): FrameSourceDao
    abstract fun frameVectorDao(): FrameVectorDao
    abstract fun frameClusterDao(): FrameClusterDao
    abstract fun frameAdoptionDao(): FrameAdoptionDao
    abstract fun canvasDao(): CanvasDao
}

/**
 * v1 → v2 (keyframe capture wave 1): only adds the five frame tables, with the statements Room generated for `2.json`.
 * No existing table or column changes; `memory_source` (memory → memory) is untouched.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `frame_meta` (`frameId` TEXT NOT NULL, `wireFrameId` TEXT, `sessionId` TEXT, `motion` TEXT NOT NULL, `dwell` INTEGER NOT NULL, `blur` REAL, `dhash` TEXT, `phash` TEXT, `width` INTEGER, `salience` REAL, `salienceTerms` TEXT, `clusterId` TEXT, `nearDupOf` TEXT, `imageVectorSpace` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`frameId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_frame_meta_wireFrameId` ON `frame_meta` (`wireFrameId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `frame_state` (`frameId` TEXT NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT, `claimedAt` INTEGER, `claimId` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`frameId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_frame_state_state` ON `frame_state` (`state`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `frame_source` (`memoryId` TEXT NOT NULL, `frameId` TEXT NOT NULL, `clusterId` TEXT, `role` TEXT NOT NULL, PRIMARY KEY(`memoryId`, `frameId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_frame_source_frameId` ON `frame_source` (`frameId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `frame_cluster` (`clusterId` TEXT NOT NULL, `openedAt` INTEGER NOT NULL, `closedAt` INTEGER, `closeReason` TEXT, `repFrameIds` TEXT NOT NULL, `memoryId` TEXT, PRIMARY KEY(`clusterId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `frame_vector` (`frameId` TEXT NOT NULL, `spaceId` TEXT NOT NULL, `blob` BLOB NOT NULL, PRIMARY KEY(`frameId`, `spaceId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_frame_vector_spaceId` ON `frame_vector` (`spaceId`)")
    }
}

/** v2 → v3 (experiment canvas): only adds the six canvas tables, with the statements Room generated for `3.json`. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `canvas_pipeline` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `draftJson` TEXT NOT NULL, `draftRev` INTEGER NOT NULL, `builtIn` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `canvas_pipeline_version` (`pipelineId` TEXT NOT NULL, `version` INTEGER NOT NULL, `json` TEXT NOT NULL, `hash` TEXT NOT NULL, `note` TEXT NOT NULL, `frozenAt` INTEGER NOT NULL, PRIMARY KEY(`pipelineId`, `version`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `canvas_run` (`runId` TEXT NOT NULL, `pipelineId` TEXT NOT NULL, `version` INTEGER, `pipelineHash` TEXT NOT NULL, `pipelineJson` TEXT NOT NULL, `status` TEXT NOT NULL, `frameCount` INTEGER NOT NULL, `estCalls` INTEGER NOT NULL, `calls` INTEGER NOT NULL, `failures` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `modelsJson` TEXT NOT NULL, PRIMARY KEY(`runId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canvas_run_pipelineId` ON `canvas_run` (`pipelineId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canvas_run_startedAt` ON `canvas_run` (`startedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `canvas_run_item` (`runId` TEXT NOT NULL, `frameId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `status` TEXT NOT NULL, `branch` TEXT, `outputJson` TEXT, `error` TEXT, `latencyMs` INTEGER, `promptTokens` INTEGER, `completionTokens` INTEGER, `model` TEXT, `promptVersion` INTEGER, `batchId` TEXT, PRIMARY KEY(`runId`, `frameId`, `nodeId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canvas_run_item_frameId` ON `canvas_run_item` (`frameId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `canvas_score` (`runId` TEXT NOT NULL, `frameId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `score` INTEGER NOT NULL, `note` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`runId`, `frameId`, `nodeId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canvas_score_frameId` ON `canvas_score` (`frameId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `canvas_publish` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pipelineId` TEXT NOT NULL, `version` INTEGER NOT NULL, `hash` TEXT NOT NULL, `publishedAt` INTEGER NOT NULL, `rolledBackAt` INTEGER, `snapshotJson` TEXT NOT NULL)")
    }
}

/**
 * v3 → v4 (wave 2, scene grouping): additive only. Nine claim/state columns on `frame_cluster`, `frame_meta.routedAt`, two indices.
 * Existing clusters get a state from their `closedAt`. No table is created or dropped (the canvas tables are untouched).
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `frame_cluster` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'OPEN'")
        db.execSQL("ALTER TABLE `frame_cluster` ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `frame_cluster` ADD COLUMN `claimId` TEXT")
        db.execSQL("ALTER TABLE `frame_cluster` ADD COLUMN `claimedAt` INTEGER")
        db.execSQL("ALTER TABLE `frame_cluster` ADD COLUMN `lastError` TEXT")
        db.execSQL("ALTER TABLE `frame_cluster` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `frame_cluster` ADD COLUMN `motionClass` TEXT")
        db.execSQL("ALTER TABLE `frame_cluster` ADD COLUMN `memberCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `frame_cluster` ADD COLUMN `lastTs` INTEGER")
        db.execSQL("UPDATE `frame_cluster` SET `state` = CASE WHEN `closedAt` IS NULL THEN 'OPEN' ELSE 'CLOSED' END")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_frame_cluster_state` ON `frame_cluster` (`state`)")
        db.execSQL("ALTER TABLE `frame_meta` ADD COLUMN `routedAt` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_frame_meta_clusterId` ON `frame_meta` (`clusterId`)")
    }
}

/**
 * v4 → v5 (wave B image intent + wave 3 capture time): four nullable columns on `frame_meta`, additive only. No table is created,
 * dropped or rewritten, and no index changes.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `frame_meta` ADD COLUMN `intentLabel` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `frame_meta` ADD COLUMN `intentConf` REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE `frame_meta` ADD COLUMN `intentAt` INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE `frame_meta` ADD COLUMN `capturedAtMs` INTEGER DEFAULT NULL")
    }
}

/**
 * v5 → v6 (canvas slice 2): additive only. Three columns on `canvas_publish`, one on `canvas_run`, one on `canvas_run_item`, and the new
 * `canvas_live_trace` table with its three indices. No existing column is changed, no table dropped or rewritten, nothing is copied. The
 * statements are the ones Room generated for `6.json`. Existing publishes read `kind = 'prompts'`.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `canvas_publish` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'prompts'")
        db.execSQL("ALTER TABLE `canvas_publish` ADD COLUMN `mode` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `canvas_publish` ADD COLUMN `rollbackReason` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `canvas_run` ADD COLUMN `abGroup` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `canvas_run_item` ADD COLUMN `calls` INTEGER DEFAULT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS `canvas_live_trace` (`publishId` INTEGER NOT NULL, `frameId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `mode` TEXT NOT NULL, `status` TEXT NOT NULL, `branch` TEXT, `outputJson` TEXT, `error` TEXT, `batchId` TEXT, `at` INTEGER NOT NULL, PRIMARY KEY(`publishId`, `frameId`, `nodeId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canvas_live_trace_frameId` ON `canvas_live_trace` (`frameId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canvas_live_trace_batchId` ON `canvas_live_trace` (`batchId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canvas_live_trace_at` ON `canvas_live_trace` (`at`)")
    }
}
