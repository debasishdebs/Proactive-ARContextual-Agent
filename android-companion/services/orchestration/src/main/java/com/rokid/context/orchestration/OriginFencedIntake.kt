package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.contract.ProposedAction
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.connectors.actions.ProposalNotifications
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.forget.ForgetService

/**
 * Origin fence for proposals (P7xP8 I2). The ask/nudge row commits before its proposal is stored, so a Delete all
 * (row deleted) or a Forget (row redacted) can land in between; neither sees a proposal that does not exist yet. After
 * [delegate] stores one, its origin is re-read in a transaction: when it is gone or redacted the proposal gets
 * Forget's redaction (PENDING expires, params become [ForgetService.REDACTED_PARAMS], log details emptied) and its
 * alarm and notifications are cancelled. Either order is covered: a wipe/Forget committed after this check finds the
 * proposal itself. Never throws (the [ProposalIntake] contract); cancellation propagates.
 */
class OriginFencedIntake(private val context: Context, private val memory: MemoryGraph, private val delegate: ProposalIntake) : ProposalIntake {
    override suspend fun onProposed(origin: ProposalOrigin, originId: String, action: ProposedAction, nowMs: Long): String? {
        val id = delegate.onProposed(origin, originId, action, nowMs) ?: return null
        val dead = try {
            memory.db.withTransaction {
                val dao = memory.db.actionDao()
                val live = when (origin) {
                    ProposalOrigin.ASK -> dao.askLive(originId)
                    ProposalOrigin.NUDGE -> dao.nudgeLive(originId)
                }
                if (!live) {
                    memory.proposals.expireForOrigins(listOf(originId), nowMs)
                    dao.scrubLogsForOrigins(listOf(originId))
                    dao.scrubParamsForOrigins(listOf(originId), ForgetService.REDACTED_PARAMS)
                }
                !live
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "origin check failed: ${SafeLog.describe(e)}")
            false
        }
        if (dead) {
            runCatching { ReminderExecutor.cancelAlarm(context, id) }
            runCatching { ProposalNotifications.cancel(context, listOf(id)) }
        }
        return id
    }

    private companion object { const val TAG = "OriginFencedIntake" }
}
