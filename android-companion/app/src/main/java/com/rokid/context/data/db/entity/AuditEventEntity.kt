package com.rokid.context.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_events")
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val eventType: String,
    val details: String,
    val createdAtEpochMs: Long,
)
