package com.rokid.context.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rokid.context.data.db.entity.AuditEventEntity
import com.rokid.context.data.db.entity.MemoryEntity
import com.rokid.context.data.db.entity.QueueJobEntity
import com.rokid.context.data.db.entity.RawFrameEntity
import com.rokid.context.data.db.entity.SummaryEntity

@Database(
    entities = [
        RawFrameEntity::class,
        SummaryEntity::class,
        MemoryEntity::class,
        QueueJobEntity::class,
        AuditEventEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ContextDatabase : RoomDatabase()
