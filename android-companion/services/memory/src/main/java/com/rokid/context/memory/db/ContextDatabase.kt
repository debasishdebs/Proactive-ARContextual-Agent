package com.rokid.context.memory.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        FrameEntity::class, MemoryEntity::class, MemoryFts::class, MemorySourceEntity::class, ForgetTombstoneEntity::class,
        EmbeddingSpaceEntity::class, MemoryVectorEntity::class, SummaryTraceEntity::class,
        EndpointEntity::class, RoleBindingEntity::class, PromptEntity::class, PromptVersionEntity::class, AskEntity::class,
        NudgeEntity::class, ProposalEntity::class, ActionLogEntity::class, PauseRecordEntity::class, PauseIntervalEntity::class,
        ShoppingItemEntity::class, NoteEntity::class,
    ],
    version = 1,
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
}
