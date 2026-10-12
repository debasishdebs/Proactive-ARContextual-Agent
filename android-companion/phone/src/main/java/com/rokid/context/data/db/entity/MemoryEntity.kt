package com.rokid.context.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val key: String,
    val value: String,
    val schemaVersion: Int,
    val updatedAtEpochMs: Long,
)
