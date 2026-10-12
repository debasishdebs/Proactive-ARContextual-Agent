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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * The glasses' persisted pause set (spec §5.5: DataStore on the glasses). Fail-closed: an unreadable file or bad JSON
 * reads as one indefinite pause ([UNREADABLE_ID]) the wearer can resume; the corrupt file is replaced on the next write.
 * [file] must end in `.preferences_pb`; only one store per file may be active.
 */
/** The persisted pause set plus tombstones (pauseId -> clearedAtMs) for records the wearer resumed locally. */
data class PauseState(val records: List<PauseRecord>, val tombstones: Map<String, Long> = emptyMap())

class PauseStore(file: File, scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private val store = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(KEY to encode(listOf(unreadable(clock())))) },
        scope = scope,
        produceFile = { file },
    )

    suspend fun read(): PauseState {
        val prefs = store.data.first()
        val text = prefs[KEY] ?: return PauseState(emptyList())
        val records = runCatching { Wire.json.decodeFromString(SERIALIZER, text) }.getOrElse { return PauseState(listOf(unreadable(clock()))) }
        // Unreadable tombstones are dropped: that can only keep more pauses, never fewer.
        val tombs = prefs[TOMBS]?.let { runCatching { Wire.json.decodeFromString(TOMB_SERIALIZER, it) }.getOrNull() } ?: emptyMap()
        return PauseState(records, tombs)
    }

    /** Both keys in one atomic edit. */
    suspend fun write(state: PauseState) {
        store.edit {
            it[KEY] = encode(state.records)
            it[TOMBS] = Wire.json.encodeToString(TOMB_SERIALIZER, state.tombstones)
        }
    }

    companion object {
        const val UNREADABLE_ID = "unreadable-store"
        private val KEY = stringPreferencesKey("pauses")
        private val TOMBS = stringPreferencesKey("tombstones")
        private val TOMB_SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
        private val SERIALIZER = ListSerializer(PauseRecord.serializer())
        private fun encode(records: List<PauseRecord>) = Wire.json.encodeToString(SERIALIZER, records)
        private fun unreadable(nowMs: Long) = PauseRecord(UNREADABLE_ID, PauseOrigin.GLASSES, nowMs, null)
    }
}
