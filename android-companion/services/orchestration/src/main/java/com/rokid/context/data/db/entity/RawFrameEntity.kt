package com.rokid.context.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "raw_frames")
data class RawFrameEntity(
    @PrimaryKey val id: String,
    val capturedAtEpochMs: Long,
    val payload: String,
)
