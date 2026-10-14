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
    ],
    version = 2,
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
