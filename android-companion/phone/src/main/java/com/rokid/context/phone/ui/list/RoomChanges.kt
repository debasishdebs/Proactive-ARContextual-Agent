package com.rokid.context.phone.ui.list

import androidx.room.InvalidationTracker
import com.rokid.context.memory.db.ContextDatabase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Room's own public change signal ([InvalidationTracker.Observer]) over [tables], no custom bus (spec §8): emits after every committed write to one of
 * them (a Forget, a retention delete, Delete all, a redaction), so a screen that shows their rows reads them again (Codex r21).
 */
fun ContextDatabase.tableChanges(vararg tables: String): Flow<Unit> = callbackFlow {
    val observer = object : InvalidationTracker.Observer(tables) {
        override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
    }
    invalidationTracker.addObserver(observer)
    awaitClose { invalidationTracker.removeObserver(observer) }
}
