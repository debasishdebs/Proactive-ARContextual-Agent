package com.rokid.context.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey val id: String,
    val rawFrameId: String,
    val summaryText: String,
    val createdAtEpochMs: Long,
)
