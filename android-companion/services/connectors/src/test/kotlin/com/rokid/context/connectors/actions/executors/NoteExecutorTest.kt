package com.rokid.context.connectors.actions.executors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.actions.ActionParams
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NoteExecutorTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val exec = NoteExecutor(db.noteDao()) { 5 }

    @After fun close() = db.close()

    private fun note(id: String, text: String) = proposal(id, ActionParams.Note(text))

    @Test fun executeSavesTheNote() = runBlocking {
        assertEquals(ExecResult.Done("note:1"), exec.execute(note("p1", "car on level 3")))
        assertEquals(listOf(NoteEntity("p1#0", "car on level 3", 5)), db.noteDao().observeAll().first())
    }

    @Test fun retryAfterCrashAddsNoDuplicate() = runBlocking {
        val p = note("p1", "car on level 3")
        exec.execute(p)
        assertEquals(ExecResult.Done("note:1"), exec.execute(p))
        assertEquals(listOf("p1#0"), db.noteDao().observeAll().first().map { it.id })
    }

    @Test fun undoDeletesOnlyThisProposalsNote() = runBlocking {
        exec.execute(note("p1", "car on level 3"))
        exec.execute(note("p2", "wifi code 1234"))
        assertTrue(exec.undo(note("p1", "car on level 3")))
        assertEquals(listOf("wifi code 1234"), db.noteDao().observeAll().first().map { it.text })
    }

    @Test fun aCorruptedRowFails() = runBlocking {
        val bad = proposal("p1", ActionParams.Note("x")).copy(paramsJson = "{}")
        assertEquals(ExecResult.Failed(FailCode.UNKNOWN), exec.execute(bad))
    }
}
