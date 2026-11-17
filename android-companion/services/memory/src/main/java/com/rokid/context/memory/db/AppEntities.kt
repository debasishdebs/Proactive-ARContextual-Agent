package com.rokid.context.memory.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** spec §4.1. [keyRef] points into the KeyVault; the key itself is never stored here. CSV columns hold enum names. */
@Entity(tableName = "endpoint")
data class EndpointEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val apiVersion: String?,
    val modelsCsv: String,
    val keyRef: String,
    val capabilitiesCsv: String,
    val createdAt: Long,
)

/** spec §4.2. One row per role. */
@Entity(tableName = "role_binding")
data class RoleBindingEntity(
    @PrimaryKey val role: String,
    val endpointId: String,
    val model: String,
    val temperature: Double?,
    val maxTokens: Int?,
    val fallbackEndpointId: String?,
    val fallbackModel: String?,
)

/** spec §4.3. [id] = role name or "SYSTEM". */
@Entity(tableName = "prompt")
data class PromptEntity(@PrimaryKey val id: String, val activeVersion: Int)

@Entity(tableName = "prompt_version", primaryKeys = ["promptId", "version"])
data class PromptVersionEntity(
    val promptId: String,
    val version: Int,
    val template: String,
    val outputSchema: String?,
    val createdAt: Long,
    val note: String,
    val testedOk: Boolean,
)

@Entity(tableName = "ask", indices = [Index("ts")])
data class AskEntity(
    @PrimaryKey val id: String,
    val ts: Long,
    val question: String,
    val answer: String,
    val sourcesJson: String,
    val rating: Int?,
    val source: String,
    val redacted: Boolean,
    /**
     * v17 (Forget lifecycle M5, user ruling 2026-11-17): the JSON source states (ids only, see [com.rokid.context.memory.forget.AskSourceStates]): what the answer cited, which earlier answers or nudges it
     * used, and whether a Forget removed a source. Null on every row written before v17 and for developer eval asks. Written always; read only with the OFF-by-default `forgetV2` flag (and by the UI state it sets).
     */
    val sourceStates: String? = null,
)

@Entity(tableName = "nudge", indices = [Index("ts"), Index("hash")])
data class NudgeEntity(
    @PrimaryKey val id: String,
    val ts: Long,
    val text: String,
    val why: String,
    val sourceIdsJson: String,
    val priority: Int,
    val hash: String,
    val shownOn: String,
    val redacted: Boolean,
)

/** spec §5.3. state ∈ PENDING | EXECUTING | EXECUTED | DECLINED | EXPIRED | SUPPRESSED | FAILED. */
@Entity(tableName = "proposal", indices = [Index("state"), Index("originId")])
data class ProposalEntity(
    @PrimaryKey val id: String,
    val origin: String,
    val originId: String,
    val type: String,
    val paramsJson: String,
    val state: String,
    val expiresAt: Long,
    val resolvedBy: String?,
    val createdRef: String?,
    val createdAt: Long,
)

@Entity(tableName = "action_log", indices = [Index("proposalId")])
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val proposalId: String,
    val transition: String,
    val detail: String,
)

@Entity(tableName = "pause_record")
data class PauseRecordEntity(@PrimaryKey val pauseId: String, val origin: String, val createdAtMs: Long, val untilMs: Long?)

@Entity(tableName = "pause_interval", indices = [Index("startMs")])
data class PauseIntervalEntity(@PrimaryKey val pauseId: String, val origin: String, val startMs: Long, val effectiveEndMs: Long?)

@Entity(tableName = "shopping_item")
data class ShoppingItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val qty: Double?,
    val unit: String?,
    val done: Boolean,
    val createdAt: Long,
    val proposalId: String?,
)

@Entity(tableName = "note")
data class NoteEntity(@PrimaryKey val id: String, val text: String, val createdAt: Long)
