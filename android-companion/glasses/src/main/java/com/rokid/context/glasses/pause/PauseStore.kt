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
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * The glasses' persisted pause set (spec §5.5: DataStore on the glasses). Fail-closed: an unreadable file or bad JSON
 * reads as one indefinite pause ([UNREADABLE_ID]) the wearer can resume; the corrupt file is replaced on the next write.
 * [file] must end in `.preferences_pb`; only one store per file may be active.
 */
/**
 * The persisted pause set plus tombstones (pauseId -> clearedAtMs) for records the wearer resumed locally,
 * [provisional] ids (phone timed records held indefinite until an exact push), [phoneResumed] (pauseId -> glasses-clock
 * instant the phone's Resume arrived) and [quietHandled] (quiet-hours occurrence start -> the record id that covered it).
 */
data class PauseState(
    val records: List<PauseRecord>,
    val tombstones: Map<String, Long> = emptyMap(),
    val provisional: Set<String> = emptySet(),
    val phoneResumed: Map<String, Long> = emptyMap(),
    val quietHandled: Map<Long, String> = emptyMap(),
    /** pauseId -> [PauseSource] name (log text only). Missing = a store from before the source was kept: [PauseSource.PERSISTED]. */
    val sources: Map<String, String> = emptyMap(),
)

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
        // Missing or unreadable extras also fail closed: provisional records stay indefinite, a handled quiet occurrence is
        // re-paused, and a phone-resumed id can only come back if the phone lists it again.
        val provisional = prefs[PROVISIONAL]?.let { runCatching { Wire.json.decodeFromString(SET_SERIALIZER, it) }.getOrNull() } ?: emptySet()
        val phoneResumed = prefs[PHONE_RESUMED]?.let { runCatching { Wire.json.decodeFromString(TOMB_SERIALIZER, it) }.getOrNull() } ?: emptyMap()
        val quiet = prefs[QUIET]?.let { runCatching { Wire.json.decodeFromString(QUIET_SERIALIZER, it) }.getOrNull() } ?: emptyMap()
        val sources = prefs[SOURCES]?.let { runCatching { Wire.json.decodeFromString(SOURCES_SERIALIZER, it) }.getOrNull() } ?: emptyMap()
        return PauseState(records, tombs, provisional, phoneResumed, quiet, sources)
    }

    /** All keys in one atomic edit. */
    suspend fun write(state: PauseState) {
        store.edit {
            it[KEY] = encode(state.records)
            it[TOMBS] = Wire.json.encodeToString(TOMB_SERIALIZER, state.tombstones)
            it[PROVISIONAL] = Wire.json.encodeToString(SET_SERIALIZER, state.provisional)
            it[PHONE_RESUMED] = Wire.json.encodeToString(TOMB_SERIALIZER, state.phoneResumed)
            it[QUIET] = Wire.json.encodeToString(QUIET_SERIALIZER, state.quietHandled)
            it[SOURCES] = Wire.json.encodeToString(SOURCES_SERIALIZER, state.sources)
        }
    }

    companion object {
        const val UNREADABLE_ID = "unreadable-store"
        private val KEY = stringPreferencesKey("pauses")
        private val TOMBS = stringPreferencesKey("tombstones")
        private val PROVISIONAL = stringPreferencesKey("provisional")
        private val PHONE_RESUMED = stringPreferencesKey("phoneResumed")
        private val QUIET = stringPreferencesKey("quietHandled")
        private val SOURCES = stringPreferencesKey("sources")
        private val SOURCES_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
        private val TOMB_SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
        private val SET_SERIALIZER = SetSerializer(String.serializer())
        private val QUIET_SERIALIZER = MapSerializer(Long.serializer(), String.serializer())
        private val SERIALIZER = ListSerializer(PauseRecord.serializer())
        private fun encode(records: List<PauseRecord>) = Wire.json.encodeToString(SERIALIZER, records)
        private fun unreadable(nowMs: Long) = PauseRecord(UNREADABLE_ID, PauseOrigin.GLASSES, nowMs, null)
    }
}
