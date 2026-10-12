package com.rokid.context.glasses.pause

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The glasses' persisted pause set (spec §5.5: DataStore on the glasses). Fail-closed: an unreadable file or bad JSON
 * reads as one indefinite pause ([UNREADABLE_ID]) the wearer can resume; the corrupt file is replaced on the next write.
 * [file] must end in `.preferences_pb`; only one store per file may be active.
 */
class PauseStore(file: File, scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private val store = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(KEY to encode(listOf(unreadable(clock())))) },
        scope = scope,
        produceFile = { file },
    )

    suspend fun read(): List<PauseRecord> {
        val text = store.data.first()[KEY] ?: return emptyList()
        return runCatching { Wire.json.decodeFromString(SERIALIZER, text) }.getOrElse { listOf(unreadable(clock())) }
    }

    suspend fun write(records: List<PauseRecord>) { store.edit { it[KEY] = encode(records) } }

    companion object {
        const val UNREADABLE_ID = "unreadable-store"
        private val KEY = stringPreferencesKey("pauses")
        private val SERIALIZER = ListSerializer(PauseRecord.serializer())
        private fun encode(records: List<PauseRecord>) = Wire.json.encodeToString(SERIALIZER, records)
        private fun unreadable(nowMs: Long) = PauseRecord(UNREADABLE_ID, PauseOrigin.GLASSES, nowMs, null)
    }
}
