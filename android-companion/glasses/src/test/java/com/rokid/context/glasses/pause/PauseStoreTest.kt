package com.rokid.context.glasses.pause

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PauseStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun oldPauseStoreFileStillLoads() = runBlocking {
        val file = File(tmp.root, "old.preferences_pb")
        val j1 = SupervisorJob()
        // A Phase 6 file: only the "pauses" and "tombstones" keys.
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + j1), produceFile = { file }).edit {
            it[stringPreferencesKey("pauses")] = """[{"pauseId":"g1","origin":"GLASSES","createdAtMs":5}]"""
            it[stringPreferencesKey("tombstones")] = """{"t1":7}"""
        }
        j1.cancelAndJoin()
        val j2 = SupervisorJob()
        val s = PauseStore(file, CoroutineScope(Dispatchers.IO + j2)) { 0 }.read()
        assertEquals(listOf(PauseRecord("g1", PauseOrigin.GLASSES, 5, null)), s.records)
        assertEquals(mapOf("t1" to 7L), s.tombstones)
        assertEquals(emptySet<String>(), s.provisional)
        assertEquals(emptyMap<String, Long>(), s.phoneResumed)
        assertEquals(emptyMap<Long, String>(), s.quietHandled)
        j2.cancelAndJoin()
    }

    @Test fun newFieldsRoundTrip() = runBlocking {
        val file = File(tmp.root, "new.preferences_pb")
        val j = SupervisorJob()
        val store = PauseStore(file, CoroutineScope(Dispatchers.IO + j)) { 0 }
        val state = PauseState(
            listOf(PauseRecord("p", PauseOrigin.PHONE, 1, null)), mapOf("t" to 2L),
            provisional = setOf("p"), phoneResumed = mapOf("r" to 3L), quietHandled = mapOf(4L to "q"),
        )
        store.write(state)
        assertEquals(state, store.read())
        j.cancelAndJoin()
    }
}
